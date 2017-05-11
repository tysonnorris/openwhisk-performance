package whisk.gatling

import java.io.InputStream
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

import com.typesafe.config.ConfigFactory
import io.gatling.core.Predef._
import io.gatling.core.structure.{ChainBuilder, PopulationBuilder}
import io.gatling.http.Predef._
import spray.json.{JsString, _}

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.io.Source
import scala.language.postfixOps


/**
  * A Simulation impl that will:
  * - generate a whisk action (deletes an old one, if it exists)
  * - execute a series of blocking+concurrent invocations
  *
  * curl -k -u "$credentials" "$host/api/v1/namespaces/_/actions/$action" -XPUT -d '{"namespace":"_","name":"test","annotations":[{"key":"max-concurrent", "value":"10000"}],"exec":{"kind":"nodejs:default","code":"function main(){return {};}"}}' -H "Content-Type: application/json"
  * curl -k -u "$credentials" "$host/api/v1/namespaces/_/actions/$action?blocking=true" -XPOST
  *
  * To configure action and loop configs at runtime, use system properties:
  * -Dwhisk.perftest.run.loop-count=100
  * -Dwhisk.perftest.run.loop-user-count=10
  * -Dwhisk.perftest.run.action-config=async-noop
  *
  */
class ThroughputSimulation extends Simulation {
  //load conf from gatling.conf
  val conf = ConfigFactory.load("gatling")


  val actionConfigNames: scala.List[String] = conf.getStringList("whisk.perftest.run.action-configs").toList
  val loopCount = conf.getInt("whisk.perftest.run.loop-count")
  val loopUserCount = conf.getInt("whisk.perftest.run.loop-user-count")

  println("---------------------------------------------")
  println("Using configs for test run:")
  println("---------------------------------------------")
  println("whisk.perftest.run.action-config:" + actionConfigNames)
  println("whisk.perftest.run.loop-count:" + loopCount)
  println("whisk.perftest.run.loop-user-count:" + loopUserCount)
  println("---------------------------------------------")

  val host = conf.getString("whisk.perftest.baseurl")
  val username = conf.getString("whisk.perftest.username")
  val pwd = conf.getString("whisk.perftest.pwd")

  val httpInitConf = http.baseURL("https://192.168.99.100")
  val httpConf = http.baseURL(host)

  //Actions!
  object SetupAllActions {

    val setupComplete = new AtomicBoolean(true) //tracks when last item setup is complete

    //TODO: it's not clear how to determine the rev id of the action...
    //controller is hacked to dump to logs, so invoke one to get the rev id:
    //curl -k -u "23bc46b1-71f6-4ed5-8c54-816aa4f8c502:123zO3xZCLrMN6v2BKK1dXYFpXlPkccOFqm12CdAsMgRU4VrNZ9lyGVCGuMDGIwP"  -X POST https://192.168.99.100/api/v/namespaces/_/actions/gatlingThroughputAsyncNoop?blocking=true

    var revId = "1-6c2eb9c0399496ca8cb3826593802f4e"

    //currently deletes tolerate action already exists (200) or does not exist(404)

    val deleteAndCreate: List[ChainBuilder] = {
      for (actionConfig <- actionConfigNames)
        yield {
          //setup vals
          println(s"setting up action ${actionConfig}")
          val action = conf.getString("whisk.perftest.action-configs." + actionConfig + ".action-name")
          val actionTemplateFile = conf.getString("whisk.perftest.action-configs." + actionConfig + ".action-template-file")
          val actionCodeFile = conf.getString("whisk.perftest.action-configs." + actionConfig + ".action-code-file")
          val stream: InputStream = getClass.getResourceAsStream("/" + actionCodeFile)
          val fileContents = Source.fromInputStream(stream).mkString
          println(" with function code: ")
          println("---------------------------------------------")
          println(fileContents)
          println("---------------------------------------------")
          val escapedContents = CompactPrinter(JsString(fileContents))


          //delete old action
          exec(http("delete action") // Here's an example of a POST request
            .delete(s"/api/v1/namespaces/_/actions/${action}".toString())
            .basicAuth(username, pwd)
            .check(status.in(200, 404)))
            //create action
            .exec(_.set("actioncode", escapedContents))
            .exec(http("create action") // Here's an example of a POST request
              .put(s"/api/v1/namespaces/_/actions/${action}".toString())
              .basicAuth(username, pwd)
              .body(ElFileBody(actionTemplateFile)).asJSON
              .check(status.in(200)))
            .pause(1 seconds)
            //run the action once
            .exec(http("run action once after create")
            .post(s"/api/v1/namespaces/_/actions/${action}?blocking=true".toString())
            .basicAuth("23bc46b1-71f6-4ed5-8c54-816aa4f8c502",
              "123zO3xZCLrMN6v2BKK1dXYFpXlPkccOFqm12CdAsMgRU4VrNZ9lyGVCGuMDGIwP")
            .check(bodyString.saveAs("body"))
            .check(status.in(200)))
            .exec(session => {
              println("body: "+ session.get("body").as[String])
//              revId = session.get("revId").as[String]
              //if this is the last config, set the completion flag
              if (actionConfig == actionConfigNames.last) {
                setupComplete.set(true)
                println("setup is complete!")
              }
              session
            })
        }
    }
  }

  val starttime = Instant.now.getEpochSecond

  object SingleAction {
    def action(actionConfig: String) = {
      val action = conf.getString("whisk.perftest.action-configs." + actionConfig + ".action-name")
      //only proceed once action was created...
      doIf(session => !SetupAllActions.setupComplete.get()) {
        println("waiting for setup to complete...")
        pause(10 seconds) //looping will occur, so try to set this pause to the time it takes for init to complete...
      }
        .exec{ session =>
          val counter = session("counter").as[Int]
          val transId = s"${session.userId}${counter}".toLong
//          val id = f"${idLong}%032d"
          val id = java.util.UUID.randomUUID.toString
          val trimmed = id.substring(id.length-32, id.length)
//          println("id: "+ trimmed)
          val transStart = Instant.now().toEpochMilli

          session.set("activationId", trimmed)
            .set("revId", SetupAllActions.revId)
            .set("transId", transId)
            .set("transStart", transStart)

        }
        .exec(http(s"run action ${action} in loop")
          .post("/invoke")
          .body(ElFileBody("activation.json")).asJSON
          .check(status.in(200)))
        .pause(200 milliseconds, 500 milliseconds)
    }
  }

  //setup scenarios + populations
  val initScenario = scenario("delete then create").exec(SetupAllActions.deleteAndCreate.iterator)
  val initPopulationBuilder = initScenario.inject(atOnceUsers(1)).protocols(httpInitConf)
  val loopedPopulationBuilders: List[PopulationBuilder] =
    actionConfigNames.map(actionConfig => {
      scenario(s"loop ${actionConfig}")
        .repeat(loopCount, "counter") {
          exec(SingleAction.action(actionConfig))
        }.inject(atOnceUsers(loopUserCount)).protocols(httpConf)
    })

  setUp(
    //merge the init population and looped populations
    //initPopulationBuilder +: loopedPopulationBuilders: _*
    loopedPopulationBuilders: _*
  )
}

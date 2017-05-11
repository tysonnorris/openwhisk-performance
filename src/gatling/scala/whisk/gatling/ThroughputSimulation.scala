package whisk.gatling

import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

import com.typesafe.config.ConfigFactory
import io.gatling.core.Predef._
import io.gatling.core.session.StaticStringExpression
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

  val httpConf = http.baseURL(host).basicAuth(username, pwd)

  object SingleAction {
    val setupComplete = new AtomicBoolean(false) //tracks when last item setup is complete

    //currently deletes tolerate action already exists (200) or does not exist(404)
    def setupAction(actionConfig: String) = {
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
        .delete(StaticStringExpression(s"/api/v1/namespaces/_/actions/${action}"))
        .check(status.in(200, 404)))
        //create action
        .exec(_.set("actioncode", escapedContents))
        .exec(http("create action") // Here's an example of a POST request
          .put(StaticStringExpression(s"/api/v1/namespaces/_/actions/${action}"))
          .body(ElFileBody(actionTemplateFile)).asJSON
          .check(status.in(200)))
        //run the action once
        .exec(http(StaticStringExpression("run action once after create"))
        .post(StaticStringExpression(s"/api/v1/namespaces/_/actions/${action}?blocking=true"))
        .check(status.in(200)))
        .exec(session => {
          //if this is the last config, set the completion flag
          if (actionConfig == actionConfigNames.last) {
            setupComplete.set(true)
            println("setup is complete!")
          }
          session
        })
    }


    def invokeAction(actionConfig: String) = {
      val action = conf.getString("whisk.perftest.action-configs." + actionConfig + ".action-name")
      //only proceed once action was created...
      doIf(session => !SingleAction.setupComplete.get()) {
        println("waiting for setup to complete...")
        pause(10 seconds) //looping will occur, so try to set this pause to the time it takes for init to complete...
      }
        .exec(http(s"run action ${action} in loop")
          .post(StaticStringExpression(s"/api/v1/namespaces/_/actions/${action}?blocking=true"))
          .check(status.in(200)))
    }
  }

  val populationBuilders: List[PopulationBuilder] = {
    val setup = actionConfigNames.map(actionConfig => {
      scenario(s"setup action ${actionConfig}")
        .exec(SingleAction.setupAction(actionConfig))
        .inject(atOnceUsers(1))
    })

    val invoke = actionConfigNames.map(actionConfig => {
      scenario(s"setup action ${actionConfig}")
        .repeat(loopCount) {
          exec(SingleAction.setupAction(actionConfig))
        }.inject(atOnceUsers(1))
      scenario(s"loop ${actionConfig}")
        .repeat(loopCount) {
          exec(SingleAction.invokeAction(actionConfig))
        }.inject(atOnceUsers(loopUserCount))
    })

    //merge the setup and invoke population builders
    setup ::: invoke
  }

  setUp(
    populationBuilders: _*
  ).protocols(httpConf)
}

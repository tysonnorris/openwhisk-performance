package whisk.gatling

import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

import com.typesafe.config.ConfigFactory
import io.gatling.core.Predef._
import io.gatling.http.Predef._
import spray.json.{JsString, _}

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


  val actionConfig = conf.getString("whisk.perftest.run.action-config")
  val loopCount = conf.getInt("whisk.perftest.run.loop-count")
  val loopUserCount = conf.getInt("whisk.perftest.run.loop-user-count")

  println("---------------------------------------------")
  println("Using configs for test run:")
  println("---------------------------------------------")
  println("whisk.perftest.run.action-config:" + actionConfig)
  println("whisk.perftest.run.loop-count:" + loopCount)
  println("whisk.perftest.run.loop-user-count:" + loopUserCount)
  println("---------------------------------------------")

  val host = conf.getString("whisk.perftest.baseurl")
  val username = conf.getString("whisk.perftest.username")
  val pwd = conf.getString("whisk.perftest.pwd")
  val action = conf.getString("whisk.perftest.action-configs." + actionConfig + ".action-name")
  val actionTemplateFile = conf.getString("whisk.perftest.action-configs." + actionConfig + ".action-template-file")
  val actionCodeFile = conf.getString("whisk.perftest.action-configs." + actionConfig + ".action-code-file")

  val httpConf = http.baseURL(host)

  //Actions!
  object DeleteAction {
    //currently tolerate action already exists (200) or does not exist(404)
    val delete = exec(http("delete action") // Here's an example of a POST request
      .delete(s"/api/v1/namespaces/_/actions/${action}".toString())
      .basicAuth(username, pwd)
      .check(status.in(200, 404)))
  }

  object CreateAction {
    val stream: InputStream = getClass.getResourceAsStream("/" + actionCodeFile)
    val fileContents = Source.fromInputStream(stream).mkString
    println(" testing function code: ")
    println("---------------------------------------------")
    println(fileContents)
    println("---------------------------------------------")
    val escapedContents = CompactPrinter(JsString(fileContents))
    val created = new AtomicInteger(0)
    val create = exec(_.set("actioncode", escapedContents))
      .exec(http("create action") // Here's an example of a POST request
        .put(s"/api/v1/namespaces/_/actions/${action}".toString())
        .basicAuth(username, pwd)
        .body(ElFileBody(actionTemplateFile)).asJSON
        .check(status.in(200)))
      .pause(1 seconds)
      .exec(http("run action once after create")
        .post(s"/api/v1/namespaces/_/actions/${action}?blocking=true".toString())
        .basicAuth("23bc46b1-71f6-4ed5-8c54-816aa4f8c502",
          "123zO3xZCLrMN6v2BKK1dXYFpXlPkccOFqm12CdAsMgRU4VrNZ9lyGVCGuMDGIwP")
        .check(status.in(200)))
      .exec(session => {
        created.incrementAndGet()
        session
      })
  }

  object LoopedActionInvocation {
    val loop = repeat(loopCount) {
      //only proceed once action was created...
      asLongAs(session => CreateAction.created.get < 1) {
        pause(1 second)
      }
        .exec(http("run action in loop")
          .post(s"/api/v1/namespaces/_/actions/${action}?blocking=true"
            .toString())
          .basicAuth(username, pwd)
          .check(status.in(200)))
        .pause(0 milliseconds)
    }
  }

  //setup!
  val init = scenario("delete then create").exec(DeleteAction.delete, CreateAction.create)
  val loop = scenario("loop").exec(LoopedActionInvocation.loop)

  setUp(
    init.inject(atOnceUsers(1)), //use a single user for init
    loop.inject(atOnceUsers(loopUserCount)) //use loopUserCount users for looping
  ).protocols(httpConf)
}

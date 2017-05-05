# Gatling Tests

## Source

* ```src/gatling/scala``` Gatling simulations
* ```src/resources/bodies/*.json``` request bodies (action templates) 
* ```src/resources/bodies/*.js``` request bodies (action nodejs functions) 
* ```src/gatling/conf``` gatling.conf + logback.xml

## Configuration

gatling.conf is used for both:
* configuring gatling [default values]( https://github.com/gatling/gatling/blob/master/gatling-core/src/main/resources/gatling-defaults.conf)
* configuration simulations

## Runtime Configs

Simulations may rely on runtime configs. 
ThroughputSimulation uses:
* a specific action-config (whisk.perftest.run.action-config)
* a loop count (whisk.perftest.run.loop-count)
* a loop user count (whisk.perftest.run.loop-user-count)  

## Action Configs

Action Configs specify how to define the action(s) that are used in the simulation:
* action-name
* action-template-file (json used to create the action on first run)
* action-code-file (code included in the exec.code JSON property when creating the action)

```
whisk.perftest {
  baseurl = "https://192.168.99.100"
  username = "23bc46b1-71f6-4ed5-8c54-816aa4f8c502"
  pwd = "123zO3xZCLrMN6v2BKK1dXYFpXlPkccOFqm12CdAsMgRU4VrNZ9lyGVCGuMDGIwP"

  #default prefix if none specified
  run.action-config = "noop"
  run.loop-count = 250
  run.loop-user-count = 40

  action-configs {
    noop {
      action-name = "gatlingThroughputNoop"
      action-template-file = "action-template1.json"
      action-code-file = "noop.js"
    }
    api {
      action-name = "gatlingThroughputApi"
      action-template-file = "action-template1.json"
      action-code-file = "api.js"
    }
    async-noop {
      action-name = "gatlingThroughputAsyncNoop"
      action-template-file = "action-template1.json"
      action-code-file = "async-noop.js"
    }
    async-noop-fast {
      action-name = "gatlingThroughputAsyncNoopFast"
      action-template-file = "action-template1.json"
      action-code-file = "async-noop-fast.js"
    }
  }

}
```

# Running

A single run will use:
* a specific simulation (currently only ThroughputSimulation)
* config based on system property overrides

## Examples

Run tests with default config (a noop action) using gradle with the gatling plugin:
```
./gradlew gatlingRun
```
Run tests with specific `whisk.perftest.run.*` config values using system properties:
```
./gradlew gatlingRun -Dwhisk.perftest.run.loop-count=100 -Dwhisk.perftest.run.loop-user-count=1 -Dwhisk.perftest.run.action-config=async-noop
```




# Issues

## ThroughputSimulation
* If an action exists before the run, it will be deleted and replace before the loop run begins.
Currently there is no tolerated
* If an action invocation response is 202, it is treated as a failure; all invocations are expected to be 200 since these are ?blocking activations.

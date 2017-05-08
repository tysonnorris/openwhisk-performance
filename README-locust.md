# locust.io tests

locust.io is a python based load test harness. 

These tests do NOT include setup steps, so the create.sh script must be run before the locust test run.

# Installation

```pip install locustio```

See http://docs.locust.io/en/latest/installation.html for more details


# Source

* ```locust/actions/*.js``` action nodejs functions
* ```locust/actionstemplate.json``` action template
* ```locust/*.py``` locustfiles (default is locustfile.py)
* ```locust/create.sh``` bash script to create actions before test run

# Configuration

## create.sh positional args
* 1 (host
* 2 (creds)
* 3 (action name)
* 4 (action source code file)

## locustfile.py ENV vars
* TEST_ACTIONS - actions to be used for test run
* TEST_USERNAME - username to be used for test run
* TEST_PASSWORD - password to be used for test run

## locust named args
* --host
* --no-web
* --clients
* --hatch-rate
* --num-request

For distributed mode see http://docs.locust.io/en/latest/running-locust-distributed.html#options

# Running

Running is a 2 step processes on new systems:
* create the action(s) using create.sh (only required first test run)
* run load tests against those actions using locust

## Example

```
./create.sh https://192.168.99.100 "23bc46b1-71f6-4ed5-8c54-816aa4f8c502:123zO3xZCLrMN6v2BKK1dXYFpXlPkccOFqm12CdAsMgRU4VrNZ9lyGVCGuMDGIwP" locustNoop ./actions/noop.js
TEST_ACTIONS=locustNoop,locustAsyncNoop,locustAsyncNoopFast TEST_USERNAME=23bc46b1-71f6-4ed5-8c54-816aa4f8c502 TEST_PASSWORD=123zO3xZCLrMN6v2BKK1dXYFpXlPkccOFqm12CdAsMgRU4VrNZ9lyGVCGuMDGIwP locust --host=https://192.168.99.100 --no-web  --clients=10 --hatch-rate=2 --num-request=2000
```
import requests
from locust import HttpLocust, TaskSet, task

from requests.packages.urllib3.exceptions import InsecureRequestWarning

import os

#allow self signed certs without the noise:
requests.packages.urllib3.disable_warnings(InsecureRequestWarning)

action = os.environ.get("TEST_ACTION")
username = os.environ.get("TEST_USERNAME")
pwd = os.environ.get("TEST_PASSWORD")
print "Testing: "
print "           action: %s " % (action)
print "           username: %s " % (username)
print "           pwd: %s " % (pwd)


creds = (username, pwd)

class ThroughputTaskSet(TaskSet):


  @task
  def invoke(self):
    self.client.post('/api/v1/namespaces/_/actions/{}?blocking=true'.format(action), auth=creds, verify=False)

class WebsiteUser(HttpLocust):
  task_set = ThroughputTaskSet
  min_wait = 10
  max_wait = 10
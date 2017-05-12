#!/bin/sh

set -e

# common docker setup
sudo gpasswd -a travis docker
sudo -E bash -c 'echo '\''DOCKER_OPTS="-H tcp://0.0.0.0:4243 -H unix:///var/run/docker.sock --storage-driver=overlay --userns-remap=default"'\'' > /etc/default/docker'
sudo service docker restart

# checkout openwhisk latest
git clone --depth 1 https://github.com/openwhisk/openwhisk.git

# install ansible
pip install --user ansible==2.3.0.0

cd openwhisk/ansible
LIMITS='{"limits":{"actions":{"invokes":{"perMinute":999999,"concurrent":999999,"concurrentInSystem":999999}},"triggers":{"fires":{"perMinute":999999}}}}'
ANSIBLE_CMD="ansible-playbook -i environments/local -e docker_image_prefix=openwhisk -e docker_registry=docker.io/ -e $LIMITS"

$ANSIBLE_CMD setup.yml
$ANSIBLE_CMD prereq.yml
$ANSIBLE_CMD couchdb.yml
$ANSIBLE_CMD initdb.yml
$ANSIBLE_CMD wipe.yml

$ANSIBLE_CMD consul.yml
$ANSIBLE_CMD kafka.yml
$ANSIBLE_CMD controller.yml
$ANSIBLE_CMD invoker.yml

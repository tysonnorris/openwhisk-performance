#!/bin/sh
set -e

# Host to use. Needs to include the protocol.
host=$1
# Credentials to use for the test. USER:PASS format.
credentials=$2
# Name of the action to create and test.
action=$3
# Source code of the action
actioncodefile=$4

actiontemplate=$(cat actiontemplate.json)

actioncode=$(cat $actioncodefile)

requestbody=$(jq -n --arg actioncode "$actioncode" "$actiontemplate")

# delete the action

# create the action
echo "Creating action $action"
curl -k -u "$credentials" "$host/api/v1/namespaces/_/actions/$action" -XPUT -d "$requestbody" -H "Content-Type: application/json"

# run the action
echo "Running $action once to assert an intact system"
curl -k -u "$credentials" "$host/api/v1/namespaces/_/actions/$action?blocking=true" -XPOST
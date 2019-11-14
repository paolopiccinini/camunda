#!/bin/sh
# Define migration functions
ES=${1:-http://localhost:9200}
# For testing prefix 'echo ' 
RESTCLIENT="curl -K curl.config"

echo "Delete all indices that match operate-*"
echo "-------------------------------"
$RESTCLIENT --request DELETE --url $ES/operate-*
echo
echo "-------------------------------"

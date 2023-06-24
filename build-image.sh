#!/bin/bash
set -e -u -o pipefail
if [ $# -lt 1 ]; then
  echo 'version is required'
  exit 1
fi
VERSION=$1
# shellcheck disable=SC1090
#source ~/.bash_profile
#mvn clean package -Dmaven.test.skip=true
docker build . -t midjourney-proxy:"${VERSION}"
docker tag midjourney-proxy:"${VERSION}" registry.cn-hangzhou.aliyuncs.com/warape/midjourney-proxy:"${VERSION}"
docker push registry.cn-hangzhou.aliyuncs.com/warape/midjourney-proxy:"${VERSION}"

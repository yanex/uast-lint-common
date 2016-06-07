#!/bin/bash

PROG_DIR=$(dirname "$0")

function copyfile() {
  echo "Copy ../../$1 -> $1"
  cp ../../$1 .
}

echo "cd to $PROG_DIR"
pushd $PROG_DIR

copyfile "build.gradle"
copyfile "settings.gradle"
copyfile "gradlew"
copyfile "gradlew.bat"
copyfile "gradle.properties"

echo "Back to previous dir..."
popd



#!/bin/bash
# Maven Wrapper — downloads Maven if not present, then runs it
# Usage: ./mvnw clean package -DskipTests
set -e

MVN_VERSION="3.9.6"
MVN_HOME="$HOME/.m2/wrapper/dists/apache-maven-$MVN_VERSION"
MVN_BIN="$MVN_HOME/bin/mvn"

if [ ! -f "$MVN_BIN" ]; then
  echo "Downloading Apache Maven $MVN_VERSION..."
  mkdir -p "$MVN_HOME"
  set +e
  curl -fsSL "https://dlcdn.apache.org/maven/maven-3/$MVN_VERSION/binaries/apache-maven-$MVN_VERSION-bin.tar.gz" \
    | tar xz --strip-components=1 -C "$MVN_HOME"
  rc=$?
  set -e
  if [ $rc -ne 0 ]; then
    echo "Primary mirror failed, trying archive.apache.org..."
    rm -rf "$MVN_HOME"
    mkdir -p "$MVN_HOME"
    curl -fsSL "https://archive.apache.org/dist/maven/maven-3/$MVN_VERSION/binaries/apache-maven-$MVN_VERSION-bin.tar.gz" \
      | tar xz --strip-components=1 -C "$MVN_HOME"
  fi
  echo "Maven downloaded to $MVN_HOME"
fi

exec "$MVN_BIN" "$@"

#!/bin/sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$JAR" ]; then
  echo "gradle-wrapper.jar is not bundled in this scaffold." >&2
  echo "Run 'gradle wrapper --gradle-version 8.5' once, or open the project in Android Studio." >&2
  exit 1
fi
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"

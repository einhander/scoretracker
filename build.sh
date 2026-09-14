#!/bin/sh
set -eu

mode="${1:-debug}"
case "$mode" in
  debug) task=assembleDebug ;;
  release) task=assembleRelease ;;
  test) task=testDebugUnitTest ;;
  *) echo "Usage: $0 [debug|release|test]" >&2; exit 2 ;;
esac

if [ -x ./gradlew ] && [ -f ./gradle/wrapper/gradle-wrapper.jar ]; then
  exec ./gradlew ":app:$task" --no-daemon --warning-mode none
fi

if command -v gradle >/dev/null 2>&1; then
  exec gradle ":app:$task" --no-daemon --warning-mode none
fi

echo "No Gradle wrapper JAR or system Gradle found." >&2
echo "Open the project in Android Studio, or run 'gradle wrapper --gradle-version 8.5' once." >&2
exit 1

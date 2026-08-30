#!/bin/sh
# Standard Gradle wrapper launch script.
# Requires gradle/wrapper/gradle-wrapper.jar to be present (see README.md);
# Android Studio will offer to regenerate it automatically on first open.

DIR="$(cd "$(dirname "$0")" && pwd)"
CLASSPATH="$DIR/gradle/wrapper/gradle-wrapper.jar"
exec java -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"

#!/bin/sh
#
# Gradle start up script for UN*X
#
GRADLE_APP_BASE_NAME=`basename "$0"`
GRADLE_APP_HOME=`dirname "$0"`

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
    fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH."
fi

exec "$JAVACMD" \
  -classpath "$GRADLE_APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
  org.gradle.wrapper.GradleWrapperMain "$@"

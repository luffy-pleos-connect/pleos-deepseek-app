#!/usr/bin/env bash
# MOCA core — JDK 17 + Kotlin compiler only. No Gradle, no Android SDK.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# JDK 17: explicit override, then the system Temurin 17, then the inherited JAVA_HOME.
if [ -n "${JAVA_HOME_17:-}" ] && [ -x "$JAVA_HOME_17/bin/java" ]; then
  JAVA_HOME_USED="$JAVA_HOME_17"
elif [ -x /Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java ]; then
  JAVA_HOME_USED=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_HOME_USED="$JAVA_HOME"
else
  echo "JDK 17 not found. Set JAVA_HOME_17 or JAVA_HOME." >&2
  exit 2
fi

JAVA="$JAVA_HOME_USED/bin/java"
JAVA_HOME="$JAVA_HOME_USED"

# Locate a Kotlin compiler: Kotlin CLI if installed, else the one bundled inside Gradle.
find_kotlin_compiler() {
  if command -v kotlinc >/dev/null 2>&1; then
    local home
    home="$(cd "$(dirname "$(command -v kotlinc)")/.." && pwd)"
    if [ -f "$home/lib/kotlin-compiler.jar" ]; then
      echo "$home/lib/kotlin-compiler.jar|$home/lib/kotlin-stdlib.jar|$home/lib"
      return 0
    fi
  fi

  local gradle_lib
  gradle_lib="$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-*/**/gradle-*/lib 2>/dev/null | head -1 || true)"
  if [ -n "${gradle_lib:-}" ] && [ -d "$gradle_lib" ]; then
    local compiler stdlib deps
    compiler="$(ls "$gradle_lib"/kotlin-compiler-embeddable-*.jar 2>/dev/null | head -1 || true)"
    stdlib="$(ls "$gradle_lib"/kotlin-stdlib-[0-9]*.jar 2>/dev/null | head -1 || true)"
    deps="$gradle_lib"
    if [ -n "$compiler" ] && [ -n "$stdlib" ]; then
      echo "$compiler|$stdlib|$deps"
      return 0
    fi
  fi
  return 1
}

COMPILER_INFO="$(find_kotlin_compiler || true)"
if [ -z "$COMPILER_INFO" ]; then
  echo "Kotlin compiler not found. Install kotlinc or run a Gradle build once to populate ~/.gradle." >&2
  exit 3
fi

IFS='|' read -r COMPILER_JAR STDLIB_JAR LIB_DIR <<< "$COMPILER_INFO"

# Jars the embeddable compiler needs on its own classpath.
COMPILER_CP="$COMPILER_JAR:$STDLIB_JAR"
for jar in \
  kotlinx-coroutines-core-jvm \
  kotlin-daemon-embeddable \
  kotlin-reflect \
  kotlin-script-runtime \
  kotlinx-serialization-core-jvm \
  kotlinx-serialization-json-jvm \
  kotlin-build-tools-impl \
  trove4j \
  annotations; do
  match="$(ls "$LIB_DIR/$jar"*.jar 2>/dev/null | head -1 || true)"
  [ -n "$match" ] && COMPILER_CP="$COMPILER_CP:$match"
done

BUILD="$ROOT/build"
MAIN_OUT="$BUILD/classes/main"
TEST_OUT="$BUILD/classes/test"
MAIN_CP="$MAIN_OUT:$STDLIB_JAR"

rm -rf "$BUILD"
mkdir -p "$MAIN_OUT" "$TEST_OUT"

echo "== MOCA core =="
echo "java     : $JAVA_HOME_USED ($("$JAVA" -version 2>&1 | head -1))"
echo "compiler : $COMPILER_JAR"
echo "stdlib   : $STDLIB_JAR"
echo

kotlinc_run() {
  "$JAVA" -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler "$@"
}

echo "-- compile main"
MAIN_SOURCES=$(find "$ROOT/src/main/kotlin" -name '*.kt' | sort)
# shellcheck disable=SC2086
kotlinc_run -no-stdlib -no-reflect -jvm-target 17 -classpath "$STDLIB_JAR" -d "$MAIN_OUT" $MAIN_SOURCES

echo "-- copy resources"
if [ -d "$ROOT/src/main/resources" ]; then
  cp -R "$ROOT/src/main/resources/." "$MAIN_OUT/"
fi

echo "-- compile test"
TEST_SOURCES=$(find "$ROOT/src/test/kotlin" -name '*.kt' | sort)
# shellcheck disable=SC2086
kotlinc_run -no-stdlib -no-reflect -jvm-target 17 -classpath "$MAIN_CP" -d "$TEST_OUT" $TEST_SOURCES

echo "-- run tests"
set +e
"$JAVA" -cp "$TEST_OUT:$MAIN_CP" moca.core.TestRunnerKt "$TEST_OUT"
STATUS=$?
set -e

echo
if [ "$STATUS" -eq 0 ]; then
  echo "ALL TESTS PASSED"
  echo "RESULT: PASS"
else
  echo "RESULT: FAIL"
fi
exit "$STATUS"

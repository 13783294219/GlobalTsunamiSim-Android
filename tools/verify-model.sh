#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
TMP="${TMPDIR:-/tmp}/global-tsunami-sim-javac"
mkdir -p "$TMP"
javac -encoding UTF-8 -d "$TMP" \
  app/src/main/java/com/globaltsunamisim/android/GridData.java \
  app/src/main/java/com/globaltsunamisim/android/TsunamiModel.java \
  tools/TsunamiModelSelfTest.java
java -cp "$TMP" com.globaltsunamisim.android.TsunamiModelSelfTest

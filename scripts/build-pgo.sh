#!/usr/bin/env bash
#
# Copyright (c) 2024, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
# Build pypowsybl with Profile-Guided Optimization (PGO).
#
# Two-pass workflow (requires Oracle GraalVM, which pypowsybl already uses for wheels):
#   1. build an instrumented native EXECUTABLE from the pypowsybl-java jar + scripts/pgo/PgoWorkload
#   2. run it to collect a profile (GraalVM only dumps profiles from executables, not --shared libs)
#   3. build the real --shared library/wheel, feeding the profile via native-image --pgo=<profile>
#
# Usage:
#   scripts/build-pgo.sh [network-file]           # then default build: pip install -e .
#   PGO_BUILD_CMD="python setup.py bdist_wheel" scripts/build-pgo.sh [network-file]
#
# Optional first argument: a representative network file (e.g. a large PEGASE MATPOWER case) to
# profile against. Without it, a self-contained IEEE 300-bus network is used.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

: "${JAVA_HOME:?Set JAVA_HOME to an Oracle GraalVM 21 installation}"
NATIVE_IMAGE="$JAVA_HOME/bin/native-image"
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"
[ -x "$NATIVE_IMAGE" ] || { echo "native-image not found at $NATIVE_IMAGE (need Oracle GraalVM)"; exit 1; }

NETWORK_ARG="${1:-}"
WORK="$REPO_ROOT/build/pgo"
JAVA_MODULE="$REPO_ROOT/java/pypowsybl"
JAR="$JAVA_MODULE/target/pypowsybl-java.jar"
HARNESS="$REPO_ROOT/scripts/pgo/PgoWorkload.java"
PROFILE="$WORK/pypowsybl.iprof"

mkdir -p "$WORK"

echo "=== [1/6] Building pypowsybl-java jar (maven) ==="
if [ ! -f "$JAR" ]; then
    (cd "$JAVA_MODULE" && "$REPO_ROOT/java/mvnw" -B -ntp clean package)
else
    echo "reusing existing $JAR"
fi

# Native math library (needed by the load flow at profiling time) lives in a dependency jar.
echo "=== [2/6] Extracting native math library ==="
MATH_JAR="$JAVA_MODULE/target/dependency/powsybl-math-native.jar"
LIBDIR="$WORK/lib"
mkdir -p "$LIBDIR"
case "$(uname -s)" in
    Linux)  NATIVE_ENTRY="natives/linux_64/libmath.so" ;;
    Darwin) NATIVE_ENTRY="natives/osx_64/libmath.dylib" ;;
    *) echo "unsupported OS for PGO profiling: $(uname -s)"; exit 1 ;;
esac
( cd "$LIBDIR" && "$JAVA_HOME/bin/jar" xf "$MATH_JAR" "$NATIVE_ENTRY" && cp "$NATIVE_ENTRY" . )

echo "=== [3/6] Capturing native-image reachability config (tracing agent) ==="
CONFIG="$WORK/native-image-config"
rm -rf "$CONFIG"
"$JAVAC" -proc:none -cp "$JAR" -d "$WORK" "$HARNESS"
"$JAVA" -agentlib:native-image-agent=config-output-dir="$CONFIG" \
    -Djava.library.path="$LIBDIR" -cp "$WORK:$JAR" PgoWorkload $NETWORK_ARG

echo "=== [4/6] Building instrumented executable (--pgo-instrument) ==="
( cd "$WORK" && "$NATIVE_IMAGE" --pgo-instrument --no-fallback \
    -cp "$WORK:$JAR" \
    -H:ConfigurationFileDirectories="$CONFIG" \
    -H:CLibraryPath="$REPO_ROOT/cpp/pypowsybl-java" \
    --add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.c=ALL-UNNAMED \
    -o pgo_workload_instr PgoWorkload )

echo "=== [5/6] Running instrumented workload to collect profile ==="
( cd "$WORK" && ./pgo_workload_instr -Djava.library.path="$LIBDIR" $NETWORK_ARG )
[ -f "$WORK/default.iprof" ] || { echo "profile was not produced"; exit 1; }
mv "$WORK/default.iprof" "$PROFILE"
echo "profile: $PROFILE ($(du -h "$PROFILE" | cut -f1))"

echo "=== [6/6] Building pypowsybl with the profile ==="
export PYPOWSYBL_PGO_PROFILE="$PROFILE"
${PGO_BUILD_CMD:-pip install -e . --no-build-isolation}

echo "=== PGO build complete (profile: $PROFILE) ==="

#!/usr/bin/env bash
# Builds and runs the PocketFly native (C++) test suite on the host.
# Requires only g++. Used locally and by CI; no Android toolchain needed.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CPP="$REPO/simulator/src/main/cpp"
OUT="$REPO/simulator/build/host-tests"
mkdir -p "$OUT"

g++ -std=c++17 -O2 -Wall -Wextra -I"$CPP" \
    "$CPP/core/brain_format.cpp" \
    "$CPP/core/runtime.cpp" \
    "$CPP/tests/test_main.cpp" \
    "$CPP/tests/test_brain_format.cpp" \
    "$CPP/tests/test_runtime.cpp" \
    "$CPP/tests/test_integration.cpp" \
    -o "$OUT/pocketfly_tests"

ARGS=()
if [ -d "$REPO/data/sample/sample_1024" ]; then
    ARGS+=(--sample "$REPO/data/sample/sample_1024")
fi

"$OUT/pocketfly_tests" "${ARGS[@]}"

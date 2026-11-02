#!/usr/bin/env bash

set -euo pipefail

adb wait-for-device
echo "Waiting for boot to complete..."
adb shell 'while [ -z "$(getprop sys.boot_completed)" ]; do sleep 1; done'
sleep 10

echo "Emulator ABI:"
adb shell getprop ro.product.cpu.abi

./gradlew installDevDebug

run_instrumented_tests() {
  local selection="$1"
  shift
  local results_dir="app/build/outputs/androidTest-results/connected"
  if [[ -d "$results_dir" ]]; then
    find "$results_dir" -type f -name 'TEST-*.xml' -delete
  fi
  ./gradlew connectedDevDebugAndroidTest "$@"
  python3 .github/scripts/assert-android-tests-ran.py "$selection"
}

suites="${ANDROID_TEST_SUITES:-all}"
suites="$(echo "$suites" | tr -d '[:space:]')"

if [[ -z "$suites" || "$suites" == "all" ]]; then
  run_instrumented_tests all
  exit 0
fi

if [[ "$suites" == "integration" ]]; then
  run_instrumented_tests integration \
    -Pandroid.testInstrumentationRunnerArguments.notAnnotation=to.bitkit.test.annotations.ComposeUi
  exit 0
fi

IFS=',' read -ra requested_suites <<< "$suites"
for suite in "${requested_suites[@]}"; do
  if [[ "$suite" == *.* || ! "$suite" =~ ^[A-Z][A-Za-z0-9]*$ ]]; then
    echo "::error::Invalid Android test annotation '$suite'. Use all or comma-separated simple annotation names such as ComposeUi."
    exit 1
  fi

  run_instrumented_tests "$suite" -PbitkitAndroidTestAnnotation="$suite"
done

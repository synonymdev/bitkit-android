#!/usr/bin/env bash

set -euo pipefail

# The emulator runner waits until the emulator's output closes. Processes that outlive the emulator
# (its crashpad_handler, the Gradle daemon, the adb server) can keep it open and hold the job until
# its timeout after the tests already finished. Stop them here, with bounded waits, so the test
# result decides the step.
EMULATOR_EXIT_TIMEOUT_SECONDS=30

stop_emulator() {
  ./gradlew --stop > /dev/null 2>&1 || true
  adb emu kill > /dev/null 2>&1 || true
  local exited=false
  for _ in $(seq "$EMULATOR_EXIT_TIMEOUT_SECONDS"); do
    if ! pgrep -f qemu-system > /dev/null; then
      exited=true
      break
    fi
    sleep 1
  done
  if [[ "$exited" != true ]]; then
    echo "::warning::Emulator did not exit ${EMULATOR_EXIT_TIMEOUT_SECONDS}s after emu kill; killing it."
    pkill -9 -f qemu-system || true
  fi
  pkill -9 -f crashpad_handler || true
  adb kill-server > /dev/null 2>&1 || true
}
trap stop_emulator EXIT

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

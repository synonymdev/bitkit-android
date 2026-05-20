#!/usr/bin/env bash

set -euo pipefail

adb wait-for-device
echo "Waiting for boot to complete..."
adb shell 'while [ -z "$(getprop sys.boot_completed)" ]; do sleep 1; done'
sleep 10

echo "Emulator ABI:"
adb shell getprop ro.product.cpu.abi

./gradlew installDevDebug

suites="${ANDROID_TEST_SUITES:-all}"
suites="$(echo "$suites" | tr -d '[:space:]')"

if [[ -z "$suites" || "$suites" == "all" ]]; then
  ./gradlew connectedDevDebugAndroidTest
  exit 0
fi

IFS=',' read -ra requested_suites <<< "$suites"
for suite in "${requested_suites[@]}"; do
  if [[ "$suite" == *.* || ! "$suite" =~ ^[A-Z][A-Za-z0-9]*$ ]]; then
    echo "::error::Invalid Android test annotation '$suite'. Use all or comma-separated simple annotation names such as ComposeUi."
    exit 1
  fi

  ./gradlew connectedDevDebugAndroidTest -PbitkitAndroidTestAnnotation="$suite"
done

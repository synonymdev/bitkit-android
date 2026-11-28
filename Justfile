set dotenv-load
set dotenv-filename := ".env"
set windows-shell := ["sh", "-cu"]

gradle := "./gradlew"
ndk_ver := "28.1.13356709"

default:
    @just list

list:
    @printf "%s\n" \
        "list" \
        "init" \
        "compile" \
        "run [docker]" \
        "build [TASK]" \
        "release" \
        "install" \
        "test" \
        "test file PATTERN" \
        "test android" \
        "test lane LANE" \
        "wear-install" \
        "wear-run [logs]" \
        "lint" \
        "lint baseline" \
        "format" \
        "deps" \
        "e2e [network|TASK] [TASK]" \
        "changelog [all|next|hotfix]" \
        "clean"

init:
    #!/usr/bin/env sh
    set -eu

    if [ -e .env ]; then
        echo ".env already exists"
        exit 0
    fi

    cp .env.example .env
    echo "Created .env"

compile:
    {{ gradle }} compileDevDebugKotlin

run mode="" logs="":
    #!/usr/bin/env sh
    set -eu

    app_id="to.bitkit.dev"
    app_dir="app/build/outputs/bitkit/devDebug"
    mode="{{ mode }}"
    logs="{{ logs }}"
    attach_logs=false

    if [ "$mode" = "logs" ]; then
        attach_logs=true
        mode=""
    fi
    if [ -n "$logs" ]; then
        if [ "$logs" != "logs" ]; then
            echo "usage: just run [docker] [logs]" >&2
            exit 1
        fi
        attach_logs=true
    fi
    if [ -n "$mode" ] && [ "$mode" != "docker" ]; then
        echo "usage: just run [docker] [logs]" >&2
        exit 1
    fi

    if ! command -v adb >/dev/null 2>&1; then
        echo "adb is required to run the app." >&2
        exit 1
    fi

    if [ -n "${ANDROID_SERIAL:-}" ]; then
        device_id="$ANDROID_SERIAL"
    else
        echo "Looking for connected Android devices..."
        device_id="$(
            adb devices -l \
                | awk 'NR > 1 && $2 == "device" && $1 !~ /^emulator-/ { print $1; exit }'
        )"

        if [ -z "$device_id" ]; then
            device_id="$(
                adb devices -l \
                    | awk 'NR > 1 && $2 == "device" { print $1; exit }'
            )"
        fi
    fi

    if [ -z "$device_id" ]; then
        echo "No connected Android device found." >&2
        exit 1
    fi

    device_name="$(
        adb -s "$device_id" shell getprop ro.product.model 2>/dev/null \
            | tr -d '\r' \
            || true
    )"

    if [ -z "$device_name" ]; then
        device_name="$device_id"
    fi

    echo "Using $device_name ($device_id)"

    build_env=""
    if [ "$mode" = "docker" ]; then
        echo "Forwarding bitkit-docker ports via adb reverse..."
        adb -s "$device_id" reverse tcp:23735 tcp:23735   # local lnd peer
        adb -s "$device_id" reverse tcp:23000 tcp:23000   # local lnurl-server
        build_env="E2E=true"
    fi

    echo "Building Debug app..."
    env $build_env {{ gradle }} :app:assembleDevDebug

    app_path="$(
        find "$app_dir" -maxdepth 1 -name '*-universal.apk' -type f \
            | sort \
            | tail -n 1
    )"

    if [ -z "$app_path" ]; then
        app_path="$(
            find "$app_dir" -maxdepth 1 -name '*.apk' -type f \
                | sort \
                | tail -n 1
        )"
    fi

    if [ -z "$app_path" ]; then
        echo "No APK found in $app_dir." >&2
        exit 1
    fi

    echo "Installing $app_path..."
    adb -s "$device_id" install -r "$app_path"

    echo "Launching $app_id..."
    adb -s "$device_id" shell am force-stop "$app_id"
    adb -s "$device_id" shell monkey -p "$app_id" -c android.intent.category.LAUNCHER 1 >/dev/null

    if [ "$attach_logs" != "true" ]; then
        echo "Launched $app_id"
        exit 0
    fi

    pid="$(
        adb -s "$device_id" shell pidof -s "$app_id" 2>/dev/null \
            | tr -d '\r' \
            || true
    )"

    if [ -z "$pid" ]; then
        echo "Launched $app_id"
        exit 0
    fi

    echo "Streaming logs for $app_id (pid $pid). Press Ctrl-C to stop."
    adb -s "$device_id" logcat --pid "$pid"

build task=":app:assembleDevDebug":
    {{ gradle }} {{ task }}

release:
    #!/usr/bin/env sh
    set -eu
    symbols_dir="app/build/outputs/native-debug-symbols/mainnetRelease"
    rm -f "$symbols_dir"/native-debug-symbols*.zip
    NDK_VERSION={{ ndk_ver }} {{ gradle }} :app:assembleMainnetRelease :app:bundleMainnetRelease
    NDK_VERSION={{ ndk_ver }} {{ gradle }} :app:syncNativeDebugSymbolArtifacts
    scripts/create-native-debug-symbols.sh
    symbols="$(find "$symbols_dir" -maxdepth 1 -name 'native-debug-symbols-*.zip' -type f | sort | tail -n 1)"
    echo "Attach this exact file to GitHub releases, upload it to Play Console for this release, and verify Play lists it: $symbols"

install:
    {{ gradle }} :app:installDevDebug

test target="" value="":
    {{ if target == "" { gradle + " testDevDebugUnitTest" } else if target == "android" { gradle + " :app:connectedDevDebugAndroidTest" } else if target == "file" { if value == "" { error("usage: just test file PATTERN") } else { "tasks=; for m in app wear; do if grep -rqF -- '" + value + "' $m/src/test; then tasks=\"$tasks :$m:testDevDebugUnitTest\"; fi; done; " + gradle + " ${tasks:-:app:testDevDebugUnitTest} --tests '" + value + "'" } } else if target == "lane" { if value == "" { error("usage: just test lane LANE") } else { gradle + " :app:connectedDevDebug" + value + "AndroidTest" } } else { error("usage: just test [file PATTERN|android|lane LANE]") } }}

# Install the dev watch app on the device in ANDROID_SERIAL, or else on the first connected watch.
wear-install:
    #!/usr/bin/env sh
    set -eu
    if [ -z "${ANDROID_SERIAL:-}" ]; then
        for serial in $(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }'); do
            if adb -s "$serial" shell getprop ro.build.characteristics | grep -q watch; then
                ANDROID_SERIAL="$serial"
                break
            fi
        done
    fi
    if [ -z "${ANDROID_SERIAL:-}" ]; then
        echo "No connected watch found; set ANDROID_SERIAL." >&2
        exit 1
    fi
    if ! adb -s "$ANDROID_SERIAL" shell getprop ro.build.characteristics | grep -q watch; then
        echo "$ANDROID_SERIAL is not a watch; the watch app shares the phone app's id and would replace it." >&2
        exit 1
    fi
    echo "Installing on $ANDROID_SERIAL"
    ANDROID_SERIAL="$ANDROID_SERIAL" {{ gradle }} :wear:installDevDebug

# Install and launch the dev watch app; `logs` streams its logcat afterwards.
wear-run logs="":
    #!/usr/bin/env sh
    set -eu
    serial="${ANDROID_SERIAL:-}"
    if [ -z "$serial" ]; then
        for candidate in $(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }'); do
            if adb -s "$candidate" shell getprop ro.build.characteristics | grep -q watch; then
                serial="$candidate"
                break
            fi
        done
    fi
    ANDROID_SERIAL="$serial" just wear-install
    adb -s "$serial" shell am force-stop to.bitkit.dev
    adb -s "$serial" shell am start -n to.bitkit.dev/to.bitkit.wear.ui.MainActivity >/dev/null
    echo "Launched to.bitkit.dev on $serial"
    if [ "{{ logs }}" = "logs" ]; then
        pid="$(adb -s "$serial" shell pidof -s to.bitkit.dev | tr -d '\r' || true)"
        if [ -n "$pid" ]; then
            adb -s "$serial" logcat --pid "$pid"
        fi
    fi

lint target="":
    {{ if target == "" { gradle + " detekt --rerun-tasks && " + gradle + " lintDevDebug" } else if target == "baseline" { gradle + " updateLintBaselineDevDebug" } else { error("usage: just lint [baseline]") } }}

format:
    {{ gradle }} detekt --auto-correct --rerun-tasks

deps:
    scripts/update-verification-metadata.sh

e2e mode="" value="" task=":app:assembleDevRelease":
    {{ if mode == "" { "E2E=true " + gradle + " " + task } else if mode == "network" { "E2E=true E2E_BACKEND=network " + gradle + " " + if value == "" { task } else { value } } else { "E2E=true " + gradle + " " + mode } }}

changelog target="all":
    ./scripts/preview-changelog.sh --target {{ target }}

clean:
    {{ gradle }} clean

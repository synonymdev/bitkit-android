set dotenv-load
set dotenv-filename := ".env"
set windows-shell := ["sh", "-cu"]

gradle := "./gradlew"

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
        "lint" \
        "lint baseline" \
        "format" \
        "translations pull" \
        "translations push source" \
        "translations push all" \
        "e2e [network|no geo|TASK] [TASK]" \
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

run mode="":
    #!/usr/bin/env sh
    set -eu

    app_id="to.bitkit.dev"
    app_dir="app/build/outputs/apk/dev/debug"
    mode="{{ mode }}"

    if [ -n "$mode" ] && [ "$mode" != "docker" ]; then
        echo "usage: just run [docker]" >&2
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
        adb -s "$device_id" reverse tcp:60001 tcp:60001  # local Electrum
        adb -s "$device_id" reverse tcp:6288 tcp:6288     # local homegate
        adb -s "$device_id" reverse tcp:9735 tcp:9735     # local lnd peer
        build_env="E2E=true"
    fi

    echo "Building Debug app..."
    env $build_env {{ gradle }} assembleDevDebug

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

build task="assembleDevDebug":
    {{ gradle }} {{ task }}

release:
    {{ gradle }} assembleMainnetRelease bundleMainnetRelease

install:
    {{ gradle }} installDevDebug

test target="" value="":
    {{ if target == "" { gradle + " testDevDebugUnitTest" } else if target == "android" { gradle + " connectedDevDebugAndroidTest" } else if target == "file" { if value == "" { error("usage: just test file PATTERN") } else { gradle + " testDevDebugUnitTest --tests '" + value + "'" } } else if target == "lane" { if value == "" { error("usage: just test lane LANE") } else { gradle + " connectedDevDebug" + value + "AndroidTest" } } else { error("usage: just test [file PATTERN|android|lane LANE]") } }}

lint target="":
    {{ if target == "" { gradle + " detekt --rerun-tasks" } else if target == "baseline" { gradle + " detektBaseline --rerun-tasks" } else { error("usage: just lint [baseline]") } }}

format:
    {{ gradle }} detekt --auto-correct --rerun-tasks

translations action value="":
    {{ if action == "pull" { "./scripts/pull-translations.sh" } else if action == "push" { if value == "source" { "tx push --source" } else if value == "all" { "./scripts/push-translations.sh" } else { error("usage: just translations pull|push source|push all") } } else { error("usage: just translations pull|push source|push all") } }}

e2e mode="" value="" task="assembleDevRelease":
    {{ if mode == "" { "E2E=true " + gradle + " " + task } else if mode == "network" { "E2E=true E2E_BACKEND=network " + gradle + " " + if value == "" { task } else { value } } else if mode == "no" { if value == "geo" { "GEO=false E2E=true " + gradle + " " + task } else { error("usage: just e2e no geo [TASK]") } } else { "E2E=true " + gradle + " " + mode } }}

changelog target="all":
    ./scripts/preview-changelog.sh --target {{ target }}

clean:
    {{ gradle }} clean

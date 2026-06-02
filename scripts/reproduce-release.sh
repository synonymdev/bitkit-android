#!/usr/bin/env bash
set -euo pipefail

repo_root=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$repo_root"

output_dir=${OUTPUT_DIR:-.ai/reproducible-release}
bundletool_version=${BUNDLETOOL_VERSION:-1.18.1}
bundletool_sha256=${BUNDLETOOL_SHA256:-675786493983787ffa11550bdb7c0715679a44e1643f3ff980a529e9c822595c}
bundletool_url=${BUNDLETOOL_URL:-https://github.com/google/bundletool/releases/download/${bundletool_version}/bundletool-all-${bundletool_version}.jar}
bundletool_jar=${BUNDLETOOL_JAR:-${output_dir}/tools/bundletool-all-${bundletool_version}.jar}

artifacts_dir="$output_dir/artifacts"
checksums_dir="$output_dir/checksums"
extracted_dir="$output_dir/extracted-apks"
native_dir="$output_dir/native-libs"

sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$@"
    else
        shasum -a 256 "$@"
    fi
}

sha256_value() {
    sha256_file "$1" | awk '{ print $1 }'
}

checksum_tree() {
    local root=$1
    local output=$2

    mkdir -p "$(dirname "$output")"
    if [[ ! -d "$root" ]]; then
        : > "$output"
        return
    fi

    (
        cd "$root"
        find . -type f | LC_ALL=C sort | while IFS= read -r file; do
            file=${file#./}
            sha256_file "$file"
        done
    ) > "$output"
}

file_mtime() {
    if stat -f %m "$1" >/dev/null 2>&1; then
        stat -f %m "$1"
    else
        stat -c %Y "$1"
    fi
}

latest_file() {
    local root=$1
    local pattern=$2
    local latest=
    local latest_mtime=0
    local candidate
    local candidate_mtime

    while IFS= read -r -d '' candidate; do
        candidate_mtime=$(file_mtime "$candidate")
        if [[ -z "$latest" || "$candidate_mtime" -gt "$latest_mtime" ]]; then
            latest=$candidate
            latest_mtime=$candidate_mtime
        fi
    done < <(find "$root" -type f -name "$pattern" -print0)

    printf '%s\n' "$latest"
}

download_bundletool() {
    if [[ -f "$bundletool_jar" ]]; then
        local actual
        actual=$(sha256_value "$bundletool_jar")
        if [[ "$actual" == "$bundletool_sha256" ]]; then
            return
        fi
        rm -f "$bundletool_jar"
    fi

    mkdir -p "$(dirname "$bundletool_jar")"
    local tmp
    tmp=$(mktemp)
    curl --fail --location --silent --show-error "$bundletool_url" --output "$tmp"

    local actual
    actual=$(sha256_value "$tmp")
    if [[ "$actual" != "$bundletool_sha256" ]]; then
        echo "bundletool checksum mismatch: expected '$bundletool_sha256', got '$actual'" >&2
        rm -f "$tmp"
        exit 1
    fi

    mv "$tmp" "$bundletool_jar"
}

signing_args=()
if [[ -n "${KEYSTORE_FILE:-}" ]]; then
    : "${KEYSTORE_PASSWORD:?KEYSTORE_PASSWORD is required when KEYSTORE_FILE is set}"
    : "${KEY_ALIAS:?KEY_ALIAS is required when KEYSTORE_FILE is set}"
    signing_args+=(
        "--ks=$KEYSTORE_FILE"
        "--ks-pass=pass:$KEYSTORE_PASSWORD"
        "--ks-key-alias=$KEY_ALIAS"
        "--key-pass=pass:${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"
    )
fi

rm -rf "$artifacts_dir" "$checksums_dir" "$extracted_dir" "$native_dir"
mkdir -p "$artifacts_dir" "$checksums_dir" "$extracted_dir" "$native_dir"

if [[ "${SKIP_GRADLE_BUILD:-false}" != "true" ]]; then
    ./gradlew bundleMainnetRelease --no-daemon --stacktrace
fi

aab_path=${AAB_PATH:-}
if [[ -z "$aab_path" ]]; then
    aab_path=$(latest_file app/build/outputs/bundle/mainnetRelease 'bitkit-mainnet-release-*.aab')
fi
if [[ ! -f "$aab_path" ]]; then
    echo "AAB not found. Set AAB_PATH or run bundleMainnetRelease first." >&2
    exit 1
fi

download_bundletool

aab_name=$(basename "$aab_path")
apks_path="$artifacts_dir/${aab_name%.aab}.apks"
cp "$aab_path" "$artifacts_dir/$aab_name"

java -jar "$bundletool_jar" build-apks \
    --bundle="$aab_path" \
    --output="$apks_path" \
    --mode=default \
    --overwrite \
    "${signing_args[@]}"

unzip -q "$apks_path" -d "$extracted_dir"

find "$extracted_dir" -type f -name '*.apk' | LC_ALL=C sort > "$output_dir/apks.txt"
grep -E 'arm64[-_]v8a|arm64-v8a|arm64_v8a' "$output_dir/apks.txt" > "$output_dir/arm64-apks.txt" || true

while IFS= read -r apk; do
    apk_name=$(basename "$apk" .apk)
    mkdir -p "$native_dir/$apk_name"
    unzip -q -o "$apk" 'lib/arm64-v8a/*.so' -d "$native_dir/$apk_name" 2>/dev/null || true
done < "$output_dir/apks.txt"

find "$native_dir" -type f -name '*.so' | LC_ALL=C sort > "$output_dir/arm64-native-libs.txt"

checksum_tree "$artifacts_dir" "$checksums_dir/release-artifacts.sha256"
checksum_tree "$extracted_dir" "$checksums_dir/extracted-apks.sha256"
checksum_tree "$native_dir" "$checksums_dir/arm64-native-libs.sha256"

diffoscope_status=0
if [[ -n "${DIFFOSCOPE_COMPARE_DIR:-}" && -d "$DIFFOSCOPE_COMPARE_DIR" ]]; then
    if command -v diffoscope >/dev/null 2>&1; then
        diffoscope "$DIFFOSCOPE_COMPARE_DIR" "$extracted_dir" \
            --html "$output_dir/diffoscope.html" \
            > "$output_dir/diffoscope.txt" || diffoscope_status=$?
    else
        echo "diffoscope is not installed; skipping comparison." > "$output_dir/diffoscope.txt"
    fi
fi

cat > "$output_dir/README.txt" <<EOF
Bitkit reproducible release artifacts

AAB: artifacts/$aab_name
APK set: artifacts/$(basename "$apks_path")
Extracted APKs: extracted-apks/
Arm64 APK list: arm64-apks.txt
Arm64 native lib list: arm64-native-libs.txt
Checksums: checksums/
Bundletool: $bundletool_version
EOF

echo "Wrote reproducibility artifacts to '$output_dir'."

if [[ "$diffoscope_status" -ne 0 ]]; then
    echo "diffoscope found differences; see '$output_dir/diffoscope.html' and '$output_dir/diffoscope.txt'." >&2
    exit "$diffoscope_status"
fi

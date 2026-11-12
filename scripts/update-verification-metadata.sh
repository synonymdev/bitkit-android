#!/usr/bin/env sh
# Records sha256 checksums for every dependency the CI and release builds resolve into
# gradle/verification-metadata.xml. Existing entries and the <configuration> block are kept.
set -eu

script_dir=$(cd "$(dirname "$0")" && pwd)
repo_root=$(cd "$script_dir/.." && pwd)
cd "$repo_root"

metadata="gradle/verification-metadata.xml"
aapt2_repo="https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2"

# Release tasks use tnet: no dependency differs per flavor, and mainnet needs secret config.
# Without keystore.properties they are signed with the tracked debug keystore, which is
# enough here because signing does not change what is resolved.
KEYSTORE_FILE=app/debug.keystore KEYSTORE_PASSWORD=android \
    KEY_ALIAS=androiddebugkey KEY_PASSWORD=android \
    ./gradlew --write-verification-metadata sha256 --no-daemon --no-configuration-cache --no-build-cache \
    help compileDevDebugKotlin testDevDebugUnitTest detekt lintDevDebug \
    assembleDevDebug assembleDevDebugAndroidTest assembleTnetRelease bundleTnetRelease \
    :app:syncNativeDebugSymbolArtifacts

# Gradle only records the aapt2 binary for the current host, so add the other platforms
# from Google Maven to keep the file valid on every OS.
tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT
trap 'exit 1' HUP INT TERM

sha256_of() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1"
    else
        sha256sum "$1"
    fi | awk '{ print $1 }'
}

aapt2_versions=$(
    sed -n 's/.*<component group="com\.android\.tools\.build" name="aapt2" version="\([^"]*\)".*/\1/p' "$metadata"
)
for version in $aapt2_versions; do
    for platform in linux osx windows; do
        artifact="aapt2-$version-$platform.jar"
        if grep -qF "<artifact name=\"$artifact\">" "$metadata"; then
            continue
        fi

        echo "Adding $artifact from Google Maven"
        curl -fsSL -o "$tmp_dir/$artifact" "$aapt2_repo/$version/$artifact"
        checksum=$(sha256_of "$tmp_dir/$artifact")
        awk -v component="<component group=\"com.android.tools.build\" name=\"aapt2\" version=\"$version\">" \
            -v artifact="$artifact" -v checksum="$checksum" '
            index($0, component) { in_component = 1 }
            in_component && /<\/component>/ {
                match($0, /^ */)
                indent = substr($0, 1, RLENGTH)
                print indent "   <artifact name=\"" artifact "\">"
                print indent "      <sha256 value=\"" checksum "\" origin=\"Google Maven\"/>"
                print indent "   </artifact>"
                in_component = 0
            }
            { print }
        ' "$metadata" > "$tmp_dir/metadata.xml"
        cat "$tmp_dir/metadata.xml" > "$metadata"
    done
done

# Fail on host-specific artifacts that lack their other-platform variants.
missing=""
host_artifacts=$(
    grep -oE 'artifact name="[^"]*-(osx|macos|mac|darwin)[-.][^"]*"' "$metadata" \
        | sed 's/^artifact name="//; s/"$//' \
        | sort -u
)
for artifact in $host_artifacts; do
    prefix=$(printf '%s\n' "$artifact" | sed -E 's/-(osx|macos|mac|darwin)[-.].*$//')
    for platform in linux windows; do
        if ! grep -qF "<artifact name=\"$prefix-$platform" "$metadata"; then
            missing="$missing $artifact($platform)"
        fi
    done
done
if [ -n "$missing" ]; then
    echo "Missing other-platform variants for:$missing" >&2
    exit 1
fi

echo "Updated $metadata"

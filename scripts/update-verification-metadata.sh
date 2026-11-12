#!/usr/bin/env sh
# Records sha256 checksums for every dependency the CI and release builds resolve into
# gradle/verification-metadata.xml. Existing entries and the <configuration> block are kept.
set -eu

script_dir=$(cd "$(dirname "$0")" && pwd)
repo_root=$(cd "$script_dir/.." && pwd)
cd "$repo_root"

metadata="gradle/verification-metadata.xml"
aapt2_repo="https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2"

# A local Bitkit Core AAR adds dependencies that only local builds use, so refuse to record them.
# Only the property name is matched, so no value is printed.
for properties in gradle.properties "${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"; do
    if [ -f "$properties" ] &&
        grep -qE '^[[:space:]]*(systemProp\.org\.gradle\.project\.)?bitkitCoreAar([[:space:]=:]|$)' "$properties"; then
        echo "Remove bitkitCoreAar from $properties before running this script." >&2
        exit 1
    fi
done
if [ -n "${ORG_GRADLE_PROJECT_bitkitCoreAar+set}" ]; then
    echo "Unset ORG_GRADLE_PROJECT_bitkitCoreAar before running this script." >&2
    exit 1
fi
case "${GRADLE_OPTS-} ${JAVA_OPTS-}" in
    *org.gradle.project.bitkitCoreAar*)
        echo "Remove org.gradle.project.bitkitCoreAar from GRADLE_OPTS and JAVA_OPTS before running this script." >&2
        exit 1
        ;;
esac

# Release tasks use tnet: no dependency differs per flavor, and mainnet needs secret config.
# Without keystore.properties they are signed with the tracked debug keystore, which is
# enough here because signing does not change what is resolved.
# useMavenLocal=false keeps locally published artifacts out of the recorded checksums.
KEYSTORE_FILE=app/debug.keystore KEYSTORE_PASSWORD=android \
    KEY_ALIAS=androiddebugkey KEY_PASSWORD=android \
    ./gradlew --write-verification-metadata sha256 --no-daemon --no-configuration-cache --no-build-cache \
    -PuseMavenLocal=false \
    testDevDebugUnitTest detekt lintDevDebug \
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
        case $checksum in
            *[!0-9a-f]*) checksum="" ;;
        esac
        if [ "${#checksum}" -ne 64 ]; then
            echo "Could not compute the sha256 checksum of $artifact" >&2
            exit 1
        fi
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
    grep -oE 'artifact name="[^"]*-(linux|osx|windows)[-.][^"]*"' "$metadata" \
        | sed 's/^artifact name="//; s/"$//' \
        | sort -u
)
for artifact in $host_artifacts; do
    for platform in linux osx windows; do
        sibling=$(printf '%s\n' "$artifact" | sed -E "s/-(linux|osx|windows)([-.])/-$platform\\2/")
        if ! grep -qF "<artifact name=\"$sibling\">" "$metadata"; then
            missing="$missing $sibling"
        fi
    done
done
if [ -n "$missing" ]; then
    echo "Missing other-platform variants:$missing" >&2
    exit 1
fi

echo "Updated $metadata"

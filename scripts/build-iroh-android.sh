#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
revision=5e451092dba0c1a09ee83ff6e5be37b1152a5c58
work="${IROH_BUILD_DIR:-$root/.local-native/build/iroh-1.1.0-$revision}"
output="${IROH_ANDROID_AAR:-$root/.local-native/iroh-android-1.1.0-16kb.aar}"
ndk="${ANDROID_NDK_HOME:-${ANDROID_HOME:?Set ANDROID_HOME or ANDROID_NDK_HOME}/ndk/28.2.13676358}"
mkdir -p "$work" "$(dirname "$output")"
work="$(cd "$work" && pwd)"

fetch() {
    local url="$1" destination="$2" checksum="$3"
    if [ ! -f "$destination" ]; then
        curl --fail --location --silent --show-error "$url" --output "$destination"
    fi
    python3 - "$destination" "$checksum" <<'PY'
from pathlib import Path
import hashlib, sys
assert hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest() == sys.argv[2], 'Download checksum mismatch'
PY
}

fetch "https://codeload.github.com/n0-computer/iroh-ffi/tar.gz/$revision" "$work/source.tar.gz" \
    aa40a880907b11a71b6c17f985083f2bc3df0e1da89545e5fd7b0dd579190b79
fetch "https://repo.maven.apache.org/maven2/computer/iroh/iroh-android/1.1.0/iroh-android-1.1.0.aar" "$work/published.aar" \
    ed747f627da6dad314b25b9ff17d38232d8d75cb31e663af348368e6be845ab8
if [ ! -d "$work/source" ]; then
    mkdir "$work/source"
    tar -xzf "$work/source.tar.gz" --strip-components=1 -C "$work/source"
fi
python3 - "$work/source.tar.gz" "$work/source" <<'PYVERIFY'
from pathlib import Path
import sys, tarfile
with tarfile.open(sys.argv[1]) as archive:
    for member in archive.getmembers():
        if member.isfile():
            relative = Path(member.name).relative_to(Path(member.name).parts[0])
            assert (Path(sys.argv[2]) / relative).read_bytes() == archive.extractfile(member).read(), \
                f'Cached source differs from the pinned archive: {relative}'
PYVERIFY

export ANDROID_NDK_HOME="$ndk"
export CARGO_TARGET_DIR="$work/target"
export RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
cd "$work/source"
cargo ndk -o "$work/jni" -t arm64-v8a -t armeabi-v7a -t x86_64 --platform 28 \
    --manifest-path "$work/source/Cargo.toml" build --release --locked --lib -p iroh-ffi -j "${IROH_BUILD_JOBS:-6}"
python3 "$root/scripts/package-iroh-android.py" "$work" "$output" "$ndk" "$revision"

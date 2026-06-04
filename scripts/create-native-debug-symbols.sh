#!/usr/bin/env sh
set -eu

script_dir=$(cd "$(dirname "$0")" && pwd)
repo_root=$(cd "$script_dir/.." && pwd)
cd "$repo_root"

variant="mainnetRelease"
output="app/build/outputs/native-debug-symbols/$variant/native-debug-symbols.zip"
output_dir=$(dirname "$output")

if [ -f "$output" ]; then
    zip -T "$output" >/dev/null
    echo "Native debug symbols: $output"
    ls -lh "$output"
    exit 0
fi

native_lib_dir=""
for candidate in "app/build/intermediates/merged_native_libs/$variant"/*/out/lib; do
    if [ -d "$candidate" ]; then
        native_lib_dir="$candidate"
        break
    fi
done

if [ -z "$native_lib_dir" ]; then
    echo "No merged native libraries found for '$variant'." >&2
    exit 1
fi

tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT

for abi in arm64-v8a armeabi-v7a; do
    source_dir="$native_lib_dir/$abi"
    if [ ! -d "$source_dir" ]; then
        echo "Missing native libraries for '$abi' in '$native_lib_dir'." >&2
        exit 1
    fi

    mkdir -p "$tmp_dir/$abi"
    found_lib=false
    for lib in "$source_dir"/*.so; do
        if [ -f "$lib" ]; then
            cp "$lib" "$tmp_dir/$abi/"
            found_lib=true
        fi
    done

    if [ "$found_lib" = false ]; then
        echo "No native libraries found for '$abi' in '$source_dir'." >&2
        exit 1
    fi
done

mkdir -p "$output_dir"
rm -f "$output"

(
    cd "$tmp_dir"
    zip -qr "$repo_root/$output" arm64-v8a armeabi-v7a
)

zip -T "$output" >/dev/null
echo "Native debug symbols: $output"
ls -lh "$output"

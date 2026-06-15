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
bundle_output_dir=app/build/outputs/bundle/mainnetRelease

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

password_files=()
temp_dirs=()
password_file_result=
cleanup_files() {
    if [[ "${#password_files[@]}" -gt 0 ]]; then
        rm -f "${password_files[@]}"
    fi
    if [[ "${#temp_dirs[@]}" -gt 0 ]]; then
        rm -rf "${temp_dirs[@]}"
    fi
}
trap cleanup_files EXIT

write_password_file() {
    local value=$1
    local file

    file=$(mktemp)
    chmod 600 "$file"
    printf '%s' "$value" > "$file"
    password_files+=("$file")
    password_file_result=$file
}

verify_rsa_signing_key() {
    local verifier_dir
    local verifier_source
    local key_algorithm

    verifier_dir=$(mktemp -d)
    temp_dirs+=("$verifier_dir")
    verifier_source="$verifier_dir/VerifySigningKeyAlgorithm.java"

    cat > "$verifier_source" <<'JAVA'
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Arrays;

public class VerifySigningKeyAlgorithm {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Expected keystore, alias, store password file, and key password file.");
        }

        char[] storePassword = Files.readString(Path.of(args[2])).toCharArray();
        char[] keyPassword = Files.readString(Path.of(args[3])).toCharArray();
        try {
            KeyStore keyStore = KeyStore.getInstance(new File(args[0]), storePassword);
            Key key = keyStore.getKey(args[1], keyPassword);
            if (key != null) {
                System.out.println(key.getAlgorithm());
                return;
            }

            Certificate certificate = keyStore.getCertificate(args[1]);
            if (certificate == null) {
                throw new IllegalArgumentException("Signing key alias was not found.");
            }
            System.out.println(certificate.getPublicKey().getAlgorithm());
        } finally {
            Arrays.fill(storePassword, '\0');
            Arrays.fill(keyPassword, '\0');
        }
    }
}
JAVA

    if ! key_algorithm=$(java "$verifier_source" "$KEYSTORE_FILE" "$KEY_ALIAS" "$keystore_password_file" "$key_password_file"); then
        echo "Failed to inspect release signing key algorithm." >&2
        exit 1
    fi

    if [[ "$key_algorithm" != "RSA" ]]; then
        echo "Release reproducibility requires an RSA signing key; '$KEY_ALIAS' uses '$key_algorithm'." >&2
        echo "EC/ECDSA signatures are not byte-stable across signing runs." >&2
        exit 1
    fi
}

preserve_overlapping_comparison_dir() {
    local compare_real
    local output_real
    local preserved_compare_dir

    if [[ -z "${DIFFOSCOPE_COMPARE_DIR:-}" || ! -d "$DIFFOSCOPE_COMPARE_DIR" || ! -d "$output_dir" ]]; then
        return
    fi

    compare_real=$(cd "$DIFFOSCOPE_COMPARE_DIR" && pwd -P)
    output_real=$(cd "$output_dir" && pwd -P)
    if [[ "$compare_real" != "$output_real" && "$compare_real" != "$output_real/"* ]]; then
        return
    fi

    preserved_compare_dir=$(mktemp -d)
    temp_dirs+=("$preserved_compare_dir")
    cp -a "$DIFFOSCOPE_COMPARE_DIR"/. "$preserved_compare_dir"/
    export DIFFOSCOPE_COMPARE_DIR="$preserved_compare_dir"
}

if [[ -z "${KEYSTORE_FILE:-}" || -z "${KEYSTORE_PASSWORD:-}" || -z "${KEY_ALIAS:-}" ]]; then
    echo "Release signing requires KEYSTORE_FILE, KEYSTORE_PASSWORD, and KEY_ALIAS." >&2
    exit 1
fi
if [[ ! -f "$KEYSTORE_FILE" ]]; then
    echo "KEYSTORE_FILE not found: '$KEYSTORE_FILE'." >&2
    exit 1
fi
export KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"

write_password_file "$KEYSTORE_PASSWORD"
keystore_password_file=$password_file_result
write_password_file "$KEY_PASSWORD"
key_password_file=$password_file_result
verify_rsa_signing_key
signing_args=(
    "--ks=$KEYSTORE_FILE"
    "--ks-pass=file:$keystore_password_file"
    "--ks-key-alias=$KEY_ALIAS"
    "--key-pass=file:$key_password_file"
)

aab_path=
aab_name=
if [[ "${SKIP_GRADLE_BUILD:-false}" == "true" ]]; then
    aab_path=${AAB_PATH:-}
    if [[ -z "$aab_path" ]]; then
        echo "AAB_PATH is required when SKIP_GRADLE_BUILD=true." >&2
        exit 1
    fi
    if [[ ! -f "$aab_path" ]]; then
        echo "AAB not found. Set AAB_PATH or run bundleMainnetRelease first." >&2
        exit 1
    fi

    aab_name=$(basename "$aab_path")
    preserved_aab_dir=$(mktemp -d)
    temp_dirs+=("$preserved_aab_dir")
    cp "$aab_path" "$preserved_aab_dir/$aab_name"
    aab_path="$preserved_aab_dir/$aab_name"
fi

preserve_overlapping_comparison_dir
rm -rf "$artifacts_dir" "$checksums_dir" "$extracted_dir" "$native_dir"
rm -f \
    "$output_dir/README.txt" \
    "$output_dir/apks.txt" \
    "$output_dir/arm64-apks.txt" \
    "$output_dir/arm64-native-libs.txt" \
    "$output_dir/diffoscope.html" \
    "$output_dir/diffoscope.txt"
mkdir -p "$artifacts_dir" "$checksums_dir" "$extracted_dir" "$native_dir"

if [[ "${SKIP_GRADLE_BUILD:-false}" != "true" ]]; then
    rm -rf "$bundle_output_dir"
    empty_maven_local_dir=$(mktemp -d)
    temp_dirs+=("$empty_maven_local_dir")
    env \
        E2E=false \
        E2E_BACKEND=local \
        E2E_HOMEGATE_URL=http://127.0.0.1:6288 \
        GEO=true \
        PAYKIT_UI_DISABLED=false \
        TREZOR_BRIDGE=false \
        TREZOR_BRIDGE_URL=http://10.0.2.2:21325 \
        ./gradlew \
        -Dmaven.repo.local="$empty_maven_local_dir" \
        bundleMainnetRelease \
        --no-daemon \
        --stacktrace
fi

if [[ -z "$aab_path" ]]; then
    aab_path=$(latest_file "$bundle_output_dir" 'bitkit-mainnet-release-*.aab')
fi
if [[ ! -f "$aab_path" ]]; then
    echo "AAB not found. Set AAB_PATH or run bundleMainnetRelease first." >&2
    exit 1
fi

download_bundletool

if [[ -z "$aab_name" ]]; then
    aab_name=$(basename "$aab_path")
fi
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
grep -E 'arm64[-_]v8a' "$output_dir/apks.txt" > "$output_dir/arm64-apks.txt" || true

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

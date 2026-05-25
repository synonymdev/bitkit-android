# Reproducible builds

This document captures the current Bitkit Android release reproduction flow for WalletScrutiny-style review.

## Current release target

- Flavor/build type: `mainnetRelease`
- Gradle wrapper: `8.13`
- Android Gradle Plugin: `8.13.2`
- Java: `17`
- Compile SDK: `36`
- Bundletool: `1.18.1`
- AAB output: `app/build/outputs/bundle/mainnetRelease/`
- APK output: `app/build/outputs/apk/mainnet/release/`

The release build needs the private mainnet Firebase config at `app/src/mainnetRelease/google-services.json` and release signing material. CI writes those files from protected GitHub environment secrets; external verifiers need an equivalent file from the project or an agreed public/non-secret release config strategy.

## Local reproduction

Configure GitHub Packages credentials without committing secrets:

```sh
export GITHUB_ACTOR=YOUR_GITHUB_USERNAME
export GITHUB_TOKEN=YOUR_READ_PACKAGES_TOKEN
```

Configure release signing without `keystore.properties`:

```sh
export KEYSTORE_FILE=/absolute/path/to/bitkit.keystore
export KEYSTORE_PASSWORD=...
export KEY_ALIAS=...
export KEY_PASSWORD=...
```

Build the mainnet release bundle and recreate APK splits:

```sh
scripts/reproduce-release.sh
```

The script writes artifacts under `.ai/reproducible-release/` by default:

- `artifacts/*.aab`
- `artifacts/*.apks`
- `extracted-apks/`
- `checksums/release-artifacts.sha256`
- `checksums/extracted-apks.sha256`
- `checksums/arm64-native-libs.sha256`
- `arm64-apks.txt`
- `arm64-native-libs.txt`

To reuse an existing AAB:

```sh
SKIP_GRADLE_BUILD=true AAB_PATH=/path/to/bitkit-mainnet-release-181.aab scripts/reproduce-release.sh
```

## GitHub workflow

The manual `Reproducible Release` workflow builds `bundleMainnetRelease`, recreates APK splits with bundletool, extracts the `arm64-v8a` native libraries, and uploads checksums plus reproduction artifacts. Workflow behavior can only be fully verified after merge because GitHub Actions workflow changes are only active for PRs opened after the workflow change is merged.

If a comparison artifact from this repository is available in GitHub Actions, pass its artifact name and source workflow run id to the manual workflow inputs. The workflow installs `diffoscope`, writes `diffoscope.html` and `diffoscope.txt`, and fails when the generated APK split tree differs from the comparison artifact.

## Manual diffoscope checks

Compare generated APK splits against a downloaded or previously generated APK set:

```sh
diffoscope path/to/reference-apks .ai/reproducible-release/extracted-apks \
  --html .ai/reproducible-release/diffoscope.html
```

Compare only the arm64 native libraries:

```sh
diffoscope path/to/reference-native-libs .ai/reproducible-release/native-libs \
  --html .ai/reproducible-release/native-libs-diffoscope.html
```

## Known WalletScrutiny issue

WalletScrutiny issue `synonymdev/bitkit-android#953` previously reported that most release APK contents reproduced, with remaining differences in native libraries inside the `arm64-v8a` split.

Known mappings:

- `libbitkitcore.so` and `libpubky_app_specs...so` come from `com.synonym:bitkit-core-android:0.1.58`
- `libdatastore_shared_counter.so` comes from `androidx.datastore:datastore-core:1.2.0`
- `libjnidispatch.so` comes from `net.java.dev.jna:jna:5.18.1`

The app repository can provide a stable release recipe and artifact checksums. The remaining native reproducibility work is upstream artifact provenance, especially for Rust-produced Android libraries.

## Upstream native follow-ups

For Rust/native Android artifacts, the upstream repositories should publish reproducible AAR/native library builds with:

- pinned Rust toolchain
- pinned Android NDK
- committed `Cargo.lock`
- stable build paths
- `SOURCE_DATE_EPOCH`
- `codegen-units = 1`
- path remapping
- deterministic stripping
- published AAR and native `.so` checksums

# Bitkit Android

Bitkit Android Native app.

## Prerequisites

1. Download `google-services.json` to `./app` from FCM Console
2. Run Polar with the default initial network

## Dev Tips

- To communicate from host to emulator use:
  `127.0.0.1` and setup port forwarding via ADB, eg. `adb forward tcp:9777 tcp:9777`
- To communicate from emulator to host use:
  `10.0.2.2` instead of `localhost`

### Linting

This project uses detekt with default ktlint and compose-rules for linting Kotlin and Compose code.

Recommended Android Studio plugins:
- EditorConfig
- Detekt

#### Commands

```sh
./gradlew detekt # run analysis + formatting check
./gradlew detekt --auto-correct # auto-fix formatting issues
```
Lint reports are generated in: `app/build/reports/detekt/`.

## Localization
See repo: https://github.com/synonymdev/bitkit-transifex-sync

## Bitcoin Networks
The build config supports building 3 different apps for the 3 bitcoin networks (mainnet, testnet, regtest) via the 3 build flavors:
- dev flavour = regtest
- mainnet flavour = mainnet
- tnet flavour = testnet

## Build for Release

**Prerequisite**: setup the signing config:
- Add the keystore file to root, eg. `./release.keystore`
- Add `keystore.properties` to root of the project (see `keystore.properties.template`)

### Routine:
Increment `versionCode` and `versionName` in `app/build.gradle.kts`, then run:
```sh
./gradlew assembleDevRelease
# ./gradlew assembleRelease # for all flavors
```

APK is generated in `app/build/outputs/apk/_flavor_/release`. (_flavor_ can be any of 'dev', 'mainnet', 'tnet').
Example for dev: `app/build/outputs/apk/dev/release`

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

## Localization
See repo: https://github.com/synonymdev/bitkit-transifex-sync

## Build for Release

**Prerequisite**: setup the signing config:
- Add the keystore file to root, eg. `./release.keystore`
- Add `keystore.properties` to root of the project (see `keystore.properties.template`)

Increment `versionCode` and `versionName` in `app/build.gradle.kts`, then run:
```sh
./gradlew assembleRelease
```

APK is generated in `app/build/outputs/apk/release/`

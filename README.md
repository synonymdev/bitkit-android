# Bitkit Android (Native)

> [!CAUTION]
> ⚠️This **NOT** the repository of the Bitkit app from the app stores!<br>
> ⚠️Work-in-progress<br>
> The Bitkit app repository is here: **[github.com/synonymdev/bitkit](https://github.com/synonymdev/bitkit)**

---

## About

This repository contains a **new native Android app** which is **not ready for production**.

## Development

**Prerequisites**  
1. Download `google-services.json` to `app/` from FCM Console.

### References

- For LNURL dev testing see [bitkit-docker](https://github.com/ovitrif/bitkit-docker)

### Linting

This project uses detekt with default ktlint and compose-rules for android code linting.

Recommended Android Studio plugins:
- EditorConfig
- Detekt

**Commands** 
```sh
./gradlew detekt # run analysis + formatting check
./gradlew detekt --auto-correct # auto-fix formatting issues
```
Reports are generated in: `app/build/reports/detekt/`.

## Localization
See repo: https://github.com/synonymdev/bitkit-transifex-sync

## Build

### Bitcoin Networks
The build config supports building 3 different apps for the 3 bitcoin networks (mainnet, testnet, regtest) via the 3 build flavors:
- `dev` flavour = regtest
- `mainnet` flavour = mainnet
- `tnet` flavour = testnet

### Build for Release

**Prerequisites**  
Setup the signing config:
- Add the keystore file to root dir (i.e. `release.keystore`)
- Setup `keystore.properties` file in root dir (`cp keystore.properties.template keystore.properties`)

**Routine**

Increment `versionCode` and `versionName` in `app/build.gradle.kts`, then run:
```sh
./gradlew assembleDevRelease
# ./gradlew assembleRelease # for all flavors
```

APK is generated in `app/build/outputs/apk/_flavor_/release`. (`_flavor_` can be any of 'dev', 'mainnet', 'tnet').
Example for dev: `app/build/outputs/apk/dev/release`

## License

This project is licensed under the MIT License.  
See the [LICENSE](./LICENSE) file for more details.

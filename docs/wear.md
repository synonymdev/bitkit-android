# Bitkit on Wear OS

> Do not publish the watch app yet: two live Spark instances on one seed are unsafe until #2152 is resolved.

The `:wear` module is a standalone Wear OS app (issue #2105, epic #2077). It runs its own Spark wallet through the
Breez SDK, with no LDK node, no Room database and no Firebase. The phone is only needed to set it up (from PR 3).

## Module overview

- **Ids and signing.** The watch app uses the phone app's application id per flavor (`to.bitkit.dev`, `to.bitkit.tnet`,
  `to.bitkit`) and the same signing configs, which Play (WO-G7) and the Wear Data Layer require. Never install a dev
  watch build on a phone: it replaces the phone app.
- **Flavors.** `dev` runs on regtest, `mainnet` on mainnet with a Breez API key. `tnet` builds, but Spark has no
  testnet, so it shows the Not supported screen.
- **SDK levels.** `minSdk` 34 (Wear OS 5), `targetSdk` 36, `compileSdk` 37. Wear OS 6+ keeps apps targeting 36
  always-on, so the home screen hides the balance and stops its animations in ambient mode.
- **ABIs.** Debug builds split `arm64-v8a`, `armeabi-v7a` and `x86_64` (emulator); release builds ship the two ARM
  ABIs. AGP strips the native libraries only when its default NDK (28.2.13676358) is installed, or the NDK that
  `NDK_VERSION` names; otherwise the Breez library alone ships about 35 MB larger per ABI.
- **Build settings.** `gradle/bitkit-env.gradle.kts` holds the BuildConfig fields `:app` and `:wear` share
  (`BREEZ_API_KEY` with its length check, `NETWORK`, `E2E`, `E2E_BACKEND`, `E2E_LOCAL_HOST`,
  `SPARK_LOCAL_CONFIG_B64`, `SPARK_LOCAL_CHAIN_URL`, `ESPLORA_URL`), read from the same sources as the phone build.
- **Backups.** `allowBackup` is off and both backup rule files exclude every domain, so neither the seed nor the Spark
  state leaves the watch.
- **Copied code.** `SparkSendKey`, the currency formatting and the colour tokens are copies of the phone's, each
  naming its source file. Keep them in sync until a shared module replaces them.

### Packages

- `security`: `SeedStore` and `WalletSeed`. `di/SecurityModule` binds the placeholder `UnimplementedSeedStore` until
  the Keystore-backed store replaces it.
- `services/WatchSparkService`: connect, disconnect, sync, info, payments, receive and send, with the phone's SDK
  settings, the local regtest fixture and the `SparkSendKey` idempotency keys.
- `repositories`: `WalletRepo` (wallet state and balance, start, wipe) and `CurrencyRepo` (Blocktank rates).
- `ui`: one `MainActivity` with `testTagsAsResourceId`, a `SwipeDismissableNavHost` with the routes Onboarding,
  Restore, Home, Send, Receive, Settings, About and Not supported, and the M3 Expressive theme (black background,
  Brand orange, Inter Tight).

Test tags are PascalCase and start with `Watch`, so they never clash with the phone's.

## Build and install

- `just wear-install` builds the dev debug app and installs it on `ANDROID_SERIAL`, or on the first connected watch.
  It refuses a device that is not a watch.
- `just wear-run` installs and launches it; `just wear-run logs` then streams its logcat.
- `./gradlew :wear:assembleDevDebug` writes the split APKs to `wear/build/outputs/apk/dev/debug/`.
- `just compile`, `just test` and `just lint` run on both modules. `just install` and `just run` stay on the phone app.

## Local Spark on the watch

The watch reads the local fixture under the same conditions as the phone: a debug, regtest, local-E2E build
(`E2E=true`, `E2E_BACKEND` left at `local`).

1. Run `./spark up` in a bitkit-docker checkout on `main`.
2. Build the watch app with `E2E=true`, `SPARK_LOCAL_CONFIG_B64` set to the output of `../bitkit-docker/spark config
   --base64`, and `SPARK_LOCAL_CHAIN_URL=http://127.0.0.1:23090/api`, then install it with `just wear-install`.
3. Forward the Spark operator and chain ports to the watch with `adb reverse` for ports 23535, 23536, 23537, 23049 and
   23090, using the watch's serial. The watch needs no Electrum port: it runs no LDK node.

The [spark-regtest](../.claude/plugins/spark-regtest/skills/spark-regtest/SKILL.md) skill has the details of the
stack and of funding it. Without these settings, a dev build connects to Lightspark's hosted regtest.

## Spike results

Measured on the emulator (Wear OS 7, API 37, 454×454 round, x86_64) unless noted. To be filled in by PR 1.

| Measure | Result | How |
|---|---|---|
| Debug APK size, `arm64-v8a` split | 115.8 MB with stripped native libraries: `libbitkitcore.so` 47.5 MB, `libbreez_sdk_spark_bindings.so` 11.3 MB (46.0 MB unstripped), unminified dex about 56 MB | A clean `:wear:assembleDevDebug` with `NDK_VERSION` set |
| Release AAB download size, arm64 | _TBD_ | Play Console or `bundletool get-size total` |
| Memory after Spark sync (PSS) | _TBD_ | `dumpsys meminfo to.bitkit.dev` on Home |
| First sync time after restore | _TBD_ | From connect to the first balance on Home, over Wi-Fi and over the phone's Bluetooth proxy |
| Cold start to Home | _TBD_ | `am start -W` |

## Open questions

- Typing a recovery phrase on the watch and Play's WO-P6 rule: confirm with a Play pre-review.
- StrongBox and wrist-detection lock behaviour on real watches.

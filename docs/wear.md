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

- `security`: `SeedStore` with its Keystore-backed implementation, `SeedKey` (the AndroidKeyStore seam), `DeviceLock`
  (screen lock state) and `Bip39`, the one interface every BIP39 call goes through. `CoreBip39` backs it with
  bitkit-core, as on the phone.
- `services/WatchSparkService`: connect, disconnect, sync, info, payments, receive and send, with the phone's SDK
  settings, the local regtest fixture and the `SparkSendKey` idempotency keys.
- `repositories`: `WalletRepo` (wallet state and balance, restore, start, lock handling, wipe) and `CurrencyRepo` (Blocktank rates).
- `ui`: one `MainActivity` with `testTagsAsResourceId`, a `SwipeDismissableNavHost` with the routes Onboarding,
  the Restore graph, Wallet removed, Home, Send, Receive, Settings, About and Not supported, and the M3 Expressive theme (black background,
  Brand orange, Inter Tight).

Test tags are PascalCase. Tags of screens the phone also has start with `Watch`, so they never clash with the
phone's; the restore screens use `Restore…` tags.

On-screen copy stays short: a title or one line of at most about six words per screen. Explanations live here.

## Seed storage

The watch holds the full recovery phrase and passphrase, because Breez needs them to open the wallet.

- **Key.** An AES-256-GCM key in AndroidKeyStore wraps the seed. It is created in StrongBox when the watch has one
  and in the trusted execution environment otherwise, and the log names which (the emulator reports software). A new
  key is created for every restore.
- **Screen lock trade-off.** Without a screen lock, anyone holding the watch can spend. With one, the key only works
  while the watch is unlocked.
- **How that is applied.** Wear OS does not require a screen lock from third-party apps, so the watch app does not
  either. When the watch has a screen lock at the moment of the restore, the key is created with the
  unlocked-device requirement; without one it is created without it. The key is not bound to user authentication:
  watches have no biometric prompt and the wearer unlocks once when putting the watch on. A wearer who adds a screen
  lock after restoring keeps the old key without the requirement until they remove and restore the wallet.
- **Ciphertext.** One file in the app's no-backup directory: a format version, the IV and the ciphertext with its tag,
  bound to its purpose by associated data. Backups and device transfer are off for the whole app as well.
- **Key loss.** When the key is missing or permanently invalidated, the seed can never be read again. The app then
  deletes the ciphertext, the Spark storage and the settings, and shows Wallet removed once. On the API 37 emulator,
  removing the screen lock leaves the key usable, so this only covers systems that destroy the key.
- **Locking.** On a watch with a screen lock the app disconnects Spark when the watch locks and reconnects after the
  unlock while the app is on screen. The system does not announce every lock, so the app reads the lock state when the
  screen turns off, a few seconds later, and whenever the app's window regains focus. A lock that happens while the
  screen is already off is noticed at the next of these moments.
- **Plaintext in memory.** The store encodes and decrypts through byte arrays that it zeroes after use, and the BIP39
  seed bytes are zeroed once the send key is derived. The phrase and passphrase themselves are JVM strings, which
  Compose text input, bitkit-core and Breez all require; they cannot be zeroed and live until garbage collection. The
  restore screens keep the words only in their view model, never in saved instance state, and drop them when the
  wallet is saved or the flow is left.
- **Logging.** Nothing logs the phrase or passphrase; the seed, restore state and word entry types print as redacted.
- **Wipe.** Remove wallet deletes the Spark storage, the key, the ciphertext and the settings.

## Restoring with a recovery phrase

Onboarding leads to the length choice (12 or 24 words), then one screen per word, the checksum check, the optional
passphrase and Home.

- Each word screen shows the progress and a text field. After two letters the wordlist words that start with them
  appear below; the rotary input scrolls them and a tap accepts one. Next, or Done on the keyboard, accepts a typed
  word that is in the wordlist. Each word is its own back stack entry, so swiping back returns to the previous word
  with its text in place.
- The phrase is never on screen as a whole, only the word being typed.
- After the last word the checksum is checked. A failure shows Checksum failed with Review, which walks through the
  words again from the first.
- The passphrase step is skipped by default. A typed passphrase is used exactly as typed.
- The text fields ask the keyboard for plain text without suggestions, personalized learning or dictation. A keyboard
  may ignore these hints: the Wear OS keyboard on the API 37 emulator still shows its own dictionary strip and
  microphone key. A password input type would silence it, but Wear OS apps must not show password fields (lint
  `WearPasswordInput`, rule WO-P6).

Play review: Wear quality rule WO-P6 bans username and password entry on the watch. A recovery phrase is not an account
credential, but the word and passphrase screens need a Play pre-review before the watch app is published.

### Driving the restore over adb

The Wear OS keyboard swallows hardware key events while it is open, so close it before typing.

1. Tap `WatchOnboardingRestore`, then `RestoreWords12` or `RestoreWords24`. The first word opens with the keyboard up.
2. For each word: send the Back key to close the keyboard, type the word with adb's text input, then send the Enter
   key. A full wordlist word moves on; a prefix leaves the suggestions `RestoreSuggestion-<word>` to tap.
3. `RestoreProgress` shows the position, such as 3/12. `RestoreChecksumError` with `RestoreChecksumReview` appears
   when the checksum fails; on review, `RestoreNext` keeps a word.
4. Tap `RestorePassphraseSkip`, or `RestorePassphraseAdd`, type into `RestorePassphraseInput` the same way and tap
   `RestorePassphraseConfirm`. `RestoreSaving` shows while the wallet is saved, `RestoreError` when saving failed.
5. Home shows `WatchHomeLoading` until the first sync, then `WatchHomeBalance`.

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
- StrongBox and wrist-detection lock behaviour on real watches, and whether they announce a lock that happens with
  the screen off.
- Re-wrapping the seed under an unlocked-device key when the wearer adds a screen lock after restoring.
- bitkit-core costs the watch 47.5 MB per ABI for BIP39 alone; `Bip39` keeps a later swap to one file.

# Bitkit on Wear OS

> Do not publish the watch app yet: two live Spark instances on one seed are unsafe until #2152 is resolved.

The `:wear` module is a standalone Wear OS app (issue #2105, epic #2077). It runs its own Spark wallet through the
Breez SDK, with no LDK node, no Room database and no Firebase. The phone is only needed to set it up (from PR 3).

## Module overview

- **Ids and signing.** The watch app uses the phone app's application id per flavor (`to.bitkit.dev`, `to.bitkit.tnet`,
  `to.bitkit`) and the same signing configs, which Play (WO-G7) and the Wear Data Layer require. Never install a dev
  watch build on a phone: it replaces the phone app.
- **Flavors.** `dev` runs on regtest. `tnet` builds, but Spark has no testnet, so it shows the Not supported screen
  ("No Spark on this network").
- **Mainnet is off.** The `mainnet` flavor builds, but shows the Not supported screen with the line "Not on mainnet
  yet" and opens no wallet, because two live Spark instances on one seed are unsafe until #2152 is resolved.
  `Env.IS_MAINNET_ENABLED` is the switch: while it is false, `WatchSparkService.sparkNetwork` offers no network on
  mainnet, so the app can neither restore nor connect a wallet there. Turn it on only when #2152 is closed; with it
  on, mainnet still needs a Breez API key.
- **Version.** The watch app shares the phone app's application id, so Play would reject a watch build whose
  version code the phone has used or will use. The watch takes its version from the phone: the version name is the
  phone's, and the version code is the phone's plus 1 000 000 000 (phone 197 gives watch 1 000 000 197).
  `wear/build.gradle.kts` reads both from `app/build.gradle.kts`, so a release bump there moves the watch too and
  the two can never collide while the phone's code stays below one billion. Play's largest version code is
  2 100 000 000.
- **NFC required.** The manifest requires `android.hardware.nfc` (`android:required="true"`), so Play offers the
  app only to watches with NFC. It is a store filter only: `pm install` ignores it, so `just wear-install` still
  installs on the NFC-less emulators. Host card emulation (`android.hardware.nfc.hce`) is not required yet; PR 4
  decides that with the tap-to-pay work.
- **SDK levels.** `minSdk` 34 (Wear OS 5), `targetSdk` 36, `compileSdk` 37. Wear OS 6+ keeps apps targeting 36
  always-on, so the home screen hides the balance and stops its animations in ambient mode.
- **ABIs.** Debug builds split `arm64-v8a`, `armeabi-v7a` and `x86_64` (emulator); release builds ship the two ARM
  ABIs. AGP 9.3 strips the native libraries with its default NDK, 28.2.13676358, or with the NDK that `NDK_VERSION`
  names (CI). Without an installed NDK the Breez library alone ships about 35 MB larger per ABI.
- **R8.** The release build is minified and resource-shrunk. bitkit-core ships its own consumer rules for JNA and
  uniffi; the Breez AAR ships none, so `wear/proguard-rules.pro` applies the same rules to `breez_sdk_spark`. CI
  runs R8 on the dev release code (`:wear:minifyDevReleaseWithR8`), a task that stops before packaging and so
  needs no release signing.
- **Build settings.** `gradle/bitkit-env.gradle.kts` holds the BuildConfig fields `:app` and `:wear` share
  (`BREEZ_API_KEY` with its length check, `NETWORK`, `E2E`, `E2E_BACKEND`, `E2E_LOCAL_HOST`,
  `SPARK_LOCAL_CONFIG_B64`, `SPARK_LOCAL_CHAIN_URL`, `ESPLORA_URL`), read from the same sources as the phone build.
  The watch app's `BREEZ_API_KEY` is always empty for now: regtest needs no key and mainnet is off, so the key stays
  out of the watch APK until `Env.IS_MAINNET_ENABLED` turns on.
- **Locales.** The watch app ships the phone app's locales (`localeFilters`) and generates its locale config from
  them, with English as the default (`res/resources.properties`).
- **Play services components.** The Breez SDK's dependencies bring Play services' sign-in and credential
  components into the manifest: three activities and two services, one of them exported
  (`RevocationBoundService`). The watch app uses none of them, so its manifest removes them
  (`tools:node="remove"`), and the merged manifest lists only `MainActivity` and the androidx startup and profile
  components. The libraries themselves stay on the classpath, since Breez's classes load them.
- **Main thread.** Nothing reads the disk, the network or a system service on the main thread at launch or while
  a screen composes. `NetworkMonitor`, `DeviceLock` and `NfcMonitor` are created empty and register with the system
  on the IO dispatcher; the HTTP client, the settings file and the seed directory are opened on first use there.
  Debug builds turn on `StrictMode` (disk reads, disk writes and network on the main thread, logged to logcat under
  `StrictMode`), so a regression shows while the journeys run.
- **Backups.** `allowBackup` is off and both backup rule files exclude every domain, so neither the seed nor the Spark
  state leaves the watch.
- **Copied code.** The currency formatting, the colour tokens and text styles, the balance swap animations and
  `clickableAlpha` are copies of the phone's, each naming its source file. Keep them in sync until a shared module
  replaces them.

### Packages

- `security`: `SeedStore` with its Keystore-backed implementation, `SeedKey` (the AndroidKeyStore seam), `DeviceLock`
  (screen lock state) and `Bip39`, the one interface every BIP39 call goes through. `CoreBip39` backs it with
  bitkit-core, as on the phone.
- `services/WatchSparkService`: connect, disconnect, sync, info and storage wipe, with the phone's SDK settings and
  the local regtest fixture. Payments, and the idempotency keys they use, come with PR 2.
- `repositories`: `WalletRepo` (wallet state and balance, restore, start, lock, background and network handling,
  wipe and its recovery at launch) and `CurrencyRepo` (Blocktank rates and the unit shown first).
- `data`: `SettingsStore` (display settings and the wipe's notes, with `WipeMarker` for the note itself), the rates
  client, `NetworkMonitor` (`ConnectivityManager`'s default network, online once the system validated it),
  `NfcMonitor` (the NFC adapter's state) and `ForegroundMonitor` (the process lifecycle: whether the app is on
  screen).
- `ui`: one `MainActivity` with `testTagsAsResourceId`, a `SwipeDismissableNavHost` with the routes Onboarding,
  the Restore graph, Wallet removed, Home, About and Not supported, and the M3 Expressive theme (black background,
  Brand orange, Inter Tight). The splash screen stays while the first screen is picked, for at most about a second;
  after that the loading ring shows until it is. When the wallet storage cannot be recovered at launch, the start
  error (`WatchStartError` with `WatchStartRetry`) shows instead of a first screen.

Test tags are PascalCase. Tags of screens the phone also has start with `Watch`, so they never clash with the
phone's; the restore screens use `Restore…` tags.

On-screen copy stays short: a title or one line of at most about six words per screen. Explanations live here.

## Screens and visual system

- **Home pager.** Home is a horizontal pager (`HorizontalPagerScaffold` with a page indicator) that opens on the
  Balance page; Settings is the next page. `HOME_PAGES` in `HomePage.kt` lists the pages in swipe order, so a page
  is added in one line. The pager leaves the left edge of the first page to swipe-to-dismiss, which leaves the app.
  There is no Send page or button: a send starts from outside the app (phone handoff, NFC or a `lightning:` link).
  The pager composes only the page on screen, so Home itself, above the pager, leaves for onboarding or the Wallet
  removed notice when the wallet's state says the wallet is gone. A removal therefore reaches the wearer on any
  page, also when the Settings page that asked for it was swiped away meanwhile.
- **Balance.** The phone's balance header: the other unit in a small row above, the amount large below, both
  left-aligned within a block that is centered on the screen,
  each with its currency symbol drawn apart in the phone's styles (`AppTextStyles.Display` and `CaptionM`, copied).
  The large row scales down only as far as a small round screen needs. The rows are about 4dp apart on a 227dp
  screen and 3dp on a 192dp one, tighter than the phone's 12dp to match the smaller type. A tap anywhere on it swaps
  the units (`WatchHomeBalanceToggle`) with the phone's spring swap animation and press fade (`BalanceAnimations` and
  `clickableAlpha`, copied). The large row scales from its top start as on the phone; the small row scales from its
  bottom start, so at the tight gap it grows upwards mid-swap instead of over the large row. Before the
  first balance a small dimmed Bitkit mark stands in for it (`WatchHomeBalancePending`); a reconnect keeps the last
  balance of the session on screen. A failed start shows a short error and a retry icon. See Offline below. In ambient mode the
  balance is replaced by the dimmed mark (`WatchHomeBalanceHidden`) and nothing animates. The last balance is kept
  in memory only, never written to disk.
- **NFC hint.** Shown at the bottom of the Balance page, above the page indicator, only while the wallet is ready
  to pay: connected and synced (`WalletStatus.Ready`) with its balance showing, so never under the loading ring,
  with the error or the offline icon, or while the watch is locked, and never in ambient mode. Its state follows
  NFC live: `NfcMonitor`, a singleton, reads the adapter on the IO dispatcher at start and on every
  `NfcAdapter.ACTION_ADAPTER_STATE_CHANGED` broadcast, through one receiver registered for the life of the process,
  and the page only collects its state. Until the first read the hint is hidden.
  - NFC on: the phone's broadcast icon (`ic_broadcast`, tinted white-64 as in the Send sheet's nearby pill) as
    `NfcHint`. Its dot stays on while the inner and then the outer arc pair brighten in a slow loop, to say that a
    tap pays. It is the still icon when animations are off.
  - NFC off: the same icon, still, dimmed to white-32 and slashed like the phone's `ic_eye_slash`
    (`ic_broadcast_off`), as `NfcHintDisabled` ("NFC off"). A tap opens the NFC settings, or the settings home on
    a watch without an NFC settings screen.
  - No NFC adapter: hidden. Only the emulators get here; a store install always has NFC (see NFC required).

  The emulators have no NFC, so the two visible states are checked on a physical watch, or in the `NfcHint` and
  `BalancePage` previews (`PreviewNfcOff`).
- **Offline.** `WalletRepo` counts a sync only when it reached Spark: offline, the SDK's `syncWallet` still returns
  at once, with a `Network` error as `lastSyncError` and no `lastSyncedAt`. Until a sync of this session reaches
  Spark, no balance shows, so an unsynced 0 (a wallet restored offline) never looks real: without a network the
  start ends in the error with its retry (`WatchHomeError`, `WatchHomeRetry`). Once a sync of this session has
  reached Spark, losing the network, or a later sync that does not reach Spark, keeps the last balance with a small
  no-network icon (`WatchHomeOffline`, no text). `NetworkMonitor` counts a network as online only once the system
  validated that it reaches the internet. When it reports the network back and the app is on screen, the wallet
  syncs again under the loading ring. A wallet that stays offline although the watch has a network tries again by
  itself while the app is on screen: after 5 s, then 15 s, then every 60 s, starting over once a sync succeeds. A
  tap on the offline icon tries at once. The error does not retry by itself; the wearer taps retry.
  One exception keeps a start from ending on a stale result: when the network, an unlock or the app came back while
  a start was still running and that start then failed, it runs once more.
- **Exchange rates.** While Home is open, the Blocktank rates are fetched when the app comes on screen and every
  5 minutes from then, each time the wallet becomes ready, and when the network returns while there are no rates
  yet. Until rates arrive the balance shows in bitcoin only.
- **Background.** The wallet is connected only while the app is on screen. `ForegroundMonitor` follows the process
  lifecycle (`ProcessLifecycleOwner`), which counts the app as off screen about 0.7 s after its last activity
  stopped. From then, `WalletRepo` waits 10 s and disconnects Spark (`WalletStatus.Paused`), so a glance at another
  app keeps the connection and closing the watch app stops its Spark instance. Nothing starts the wallet in the
  background: not the network coming back, not an unlock, not a retry. A start asked for there is put off, and a
  start that the app left while it ran is disconnected and put off too. Returning to the app connects and syncs
  again under the loading ring, over the last balance. Returning within the 10 s only cancels the wait; a
  disconnect that already began always finishes, and the wallet then connects again. When Spark cannot be
  disconnected, the app keeps hold of it so the next lock, pause or wipe tries again. Ambient mode is not the
  background: the app stays on screen and connected.
- **Settings.** A short list of icon rows: About and Remove wallet. The unit has no setting: tapping the balance is
  the only way to switch it, and the choice is kept in the settings store across restarts. Remove wallet asks with a
  one-line title and the two round ✕/✓ buttons.
- **Loading.** Every wait shows `BezelLoading`: an indeterminate ring around the screen edge (test tag
  `BezelLoading`), with the screen's content readable inside it. It covers connecting and syncing on Balance, saving
  the restored wallet, and removing the wallet. It is hidden in ambient mode. Accessibility services report only
  content that no node above it covers, so the full-screen ring has no semantics; its one progress node
  (`BezelLoading`) is a stroke-sized spot at the top of the ring, and the content beneath stays reachable by TalkBack
  and `android layout`. `BezelPadding` and `BezelStrokeWidth` hold the ring's padding and stroke for other edge
  rings, such as hold-to-pay.
- **Style.** Black background; Brand orange only on the one primary action (an `EdgeButton`) and accents; Inter
  Tight; content centered inside the round screen and clear of the time; icons rather than labels where the icon is
  clear; no stacked button lists. Icon and text buttons morph their shape on press and the theme uses the expressive
  (spring) motion scheme. Icons are vector drawables in `res/drawable`, with no icon library.

## Seed storage

The watch holds the full recovery phrase and passphrase, because Breez needs them to open the wallet.

- **Key.** An AES-256-GCM key in AndroidKeyStore wraps the seed. It is created in StrongBox when the watch has one
  and in the trusted execution environment otherwise, and the log names which. When StrongBox fails to make the key
  for any reason, the app deletes what it left and makes the key in the trusted execution environment. A new key is
  created for every restore.
- **No software keys.** A release build stores a seed only under a key that the Keystore reports as StrongBox or
  trusted execution environment. Any other key is deleted, nothing is stored and the restore ends with the one
  line "No secure hardware" (`RestoreError`). Debug builds are exempt, because the emulators report software; a
  release build therefore cannot restore a wallet on an emulator.
- **One seed at a time.** Saving refuses while a seed is stored, so a restore can never destroy the key of a seed
  that is still there. The wallet is removed first, which is the only way to a second restore.
- **Screen lock trade-off.** Without a screen lock, anyone holding the watch can spend. With one, the key only works
  while the watch is unlocked.
- **How that is applied.** Wear OS does not require a screen lock from third-party apps, so the watch app does not
  either. When the watch has a screen lock at the moment of the restore, the key is created with the
  unlocked-device requirement; without one it is created without it. The key is not bound to user authentication:
  watches have no biometric prompt and the wearer unlocks once when putting the watch on. A wearer who adds a screen
  lock after restoring keeps the old key without the requirement until they remove and restore the wallet.
- **Ciphertext.** One file in the app's no-backup directory: a format version, the IV and the ciphertext with its tag,
  bound to its purpose by associated data. It is written to a temporary file, synced, renamed over the seed file,
  and the directory is synced, so a power cut leaves either no seed or the whole one. Backups and device transfer
  are off for the whole app as well.
- **Key loss.** When the key is certainly gone, the seed can never be read again. The app then wipes the wallet
  (see Wipe) and shows Wallet removed once. See Key loss or a passing error below for what counts.
- **Removing the screen lock.** Before Android 15, removing the screen lock deletes every key that requires an
  unlocked device, so on Wear OS 5 (API 34) a wallet restored with a screen lock set is lost when the wearer removes
  the lock: the next launch finds the key gone, wipes the wallet and shows Wallet removed. This is expected key
  loss, not a fault; the wearer restores again from the recovery phrase. From Android 15 the key survives, as on
  the API 37 emulator, where removing the screen lock leaves the wallet usable.
- **Locking.** On a watch with a screen lock the app disconnects Spark when the watch locks and reconnects after the
  unlock while the app is on screen. The system does not announce every lock, so the app reads the lock state when the
  screen turns off, a few seconds later, whenever the app comes on screen and whenever its window regains focus. A
  lock that happens while the screen is already off is noticed at the next of these moments. A lock that lands while
  the wallet is starting wins: the start ends locked and disconnected, never ready.
- **Plaintext in memory.** The store encodes and decrypts through byte arrays that it zeroes after use. The phrase
  and passphrase themselves are JVM strings, which Compose text input, bitkit-core and Breez all require; they cannot
  be zeroed and live until garbage collection. The restore screens keep the words only in their view model, never in
  saved instance state, and drop them when the wallet is saved, the flow is left or the app leaves the screen.
- **Logging.** The app logs to logcat under the tag `BitkitWear`; debug builds also write each process's log to
  `files/logs/` (the newest five files), as the phone does. Nothing logs the phrase or passphrase; the seed,
  restore state and word entry types print as redacted. A release build logs only the class of an error, never its
  message: the Keystore, Breez and bitkit-core write those messages, and the app cannot vouch for what they hold.
- **Wipe.** Remove wallet, and a lost key, run one wipe that the screen which asked for it cannot cancel. It writes
  a note that a wipe is under way, deletes the seed (key, then ciphertext), deletes the Spark storage, and last
  clears the settings and then the note. The seed goes first, so whatever happens next the wallet can no
  longer be opened. Every step after the note runs even when an earlier one fails. When a step fails or the process
  dies, the note stays, and the next launch finishes the wipe before it picks the first screen; a wallet start
  finishes it too, and connects nothing while it cannot. When the launch cannot finish it, the start error shows
  with a retry and no wallet opens. A launch, and every restore, also deletes what no stored seed owns: Spark
  storage, a seed key and a half-written seed file.
  - The note is an empty file, `wipe.pending`, beside the seed in the no-backup directory; the file existing is the
    note. It and its directory are synced to disk. It is not in the settings file, which is reset when it cannot be
    read: a reset neither loses a wipe under way nor starts one. A note that an earlier build left in the settings
    file is moved once: the file is written, then the old note removed.
  - The wallet counts as wiped once the seed file and the Spark storage are gone. A seed key that cannot be
    deleted, after a second try, does not fail the wipe: without its ciphertext it protects nothing, and each launch
    without a wallet tries the delete again. A Spark instance that cannot be disconnected is dropped, so the next
    wallet never uses it.
  - The note is written twice at most. When it still cannot be written, Remove wallet fails and deletes nothing, so
    a removal the wearer asked for can never be left half done without a note to finish it. A lost-key wipe goes on
    without the note: its seed is unreadable either way.
  - A settings file that cannot be read is replaced by an empty one, so it never blocks a launch or a removal. The
    removed notice of a lost-key wipe lives there and can be lost that way; the wipe itself cannot.
  - While a wipe runs nothing connects to Spark. A wallet start takes a token before it reads the seed; no token is
    handed out during a wipe, and a token stops being valid when a wipe begins and again when it ends. So a start
    begun before or during a wipe is refused, and a balance that arrives is dropped. The token is checked again once
    Spark is built: an instance built while a wipe began is disconnected and never used.

### Key loss or a passing error

Deleting the wallet for a Keystore error that would have passed loses the wearer's access until they restore, so the
app only treats the key as lost in the cases the platform reports for a key that is gone. They were read from the
AOSP sources of `android.security.keystore2` and `android.security.KeyStoreException` for Android 14
(`android14-release`, API 34), 15 (the API 35 SDK sources), 16 (`android16-release`, API 36) and `main` on
2026-11-27; the code quoted here is the same in all four.

- `AndroidKeyStoreSpi.engineContainsAlias` returns false for every Keystore error, not only for a missing key
  (`getKeyMetadata` logs any other error and returns null). The app therefore never asks whether the key exists.
- `AndroidKeyStoreSpi.engineGetKey` returns null only for the response code `KEY_NOT_FOUND`. Every other error is an
  `UnrecoverableKeyException` whose cause is the `KeyStoreException`
  (`AndroidKeyStoreProvider.loadAndroidKeyStoreKeyFromKeystore`, "Failed to obtain information about key").
- Starting a cipher operation maps `KEY_NOT_FOUND` and `KEY_PERMANENTLY_INVALIDATED` to
  `KeyPermanentlyInvalidatedException`, a locked or uninitialized Keystore to `UserNotAuthenticatedException`, and
  every other error to a plain `InvalidKeyException` (`KeyStoreCryptoOperationUtils.getInvalidKeyException`).
- `KeyStoreException.getNumericErrorCode` gives `ERROR_KEY_DOES_NOT_EXIST` for `KEY_NOT_FOUND` and
  `KEY_PERMANENTLY_INVALIDATED` only, and `isTransientFailure` is false for both.

So the key counts as lost when reading it returns null, when an operation throws
`KeyPermanentlyInvalidatedException`, or when an `UnrecoverableKeyException` is caused by a `KeyStoreException`
with `ERROR_KEY_DOES_NOT_EXIST` that is not transient. An `UnrecoverableKeyException` with any other cause or with
none, a transient failure, a system or KeyMint error and a corrupted key blob all show the error with its retry and
delete nothing. A lock always wins: an error that names the lock, or a lost-looking key while the watch is locked,
is the locked state. Before reporting a lost key the store waits half a second and reads the key a second time, and
only two matching verdicts wipe the wallet. One case is left to the retry on purpose: `engineGetKey` turns a permanently invalidated
key into an `UnrecoverableKeyException` without a cause, which cannot be told from other errors. The seed key is not
bound to user authentication, so the platform has no reason to invalidate it that way; Remove wallet in Settings
still works if it ever happens.

## Restoring with a recovery phrase

Onboarding leads to the length choice (12 or 24 words), then one screen per word, the checksum check, the optional
passphrase and Home.

- The length is one control: tapping 12 or 24 starts typing.
- Each word screen shows the progress, such as 3/12, and a text field. After two letters up to six wordlist words
  that start with them appear below as chips; a tap accepts one, and the rotary input scrolls the screen. Next, or Done on the keyboard, accepts a typed
  word that is in the wordlist. Each word is its own back stack entry, so swiping back returns to the previous word
  with its text in place.
- The phrase is never on screen as a whole, only the word being typed.
- In ambient mode the word and passphrase screens show an empty screen (`RestoreHidden`) instead of the field and
  the chips, so nothing typed stays on a dimmed watch. Waking the watch brings the screen back as it was.
- The words live only in memory. When the system recreates the app's process during the restore, they are gone, and
  the word and passphrase screens return to the length choice instead of showing an empty screen.
- After the last word the checksum is checked. A failure shows an error icon, Invalid phrase and Review, which walks
  through the words again from the first.
- The passphrase step continues without one by default; a small key button adds one. A typed passphrase is used
  exactly as typed. It holds at most 256 characters: typing past that is refused with Too long
  (`RestorePassphraseTooLong`), never cut short without a word.
- Leaving the app during the restore drops the words and the passphrase and returns to the length choice, so
  nothing typed lingers in memory behind another app. The drop follows the process lifecycle
  (`ProcessLifecycleOwner`), which reports the app off screen about 0.7 s after its last activity stopped. Only a
  save already under way finishes; when it fails behind another app, the words are dropped then. Ambient mode keeps
  the app on screen and the words with it.
- While the wallet is saved, the passphrase screen cannot be left by a swipe or the back button, so the wearer
  never lands on an earlier screen with a wallet stored behind it. Onboarding checks for a stored wallet each time
  it comes on screen and opens Home when there is one, as after a save that finished while the wearer was away.
- The word and passphrase screens set `FLAG_SECURE` while shown, in debug builds too (the phone's
  `BlockScreenshots` exempts debug builds). Screenshots and screen recordings of them come out black, so journeys
  check these screens with `android layout` only.
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
   key. A full wordlist word moves on; a prefix leaves the suggestions to tap, tagged by position from `RestoreSuggestion-0` so that no tag
   names a word.
3. `RestoreProgress` shows the position, such as 3/12. `RestoreChecksumError` with `RestoreChecksumReview` appears
   when the checksum fails; on review, `RestoreNext` keeps a word.
4. Tap `RestorePassphraseSkip`, or `RestorePassphraseAdd`, type into `RestorePassphraseInput` the same way and tap
   `RestorePassphraseConfirm`. `RestoreSaving` (holding `BezelLoading`) shows while the wallet is saved,
   `RestoreError` when saving failed ("Couldn't save wallet", or "No secure hardware" in a release build on a
   watch without a hardware Keystore).
5. Home shows `BezelLoading` with `WatchHomeBalancePending` until the first sync, then `WatchHomeBalance`. Swipe
   left (`adb shell input swipe 400 227 60 227`) for Settings.

## Build and install

- `just wear-install` builds the dev debug app and installs it on `ANDROID_SERIAL`, or on the first connected watch.
  It refuses a device that is not a watch.
- `just wear-run` installs and launches it; `just wear-run logs` then streams its logcat.
- `./gradlew :wear:assembleDevDebug` writes the split APKs to `wear/build/outputs/apk/dev/debug/`.
- A bad local `BREEZ_API_KEY` stops only the `:app` build: the key is checked when a build reads it, and `:wear`
  never does.
- `just compile`, `just test` and `just lint` run on both modules. `just build`, `just install` and `just run` stay
  on the phone app. An unqualified Gradle install task, such as `./gradlew installDevDebug`, would install both apps on
  one device, where the watch app replaces the phone app: always name the module (`:app:` or `:wear:`).

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

Measured on the emulator (Wear OS 7, API 37, 454×454 round, x86_64) unless noted, on 2026-11-27.

| Measure | Result | How |
|---|---|---|
| Debug APK size, `arm64-v8a` split | 116.8 MB: `libbitkitcore.so` 47.5 MB, `libbreez_sdk_spark_bindings.so` 11.3 MB (both stripped; Breez is 46.0 MB unstripped), `libjnidispatch.so` 0.2 MB, unminified dex about 54 MB | `:wear:assembleDevDebug`, AGP 9.3 with its default NDK 28.2.13676358 |
| Release APK size, `arm64-v8a` split | 64.6 MB with R8 (dex 4.2 MB, `resources.arsc` 0.2 MB, the same native libraries); about 31.8 MB gzipped | `:wear:assembleDevRelease`, signed locally with the debug key |
| Release AAB | The bundle file is 77.6 MB with both ARM ABIs and their debug symbols. Its arm64 payload is 63.5 MB before Play compresses it: the native libraries 59.0 MB, dex 4.3 MB, resources 0.3 MB. The download size Play reports is not measured | `:wear:bundleTnetRelease`; the download size needs Play Console or `bundletool get-size total` |
| Memory after Spark sync (PSS) | 130 MB (RSS 272 MB), debug build | `dumpsys meminfo to.bitkit.dev` on Home after the first sync |
| First sync time after restore | 214 s (3 min 34 s) against Lightspark's hosted regtest; an earlier run took 204 s. Later syncs take 7–10 s. Connecting takes under 1 s. `WalletRepo` gives up after 30 s connecting or 5 minutes syncing and shows the retry | `Synced wallet in` in logcat. Not measured over a phone's Bluetooth proxy, which needs a physical watch |
| Cold start to Home | 1.37 s release (API 37), 1.77 s debug (API 34) | `am start -W` |

## Open questions

- Typing a recovery phrase on the watch and Play's WO-P6 rule: confirm with a Play pre-review.
- StrongBox and wrist-detection lock behaviour on real watches, and whether they announce a lock that happens with
  the screen off.
- Re-wrapping the seed under an unlocked-device key when the wearer adds a screen lock after restoring.
- bitkit-core costs the watch 47.5 MB per ABI for BIP39 alone; `Bip39` keeps a later swap to one file.

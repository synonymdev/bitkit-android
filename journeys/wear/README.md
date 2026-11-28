# Wear OS

Journeys for the standalone Wear OS app in `:wear` ([docs/wear.md](../../docs/wear.md)). They run on a Wear OS
emulator, and every one of them also runs on a physical watch over Wi-Fi debugging unless its `<description>` says
"physical-only". Android only: bitkit-ios has no watch app yet.

Every target is a test tag, the `resource-id` in `android layout`. Where `android layout` cannot see something, the
journey says to take a screenshot instead (`android screen capture -o shot.png`).

What `android layout` misses on the watch, verified on the API 37 emulator:

- **The loading ring.** `android layout` leaves out `BezelLoading`, a node with no text or action, though
  `adb shell uiautomator dump` lists it as a `ProgressBar`. Check the ring from a screenshot. What is under it stays
  listed (the balance, for one), but wait for the ring to stop before asserting on a balance that is still loading.
- **The pending and hidden marks.** `WatchHomeBalancePending` has no text either, so `android layout` leaves it out;
  `uiautomator dump` lists it. The same goes for `RestoreHidden`, the empty screen the restore shows in ambient mode.
- **The receive QR.** `WatchReceiveQr` is an image with no text or action, so `android layout` leaves it out;
  `adb shell uiautomator dump` lists it. Check it there or from a screenshot. `WatchReceiveHidden`, the screen with the
  dimmed icon that replaces it in ambient mode and once it rests, has a content-desc and is listed.
- **Suggestion chips.** A chip's tag (`RestoreSuggestion-0`, counted from 0) is on the button and its word is a
  text node at the same `center`. A chip below the screen edge is not listed until the screen scrolls.
- **The keyboard.** The Wear OS keyboard is a full-screen window that `android layout` does not show. It lists the
  app's screen underneath, so a tap you aim at a tag lands on a key instead. See [Typing](#typing).
- **Screens with `FLAG_SECURE`.** The word and passphrase screens come out black in a screenshot and a screen
  recording. `android layout` still lists their tags, so check them there.

## Emulators

| AVD | Screen | System image |
| --- | --- | --- |
| `Wear_Large_Round_API37` | 454×454 px, 227 dp, round | `system-images;android-37.0;android-wear-signed;x86_64` (Wear OS 7) |
| `Wear_Small_Round_API34` | 384×384 px, 192 dp, round | `system-images;android-34;android-wear;x86_64` (Wear OS 5) |

The journeys are written against `Wear_Large_Round_API37`. The small one is for checking that a screen fits.

Create them with:

```bash
sdkmanager "system-images;android-37.0;android-wear-signed;x86_64" "system-images;android-34;android-wear;x86_64"
avdmanager create avd -n Wear_Large_Round_API37 -k "system-images;android-37.0;android-wear-signed;x86_64" -d wearos_large_round
avdmanager create avd -n Wear_Small_Round_API34 -k "system-images;android-34;android-wear;x86_64" -d wearos_small_round
```

**Gotcha:** `avdmanager` does not copy the device profile's round screen or rotary input into the AVD, so the emulator
starts square and ignores the crown. Add these two lines to `~/.android/avd/<name>.avd/config.ini` of each AVD
(Android Studio's Device Manager sets them for you):

```ini
hw.lcd.circular=true
hw.rotaryInput=yes
```

Start and stop one:

```bash
android emulator start Wear_Large_Round_API37    # prints its serial, e.g. emulator-5554
android emulator stop emulator-5554
```

Pick the watch for every `adb` and `android` command with `ANDROID_SERIAL`, so a phone connected at the same time is
never touched:

```bash
export ANDROID_SERIAL=emulator-5554              # or the watch's ip:port, see On a physical watch
```

## Build and install

```bash
just wear-install                                # dev debug build, onto ANDROID_SERIAL or the first watch
adb shell am start -n to.bitkit.dev/to.bitkit.wear.ui.MainActivity
```

**Never install the watch app on a phone.** It uses the phone app's application id (`to.bitkit.dev`), so it would
replace the phone app and its wallet. `just wear-install` refuses a device that is not a watch; a plain
`./gradlew :wear:installDevDebug` does not check.

## Backend

A dev watch build runs Spark on regtest. Two regtest networks exist, as for the phone
([spark-regtest](../../.claude/plugins/spark-regtest/skills/spark-regtest/SKILL.md)):

| | Lightspark hosted regtest | Local `./spark` stack (bitkit-docker) |
| --- | --- | --- |
| Setup | None: `just wear-install` connects to it | `./spark up` (the first run builds the images from source), a special build and five `adb reverse` ports |
| First sync of a funded wallet | About 3.5 minutes; later starts sync in under 10 s | Seconds |
| Empty wallet | Syncs in a few seconds | Seconds |
| Funding | Faucet | `./spark send`, `./spark pay` |
| Physical watch | Works over Wi-Fi or LTE | Needs `adb reverse` over the Wi-Fi debugging connection, or the stack on a LAN IP |

**Recommendation: hosted regtest for every journey that makes no payment**, where the only cost is the first sync of
the test wallet, which the [Restored wallet](#restored-wallet) precondition pays once. **The payment journeys run on
the [local `./spark` stack](#local-spark-stack)**, whose funds are ours alone and which pays and makes invoices from
the command line. Run `receive-paid.xml` first: it funds the wallet the send journeys spend from.

| Journey | Backend | Needs funds |
| --- | --- | --- |
| `restore-12-words.xml` | hosted | yes, any balance above 0 |
| `restore-word-entry.xml` | none (stops before the wallet connects) | no |
| `restore-ambient-hides-word.xml` | none (stops before the wallet connects) | no |
| `restore-background-drops-words.xml` | none (stops before the wallet connects) | no |
| `restore-invalid-checksum.xml` | none (stops before the wallet connects) | no |
| `restore-passphrase.xml` | hosted | no, a new passphrase per run is an empty wallet |
| `balance-unit-swap.xml` | hosted | yes |
| `pager-settings-about.xml` | hosted | no |
| `remove-wallet-cancel.xml` | hosted | yes |
| `remove-wallet.xml` | hosted | no |
| `ambient-hides-balance.xml` | hosted | yes |
| `no-backup.xml` | none | no |
| `nfc-required.xml` | none | no |
| `connect-error-retry.xml` | hosted | yes |
| `offline-keeps-last-balance.xml` | hosted | yes |
| `background-disconnects-spark.xml` | hosted | yes |
| `nfc-hint-states.xml` | hosted, physical watch only | yes |
| `screenshot-blocked.xml` | none | no |
| `receive-qr.xml` | local | no |
| `receive-qr-rests.xml` | local | no |
| `receive-paid.xml` | local | no, it funds the wallet with 50 000 sats |
| `send-deeplink-bolt11.xml` | local | yes, 2 000 sats |
| `send-hold-cancel.xml` | local | yes, 2 000 sats |
| `send-ambient-hides-amount.xml` | local | yes, 2 000 sats |
| `send-insufficient.xml` | local | yes, less than 10 000 000 sats |
| `send-own-invoice-guard.xml` | local | yes, any balance above 0 |
| `activity-list.xml` | local | yes, after `receive-paid.xml` and `send-deeplink-bolt11.xml` |
| `receive-paid-ambient.xml` | local | no, it funds the wallet with 1 000 sats |
| `tile-balance.xml` | hosted or local | no |

Paying an LNURL-pay link or a Lightning address has no journey: the local stack's LNURL server serves only the
Lightning addresses its Spark wallets registered, over plain HTTP, and nothing in it registers one. It is covered by
`PaymentsRepoTest.kt` and `SendViewModelTest.kt`, which check the range, the amount entry and the invoice the service
returns.

### Local `./spark` stack

Use it when a journey needs payments you control, or when hosted regtest is down. From the bitkit-docker checkout next
to this repo (`../bitkit-docker`):

```bash
../bitkit-docker/spark up && \
CFG="$(../bitkit-docker/spark config --base64)" && \
E2E=true SPARK_LOCAL_CONFIG_B64="$CFG" SPARK_LOCAL_CHAIN_URL=http://127.0.0.1:23090/api just wear-install && \
for port in 23535 23536 23537 23049 23090; do adb reverse tcp:$port tcp:$port; done
```

The watch runs no LDK node, so it needs no Electrum port. Without `E2E=true` the build silently stays on hosted
regtest. A local wallet is new to that chain, so it starts at 0.

### Funding the test wallet

On the local stack, `receive-paid.xml` funds the watch through its own receive QR. Otherwise, or for an on-chain
deposit, take the deposit address or invoice from the phone app with the same recovery phrase: restore [the test wallet](#the-test-wallet) in a phone dev build on the same Spark network, read the
Spark deposit address at Settings › Advanced › Lightning Connections › Spark (`SparkDepositAddress`), fund it, then
close the phone app before you start the watch again. Two live Spark instances on one seed are unsafe (#2152), so never
keep both open.

- **Hosted regtest:** send to the deposit address from https://app.lightspark.com/regtest-faucet (up to 50,000 sats per
  deposit). Lightspark mines about once a minute and Spark claims it after 3 confirmations.
- **Local stack:** `../bitkit-docker/spark send <deposit address> <sats>` (it mines a block), or create a Spending
  invoice in the phone app and pay it with `../bitkit-docker/spark pay <invoice>`.

Anyone can move the public test wallet's coins, so the journeys only expect a balance above 0; fund the wallet
again when it is empty.

## The test wallet

The journeys restore the BIP39 test vector phrase, eleven times `abandon` followed by `about`, with no passphrase:

```text
abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about
```

It is a public test phrase: anyone can spend what it holds, so fund it only with regtest coins, and its balance on
hosted regtest changes without notice. The journeys therefore never expect a fixed amount, only a balance above 0.

With a passphrase it is a different wallet. A fixed passphrase would be as public as the phrase, so
`restore-passphrase.xml` makes one up for each run, which gives a wallet nobody has seen and so an empty one:

```bash
PASS="watch$(tr -dc a-z </dev/urandom | head -c 6)"; echo "$PASS"
```

**Recommendation:** for a balance nobody else can move, restore a private 12-word phrase of your own instead and
fund it once (see [Funding the test wallet](#funding-the-test-wallet)), or run the journeys on the
[local `./spark` stack](#local-spark-stack), where the public phrase is yours alone. The journeys work with any
funded 12-word wallet: type your words where they type the test phrase. Never use a phrase that holds real funds.

## Preconditions

Each journey names one of these in its `<description>`.

### Fresh app

No wallet on the watch, on the onboarding screen.

```bash
adb shell pm clear to.bitkit.dev
adb shell am start -n to.bitkit.dev/to.bitkit.wear.ui.MainActivity
```

`android layout` lists `WatchOnboardingLogo` and `WatchOnboardingRestore`.

### Restored wallet

The test wallet restored with no passphrase, its balance loaded, Bitcoin as the large unit.

1. [Fresh app](#fresh-app).
2. Tap `WatchOnboardingRestore`, then `RestoreWords12`.
3. Type the whole phrase with [Typing several words](#typing-several-words).
4. Tap `RestorePassphraseSkip`.
5. Dump `android layout` every 15 s until it lists `WatchHomeBalance`, for up to 5 minutes (see [Backend](#backend)).
6. If `WatchHomeBalanceSymbol` reads `$`, tap `WatchHomeBalanceToggle` once, so it reads `₿`.

A wallet restored by an earlier journey can stay: only [Fresh app](#fresh-app) and `remove-wallet.xml` remove it.

### Restart

The journeys restart the app with:

```bash
adb shell am force-stop to.bitkit.dev
adb shell am start -n to.bitkit.dev/to.bitkit.wear.ui.MainActivity
```

The balance comes back within about 10 s, once the loading ring stops.

## Typing

The Wear OS keyboard swallows `adb shell input text` while it is open, and it fills the whole screen. Check whether it
is open with:

```bash
adb shell dumpsys input_method | grep -m1 mInputShown     # mInputShown=true: the keyboard is open
```

To type `<word>` into a field:

1. Make the keyboard open on that field. A new word screen opens it by itself. Otherwise tap the field's tag
   (`RestoreWordInput`, `RestorePassphraseInput`), which focuses the field and opens it.
2. Close it with `adb shell input keyevent KEYCODE_BACK`. The field keeps its focus. Press Back only while the keyboard
   is open: with it closed, Back leaves the screen.
3. `adb shell input text <word>`
4. On a word screen, `adb shell input keyevent KEYCODE_ENTER` accepts the word and moves on. On the passphrase screen
   Enter only closes the keyboard, so tap `RestorePassphraseConfirm` instead.

A word screen with an empty field opens the keyboard; a word screen with text in place (on Review, or after swiping
back) does not.

### Typing several words

On a new word screen, this types each word and moves on, and leaves the next empty word screen with its keyboard open:

```bash
for w in abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about; do
  adb shell input keyevent KEYCODE_BACK; sleep 1
  adb shell input text "$w"
  adb shell input keyevent KEYCODE_ENTER; sleep 1.5
done
```

Drop words from the front of the list to start later in the phrase. Check `RestoreProgress` afterwards: a word that
did not land leaves it short.

### Replacing a word

To replace the text of a word that is already filled in (keyboard closed): tap `RestoreWordInput` (the keyboard
opens), press Back, then select all and delete, then type:

```bash
adb shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_A
adb shell input keyevent KEYCODE_DEL
adb shell input text about
adb shell input keyevent KEYCODE_ENTER
```

Tapping a filled field puts the cursor where you tapped, so deleting with `KEYCODE_DEL` alone leaves part of the word.

## Payments

### Reading the receive invoice

`android layout` cannot read a QR code. Debug builds log the invoice the receive QR shows, in lowercase:

```bash
adb logcat -d -s BitkitWear | grep -o "Showing invoice '[^']*'" | tail -n 1 | cut -d "'" -f 2
```

Each new invoice logs a new line (debug builds only; verified on the API 37 emulator), so the last line is the invoice on screen.

### Holding to pay

The review's pay button (`WatchSendHold`) pays only when held for about 1.2 s; a tap does nothing. Hold it with a
swipe that does not move, for as long as its last argument says, at the `center` that `android layout` gives for the
button:

```bash
adb shell input swipe <x> <y> <x> <y> 2000     # held 2 s: the ring fills and the payment goes out
adb shell input swipe <x> <y> <x> <y> 500      # released after 0.5 s: the ring empties and nothing is paid
```

### Local invoices

From the bitkit-docker checkout, `./spark invoice <sats>` prints a BOLT11 invoice of Alice's node, which the watch
pays out of Spark, and `./spark pay <invoice> <sats>` pays a watch invoice from Alice (the amount is needed, the
watch's invoices are amountless). Hand an invoice to the watch as a link, never by typing it:

```bash
adb shell am start -a android.intent.action.VIEW -d "lightning:<invoice>" to.bitkit.dev
```

## Tiles

The system draws a tile, so `android layout` does not list what it shows; check it from a screenshot. Add, show and
remove the Bitkit tile with the Wear OS debug broadcasts:

```bash
adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation add-tile \
  --ecn component to.bitkit.dev/to.bitkit.wear.tile.BalanceTileService
adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SYSUI --es operation show-tile --ei index 0
adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation remove-tile \
  --ecn component to.bitkit.dev/to.bitkit.wear.tile.BalanceTileService
```

`add-tile` prints the index the tile took; pass that one to `show-tile` when other tiles are added.
Wait about 5 s after `show-tile` before a screenshot: an earlier one can catch the watch face mid-transition. The
Receive edge button is at about (227, 400) on the large screen. Adding the tile again (remove, then add) makes the
system ask the app for it at once; the app also asks the system to redraw it whenever the balance or the wallet's
status changes.

## Watch gestures

Coordinates are pixels. The 227 dp screen is 454 px square (centre 227), the 192 dp screen 384 px (centre 192).

| Gesture | 227 dp (`Wear_Large_Round_API37`) | 192 dp (`Wear_Small_Round_API34`) |
| --- | --- | --- |
| Pager to the next page (swipe left) | `adb shell input swipe 400 227 60 227 300` | `adb shell input swipe 340 192 50 192 300` |
| Pager to the previous page (swipe right) | `adb shell input swipe 100 227 420 227 300` | `adb shell input swipe 85 192 355 192 300` |
| Swipe-to-dismiss (back) | `adb shell input swipe 20 227 400 227 300` | `adb shell input swipe 15 192 340 192 300` |
| Rotary crown, scroll down | `adb shell input rotaryencoder scroll --axis SCROLL,-3` | same |
| Rotary crown, scroll up | `adb shell input rotaryencoder scroll --axis SCROLL,3` | same |
| Ambient on | `adb shell input keyevent KEYCODE_SLEEP` | same |
| Ambient off | `adb shell input keyevent KEYCODE_WAKEUP` | same |
| Leave the app | `adb shell input keyevent KEYCODE_HOME` | same |
| Return to the app | `adb shell am start -n to.bitkit.dev/to.bitkit.wear.ui.MainActivity` | same |

- A pager swipe must start away from the left edge: a swipe from the edge is swipe-to-dismiss, which goes back, and
  on the Balance page leaves the app.
- A negative rotary value scrolls the content up, revealing what is below.
- `KEYCODE_SLEEP` puts the watch in ambient mode only when the always-on screen is on (Settings › Display ›
  Always-on screen; on by default on the emulators). With it off, the screen goes dark and the app stops instead.
- Ambient mode keeps the app on screen. Leaving the app is different: a restore under way drops its words within
  about a second, and the wallet disconnects from Spark 10 s later and reconnects on return (Background in
  [docs/wear.md](../../docs/wear.md)).

## Media

Screenshots, for a step the journey marks as a screenshot check:

```bash
android screen capture -o shot.png
```

Screen recordings, up to 170 s (the recorder stops at 180 s). The recording takes the screen's own size: 454 px
square on the large emulator, 384 px on the small one. Start the recorder in the background, run the steps, then
stop it and wait for it to finish writing before pulling the file: a file pulled while `screenrecord` still writes
has no `moov` atom and does not play.

```bash
adb shell 'screenrecord --time-limit 170 /sdcard/<name>.mp4' &
REC=$!
# ... run the journey's steps ...
adb shell pkill -INT screenrecord
wait "$REC"
sleep 2
adb pull /sdcard/<name>.mp4
adb shell rm /sdcard/<name>.mp4
grep -q moov <name>.mp4 || echo "<name>.mp4 has no moov atom: record it again"
```

The word and passphrase screens record as black frames (`FLAG_SECURE`), which is what `screenshot-blocked.xml`
checks.

## On a physical watch

Every journey here runs on a real watch; none needs an emulator-only command.

### Connecting

1. On the watch: Settings › System › About › Versions, tap Build number seven times. Then Settings › Developer options:
   turn on ADB debugging and Wireless debugging, and keep the watch on the same Wi-Fi as the computer.
2. Wireless debugging › Pair new device shows a pairing code and an `ip:port`:

   ```bash
   adb pair <ip>:<pairing port>          # enter the six-digit code
   adb connect <ip>:<port>               # the port on the Wireless debugging screen, not the pairing port
   export ANDROID_SERIAL=<ip>:<port>
   just wear-install
   ```

3. Keep the watch on its charger or on the wrist, awake: Wi-Fi debugging drops when the watch turns Wi-Fi off to save
   power, and `adb connect` has to be run again.

### What differs

- **Rotary input.** A real crown (Pixel Watch) or a rotating bezel (Galaxy Watch) sends rotary events with its own
  step size; turn it instead of running `rotaryencoder`. One bezel click is not one `SCROLL` unit, so check the result,
  not the amount.
- **Screen size.** Read the size with `adb shell wm size` and scale the gesture coordinates: start the pager swipe at
  about 88% of the width, end it at about 13%, and keep the swipe-to-dismiss start within 5% of the left edge.
- **Ambient.** Lowering the wrist or covering the screen with a palm enters ambient mode as `KEYCODE_SLEEP` does;
  either works.
- **Wrist detection and screen lock.** With a screen lock, taking the watch off locks it, and the app disconnects
  Spark (see Locking in [docs/wear.md](../../docs/wear.md)). Keep the watch on the wrist, or run the journeys without a
  screen lock.
- **Network.** An LTE watch reaches hosted regtest without Wi-Fi or the phone; one without LTE goes through Wi-Fi or
  the phone's Bluetooth proxy, which makes the first sync slower.
- **Screenshots and screen recordings** work over adb as on the emulator, and the secure screens come out black the
  same way.

## Offline

Airplane mode takes the watch offline: `adb shell cmd connectivity airplane-mode enable`, and `disable` to come back.
On the API 37 emulator, `adb shell svc wifi disable` is not enough: the emulator's cellular network stays the default
network. Spark's sync still returns at once offline, with a network error, and the app reads that error (see Offline
in [docs/wear.md](../../docs/wear.md)). Turn airplane mode off again after a journey that turns it on.

## NFC

The emulators have no NFC, so the NFC hint is hidden there in every state (no adapter). On a physical watch it shows
only on a ready wallet: `NfcHint` with NFC on, `NfcHintDisabled` with NFC off (`adb shell svc nfc disable` and
`enable`). `nfc-hint-states.xml` checks both and is physical-only. The manifest requires `android.hardware.nfc`, a
Play filter that `pm install` ignores, so `just wear-install` works on the emulators. `dumpsys package` does not list
required features, so `nfc-required.xml` reads them from the installed APK with `aapt2`, taken from the newest SDK
build tools installed:

```bash
AAPT2="$(ls -d "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"/build-tools/*/ | sort -V | tail -n 1)aapt2"
```

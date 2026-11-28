# Bitkit on Wear OS

> Do not publish the watch app yet: two live Spark instances on one seed are unsafe until #2152 is resolved.

The `:wear` module is a standalone Wear OS app (issue #2105, epic #2077). It runs its own Spark wallet through the
Breez SDK, with no LDK node, no Room database and no Firebase. It needs no phone nearby: it adopts the phone's
settings by reading the phone's backup (see Settings sync).

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
  `SPARK_LOCAL_CONFIG_B64`, `SPARK_LOCAL_CHAIN_URL`), read from the same sources as the phone build.
  The watch app's `BREEZ_API_KEY` is always empty for now: regtest needs no key and mainnet is off, so the key stays
  out of the watch APK until `Env.IS_MAINNET_ENABLED` turns on.
- **Locales.** The watch app ships the phone app's locales (`localeFilters`) and generates its locale config from
  them, with English as the default (`res/resources.properties`).
- **Play services components.** The Breez SDK's dependencies bring Play services' sign-in and credential
  components into the manifest: three activities and two services, one of them exported
  (`RevocationBoundService`). The watch app uses none of them, so its manifest removes them
  (`tools:node="remove"`), and the merged manifest lists only `MainActivity`, the balance tile's service and the
  androidx startup and profile components. The libraries themselves stay on the classpath, since Breez's classes load them.
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
- `services`: `WatchSparkService` (connect, disconnect, sync, info, storage wipe, invoices, sends and the payment
  list, with the phone's SDK settings and the local regtest fixture), `SparkSendKey` (the idempotency keys of
  sends, a copy of the phone's), `InputDecoder` (bitkit-core's reader of payment inputs), `SelfSendGuard` and
  `PhoneBackupReader`, the read-only way to the phone's backups on VSS.
- `repositories`: `WalletRepo` (wallet state and balance, restore, start, lock, background and network handling,
  wipe and its recovery at launch), `PaymentsRepo` (receive, read an input, price, send, recent payments; see
  Payments), `CurrencyRepo` (Blocktank rates and the unit shown first), `SettingsSyncRepo` (the import of the
  phone's settings) and `QuickPayRepo` (whether a send may skip the hold to pay).
- `data`: `SettingsStore` (display settings, what was imported from the phone and the wipe's notes, with
  `WipeMarker` for the note itself), `OwnInvoiceStore` (the invoices this wallet created, in the settings file),
  `QuickPaySpendStore` (the day's QuickPay spend, in the settings file), the rates
  client, `NetworkMonitor` (`ConnectivityManager`'s default network, online once the system validated it),
  `NfcMonitor` (the NFC adapter's state), `ForegroundMonitor` (the process lifecycle: whether the app is on
  screen), `AmbientMonitor` (whether the activity is in ambient mode) and `ElapsedClock` (the time since boot,
  sleep included, for ages).
- `tile`: `BalanceTileService` (the balance tile), `TileState` (what it shows) and `TileUpdates` (asks the system
  to draw it again). See Tile below.
- `models`: among others `PhoneSettingsParser`, the reader of the phone's settings backup.
- `ui`: one `MainActivity` with `testTagsAsResourceId`, a `SwipeDismissableNavHost` with the routes Onboarding,
  the Restore graph, Wallet removed, Home, About, Receive QR, Send, Activity detail and Not supported, and the M3
  Expressive theme (black background, Brand orange, Inter Tight). `LaunchRequest` reads what an intent asks for
  beyond opening the app: a payment link or the tile's Receive. The splash screen stays while the first screen is picked, for at most about a second;
  after that the loading ring shows until it is. When the wallet storage cannot be recovered at launch, the start
  error (`WatchStartError` with `WatchStartRetry`) shows instead of a first screen.

Test tags are PascalCase. Tags of screens the phone also has start with `Watch`, so they never clash with the
phone's; the restore screens use `Restore…` tags.

On-screen copy stays short: a title or one line of at most about six words per screen. Explanations live here.

## Screens and visual system

- **Home pager.** Home is a horizontal pager (`HorizontalPagerScaffold` with a page indicator) that opens on the
  Balance page; Receive, Activity and Settings follow, in that order. `HOME_PAGES` in `HomePage.kt` lists the pages in swipe order, so a page
  is added in one line. The pager leaves the left edge of the first page to swipe-to-dismiss, which leaves the app.
  There is no Send page or button: a send starts from outside the app (phone handoff, NFC or a payment link).
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
    tap pays. It is the still icon when animations are off; that setting is read on the IO dispatcher each time
    the hint appears, and the icon is still until then.
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
  disconnected, the app keeps hold of it so the next lock, pause or wipe tries again: each of them disconnects
  whatever the wallet's status says, as does a start that finds the watch locked. Ambient mode is not the
  background: the app stays on screen and connected. `AmbientMonitor` carries it from the activity to what runs
  outside the screens: in ambient mode the phone's settings are not imported and no send goes out under QuickPay.
- **Receive.** The Receive page is one large round QR button (`WatchReceiveOpen`); it has no amount entry. It opens
  the receive QR (`WatchReceiveQr`): an amountless invoice, uppercased so the code uses QR's alphanumeric mode, black
  on white with the standard four-module quiet zone, error correction L, filling the largest square inside the round
  screen (1/√2 of its width). Each module is one bitmap pixel scaled up without smoothing (zxing, as on the phone).
  While the QR shows, the screen stays on at full brightness; both end when it leaves. After 2 min without a touch
  the QR rests: it hides behind the dimmed QR icon (`WatchReceiveHidden`, "Show QR") and the screen may turn off
  again, so a forgotten QR does not hold the screen at full brightness; a tap anywhere on that screen, or waking the watch from
  ambient mode, shows it for another 2 min. The invoice is made once the
  wallet is ready, under the loading ring; a failure shows one line and a retry (`WatchReceiveError`,
  `WatchReceiveRetry`). An invoice is replaced by a new one a minute before it expires, and it stays on screen until
  the new one exists; a replacement that fails is tried again every 5 s meanwhile, and when none exists by then, the
  loading ring, or the error, takes its place as it expires. A replaced invoice is still watched until it expires, so
  a payer who scanned the earlier QR gets the check too. An
  invoice shows for at least 30 s, also when the watch's clock runs ahead. Paid, the QR turns into a
  check with the amount (`WatchReceivePaid`, `WatchReceivePaidAmount`) and a firm click (the `LongPress` haptic), unlike
  the confirm pattern that ends a send; a tap on the check shows a new invoice. In ambient mode the QR is replaced by
  a dimmed QR icon (`WatchReceiveHidden`), and an invoice paid meanwhile shows the check dimmed and without the
  amount, with the same haptic, so the always-on screen shows no amount; the amount shows once the watch wakes. Debug builds log each invoice shown (`Showing invoice`), which is how the
  journeys read it.
- **Tile.** The Bitkit tile (`BalanceTileService`) uses the tiles and ProtoLayout Material 3 libraries: a primary
  layout with the balance in the main slot and an edge button below. With a wallet it shows the balance in bitcoin
  (white, as on the Balance page) and a round Brand orange edge button with the QR icon ("Receive" to accessibility
  services), which opens the app on the receive QR: the launch intent carries the extra `to.bitkit.wear.OPEN` with
  `receive`, which `LaunchRequest` reads as `Receive`, and the receive QR opens above Home, as a payment link's send
  does. Without a wallet it shows the Bitkit mark and a "Restore" edge button that opens the app. Tiles cannot use
  the app's fonts, so the balance is in the system font.
  - The tile only reads `WalletRepo`'s state in memory and never starts the wallet, so the balance shows only once
    the app loaded it in this process and a sync confirmed it, as on the Balance page; until then, and while the
    watch is locked, the dimmed mark stands in for it. A removed wallet shows the signed-out tile at once.
  - `TileUpdates`, started by `WearApp`, asks the system to draw the tile again whenever the balance shown or the
    wallet's status changes, and once when the process starts, so a tile drawn by an earlier process drops a balance
    the new one has not loaded. Each tile is fresh for 15 min, after which the system asks again; a tile drawn just
    before the process died can show the last balance until then.
  - Privacy: the tile shows the balance on the tile carousel, so it hides it while the wallet is disconnected
    because the watch locked (`WalletStatus.Locked`). Tiles have no ambient mode: in ambient the system shows the watch face, so the app's rule that the always-on screen
    shows no amount holds without code in the tile. The tile carries no secret: the receive invoice is made by the
    app after the tap, never by the tile.
  - The tile picker shows `tile_preview` (a drawing of the balance and the Receive button, no real amount).
    `tile_logo` and `tile_logo_dim` are the mark's letters and dot without its black outline, the dim one at 40%
    opacity baked into its colours, since a tile image cannot be drawn half transparent or tinted per path.
- **Send.** There is no Send page or button. A `bitcoin:`, `BITCOIN:`, `lightning:`, `LIGHTNING:`, `lnurl:`,
  `lnurlp:` or `spark:` link opens the app, as on the phone (Android matches schemes case-sensitively, and QR codes
  often use the uppercase ones). `lnurlw:` and `lnurlc:` (LNURL withdraw and channel) are not taken: the watch only
  pays. `PaymentsRepo.parse` drops `lightning:`, `lnurl:` and `lnurlp:` before decoding, as the phone's scanner
  does, reads a LUD-17 `lnurlp://host/path` as `https://host/path` (`http` for an onion host) and lowercases an
  all-uppercase payload. A link opens the send flow above Home (`MainActivity` is `singleTop`, so a link reaches the
  open app); without a wallet, during the restore, or while a send is under way, the link is dropped. The flow waits
  for the wallet to be ready (the loading ring), reads the input and prices the send (see Payments), all before the
  hold. An input
  without an amount, an amountless invoice, a Spark address or an LNURL with a range, gets a minimal amount entry
  (the number keyboard, the LNURL range below it); Receive never has one.
  - Review: the amount in the balance's large style, fiat below in the small style, the fee only when it is at
    least 1 % of the amount, and one line naming the payee when the input names one. The fee line
    (`WatchSendFee`) may wrap to 2 lines and is never cut: under Classic a long translation, as the Italian
    "Commissione ₿ 0.00001234", does not fit one. The pay button is the edge
    button, "Hold to pay" (`WatchSendHold`).
  - Hold to pay: holding the button fills a ring around the bezel (`HoldToPayRing`, the geometry of `BezelLoading`)
    over 1.2 s, with a light tick (`SegmentTick`) at each quarter and the confirm haptic at the end, and only then
    the payment goes out. Releasing early empties the ring; a tap never pays. While the wallet connects, locks or
    pauses, the button is disabled under the loading ring.
  - Offline: the app pays only online. A wallet that is offline (`WalletStatus.Offline`) while a request is open and
    its send has not started (on arrival, while it is read or priced, on the amount entry or on review before the
    hold completes) ends the flow on its own refusal, `SendStep.Offline`: the crossed-out cloud, "Can't pay
    offline" (`WatchSendOfflineError`), the reject haptic and OK (`WatchSendClose`), which closes the flow. The
    request is dropped, never kept to pay when the network returns, so nothing (QuickPay included) pays it later.
    Once the hold completed and the send started, going offline changes nothing: the send keeps its pending or
    unknown outcome.
    `PaymentsRepo.skipConfirmation` is asked once the send is priced, for a send the wearer is there for: under
    the phone's QuickPay the review and the hold are skipped and the send goes out at once (see QuickPay).
  - Screen readers: a screen reader's wearer cannot hold, and the button's click pays nothing, so the button
    offers "Pay" as a custom accessibility action, picked from the actions menu, and as its long click (a double
    tap and hold); both only while the hold is enabled. `SendReviewTest` checks the three; the actions menu of
    TalkBack on a watch has not been tried on a device. The Receive button reads "Receive".
  - Ambient: the amount entry, the review and the result are replaced by a dimmed bolt (`WatchSendHidden`), so
    the always-on screen shows no amount; the hold starts over when the watch wakes.
  - Sending: the loading ring, with "Waiting for network" after 3 s. The screen stays on and the flow cannot be
    swiped away or left with Back while the send runs.
  - Result: sent shows a check, "Sent" and the amount with the confirm haptic, and closes after 2 s. A payment that
    did not settle in time, or one that was already under way, from the watch or the phone, shows a clock and
    "Pending"; an outcome Spark could not report shows "Check activity", never a failure; both close after 3 s. The
    amount is the one Spark paid. A request that was not sent because another send still holds the wallet shows
    "Wallet busy" as a failure, never "Pending"; the request whose own stalled call Spark still holds shows "Pending",
    since that call may still pay. A refusal or a failure shows a cross, one line of at most four words and
    an OK button (`WatchSendError`, `WatchSendClose`), with the reject haptic. Insufficient funds, an expired or
    unreadable invoice and an own invoice end there before any hold.
- **Activity.** The Activity page lists the newest 20 payments from Spark's local list: a direction icon (or a
  clock for pending, a cross for failed), the amount with its sign and how long ago (`WatchActivityItem-0` onwards).
  An amount too long for one line, as with eight decimals on a small screen, takes a second line.
  It is read when Spark connects and after every payment or sync, and the last list stays while Spark is
  disconnected, with its times counted again every minute; a list just read counts from its own time. A tap opens the detail: the amount, the fee of a send, the
  date and time and the status (`WatchActivityDetailAmount`, `…Fee`, `…Time`, `…Status`); Spark's list carries no payee or description. With no
  payments it shows a dimmed icon and "No activity" (`WatchActivityEmpty`). A detail whose payment the list does not
  hold, while it loads or after newer payments pushed it out, shows the dimmed icon (`WatchActivityDetailMissing`).
  Ambient mode hides the amounts.
- **Settings.** A short list of icon rows: About, Phone settings and Remove wallet. The watch has no settings of
  its own to edit: the units, the currency and QuickPay come from the phone (see Settings sync). The Phone settings
  row (`WatchSettingsSync`) shows how long ago they were last imported (`WatchSettingsSyncTime`, absent until a first
  import, or after the phone's backup was found missing or unreadable; it moves on every minute while the page is
  open), and a tap imports them now under the loading ring. An import from the tap that fails shows one line under
  the row (`WatchSettingsSyncFailed`). Tapping the balance still swaps which unit shows first, and the choice is kept across restarts until the
  phone's own setting changes. Remove wallet asks with a one-line title and the two round ✕/✓ buttons.
- **Loading.** Every wait shows `BezelLoading`: an indeterminate ring around the screen edge (test tag
  `BezelLoading`), with the screen's content readable inside it. It covers connecting and syncing on Balance, saving
  the restored wallet, removing the wallet, importing the phone's settings from the Settings page, making an
  invoice, the first activity list, and a send being read, priced and sent. It is hidden in ambient mode. Accessibility services report only
  content that no node above it covers, so the full-screen ring has no semantics; its one progress node
  (`BezelLoading`) is a stroke-sized spot at the top of the ring, and the content beneath stays reachable by TalkBack
  and `android layout`. `BezelPadding` and `BezelStrokeWidth` hold the ring's padding and stroke for other edge
  rings, such as hold-to-pay.
- **Style.** Black background; Brand orange only on the one primary action (an `EdgeButton`) and accents; Inter
  Tight; content centered inside the round screen and clear of the time; icons rather than labels where the icon is
  clear; no stacked button lists. Icon and text buttons morph their shape on press and the theme uses the expressive
  (spring) motion scheme. Icons are vector drawables in `res/drawable`, with no icon library.

## Payments

`PaymentsRepo` holds every payment rule; the screens only show what it returns. Everything that reaches Spark needs
the wallet connected and synced (`WalletStatus.Ready`): no invoice is made, nothing is priced and nothing is sent
in any other state.

- **Receive.** An amountless BOLT11 invoice from Spark, valid for one hour. The receive screen waits for its
  payment, which arrives as an SDK event; Spark's payment list is read at the start of the wait and after every
  sync, so a payment that landed while the wallet was disconnected is found too.
- **Inputs.** A BOLT11 invoice, an LNURL-pay link, a Lightning address and a BIP21 URI with a `lightning`
  parameter are read by bitkit-core, as on the phone. A Spark address is read by the Breez SDK's parser, since
  bitkit-core has no such type; only text with the shape of a Spark address is handed to it, because the SDK's
  parser may look other text up on the network. On-chain addresses and every other input are refused.
- **Pricing.** Before the hold to pay, a send is priced by Spark and checked: the amount (an amountless invoice
  and a Spark address need one, an LNURL amount has to be in the service's range), expiry, the self-send guard, and
  the balance against the amount plus its fee. The invoice an LNURL service returns has to be for exactly the
  amount asked, to the millisat as Spark reads it.
- **Sending.** A send runs in the repository's own scope, so the screen that started it cannot cancel it, also not
  by closing, and one send runs at a time. An invoice's send first syncs with Spark, for up to 20 s, and is
  refused when the sync does not reach Spark. The call returns when Spark has the payment; a call that stalls for
  a minute ends as an unknown outcome, and nothing else is sent until Spark returns that call: another request is
  refused as busy (`SendError.Busy`, "Wallet busy"), since nothing of it went out, and the same request (the same
  invoice, or a transfer to the same Spark wallet) is reported as under way (`SendError.InFlight`, "Pending"). The result then waits
  up to a minute for the payment to settle and reports it as pending after that. A lock, a pause or a wipe waits up to 10 s for a send before it
  disconnects, from the moment the send is noted until Spark returns its call; a wallet that is disconnected before
  Spark got the call, or wiped before the note was written, sent nothing: "Wallet not ready", and no note stays. The send screen keeps the screen on, so the app stays on screen and connected while it waits.
  **Known gap:** a wearer who leaves the app during that wait pauses the wallet 10 s later (see Background); the
  settlement is then not seen and the send reports pending after the minute, although it may have completed. The
  activity list shows the real state once the app is back.
- **LNURL.** LNURL-pay and Lightning addresses are covered by unit tests only: the local stack's LNURL server serves
  only addresses its own wallets registered, so no journey pays one.
- **Paying once.** An invoice is sent under an idempotency key derived from the wallet seed, the payment hash and
  the attempt number (`SparkSendKey`, the same derivation as the phone and iOS). Spark makes one transfer per key,
  so the same invoice sent from the watch and the phone, or twice from the watch, is paid once. Before each send
  the wallet syncs and Spark's payment list is read: an invoice it shows paid or pending is not sent again, and
  each attempt it shows failed moves the send to the next key. A sync that does not reach Spark, or a list that
  cannot be read, refuses the send.
- **Spark addresses.** A Spark address has no payment hash to derive a key from, so its transfer goes out under a
  random key. `UncertainSendStore`, in the settings file the wipe clears, notes every send before it goes out and
  drops it once Spark shows its payment or refuses it unsent. A transfer is noted under the Spark identity public
  key its address encodes, which the `sp…` and the `spark…` spelling of one wallet's address share. A transfer whose
  outcome was never learned, also because the app was closed during it, keeps its key there: the next send to the
  same wallet syncs and asks Spark's list about every transfer noted for it. A transfer the list shows completed is not sent again ("Already paid", once), one it shows pending
  is "Pending", one it shows failed makes way for a new key, and one it does not show goes out again under the
  same key, which pays once. Without a sync that reaches Spark, nothing is sent to that address.
- **Reused key on a Spark address.** That a reused key pays once also for a transfer to a Spark address was read
  from the SDK's source at tag 0.28.3, not tried against the operators. `SendPaymentRequest.idempotency_key` is
  documented for "all Spark based transfers": a reused key returns the same payment, and the payment's id is the key.
  `orchestrate_send` first returns the payment its local storage holds under the key. Otherwise the key is the
  transfer id handed to the operators (`send/spark_address.rs`, `TransferService.send_transfer_inner`), and when
  they refuse the submission, as for an id they already hold ("a transfer id is single-use"),
  `recover_committed_transfer` asks them for that id and returns the earlier transfer. The one part the source
  cannot show is that the operators refuse a second submission of an id. The watch does not lean on it alone: a
  transfer goes out again under its key only after a sync that reached Spark shows no payment under it, and any
  error on that send is "unknown" with the note kept, never a new key.
- **Unknown outcome.** When the send call fails or stalls after Spark may have taken the payment and Spark's list
  does not show it, the result is "unknown", never "failed". The activity list tells once Spark lists it, and
  sending the same request again from the watch goes out under the same key, so it cannot pay twice. A refusal
  (expired, not enough funds) that follows an attempt of unknown outcome under the same key is "unknown" too, also
  after the app was closed: the earlier attempt may have paid. An invoice's note is kept until a day after the
  invoice expired.
- **Known gap: the phone's attempts.** The phone picks an invoice's attempt number from its own record of sends
  plus Spark's list; the watch has Spark's list alone and cannot read the phone's record until the two are paired
  (PR 4). The two agree whenever the watch's synced list shows the phone's sends, which is why a send needs a sync
  that reached Spark. Should Spark's operators not report an attempt of the phone, the watch can derive another
  attempt number than the phone: when the phone's attempt failed before it reached the operators and its next
  attempt is in flight, unseen by the watch's sync, the watch's send under the first key is a second payment of
  the invoice. The same holds the other way round for a send of unknown outcome on the watch that the phone's list
  does not show. A transfer to a Spark address has no shared key at all: the phone and the watch each pay it when
  asked. Pairing closes both by sharing the send records.
- **QuickPay.** `PaymentsRepo.skipConfirmation` is the one place that decides whether a send skips the review and
  the hold to pay. See QuickPay below.

### QuickPay

The watch has no QuickPay switch. It follows the phone's: QuickPay on or off, the amount per payment in US dollars
and the daily limit, which is that amount times a multiplier, all read from the phone's settings backup (see
Settings sync). The decision follows the rules of the phone's own `QuickPayRepo`, with two differences that the
watch needs, listed below. A send skips the review and the hold, and goes from pricing straight to sending and the
usual result screens, only when every one of these holds:

- The wallet is connected and synced (`WalletStatus.Ready`), and no wipe runs.
- The send was priced by `PaymentsRepo.prepare`, so the self-send guard, the expiry, the balance and every other
  check before the hold passed. A request those checks refuse never reaches QuickPay.
- The request fixes the amount, as on the phone: a BOLT11 invoice that carries its amount, also the one inside a
  `bitcoin:` URI, or an LNURL-pay link or a Lightning address whose smallest and largest amount are the same. An
  amount the wearer typed always gets the review and the hold, and so does a Spark address, which names no amount.
- QuickPay is on in the settings last imported from the phone. They count however long ago the import was, as the
  phone's own settings do on the phone.
- The amount, without the fee, is no more than the phone's QuickPay amount. As on the phone, the QuickPay amount is
  turned into sats at the dollar rate, rounded half up, and the invoice's sats are compared with that. The
  invoice's unrounded dollar value is compared with the QuickPay amount too, which is the phone's check when it
  pays: where the sats rounded up, the last sat is worth a fraction of a cent too much and is reviewed.
- Today's QuickPay payments from this watch, with this one, stay within the daily limit. The payment counts as its
  dollar value in cents, rounded half up and at least one cent, as on the phone.
- The wearer is there: the first of the two differences below.

The rate is the last one the watch fetched, whatever its age: the phone has no age limit on its rates either. The
phone keeps its rates across restarts and the watch does not, so when QuickPay is on and no dollar rate is in yet,
as for a link that started the app, the decision first fetches the rates, for 5 s at most under the loading ring
(`QuickPayRepo.awaitRate`). Without a rate the answer is no.

The two differences from the phone:

- **The wearer has to be there** (`SendViewModel`, `MainActivity`). The phone turns QuickPay off for a scan it put
  off across its lock, since whoever unlocks did not confirm it. The watch has the same case in more shapes, so:
  - The send is priced within 30 s of its link arriving (`QUICKPAY_WINDOW`), and from the link to the decision the
    app stayed on screen and out of ambient mode, the watch was never locked, and the wallet was not paused again
    once it ran. A link that waited longer for the wallet, as one opened without a network that connects minutes
    later by itself, always ends on the review and the hold. The 30 s cover a wallet that connects and syncs as
    the link opens the app.
  - It is the link's first send flow. Android keeps the Send route and its link in the saved back stack, and
    builds the flow again when it restores the app, as after the process was killed in the background. The first
    flow leaves a mark in that saved state (`SendViewModel.KEY_STARTED`), and a flow that finds it never asks
    QuickPay, whenever it is built.
  - A link that Android sends again from Recents is no request at all. A task that a link created keeps that
    link as its intent, and opening the task from Recents starts the app with it again
    (`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`), minutes or hours after the wearer saw it. `MainActivity` takes such a
    launch as a plain one (`isReplayedLaunch`): the app opens on Home and nothing is priced or paid.
- **QuickPay is off until the phone's settings were imported.** The phone has its settings at hand; the watch has
  none of its own. So QuickPay is off before the first import, and it is turned off by an import that finds no
  backup, a backup that cannot be decrypted or read, one with a newer layout, or QuickPay values outside what the
  phone's sliders offer (1 to 50 dollars, 1× to 50×). An import that fails, as without a network, changes nothing.

Anything that cannot be read answers no, and the wearer holds to pay as without QuickPay: no rate, a settings file
or a count that cannot be read.

- **Daily limit.** `QuickPaySpendStore` keeps one day and its spend in cents in the settings file, which the wipe
  clears. A payment is counted when QuickPay lets it out, before it is sent. As on the phone, it is taken out
  again when it did not go out, as when the wearer turned out to have looked away meanwhile, and when it failed
  (`PaymentsRepo.send`), and when the send flow was closed while QuickPay was decided. It stays counted when it
  was paid, when it is still pending, when its outcome is unknown and when the send ended in an error the watch
  does not know. The phone goes on to take out a pending payment that fails later, from its ledger; the watch keeps no
  ledger, so that one stays counted, and so does a payment the app was closed during. The count can only run ahead
  of what was paid, never behind. The day is the watch's local date; a date earlier than the one last counted
  starts no new count, so setting the watch's clock back frees nothing.
- **Known gap: a change on the phone takes an import to arrive.** QuickPay turned off or lowered on the phone
  applies on the watch from the first import after the phone uploaded its settings. A watch that cannot read the
  backup, as without a network, and a phone that could not upload it, leave the old settings in force. The Phone
  settings row shows when they were last imported.
- **Known gap: two counts.** The phone keeps its own count of the day's QuickPay payments and the watch cannot read
  it, so the phone and the watch each allow the daily limit: together up to twice the limit a day. Pairing (PR 4)
  is where the two can share one count.
- **Known limit: a link sent from the phone.** Being there is measured as the watch unlocked, the app on screen
  and out of ambient mode. It is not measured as a look or a touch: the watch has no sensor it could ask. An app on
  the paired phone can open a `lightning:` link on the watch (`RemoteActivityHelper`) while the wearer looks at the
  phone. With the watch on the wrist and unlocked, and the screen lit by the launch, that send counts as attended
  and goes out under QuickPay with no touch on the watch, as a link from another app on the watch does. It is
  bounded by the phone's QuickPay amount per payment and by the daily limit, and by the two counts above. Whether
  a remote launch lights the screen was not tried on a device. Nothing in V1 tells such a launch from a tap on
  the watch; turning QuickPay off on the phone is the wearer's way to rule it out.
- **Known gap: the phone's payment PIN.** The phone can ask for its PIN before a QuickPay limit is raised; the
  watch has no PIN and adopts whatever the phone saved.

## Settings sync

The watch holds the same seed as the phone, so it can read what the phone backed up. It imports the phone's settings
from VSS and never writes there.

- **What the phone stores.** The phone uploads its whole settings file as the backup category `SETTINGS`: one JSON
  envelope (`SettingsBackupV1`) with `version` 1, `createdAt` and `settings` (`BackupRepo.getBackupDataBytes`,
  `SettingsData` in the phone's `SettingsStore.kt`), stored on VSS under the key `SETTINGS`. The phone uploads it
  about 5 s after a setting changes. The phone reads a bitkit-ios backup under the same keys, with enums in
  Swift's form (`BackupJson.kt`), and the watch reads those the same way; no bitkit-ios backup was tried.
- **What the watch adopts.** Six of those keys, and nothing else:
  - `primaryDisplay` (bitcoin or fiat first) and `displayUnit` (`MODERN` for sats, `CLASSIC` for bitcoin with
    eight decimals). The balance, the send review and result, the paid receive and the activity list and detail
    are written in that unit; the amount entry of a send stays in sats.
  - `selectedCurrency`, the fiat currency.
  - `isQuickPayEnabled`, `quickPayAmount` (US dollars) and `quickPayDailyLimitMultiplier`.

  The phone stores no other QuickPay value and no watch-specific limit; none is invented here.
- **Access.** `VssPhoneBackupReader` sets vss-client up exactly as the phone's `VssBackupClient` and
  `VssStoreIdProvider` do: the server (`Env.vssServerUrl`), the store id derived from the recovery phrase and
  passphrase with the prefix `bitkit_v1_` plus the network name, and the LNURL-auth sign-in
  (`Env.lnurlAuthServerUrl`) whose key the library derives from the same seed. The library decrypts the object with
  a key from the seed too. Dev and tnet builds use Blocktank's staging servers, as the phone does; the phone's
  `E2E_VSS_SERVER_URL` override for local backup tests has no counterpart on the watch.
- **Kept equal to the phone.** `wear/src/test/resources/phone-backup-contract.json` holds what the watch relies on:
  the backup's key, the six settings in the phone's encoding, and each network's store id prefix and two servers.
  The phone's `WatchBackupContractTest` compares it with what the phone app's own serializer writes and with the
  phone's `Env` for the network it is built for; the watch's `PhoneBackupContractTest` reads the same file with the
  watch's parser and compares the watch's `Env` for every network. A renamed setting or a moved server fails one of
  them. The phone's values for mainnet and testnet are compared only when its tests run in a build for that network.
- **Read-only, by structure.** `PhoneBackupReader` is the watch's only way to VSS and has one call, a read of one
  key. Its implementation is the only source file that names the VSS library, and it imports four of its
  functions, derive the store id, sign in, get one object, shut the client down, and its error type.
  `PhoneBackupReaderTest` fails the
  build when the interface gains a call, when another file names the library, or when any source names one of the
  library's store, delete, put, list or LDK calls. The sign-in itself asks the LNURL-auth server for a token and
  changes nothing on VSS; in the library's source the client's constructor and its get make no put or delete
  request (read at the head of synonymdev/vss-rust-client-ffi on 2026-11-28; the shipped binary is 0.5.26-rc2).
- **No client kept.** The library keeps one client for the process, with keys derived from the seed. Each import
  builds it, reads and shuts it down, so nothing of the wallet stays in it once the read is over. The library runs
  each of its calls to the end on the calling thread (`block_on`), so a read cannot be interrupted: one that was
  given up is abandoned, ends with the HTTP call it is in and only then shuts its client down. Until then, a
  moment after a lock or a pause, the client is still alive.
- **The library's timeouts** (read in synonymdev/vss-rust-client-ffi at `v0.5.26-rc2` and its `vss-client-ng`
  0.4.0). Each request to the VSS server ends after 10 s, for the connection, for a read and in all, and a failed
  one is tried again up to 10 times with at most 15 s of pauses in total, so a read of the backup ends by itself.
  The sign-in before it does not: the LNURL-auth requests go through an HTTP client built without a timeout. A
  sign-in server that accepts the connection and never answers would hold the read, and the reader behind it,
  until the connection drops, and every later import would wait its 30 s behind it and import nothing until the
  app's process ends. The watch then goes on with the settings last imported, and a tap on the Phone settings row
  shows the failure. A timeout there has to come from the library.
- **Who waits.** `SettingsSyncRepo` runs each import in a scope of its own, and the read apart from the import.
  A caller only waits: one that is cancelled returns at once, and the import goes on and lands. The import stops waiting for its read after 30 s or when the wallet stops being
  ready, at once in both cases, and then writes nothing.
- **When.** While Home is open, each time the wallet is ready with the app on screen and out of ambient mode, so at
  every start, every return to the app and every wake from ambient mode, and every 5 minutes from then; and on a tap on the Phone settings row. An import
  within a minute of the last read of this wallet's backup is skipped (`MIN_READ_GAP`), so a wallet that keeps
  losing and finding its network does not sign in each time; the tap is never skipped. None is started in the
  background or in ambient mode, and none for a wallet that is not connected and synced: a read under way is given
  up the moment the wallet stops being ready, as on a lock or a pause, whoever started it, and its client is shut
  down when its HTTP call returns. An import under way when the watch goes ambient or its caller leaves still
  lands. Reading takes about 2 s; it is given up after 30 s.
- **Outcomes.** A backup that was read replaces the six values. The phone's `primaryDisplay` is adopted when it
  differs from the one last imported, so the wearer's own swap on the balance lasts until the phone's setting
  changes. A display value the backup lacks, or holds in a form the watch cannot read, keeps the watch's. A backup
  that is missing, cannot be decrypted or decoded, is not the envelope, or has a `version` above 1 keeps the display
  values and turns QuickPay off at once. The library reports an object it fetched and cannot decrypt or decode as
  its get error and every failure to reach the server or sign in as another error (read in
  synonymdev/vss-rust-client-ffi), so `VssPhoneBackupReader` takes a get error for an unreadable backup. QuickPay
  values that are missing, mistyped or out of the phone's range turn QuickPay off. A read that fails, as without a
  network, changes nothing, and QuickPay goes on with the settings last imported. Failures are
  logged and never thrown. Only the wearer's own tap shows one, as one line under the row
  (`WatchSettingsSyncFailed`, "Couldn't import"), until the next tap.
- **Parsing.** `PhoneSettingsParser` reads the JSON tree key by key rather than into the phone's settings class, so
  unknown keys, new settings and other types never fail it. An envelope above 256 KB is refused.
- **Wipe.** Everything imported lives in the settings file, which the wallet's wipe clears: the units, the
  currency, QuickPay, the import time and the day's QuickPay spend. An import that a wipe overtook is taken out
  again, so a removed wallet's settings never reach the next wallet.
- **Logging.** The import logs its outcome only. The seed, the store id and the backup's content are never logged.
- **Size.** vss-client adds `libvss_rust_client_ffi.so` to the watch app: 4.3 MB for `arm64-v8a`, 2.8 MB for
  `armeabi-v7a` and 4.8 MB for `x86_64`, about 2.0 MB gzipped for arm64, plus about 0.2 MB of bindings in the
  debug dex. It ships its own R8 rules, and `:wear:minifyDevReleaseWithR8` passes with it.
- **Checked on the emulator.** On the API 37 emulator a dev build signed in to the staging VSS and imported the
  settings backup of its restored wallet 1.8 s after the wallet synced (2026-11-28).
- **Known gap: a stale backup.** The watch sees a change only once the phone uploaded it. A phone that is offline,
  or whose backup fails, leaves the old settings on VSS, and the watch keeps following them.
- **Later.** Pairing (PR 4) adds a live push from the phone over the Data Layer and the watch-specific limits.

### Self-send guard

The phone and the watch share one Spark wallet, so neither may pay a request the other, or itself, created. Both
refuse with "Cannot Pay Own Invoice". Two checks recognise such a request:

- **Spark identity.** Both devices have the wallet's Spark identity public key (`getInfo().identityPubkey`). A
  Spark address encodes it, and a BOLT11 invoice carries it as the node of a route hint hop with the short
  channel id 17592187092992000001 (`16000000x16000000x1`) when the wallet that made it had the SDK's
  `preferSparkOverLightning` on.
- **Payment hash.** Each device remembers the payment hashes of the invoices it created on Spark until they
  expire, at most 200 on the watch and 2 000 on the phone, whose invoices live a day, and refuses to pay them. An
  invoice that cannot be remembered is not handed out, and the phone refuses an invoice while it cannot read the
  ones it remembers. The watch keeps them in its settings file, which the wallet's wipe
  clears (`OwnInvoiceStore`); the phone keeps them in its cache store (`sparkOwnInvoices`).

The second check exists because Bitkit creates its invoices with `preferSparkOverLightning` off: such an invoice
names Spark's provider node as its payee and one of that node's channels as its only route hint, which every Spark
wallet's invoices share, and nothing in it tells whose it is. This was read from invoices made with SDK 0.28.3 on
the local regtest stack on 2026-11-27: with the setting off the invoice had the provider's hint alone, and with it
on it gained the hint above, whose node equalled the identity key. The SDK itself prices a payment of the wallet's
own invoice without complaint.

**Known gap.** A device does not know the invoices the other one created: the watch pays an invoice the phone made,
and the phone one the watch made, until the two share their hashes. Both stores take hashes from outside
(`OwnInvoiceStore.add`, `CacheStore.addSparkOwnInvoice`), which the pairing work (PR 4) uses to merge them. An
unpaired watch has no way to learn the phone's invoices. The watch runs the guard when an input is read, if the
wallet is ready then, and always before a send is priced, also on the invoice an LNURL service returns; a guard
that cannot tell refuses the send.

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
    note. It and its directory are synced to disk, and a new note that cannot be synced is deleted again when
    possible, so a note that failed to be written does not start a wipe. It is not in the settings file, which is
    reset when it cannot be read: a reset neither loses a wipe under way nor starts one.
  - The wallet counts as wiped once the seed file and the Spark storage are gone. A seed key that cannot be
    deleted, after a second try, does not fail the wipe: without its ciphertext it protects nothing, and each launch
    without a wallet tries the delete again. A Spark instance that cannot be disconnected, after a second try, fails
    the wipe before its storage is deleted: the storage is never deleted under a live instance, the note stays, and
    the wipe is finished later as after any failed step.
  - The note is written twice at most. When it still cannot be written, Remove wallet fails and deletes nothing, so
    a removal the wearer asked for can never be left half done without a note to finish it. A lost-key wipe goes on
    without the note: its seed is unreadable either way.
  - A settings file that cannot be read is replaced by an empty one, so it never blocks a launch or a removal. The
    removed notice of a lost-key wipe lives there and can be lost that way; the wipe itself cannot.
  - While a wipe runs nothing connects to Spark. A wallet start takes a token before it reads the seed; no token is
    handed out during a wipe, and a token stops being valid when a wipe begins and again when it ends. So a start
    begun before or during a wipe is refused, and a balance that arrives is dropped. The token is checked again once
    Spark is built: an instance built while a wipe began is disconnected and never used. When it cannot be
    disconnected, the app keeps hold of it, so the wipe fails before its storage is deleted.

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
- Whether the watch's count of the day's QuickPay payments should be shared with the phone's before pairing
  exists.
- Whether bitkit-ios writes all three QuickPay keys into its settings backup: the watch needs all three and leaves
  QuickPay off when one is missing.

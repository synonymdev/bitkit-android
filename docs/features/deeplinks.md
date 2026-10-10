# Deeplinks

URI schemes and intents that open Bitkit: payment links handed to the scanner, a debug-only `bitkit://screen/...` router for screens and sheets, contact links, Pubky auth links and a recovery shortcut.

## What it does
- One exported activity, `to.bitkit.ui.MainActivity` (`launchMode="singleTask"`), receives all links. `MainActivity.handleLaunchIntent` runs in `onCreate` and `onNewIntent` and calls `AppViewModel.handleDeeplinkIntent`, which accepts the actions `ACTION_VIEW` and `NFC NDEF_DISCOVERED` (`DEEPLINK_ACTIONS`). `launchKey` and `KEY_CONSUMED_LAUNCH_INTENT` keep a recreated activity from replaying a link already consumed.
- `AppViewModel.processDeeplink` order: (1) SamRock/BTCPay setup URL, queued as a scan (`Sheet.BTCPayConnection`); (2) `bitkit://screen/...` (below); (3) `bitkit://recovery-mode` (host or single path segment) turns on recovery mode and opens `Routes.RecoveryMode` with a cleared back stack; (4) `pubkyauth:` and `pubkyring://signup` URLs, scanned with Pubky auth allowed (`handlePubkyAuth`); (5) if no wallet exists, anything else is ignored; (6) otherwise `launchScan(source = DEEPLINK)`.
- Payment links (`bitcoin:`, `lightning:`, `lnurl:`, `lnurlw:`, `lnurlc:`, `lnurlp:`, upper-case `BITCOIN:` / `LIGHTNING:`) go through the same decode path as a scanned QR code (send.md, lnurl.md, receive.md for outcomes). A locked app (PIN) queues the scan and replays it after unlock (`enqueueDeferredScan`, `deferLockedScan`).
- Contact link `bitkit://contact?pubky=<key>` (`models/PubkyContactLink.kt`): exactly one `pubky` parameter, raw 52-character key or `pubky`-prefixed, URL-encoded. Routes to Add Contact (unsaved key), Contact Detail (saved), Profile (own key); invalid values show the generic scan error toast (contacts.md, profile.md).
- Pubky auth: `PubkyAuthRequest.isProtocolUrl` accepts `pubkyauth:` and `pubkyring://signup`. With Paykit UI off in settings the scan shows the generic error toast. Signup links open `Sheet.PubkyAuth` only when no identity exists (else an "already signed in" toast); sign-in links need a local identity with its secret key (else toasts) (profile.md).
- Screen deeplinks `bitkit://screen/<id>[/<child>]`: ids are kebab-case class names (`ScreenDeepLinks.kebabId`). Only in debug builds (`app/src/debug/java/to/bitkit/ui/utils/ScreenDeepLinkRuntime.kt`; the release stub `app/src/release/java/to/bitkit/ui/utils/ScreenDeepLinkRuntime.kt` has `isEnabled = false` and no routes) and only with dev mode on (`ScreenDeepLinks.shouldQueue`). Otherwise the app logs `Ignoring screen deeplink, not queued`.
- Resolution in `ContentView.kt` (`pendingScreenDeepLink`): `SheetDeepLinks.sheetFor` first, else `navController.handleDeepLink`; an unmatched link logs `Unhandled screen deeplink` and changes nothing. Sheet families and their start children (from each `DEEP_LINK_STARTS`): `send` (recipient, address, contact-select, amount, qr-scanner, coin-selection, add-tag, coming-soon, support), `receive` (qr, amount, edit-invoice, add-tag, geo-block), `backup` (intro, multiple-devices, metadata), `widgets` (gallery and the preview/edit screens), `hardware` (intro); standalone sheets `activity-date-range-selector`, `activity-tag-selector`, `qr-scanner`. Screens are the `Routes.DeepLinkable` entries registered with `deepLinkableComposable` (for example `settings`, `log-detail/<fileName>`); `Routes.InternalOnly`, `SendRoute.InternalOnly` cannot be targeted; the journeys verify that `recovery-mnemonic`, `backup/show-mnemonic` and `send/fee-rate` are rejected.
- After a handled screen link `ScreenDeepLinks.detachScreenUri` clears the intent data so a relaunch does not reopen it.

## How a user reaches it
- From outside the app: a browser or app firing a `bitcoin:` / `lightning:` / `lnurl*:` link, an NFC tag (same schemes, `NDEF_DISCOVERED`), a QR scanned by another app, or the Android app shortcut `recovery-mode` (`app/src/main/res/xml/shortcuts.xml`, `bitkit://recovery-mode`).
- Dev mode: Settings > Support, tap the version row five times (`DEV_MODE_TAP_THRESHOLD`, toast `DevModeEnabledToast`; the same gesture turns it off, `DevModeDisabledToast`).
- Intent filters in `app/src/main/AndroidManifest.xml`: (a) `VIEW` with schemes `bitkit`, `slash`, `slashauth`, `bitcoin`, `BITCOIN`, `lightning`, `LIGHTNING`, `lnurl`, `lnurlw`, `lnurlc`, `lnurlp`; (b) `NDEF_DISCOVERED` with the same schemes; (c) `VIEW` on `https://www.bitkit.to/treasure-hunt` (`autoVerify`); (d) `USB_DEVICE_ATTACHED` with `res/xml/usb_device_filter` (hardware wallets, hardware-wallet.md); (e) activity-aliases `.ui.MainActivityPubkyAuth` (`pubkyauth` hosts `signin_grant`, `signup_grant`) and `.ui.MainActivityPubkySignup` (`pubkyauth` hosts `signup`, `direct_signup`; `pubkyring://signup`), both `enabled="false"` and switched on by `services/PubkyAuthHandlerRegistrar.kt` when the identity state allows.

## Code
- `app/src/main/java/to/bitkit/ui/MainActivity.kt`: intent entry, `launchKey`, USB attach intent.
- `app/src/main/java/to/bitkit/viewmodels/AppViewModel.kt`: `handleDeeplinkIntent`, `processDeeplink`, `launchScan`, `handleScan`, `consumeScreenDeepLink`, `awaitPubkyDeeplinkInitialization` (waits for Pubky init before contact and auth links).
- `app/src/main/java/to/bitkit/ui/utils/ScreenDeepLinks.kt`, `app/src/main/java/to/bitkit/ui/utils/SheetDeepLinks.kt`, `app/src/main/java/to/bitkit/ui/utils/Transitions.kt` (`deepLinkableComposable`).
- `app/src/debug/java/to/bitkit/ui/utils/ScreenDeepLinkRuntime.kt` (routes and sheet ids), `app/src/release/java/to/bitkit/ui/utils/ScreenDeepLinkRuntime.kt` (disabled stub).
- `app/src/main/java/to/bitkit/ui/ContentView.kt`: `Routes` sealed interface (`DeepLinkable`, `InternalOnly`), pending-link effect, sheet hosting. `app/src/main/java/to/bitkit/ui/components/SheetHost.kt`: `Sheet` types.
- Sheet route files with `DEEP_LINK_STARTS`: `app/src/main/java/to/bitkit/ui/sheets/SendSheet.kt` (`SendRoute`), `app/src/main/java/to/bitkit/ui/screens/wallets/receive/ReceiveSheet.kt` (`ReceiveRoute`), `app/src/main/java/to/bitkit/ui/sheets/BackupSheet.kt`, `app/src/main/java/to/bitkit/ui/sheets/WidgetsSheet.kt`, `app/src/main/java/to/bitkit/ui/sheets/hardware/HardwareSheet.kt`.
- `app/src/main/java/to/bitkit/models/PubkyContactLink.kt`, `app/src/main/java/to/bitkit/models/PubkyAuthRequest.kt`, `app/src/main/java/to/bitkit/models/SamRockSetupRequest.kt`, `app/src/main/java/to/bitkit/services/PubkyAuthHandlerRegistrar.kt`.
- `app/src/main/AndroidManifest.xml` (intent filters), `app/src/main/res/xml/shortcuts.xml` (recovery shortcut).
- Unit test: `app/src/test/java/to/bitkit/ui/utils/ScreenDeepLinksTest.kt`.

## How to drive it
- Command: `adb shell am start -a android.intent.action.VIEW -d "<uri>" to.bitkit.dev` (add `-W` to wait). The log (`adb logcat`) shows `Received deeplink`, `Queuing 'deeplink' scan`, `Starting scan from 'deeplink'`; an undecodable value logs `Failed to decode scan data`. `am` prints the data redacted; that is not a truncated URI. Quote a URI containing `&` twice (outer double quotes, inner single quotes), see `journeys/send/own-invoice-guard.xml`.
- `journeys/deeplinks/screen-deeplink.xml`: `bitkit://screen/settings` opens Settings, back returns Home, `log-detail/<file>` opens the log, `recovery-mnemonic` is rejected, cold start works, dev mode off ignores the link. Needs a debug build, dev mode on, a log file.
- `journeys/deeplinks/sheet-deeplink.xml`: `screen/send`, `screen/widgets/price-edit`, `screen/backup` open sheets; `backup/show-mnemonic` and `send/fee-rate` are rejected without a crash.
- `journeys/deeplinks/pubky-contact.xml`: `bitkit://contact?pubky=` with unsaved, saved, own and invalid keys, and a cold start behind the PIN screen. Needs Paykit enabled, a profile, one saved contact, PIN.
- Payment links in journeys: `journeys/send/own-invoice-guard.xml` (`lightning:` and unified `bitcoin:` URIs), `journeys/lnurl/lnurl-pay-comment-note.xml` (`lightning:<lnurl>`), `journeys/coin-selection/manual-coin-selection.xml` and `journeys/coin-selection/manual-coin-selection-load.xml` (`bitcoin:<address>?amount=0.0001`). `journeys/onchain-receive/`, `journeys/receive/` and `journeys/amount-limits/` use no OS links.
- E2E: none of `receive`, `send`, `lnurl`, `onchain`, `lightning`, `numberpad`, `mainnet/*` specs send an OS intent; they type or inject strings (`typeAddressAndVerifyContinue`, `ScanPrompt`). Searches of `bitkit-e2e-tests/test` for `deepLink`, `am start` and `bitkit://` found nothing.

## What proves it
- Screen and sheet links: the target screen or sheet is visible (for example the Settings tabs "General", "Security", "Advanced"; the Send recipient picker with "Scan QR", "Contact", "Paste Invoice", "Enter Manually"); rejected links leave the screen unchanged and log `Unhandled screen deeplink`.
- Payment links: the Send sheet at Amount or Confirm, an error toast, or `SelfPaymentToast`; log lines above.
- Contact links: Add Contact with the key prefilled, Contact Detail, or Profile.

## Not covered by tests
- OS-level behavior of the `https://www.bitkit.to/treasure-hunt` link, NFC `NDEF_DISCOVERED`, the `slash` / `slashauth` schemes, `BITCOIN:` upper-case URIs, the `recovery-mode` shortcut and `bitkit://recovery-mode` link.
- `pubkyauth:` and `pubkyring://signup` links (no journey in my folders; see profile.md for Pubky journeys), SamRock/BTCPay setup links.
- Link handling while a PIN lock is up is covered only for contact links.
- Most `bitkit://screen/...` ids: the journeys try `settings`, `log-detail`, `send`, `widgets/price-edit`, `backup`.

## Gotchas
- Screen deeplinks never work on release builds and need dev mode; a "link did nothing" report is usually one of these.
- Without a wallet, SamRock setup URLs and payment links are ignored (`walletRepo.walletExists()` checks); `bitkit://screen`, `bitkit://recovery-mode` and Pubky auth URLs are handled before that check.
- The `slash`, `slashauth` and `https://www.bitkit.to/treasure-hunt` filters are declared, but no code besides the manifest mentions them in `app/src/main/java` (the treasure-hunt link would reach the scanner like any other URL; behavior unverified).
- Link data is logged redacted (`sanitizedDeeplinkLogValue`).

Unclear: what the app does with `https://www.bitkit.to/treasure-hunt`, `slash:` and `slashauth:` links (declared in the manifest, no handler found in `app/src/main/java`).

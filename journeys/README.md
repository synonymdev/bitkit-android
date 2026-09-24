# Journeys

A journey is an XML-specified walkthrough of app behaviour, evaluated by an agent driving a running
emulator or device. They are developer-assistance specs: they give an agent a reliable route through
a flow so it can reproduce a bug, check a change by hand, or show you what a screen does today.

**Journeys are the QA contract for a PR.** A PR with a user-visible change adds or updates the
journeys that prove it and lists them in its body, and reviewers drive the listed journeys on a
device instead of reading a prose walkthrough.

**A journey is not the source of truth.** The `android` CLI ships its own journey documentation
(`references/journeys.md` in the `android-cli` skill) which says the opposite — "the journey XML is
the source of truth; if the app disagrees with the journey, the app has failed". That rule does not
apply here. A journey that no longer matches the app is most likely **stale**: the corpus is
maintained by hand alongside two codebases and drifts. Say what you found, update the journey, and
escalate only once you have separately confirmed the app is wrong. Never quietly rewrite a journey
to describe a behaviour you have not checked — that swaps one wrong description for another.

The one exception: if the app crashes, exits or freezes, evaluation stops there, and that *is* worth
escalating.

## Format

```xml
<journey name="short lowercase name">
  <description>What this proves, and the preconditions needed to prove it.</description>
  <actions>
    <action>Tap the Spending balance card on the home screen</action>
    <action>Verify the spending amount screen (tag "SpendingAmount") is visible</action>
  </actions>
</journey>
```

- Evaluate `<action>` elements in order, and report each one. An action that does not hold is worth
  reporting as-is — it may be a stale step as easily as a real problem.
- An action beginning with "check" or "verify" is an expectation about the **current** screen —
  inspect it, do not scroll or interact to satisfy it.
- An action that specifies several interactions is split into sub-actions and evaluated individually.
- An action may also name a shell command to run (`adb shell am start …`); run it as written.
- If an interaction cannot be performed as written, say so and stop rather than improvising a route
  around it — the point is to find out where the written route stopped matching the app.

## Running a journey

Drive the device with the `android` CLI (see the Agent CLI section in `AGENTS.md`):

```bash
android emulator list                  # AVD names; `start` requires one, it has no default
android emulator start Pixel_9         # or `android run` against a connected device
android layout --pretty                # flat JSON of on-screen elements
android layout --diff                  # only what changed, to keep context small
android screen capture -o shot.png     # secondary; use when layout hits a WebView or animation
adb shell input tap <x> <y>            # tap an element's `center`
```

`android layout` reports each element's `resource-id`, `text`, `content-desc`, `interactions`,
`bounds` and `center`. Compose `testTag`s land in `resource-id`, which is what the journeys assert
on. **The JSON keys are hyphenated, not camelCase** — the skill's `references/interact.md` documents
`resourceId` and `contentDesc`, and a runner filtering on those finds nothing.

Prefer `android layout` over screenshots: it names elements by their test tag, and full-resolution
screenshots can exceed image size limits.

**Not everything on screen reaches `android layout`.** Verified while running these journeys:

- **Toasts never appear.** The "Insufficient balance" warning the amount journeys assert on is
  visible only in a screenshot. Capture one immediately after the rejected keypress — the toast
  lasts about 1.5s.
- **Widget gallery tiles carry no text.** The Add Widget sheet lists `WidgetListItem-price`,
  `WidgetListItem-weather` and so on with empty `text`, so "verify Bitcoin Price and Bitcoin Weather
  are visible" needs a screenshot. Assert the identifier instead where the journey allows it.
- **Exposure is not stable between dumps.** `HeaderMenu` and `ProfileButton` were absent from one
  home-screen dump and present in the next, with no navigation in between. If an element a journey
  names is missing, dump again before concluding it is gone, then fall back to
  `android screen capture --annotate` and `android screen resolve` to tap it by coordinate.

**Do not type long strings.** `adb shell input text` silently drops characters — it lost 54 of a
397-character invoice in testing — and `adb shell cmd clipboard` is not implemented on the emulator
image. Hand an address or invoice to the app as a URI instead, which also skips the recipient screen:

```bash
adb shell am start -a android.intent.action.VIEW -d "lightning:<invoice>" to.bitkit.dev
```

`am` echoes the intent back with the data redacted — `dat=bitcoin:` with nothing after the scheme.
That is `Uri.toSafeString()` hiding the rest, not a truncated argument; the full URI is delivered.
To confirm the app received it, read the app log rather than the `am` output: a good delivery logs
`Received deeplink`, `Queuing 'deeplink' scan` and `Starting scan from 'deeplink'`, and an invalid
address then fails with `Failed to decode scan data`.

For short strings that must be typed, enter digit groups and separators separately and verify after —
dotted strings such as host IPs are where the dropping shows up first.

## Backend preconditions

The dev flavor targets the **staging regtest** backend (`Env.kt` points regtest at
`*.stag0.blocktank.to`), so most journeys need nothing running locally beyond the app. Fund and mine
through the `lsp` helper at the repo root, which talks to the same staging LSP:

```bash
./lsp POST /regtest/chain/deposit '{"address":"<savings addr>","amountSat":100000}'
./lsp POST /regtest/chain/mine '{"count":3}'
```

`deposit` prints the funding txid; **`mine` prints nothing on success** and signals only through its
exit status, so an empty response there is not a failure. Give the wallet ~20s to sync.

Fund a wallet before any amount journey — with a zero balance the caps fall back to the global
maximum and the journeys pass for the wrong reason. Per-suite preconditions (Trezor emulator, Pubky
fixtures, push notifications) live in each suite's README.

## Capabilities

This table is the authority for what the journey environment provides: a step it covers belongs in a
journey, and a step it does not is a manual test in the PR body naming the missing capability.

It changes when the environment gains or loses a capability, not when a journey is added, so adding
a journey does not touch this file. `ls journeys/` is the suite list and each suite's README is its
own documentation; nothing here restates them. A listing kept here would have to be edited by every
journey PR, which is what made this file conflict on every merge.

| Capability | Provided by |
| --- | --- |
| On-chain funds and blocks on regtest | `./lsp` deposit and mine against the staging LSP — [Backend preconditions](#backend-preconditions) |
| Several separate on-chain UTXOs to choose between | three or more `./lsp` deposits, each mined, so manual coin selection has inputs to list — [Backend preconditions](#backend-preconditions) |
| Lightning channels, CJIT orders and quoted maxima | the same staging LSP the dev flavor targets, plus its node as an external LN peer — [Backend preconditions](#backend-preconditions), [amount-limits](amount-limits/README.md) |
| A hardware wallet to pair, watch and sign with | the deterministic Trezor emulator from `bitkit-docker` over the Bridge transport, with the USB attach intent injected by `adb`; USB enumeration, permission grants, the OS picker and BLE are not simulated — [hardware-wallet](hardware-wallet/README.md) |
| Push notifications to a backgrounded or killed app | an FCM push from a CJIT order paid through `./lsp`, read back with `adb shell dumpsys notification` — [cjit-notifications](cjit-notifications/README.md) |
| The OS notification-permission dialog | an API 33+ target, reset with `adb shell pm revoke to.bitkit.dev android.permission.POST_NOTIFICATIONS` — [notification-permission](notification-permission/README.md) |
| An incoming Payment Request from a linked issuer | the fixture issuer, saved as a contact and linked on receiver path `bitkit/server` — [payment-requests](payment-requests/README.md) |
| Two linked Bitkit wallets for a subscription lifecycle | a second Bitkit instance linked to the first, so a proposal can be reviewed and accepted — [subscriptions](subscriptions) |
| A Pubky identity and a two-wallet marketplace purchase | the integration fixture runtime: Pubky testnet, Paykit Server, regtest bitcoind and Fulcrum — [pubky-marketplace](pubky-marketplace/README.md) |
| LNURL pay, withdraw, channel and auth, and Lightning Addresses | the `bitkit-docker` `lnurl-server` on local regtest, with the app started by `just run docker`, which builds with `E2E=true` and forwards its ports over `adb reverse`; it issues memo invoices, so a check that needs a description-hash invoice needs another endpoint — [lnurl](lnurl) |
| Deep links, addresses and invoices handed to the app | `adb shell am start -a android.intent.action.VIEW -d "<uri>"`; `bitkit://` screen and sheet routes sit behind the dev-mode gate — [Running a journey](#running-a-journey), [deeplinks](deeplinks) |

## Cross-platform

These journeys are also carried by [`bitkit-ios/journeys`](https://github.com/synonymdev/bitkit-ios/tree/main/journeys),
which keeps the same file names, journey names and step sequence so the two sides stay diffable.
The prose is not byte-identical: each side annotates its own identifier vocabulary, so a step reads
`(testTag "N9")` here and `(id "N9")` on iOS, and a platform sometimes adds a note of its own. Diff
for the shape of the journey, not for equality. `AGENTS.md` has the command equivalents and the
rules for porting.

Known differences in the corpus, as of the iOS port (synonymdev/bitkit-ios#691):

| Here | On iOS |
| --- | --- |
| `cjit-notifications/cjit-foreground-service-notification.xml` | `cjit-background-notification.xml`, with the thousands-separator assertions dropped — the notification extension never formats an amount |
| `notification-permission/toggle-off-opens-system-settings.xml` | `toggle-off-and-system-settings-route.xml` — iOS does not deep link into system settings, so it asserts the real route in through Settings ▸ Notifications |
| `hardware-wallet/usb-reconnect.xml` | `reconnect.xml` — over Bridge, since iOS cannot do WebUSB |
| `hardware-wallet/receive-onchain.xml`, `hardware-wallet/send-onchain.xml` | not ported |
| `activity/date-range-rapid-month-taps.xml` | not ported — iOS has no activity journey suite, and the rapid month tap behaviour was not checked there |
| `coin-selection/manual-coin-selection.xml` | not ported — iOS has the screen (`SendUtxoSelectionView`) but no accessibility identifiers on it yet |
| `payment-requests/requested-resolution-failure.xml` | not ported |
| `node-lifecycle/cancelled-node-restart.xml` | not ported — the routes run through Android's LDK Debug and Rapid-Gossip-Sync screens and assert on Android app-log lines |
| `restore-wallet/paste-seed-fragment.xml` | not ported — the iOS Restore screen still has the 12/24-only paste guard, so the behaviour does not exist there yet |
| `send/own-invoice-guard.xml` | not ported — iOS has no own-invoice guard |
| `settings/electrum-server-error-toasts.xml` | not ported — iOS still shows one generic message for every manual Electrum connect failure |
| `transfers/closed-channel-transfer-settles.xml` | not ported — the closed-channel and order-closure settle rules are an iOS follow-up |
| `deeplinks/pubky-contact.xml` | ported — same contact routing and unlock behavior |
| `deeplinks/screen-deeplink.xml`, `deeplinks/sheet-deeplink.xml` | not ported — iOS registers the `bitkit` scheme but has no screen or sheet router |
| `backup-restore/restore-keeps-tags-and-closed-channels.xml` | not ported yet — iOS already gates uploads across the whole restore (`AppScene.restoreFromMostRecentBackup` sets `BackupService.setRestoring(true)` before the timestamp probe), but still applies the three activity slices in one block (`BackupService.performFullRestoreFromLatestBackup`), which is the half this journey pins; port it with the iOS slice fix |
| `shop/gift-card-category-titles.xml` | not ported — iOS still hardcodes the category names, and its route in has no screen deeplink |
| `amount-limits/transfer-spending-preset-delete.xml` | not ported yet — the same fix shipped in synonymdev/bitkit-ios#289, so this one should port |
| `app-update/critical-update-onboarding.xml` | not ported yet — iOS already blocks at the top level in `AppScene`, so the journey applies there once written |
| `home/pull-to-refresh-rates.xml` | not ported — iOS does not refresh exchange rates on pull to refresh |
| `receive/receive-auto-tab-selection.xml` | not ported — the Auto tab override fix is Android-only so far; iOS parity not checked |
| `security/pin-lock-on-resume.xml` | not ported yet — iOS already clears the PIN verification on entering the background, so the journey applies; it lands with the iOS side of synonymdev/bitkit-android#1298 |
| `security/pin-result-long-label.xml` | not ported — the toggle exists on the iOS security success screen, but the overlap check is a follow-up |
| `tags/activity-tag-length-cap.xml` | not ported — iOS has no 20-character cap on tag input |
| `transfer/transfer-to-savings-returns-home.xml` | not ported — iOS already resets navigation to home on the same OK, so the journey has no iOS counterpart yet |
| `lnurl/lnurl-pay-comment-note.xml` | not ported — bitkit-ios has not been checked for keeping the LNURL-pay comment on the activity |
| `coin-selection/manual-coin-selection-load.xml` | not ported — iOS has `SendUtxoSelectionView` but no load error, retry or identifiers to assert on |
| — | `hardware-wallet/transfer-to-spending-over-max.xml` exists only on iOS |

### Running one on iOS

Two mechanics differ from the Android runner, both hit while checking this corpus:

- **`elementRef`s expire on a timer**, not only when the layout changes. A tap against a ref from a
  snapshot taken a minute earlier fails with "the runtime UI snapshot for this simulator has
  expired". Snapshot and tap in the same step.
- **`snapshot-ui` omits controls that are still tappable.** The receive screen's Copy button is not
  in its target list, but `wait-for-ui --identifier ReceiveCopyQR --predicate exists` returns it
  *with a usable ref*. Use that to reach an element the snapshot does not list, rather than
  concluding it is gone.

### Identifiers

The vocabulary is deliberately shared, and mostly matches: the whole number pad (`N0`–`N9`, `N000`,
`NDecimal`, `NRemove`), `SpendingAmount*`, `SpendingAdvanced*`, `External*`, `Hardware*`, `Widget*`,
and Settings (`Tab-general`, `Tab-security`, `Tab-advanced`, `NavigationBack`, `HeaderMenu`,
`CurrenciesSettings`, `UnitSettings`, `WidgetsSettings`, `QuickpaySettings`).

| Concept | Android testTag | iOS accessibilityIdentifier |
| --- | --- | --- |
| Send amount screen | `send_amount_screen` | `SendAmount` |
| Send available balance | `AvailableAmount` **and** `available_balance` | `AvailableAmount` |
| Send max | `SendAmountMax` | *(no button — tap `AvailableAmount`)* |
| External amount available | — | `ExternalAmountAvailable` |
| Background payments setting row | `BackgroundPaymentSettings` | `NotificationsSettings` |
| Send over-max toast | — *(no tag; assert it from a screenshot)* | `SendAmountExceededToast` |
| Widgets intro screen container | — | `WidgetsOnboarding` |
| Home suggestion cards | `Suggestion-<id>` | — *(cards expose no identifier)* |
| Receive QR copy button | `ReceiveCopyQR` | `ReceiveCopyQR` *(absent from `snapshot-ui` targets; see below)* |
| Payment Request row | `PaymentRequestRow-<id>` | `PaymentRequestRow-<id>-<period>` *(`-one-time` for a one-off)* |

Two of those are unreconciled rather than intentional: the Send screen emitting both
`AvailableAmount` and `available_balance`, and the background-payments row name. Settling either is a
code change on one side, not a journey change.

`SubscriptionRow-<id>` matches on both platforms. iOS appends the billing period to
`PaymentRequestRow` because every recurring payment of one subscription shares the same id, so a
journey that must run on both platforms matches on the `PaymentRequestRow-<id>` prefix.

One asymmetry worth knowing when comparing: Android builds `Tab-*` from the enum name
(`CustomTabRowWithSpacing`), so `Tab-all` is stable in any locale, while iOS derives it from the
tab's display name and becomes `Tab-todas` in Spanish. Journeys naming a `Tab-*` identifier assume an
English device for iOS's sake.

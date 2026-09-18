# Journeys

A journey is an XML-specified walkthrough of app behaviour, evaluated by an agent driving a running
emulator or device. They are developer-assistance specs: they give an agent a reliable route through
a flow so it can reproduce a bug, check a change by hand, or show you what a screen does today.

**Journeys are not a QA gate.** Nothing in `.github/workflows` reads `journeys/` — `ui-tests.yml`
runs the instrumented tests and never touches this directory. They are agent-evaluated and
non-deterministic, which is why they belong on a manual, developer-triggered run rather than a
blocking CI gate. An agent runs one on request.

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

## Suites

| Suite | Journeys | Notes |
| --- | --- | --- |
| [amount-limits](amount-limits) | 4 | Number pad caps on all four amount screens |
| [backup](backup) | 1 | Clearing a wrong word on Confirm Recovery Phrase; throwaway wallet only, no README |
| [cjit-notifications](cjit-notifications) | 3 | CJIT channel-ready notifications; needs FCM push |
| [deeplinks](deeplinks) | 2 | `bitkit://screen/…` and sheet routing behind the dev-mode gate; no README |
| [hardware-wallet](hardware-wallet) | 17 | Trezor over USB; needs the Trezor emulator |
| [notification-permission](notification-permission) | 4 | Background-setup toggles |
| [payment-requests](payment-requests) | 2 | Requires a linked fixture issuer; rejected shapes are unit fixtures |
| [pubky-marketplace](pubky-marketplace) | 1 | Two-wallet Paykit marketplace payment; integration fixture required |
| [widgets](widgets) | 2 | Needs no backend — the quickest way to see the loop work; no README |

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
| `payment-requests/requested-resolution-failure.xml` | not ported |
| `deeplinks/*` | not ported — iOS registers the `bitkit` scheme but has no screen or sheet router |
| `backup/confirm-mnemonic-clear-wrong-word.xml` | not ported — `BackupConfirmMnemonic.swift` clears only the last word by its chip, with no red-word tap |
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
| Payment Request row | `PaymentRequestRow-<id>` | `PaymentRequestRow-<id>-<counterparty>-<receiverPath>-<period>` |

Two of those are unreconciled rather than intentional: the Send screen emitting both
`AvailableAmount` and `available_balance`, and the background-payments row name. Settling either is a
code change on one side, not a journey change.

One asymmetry worth knowing when comparing: Android builds `Tab-*` from the enum name
(`CustomTabRowWithSpacing`), so `Tab-all` is stable in any locale, while iOS derives it from the
tab's display name and becomes `Tab-todas` in Spanish. Journeys naming a `Tab-*` identifier assume an
English device for iOS's sake.

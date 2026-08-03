## Screen deep links

Every screen in the root navigation graph is reachable by URI, so a flow can be entered directly
instead of tapped through. Intended for QA, agent journeys and bug repros.

```
bitkit://screen/<screen-id>[/<required-arg>...][?<optional-arg>=<value>]
```

Only available while dev mode is on (Settings ▸ Advanced ▸ Dev Settings), which is the default on
debug builds.

### Screen ids

The id is the route's class name in `Routes` (`ContentView.kt`) converted to kebab-case, so
`Routes.ActivityDetail` is `activity-detail` and `Routes.RgsServer` is `rgs-server`. Nothing
registers a screen by hand: `composableWithDefaultTransitions` derives the link from the route type,
so a new screen is reachable as soon as it is added to the graph.

Route arguments become part of the pattern. Arguments without a default are path segments,
arguments with a default are query parameters.

| Route | URI |
| - | - |
| `Settings` | `bitkit://screen/settings` |
| `ActivityAssignContact(id)` | `bitkit://screen/activity-assign-contact/{id}` |
| `ActivityDetail(id, walletId = null)` | `bitkit://screen/activity-detail/{id}?walletId={walletId}` |
| `ChannelDetail(channelId)` | `bitkit://screen/channel-detail/{channelId}` |
| `ShopWebView(page, title)` | `bitkit://screen/shop-web-view/{page}/{title}` |
| `Contacts(showAddContactSheet = false)` | `bitkit://screen/contacts?showAddContactSheet={showAddContactSheet}` |

### Firing a link

```sh
adb shell am start -W -a android.intent.action.VIEW \
  -d "bitkit://screen/settings" to.bitkit.dev
```

Escape `&` as `\&` when passing more than one query parameter.

`-W` only reports that an activity resolved, which it always does: the manifest accepts every
`bitkit:` URI by scheme, so `bitkit://screen/typo` still starts `MainActivity` and is rejected later
in the app. To tell a good id from a bad one, assert on the destination, or watch for the rejection:

```sh
adb logcat -c
adb shell am start -a android.intent.action.VIEW -d "bitkit://screen/typo" to.bitkit.dev
adb logcat -d | grep "Unhandled screen deeplink"
```

The app must already be past onboarding. A link that arrives earlier is held and replayed once the
wallet is loaded. A link that arrives while the PIN screen is up navigates behind it, so the PIN is
still required before anything is visible.

Back from a deep-linked screen returns to the wallet overview rather than leaving the app.

### Excluded screens

Deep links are an input any installed app or web page can send, so they navigate and prefill only:
they never send, broadcast or change a setting without the usual confirmation.

These screens are unreachable by URI and a link naming one is dropped with a warning:

| Route | Reason |
| - | - |
| `RecoveryMnemonic` | displays the recovery phrase |
| `AuthCheck` | performs the action named by `onSuccessActionId`, including disabling the PIN |
| `RecoveryMode` | already has `bitkit://recovery-mode` |
| `LegacyRnRecovery` | migration recovery |
| `LnurlChannel` | carries an LNURL callback, scanner-driven only |
| `CriticalUpdate` | blocking screen |
| `ExternalAmount`, `ExternalConfirm`, `ExternalSuccess` | a fresh `ExternalNav` scope has no peer, so confirm crashes on `requireNotNull` and success reports an open that never happened |
| `SavingsProgress`, `SettingUp`, `SpendingAdvanced`, `SpendingConfirm`, `SpendingHwSign`, `SpendingHwSigned` | read transfer state built by earlier steps; a fresh link renders empty or returns home, and `SavingsProgress` can act on default or retained channel state |

Reaching the late transfer screens directly needs a per-flow setup contract that prepares the
expected state, does the server-side preparation, passes stable identifiers as route parameters and
reloads validated data before navigating. That is tracked separately.

Payment URIs (`bitcoin:`, `lightning:`, `lnurl*`) are unaffected and still go through the scanner
decode path.

### Bottom sheets

Sheets are not part of the root graph. They are `Sheet` values rendered by `SheetHost`, each
carrying the start route of its own nested graph. `SheetDeepLinks` maps a path to a `Sheet`, so the
URI shape is the same:

```
bitkit://screen/<sheet-id>[/<route-id>]
```

The sheet id is the `Sheet` class name in kebab-case and the route id is the nested route's class
name, so the same rule holds as for screens. The bare id opens the sheet at its first registered
route (`bitkit://screen/send` is the recipient picker, `bitkit://screen/backup` the backup intro).

Each sealed route family owns which of its states may start a flow, through a `DeepLinkStart` marker
and its own `fromDeepLink`. `SheetDeepLinks` only picks the family and wraps the result.

| Sheet | Reachable routes |
| - | - |
| `send` | `recipient`, `address`, `contact-select`, `amount`, `qr-scanner`, `coin-selection`, `add-tag`, `coming-soon`, `support` |
| `receive` | `qr`, `amount`, `edit-invoice`, `add-tag`, `geo-block` |
| `backup` | `intro`, `multiple-devices`, `metadata` |
| `widgets` | `gallery` and every `*-preview` / `*-edit` route |
| `hardware` | `intro` |
| `activity-date-range-selector`, `activity-tag-selector`, `qr-scanner` | no nested graph, id only |

Unlike screens, sheet routes are registered by hand in `SheetDeepLinks`. A route is left out when it
cannot stand on its own:

- **Children of a nested graph** cannot be a `NavHost` start destination. `SendRoute.FeeRate` and
  `FeeCustom` live inside `navigationWithDefaultTransitions<SendRoute.FeeNav>`, so starting there
  throws `IllegalStateException: Cannot find startDestination ... from NavGraph`. `send/fee-nav`
  itself resolves but renders only the screen title, so it is out too.
- **Routes that require state the flow built up.** `send/quick-pay` does
  `requireNotNull(quickPayData)` (`SendSheet.kt:309`) and throws when entered cold. `send/confirm`
  and the receive confirm/liquidity routes do not crash, but render an empty or zero-amount screen.
  `send/confirm` offers a "Swipe To Pay" control over a payment that was never built.
  `hardware/searching` waits forever, because discovery is started by the intro's continue action
  rather than by the screen. `hardware/paired` claims a paired device over default state.
- **Routes whose continue action commits the flow.** `backup/success` reports a backup that never
  ran, and its OK button persists `backupVerified = true`
  (`BackupNavSheetViewModel.onSuccessContinue`), which would mark a wallet backed up without the
  recovery phrase ever being shown. `backup/warning` is one tap upstream of the same write.
- **Routes carrying flow-internal arguments** (`send/pending`, `hardware/pair-code`) are out.
- **Sensitive routes** follow the deny rules above: `backup/show-mnemonic`, `backup/show-passphrase`
  and both confirm-mnemonic steps display or verify the recovery phrase, and the `pin`, `change-pin`
  and `disable-pin` sheets are auth surfaces. `force-transfer` force-closes channels.

An unregistered path is dropped with the same "Unhandled screen deeplink" warning as an unknown
screen, and it never falls back to the sheet's default route.

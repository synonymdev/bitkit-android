## Screen deep links

Every screen in the root navigation graph is reachable by URI, so a flow can be entered directly
instead of tapped through. Intended for QA, agent journeys and bug repros.

```
bitkit://screen/<screen-id>[/<required-arg>...][?<optional-arg>=<value>]
```

Only available while dev mode is on (`Settings > Advanced > Dev Settings`), which is the default on
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

Escape `&` as `\&` when passing more than one query parameter. `-W` reports whether the intent
resolved, so a mistyped id fails loudly instead of silently opening the wallet overview.

The app must already be past onboarding. A link that arrives earlier is held and replayed once the
wallet is loaded. A link that arrives while the PIN screen is up navigates behind it, so the PIN is
still required before anything is visible.

Back from a deep-linked screen returns to the wallet overview rather than leaving the app.

### Excluded screens

Deep links are an input any installed app or web page can send, so they navigate and prefill only —
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

Payment URIs (`bitcoin:`, `lightning:`, `lnurl*`) are unaffected and still go through the scanner
decode path.

Screens presented as bottom sheets (Send, Receive, Backup, Widgets) run their own nested graphs and
are not reachable yet.

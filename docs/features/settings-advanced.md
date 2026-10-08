# Settings: Advanced (address types, coin selection, node, Electrum, RGS, channels)

The Advanced tab of Settings: payment options, network servers, Lightning connections and the node lifecycle behind them; General, Support and developer tools are in `settings.md`.

## What it does
- Advanced tab rows (`SettingsScreen.kt`, container tag `advanced_settings_screen`): Dev settings (`DevSettings`, dev mode only), Address type (`AddressTypePreference`), Coin selection (`CoinSelectPreference`), Address viewer (`AddressViewer`), Watch-only accounts (`WatchOnlyAccounts`, Paykit UI on), Lightning connections (`Channels`), Lightning node (`LightningNodeInfo`), Electrum server (`ElectrumConfig`, value "Auto" while on the default server), RGS server (`RGSServer`).
- Address type: pick the primary receive type from Legacy, Nested Segwit, Native Segwit (default), Taproot (E2E ids `p2pkh`, `p2sh-p2wpkh`, `p2wpkh`, `p2tr`). In dev mode a second list (`MonitorToggle-<id>`) chooses which types count toward balance and channel funding; the selected type cannot be unmonitored. A change restarts monitoring through `LightningRepo.updateAddressType`/`setMonitoring`, shows `AddressTypeApplyingToast` then `AddressTypeSettingsUpdatedToast`, and rolls back on failure. Rules and funding matrix: `docs/address-types.md`.
- Coin selection: Manual (`manual_button`) or Autopilot (`autopilot_button`, default) with an algorithm: Largest first (`largest_first_button`), Consolidate, First in first out, Branch and Bound (default), Single random draw. Autopilot picks UTXOs in `LightningRepo.determineUtxosToSpend`; Manual sends the on-chain Send flow to `SendCoinSelectionScreen` (`coin_selection_screen`, rows `utxo_row_<key>`, `continue_button`), see `send.md`. Lightning and hardware-wallet sends skip it.
- Address viewer: per address type (buttons Legacy, Nested Segwit, Native Segwit, Taproot) lists Receiving or Change addresses (`Address-<index>`, path `Path`), with search, copy, block-explorer link, "generate 20 more" and "check balances".
- Watch-only accounts: accounts created by Pubky authorization with watch-only consent; rename, copy xpub, tracking toggle (`WatchOnlyAccount_<n>`, `WatchOnlyAccountsEmpty`). See `profile.md`.
- Electrum server: host, port, TCP/TLS protocol, scan, Reset to default, Connect. Connect first probes the server (wrong network, TLS mismatch, untrusted certificate, unreachable each give their own toast text), then stops and restarts the node on the new server and saves it. Shown state `Connected` or `Disconnected`.
- RGS server: URL field with debounced validation, Reset, Connect (trailing slash is trimmed); Connect restarts the node (`restartWithRgsServer`).
- Node info (`NodeInfoScreen.kt`): node id (`LDKNodeID`), lifecycle status, block/sync heights and times, balances, peers with disconnect, channels.
- Lightning connections: pending, open, failed and closed (hidden behind `ChannelsClosed`) channels, spending/receiving totals, pull to refresh, Export logs, Add connection (opens the transfer funding flow, `transfer.md`). A row (`Channel`) opens channel detail (`TotalSize`, `IsUsableYes`/`IsUsableNo`, `CloseConnection`); Close connection (`CloseConnectionButton`, cancel `CloseConnectionCancel`) closes cooperatively, records a `COOP_CLOSE` transfer and shows the "Transfer Initiated" toast.
- Node lifecycle (`NodeLifecycleState`: Stopped, Starting, Running, Stopping, ErrorStarting, Initializing): started on app foreground, stopped 5 s after background unless "keep active" plus the foreground service holds it; a config change (Electrum, RGS, address type) stops and restarts the node and recovers the previous config if the start fails or is cancelled.

## How a user reaches it
- `HeaderMenu`, `DrawerSettings`, `Tab-advanced`, then the row tag above. Back is `NavigationBack`.
- Routes in `ContentView.kt`: `advancedSettingsSubScreens` (`CoinSelectPreference`, `ElectrumConfig`, `RgsServer`, `AddressTypePreference`, `AddressViewer`, `WatchOnlyAccounts`, `NodeInfo`), `lightningConnections` (`LightningConnections`, `ChannelDetail(channelId)`, `CloseConnection(channelId)`). All are debug deeplinks `bitkit://screen/<kebab-route>` with dev mode on (see `settings.md`).
- Also from App status rows: `Status-electrum` opens Electrum, `Status-lightning_node` node info, `Status-lightning_connection` connections.

## Code
- `app/src/main/java/to/bitkit/ui/settings/advanced/`: `AddressTypePreferenceScreen.kt` + `ViewModel`, `CoinSelectPreferenceScreen.kt` + `ViewModel`, `AddressViewerScreen.kt` + `ViewModel`, `WatchOnlyAccountsScreen.kt` + `ViewModel`, `ElectrumConfigScreen.kt` + `ViewModel`, `RgsServerScreen.kt` + `ViewModel`.
- `ui/NodeInfoScreen.kt`, `ui/NodeInfoViewModel.kt`; `ui/settings/AdvancedSettingsViewModel.kt` (row values).
- `ui/settings/lightning/LightningConnectionsScreen.kt`, `ChannelDetailScreen.kt`, `CloseConnectionScreen.kt` with their view models.
- `ui/screens/wallets/send/SendCoinSelectionScreen.kt` (Manual mode, owned by `send.md`).
- `repositories/LightningRepo.kt`: `start`, `stop`, `stopDebounced`, `restartWithElectrumServer`, `restartWithRgsServer`, `updateAddressType`, `setMonitoring`, `restartWithPreviousConfig`; `services/LightningService.kt` (ldk-node wrapper); `repositories/WatchOnlyAccountRepo.kt`; `models/AddressType.kt`, `models/NodeLifecycleState.kt`.
- Lifecycle triggers: `ui/ContentView.kt` (ON_START/ON_STOP), `viewmodels/WalletViewModel.kt` (`start`, `stop`), `androidServices/LightningNodeService.kt` (foreground service), `fcm/WakeNodeWorker.kt` (push wake).
- Dev tools for this area (LDK Debug Restart, Channel Orders, Blocktank Regtest) are in `settings.md`.

## How to drive it
- Journeys:
  - `journeys/settings/electrum-server-error-toasts.xml`: rejected servers (wrong network, TLS on a plain port, unreachable) show distinct toasts and leave the connected server unchanged.
  - `journeys/coin-selection/manual-coin-selection.xml`: Manual mode lists only UTXOs, no Auto row, Continue follows TOTAL SELECTED vs TOTAL REQUIRED.
  - `journeys/coin-selection/manual-coin-selection-load.xml`: UTXOs load without a toast; a manual selection survives a received-payment update.
  - `journeys/node-lifecycle/cancelled-node-restart.xml`: LDK Debug Restart, then a cancelled RGS rebuild; checks log lines, recovery and a Lightning receive afterwards.
  - Coin-selection and Electrum journeys need an onboarded dev wallet on staging regtest; coin selection needs 2-3 confirmed UTXOs from `./lsp POST /regtest/chain/deposit` plus `.../mine` (`journeys/README.md`). Node lifecycle needs dev mode and a Lightning channel with inbound capacity.
- E2E `bitkit-e2e-tests/test/specs/settings.e2e.ts` (shard `settings`, `@settings`, local backend): `@settings_09` node info (`LightningNodeInfo`, `LDKNodeID`); `@settings_10` wrong Electrum server and scanner formats (runs only with `BACKEND=local`, uses `HostInput`, `PortInput`, `ConnectToHost`, `ElectrumErrorToast`, `ElectrumUpdatedToast`); `@settings_11` RGS URL change and reset (`RGSUrl`, `ConnectedUrl`, `RgsUpdatedToast`); `@settings_08` address type switch is `ciIt.skip` with the comment "not available in ldk-node".
- E2E `bitkit-e2e-tests/test/specs/multiaddress.e2e.ts`, tags `@multi_address_1`, `_3`, `_4` in the `multi_address_local` shard of `e2e.yml`; `@multi_address_2` also carries `@multi_address_staging`, run by `e2e-staging.yml` (`BACKEND=regtest`). Each test reinstalls, funds every address type through `switchAndFundEachAddressType` (`AddressTypePreference` + `p2pkh|p2sh-p2wpkh|p2wpkh|p2tr`). `_1` send max across types; `_2` transfer to spending and close to Taproot, check `AddressViewer`; `_3` send, change to primary type, RBF; `_4` open an LND channel with max, Legacy untouched. Needs `bitkit-docker` (bitcoind, Electrum, LND for `_4`).
- Other specs touching this area: `lightning.e2e.ts` `@lightning_1` opens `Channels`, `Channel`, `CloseConnection`, `CloseConnectionButton` (shard `lightning_security`); `transfer.e2e.ts` reads `Channels`/`Channel`; `onboarding.e2e.ts` `@onboarding_2` reads `Address-0`/`Address-1` in `AddressViewer`; `migration.e2e.ts` sets an address type on a legacy app (migration workflow).

## What proves it
- Address type: Receive shows an address with the matching prefix (regtest: `m`/`n`, `2`, `bcrt1q`, `bcrt1p`; `assertAddressMatchesType` in `test/helpers/actions.ts`), the row value shows the type name, `AddressTypeSettingsUpdatedToast` appears.
- Balances: Savings equals the sum funded across types; after send max, total, Savings and Spending are 0; Address viewer shows the sats under the expected type tab.
- Coin selection: row value "Auto"/"Manual"; manual screen shows `utxo_row_*` rows and `continue_button` enabled only when TOTAL SELECTED covers TOTAL REQUIRED.
- Electrum/RGS: `Connected` visible and `ConnectedUrl`/host fields show the new value; failed connects show the warning toast and keep the old server.
- Node: `LDKNodeID` visible; log lines "Node restarted successfully" or "Successfully started node with previous config"; `Channel` row appears or disappears after open or close.

## Not covered by tests
- Monitoring toggles (`MonitorToggle-*`), disabling a type with balance (error toasts), rollback on restart failure, the 60 s loading timeout; Taproot/Nested channel close destination.
- Coin selection algorithms other than the default, Consolidate behaviour, Manual mode with a fee change.
- Watch-only accounts screen (rename, tracking, pending account); address viewer search, "generate 20 more", "check balances", copy and explorer link.
- Electrum reconnect to a custom working server on the regtest staging backend, scan of an Electrum QR in a journey, RGS invalid URL and `RgsErrorToast`.
- Node info peers/disconnect; Lightning connections failed and pending orders, closed list, Export logs, pull to refresh; channel detail Support button; foreground service keep-alive and `WakeNodeWorker` wake.

## Gotchas
- Journey: tapping a protocol row rewrites an empty or default port (51002/50002/51001/50001) to that protocol's default (60002 for TLS on the regtest dev build); set the protocol before typing the port. Type hosts in short chunks; `adb shell input text` drops characters.
- `ResetToDefault` on Electrum with the default server leaves `ConnectToHost` disabled; the cancelled-restart journey uses the RGS screen for that reason.
- `settings_10` is a no-op unless `BACKEND=local`; its scan step needs the camera permission dialog accepted on first use.
- Cancelling a config change (Back mid-rebuild) is never persisted; recovery runs in the background and logs "Cancelled ldk-node config change, recovering in background".
- LDK Debug Restart runs on the repo scope, so leaving the screen does not cancel it.
- The node stops 5 s after the app is backgrounded (`BACKGROUND_STOP_DELAY`); a quick return cancels the stop (`cancelPendingStop`).
- Monitoring toggles show only in dev mode; Legacy is excluded from channel funding.
- Toasts are absent from `android layout`; assert them from a screenshot taken 2-6 s after the tap.
- Unclear: `docs/e2e-test-ids.md` lists `AdvancedSettings` but no such testTag exists in `app/src/main`; the tab tag is `Tab-advanced`.

# Settings (general, support, developer)

The Settings screen has three tabs (General, Security, Advanced); this file covers General, Support, App Status and developer tools, and `settings-advanced.md` covers the Advanced tab.

## What it does
- `SettingsScreen.kt` is a pager of `SettingsTab.General | Security | Advanced`. Security rows belong to other files: backup and reset see `backup.md`; PIN, biometrics, hide-balance and clipboard toggles see `security.md`.
- General tab, Interface: Language (`LanguageSettings`), Local currency (`CurrenciesSettings`), Unit (`UnitSettings`), Widgets (`WidgetsSettings`, see `widgets.md`), Tags (`TagsSettings`, row shown only when at least one last-used tag exists).
- General tab, Payments: Contact payments switch (`ContactPaymentsToggle`, only with Paykit UI on and a signed-in Pubky profile, see `contacts.md`, `profile.md`), Transaction speed (`TransactionSpeedSettings`), QuickPay (`QuickpaySettings`), Background payments (`BackgroundPaymentSettings`), Hardware wallets (`HardwareWalletsSettings`, see `hardware-wallet.md`).
- Currency: search field plus "most used" (USD, GBP, CAD, CNY, EUR) and the other rates sorted by code; one tap selects. Unit: primary display Bitcoin or the selected fiat, and denomination Modern or Classic (`BitcoinDisplayUnit`).
- Transaction speed: Fast, Normal, Slow set the default and close the screen; Custom opens a sat/vB number pad (max 3 digits, Continue disabled at 0). Default is `TransactionSpeed.Medium`. The default feeds the Send fee row, external channel opening and `LightningRepo`.
- Tags: tap a tag chip to delete it; deleting the last tag closes the screen. Tags are added from Receive and Send.
- QuickPay: toggle, amount slider (steps 1, 5, 10, 20, 50 USD, default 5) and daily-limit multiplier (steps 1, 3, 5, 10, 50, default 5). `QuickPayRepo.canApply` allows it only when the payment is at most the threshold and the day's spend stays under the cap. The first visit shows `QuickPayIntroScreen` (Continue sets `quickPayIntroSeen`). The payment itself is the Send flow, see `send.md`.
- Background payments: the first visit without notification permission shows the intro (Enable asks `POST_NOTIFICATIONS` on API 33+); the settings screen sends the user to system notification settings and has a "keep Bitkit active in background" switch, enabled only with permission. With it on and the foreground service running, leaving the app does not stop the node.
- Support: Report issue, Help center and Legal (open the browser), App status, Share, version row. Five taps on the version row (`DevOptions`) toggle dev mode.
- Report issue: email plus message; Send is enabled for a valid email and non-blank message; it posts to Chatwoot with zipped logs (`LogsRepo.postQuestion`) and opens a success or failure screen. The contact-support action on a Send error opens it with a prefilled message (`SendSheet.kt`, `AppViewModel.navigateToReportIssue`).
- App status: rows `Status-internet`, `Status-electrum`, `Status-lightning_node`, `Status-lightning_connection`, `Status-backup`, each READY, PENDING or ERROR (`HealthRepo`).
- Developer settings (`DevSettingsScreen.kt`, Advanced tab row `DevSettings`, shown only with dev mode; default on in debug builds): Fee Settings (live fee rates), Channel Orders, LDK, VSS, Probing Tool, Disable All Toasts, Swaps and Enable Savings Swap, Legacy Close Recovery, Paykit UI toggle, Trezor, Logs, Export/Wipe Logs, Blocktank Regtest (regtest builds only), cache/state reset buttons, fake background receive, LSP notification tests.

## How a user reaches it
- Home `HeaderMenu` then `DrawerSettings` opens `Routes.Settings`; `Tab-general`, `Tab-security`, `Tab-advanced` switch tabs (tag built from the enum name in `CustomTabRowWithSpacing.kt`). Each General row above opens its route (`ContentView.kt`: `settings`, `generalSettingsSubScreens`, `transactionSpeedSettings`, `localCurrencySettings`, `defaultUnitSettings`).
- Support: `HeaderMenu` then `DrawerSupport` (`Routes.Support`); then `AppStatus` row, or the drawer footer `DrawerAppStatus`.
- Dev settings: Advanced tab, `DevSettings`. Logs: Dev settings, Logs row (`Routes.Logs`, `Routes.LogDetail`).
- Debug builds with dev mode on accept `bitkit://screen/<kebab-route>` (`ScreenDeepLinks.kt`), e.g. `bitkit://screen/settings`, `bitkit://screen/log-detail/<file>`. Release builds do not (`ScreenDeepLinkRuntime` in `app/src/release`).

## Code
- `app/src/main/java/to/bitkit/ui/settings/SettingsScreen.kt`: tabs and all rows; state from `viewmodels/SettingsViewModel.kt`, `ui/settings/AdvancedSettingsViewModel.kt`, `viewmodels/LanguageViewModel.kt`; persisted in `data/SettingsStore.kt` (`SettingsData`).
- `ui/settings/LanguageSettingsScreen.kt`, `ui/utils/AppLocaleManager.kt` (system per-app locale).
- `ui/settings/general/LocalCurrencySettingsScreen.kt`, `DefaultUnitSettingsScreen.kt` with `viewmodels/CurrencyViewModel.kt`, `repositories/CurrencyRepo.kt`; `TagsSettingsScreen.kt`; `WidgetsSettingsScreen.kt` and `HardwareWalletsSettingsScreen.kt` (owned by `widgets.md`, `hardware-wallet.md`).
- `ui/settings/transactionSpeed/TransactionSpeedSettingsScreen.kt`, `CustomFeeSettingsScreen.kt`; `models/TransactionSpeed.kt`.
- `ui/settings/quickPay/QuickPaySettingsScreen.kt`, `QuickPayIntroScreen.kt`; `repositories/QuickPayRepo.kt`; `utils/timedsheets/sheets/QuickPayTimedSheet.kt`; `docs/timed-sheets.md`.
- `ui/settings/backgroundPayments/BackgroundPaymentsSettings.kt`, `BackgroundPaymentsIntroScreen.kt`.
- `ui/settings/support/SupportScreen.kt`, `ReportIssueScreen.kt`, `ReportIssueResultScreen.kt`, `ReportIssueViewModel.kt`; `ui/settings/appStatus/AppStatusScreen.kt`, `AppStatusViewModel.kt`; `repositories/HealthRepo.kt`.
- `ui/screens/settings/DevSettingsScreen.kt` (`DevSettingsViewModel`), `FeeSettingsScreen.kt`, `LdkDebugScreen.kt`, `VssDebugScreen.kt`, `ProbingToolScreen.kt`, `LegacyRnRecoveryScreen.kt`.
- `ui/settings/LogsScreen.kt` (`LogsViewModel`, `repositories/LogsRepo.kt`), `ChannelOrdersScreen.kt`, `SwapsScreen.kt` (`SwapsViewModel`, `services/BoltzService.kt`), `BlocktankRegtestScreen.kt`.
- LDK Debug: add peer by node URI, log/export network graph, Restart. VSS Debug: list and delete backup keys, list/share/delete LDK keys. Probing Tool: probes an invoice, LNURL, node id or URI (`ProbingToolViewModel`). Swaps: Boltz swap list and detail with "Claim now" for claimable reverse swaps.

## How to drive it
- Journeys: `journeys/settings/` holds only `electrum-server-error-toasts.xml` (Advanced tab, see `settings-advanced.md`). Related, outside this folder: `journeys/deeplinks/screen-deeplink.xml` (opens Settings by deeplink, toggles dev mode through the version row) and `journeys/send/own-invoice-guard.xml` (turns QuickPay on and off through `QuickpaySettings`).
- E2E `bitkit-e2e-tests/test/specs/settings.e2e.ts`, describe tag `@settings @ios_nightly`, shard `settings` (`@settings`) in `.github/workflows/e2e.yml`, local `bitkit-docker` backend:
  - `@settings_01` switch local currency (`CurrenciesSettings`, EUR then USD). `@settings_02` unit and denomination (`UnitSettings`, `DenominationClassic`). `@settings_03` transaction speed (`fast`, `custom`, `normal`).
  - `@settings_04` last-used tags removal (`TagsSettings`, `Tag-<tag>-delete`). `@settings_05` Support screen (`AboutLogo`). `@settings_12` reset suggestions (`WidgetsSettings`, `ResetSuggestions`).
  - `@settings_13` dev mode off/on through `DevOptions` x5 (`DevModeDisabledToast`, `DevModeEnabledToast`). `@settings_14` App status rows.
  - The same spec also holds `@settings_06` (swipe to hide balance, `security.md`) and `@settings_07` (backup phrase, `backup.md`), and `@settings_08` to `@settings_11` for the Advanced tab (`settings-advanced.md`).
- Other specs: `bitkit-e2e-tests/test/specs/send.e2e.ts` enables QuickPay (`QuickpaySettings`, `QuickpayToggle`).
- Preconditions: fresh wallet (`completeOnboarding`), dev mode on (default in the debug build), no funds needed. `openSettings(tab)` in `test/helpers/navigation.ts` is the shared entry.
- Dev tools by command (debug build): `adb shell content call --uri content://to.bitkit.dev.devtools --method createInvoice|probeInvoice|probeNode|probeReadiness|resetScores` (`app/src/debug/java/to/bitkit/dev/DevToolsProvider.kt`; used by `test/specs/mainnet/probe.e2e.ts`, which is in no CI shard).

## What proves it
- Row value text updates on the General tab: `UnitSettings` shows `Bitcoin` or the currency code, `TransactionSpeedSettings` shows Fast/Normal/Slow/Custom, `QuickpaySettings` and `BackgroundPaymentSettings` show On/Off.
- Home balance changes symbol or format (`MoneyFiatSymbol`, `MoneyText` under `TotalBalance-primary`) after currency, unit and denomination changes.
- `TagsSettings` row disappears and the tag no longer appears in the Receive tag list after deleting it.
- Dev mode toggles show `DevModeEnabledToast` or `DevModeDisabledToast` and add or remove `DevSettings`.
- App status: all five `Status-*` rows displayed; ERROR rows are red.

## Not covered by tests
- Language selection, Report issue (send, success, failure), Help center, Legal, Share, Logs list/detail/delete, Export/Wipe Logs: no journey or spec.
- Background payments intro, switch and keep-active; QuickPay intro, sliders, daily cap and the timed-sheet intro (only the toggle is driven, in `send.e2e.ts`).
- Custom fee value effect on a real Send; Fee Settings, Channel Orders and order detail, Swaps and Claim now, VSS Debug, Probing Tool in-app screen (the mainnet spec uses the content provider), Blocktank Regtest, Legacy Close Recovery, every cache/state reset row, Disable All Toasts, Savings Swap and Paykit UI toggles.
- Contact payments switch and the Hardware wallets row.

## Gotchas
- `UnitSettings` row tags are localized: the Bitcoin row uses the string `settings__general__unit_bitcoin` as its tag and the fiat row the currency code (`DefaultUnitSettingsScreen.kt`).
- Fast/Normal/Slow close the screen on tap; Custom writes the setting only on its Continue button. `settings_03` waits 1 s before reading the row.
- "Disable All Toasts" hides toasts only while dev mode is also on (`AppViewModel.kt`); specs that wait for toasts break if it is left on.
- Toasts do not appear in `android layout`; assert from screenshots (`journeys/README.md`).
- Dev settings rows have no testTags except toggles (`DisableAllToastsToggle`, `SavingsSwapToggle`, `PaykitUiToggle`, `SubscriptionClockOffset`); journeys tap them by text (e.g. "LDK").
- "Wipe App" and the reset rows act at once, no confirmation dialog (`DevSettingsScreen.kt`).
- `ciIt` in `test/helpers/suite.ts` skips tests already green on a CI retry (lock files in `/tmp/lock/`).
- `Env.isDebug` makes dev mode default on, so `DevSettings` and the address-type monitoring toggles exist in the E2E build without tapping the version row.

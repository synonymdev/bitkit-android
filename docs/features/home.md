# Home

The wallet overview: total balance, Savings and Spending cards, recent activity, the drawer menu, the widgets page with suggestion cards, and the timed sheets shown from it.

## What it does
- Home is `Routes.Home`, a `VerticalPager`: page 0 is the wallet (balances, banners, latest activity), page 1 is the widgets page. Page 1 exists only when `showWidgets` is on (default true, toggle in Widgets settings, see `widgets.md`). Swiping is disabled while widgets are being edited.
- Page 0 has pull-to-refresh (`PullToRefreshBox`): it re-syncs node and wallet, resyncs activities, refreshes exchange rates and refreshes enabled widgets. The widgets page has no pull-to-refresh.
- Total balance (`TotalBalance`) = onchain + lightning + both pending-transfer amounts + hardware wallets (`BalanceState.totalWithHardwareSats`). Tap switches primary display Bitcoin/fiat (toast tag `BalanceUnitSwitchedToast`). Swipe right hides it when `SwipeBalanceToHide` is on in Security settings (default on; `HideBalanceOnOpen` default off); `ShowBalance` (eye icon) appears and tapping it reveals. First hide shows toast `BalanceHiddenToast`.
- Pending transfers are not in the Savings or Spending numbers; they show as banners on Home (`ActivityBanner`, "transfer in progress", tap opens `Routes.SettingUp` for the Spending one) and as `IncomingTransfer` on the wallet screens. Rules: `docs/balance.md`.
- Savings card (`ActivitySavings`) and Spending card (`ActivitySpending`) open `SavingsWalletScreen` / `SpendingWalletScreen`: balance, incoming transfer, a transfer button, and that wallet's activity.
  - Savings: `TransferToSpending` only when onchain balance > 0 and not geo-blocked.
  - Spending: `TransferToSavings` only when lightning balance > 0 and a channel exists; `TransferFromSavings` only when spending is empty (no balance, no lightning activity) and onchain balance > 0.
  - Both show `EmptyStateView` when balance is 0 and no activity.
- Hardware wallet tiles (`ActivityHardware`) appear under the cards, see `hardware-wallet.md`.
- Latest activity list holds 4 rows (3 when window height < 800dp), minus one slot for banners and one for the widgets swipe hint. Row taps and "show all" are in `activity.md`.
- Header (`TopBar`): `ProfileButton` only when Paykit is enabled, `PaymentRequestsBell` only when Paykit is enabled and requests are pending; `WidgetsEdit` (page 1 only); app status icon; `HeaderMenu` opens the drawer. Bottom `TabBar` has `Send`, `Scan`, `Receive` (see `send.md`, `receive.md`).
- Drawer (`DrawerMenu`) items: `DrawerWallet`, `DrawerActivity`, `DrawerSubscriptions` (Paykit only), `DrawerContacts`, `DrawerProfile`, `DrawerWidgets`, `DrawerShop`, `DrawerSupport`, `DrawerSettings`, `DrawerAppStatus`. `DrawerWidgets` and `DrawerShop` go to their intro first until the intro was seen.
- Suggestion cards live inside the Suggestions widget on page 1, so they show only when widgets are on and that widget is present. At most 4, picked by `SuggestionsRepo` by balance: lightning > 0 (QuickPay, Notifications, Hardware, Shop, Profile, Support, Invite, Buy), else onchain > 0 (Back up, Secure, Lightning, Hardware, Support, Profile, Invite, Buy), else empty wallet (Buy, Lightning, Hardware, Support, Back up, Secure, Profile, Invite). Items are skipped when done (backup verified, PIN on, QuickPay on, notifications granted, profile authenticated, hardware paired, transfer to spending pending). Card tag `Suggestion-<enum name lowercase>` (for example `Suggestion-lightning`), close `SuggestionDismiss`.
- Card actions (`HomeScreen.kt`): Buy -> `Routes.BuyIntro` (opens exchanges list in the browser, `Env.EXCHANGES_URL`, and dismisses the card); Hardware -> `Sheet.Hardware`; Lightning -> transfer intro or funding; Back up -> `Sheet.Backup`; Secure -> `Sheet.Pin`; Support -> `Routes.Support`; Invite -> share text; Profile -> profile; Shop -> `Routes.ShopIntro` or `ShopDiscover`; QuickPay -> `Routes.QuickPayIntro` or settings; Notifications -> background payments intro or settings.
- Timed sheets (`utils/timedsheets`, `docs/timed-sheets.md`): 2 s after Home resumes, at most one sheet, priority App update, Backup, Background payments, QuickPay, High balance (> USD 500). Each is removed once shown and returns on a new manager instance.

## How a user reaches it
- App launch after unlock/onboarding lands on `Routes.Home` (start destination, `ContentView.kt`). Deeplink `bitkit://screen/home`, `.../savings`, `.../spending`, `.../buy-intro` (debug builds with dev mode on only; see `deeplinks.md`).
- Savings/Spending: tap `ActivitySavings` / `ActivitySpending` on Home.
- Widgets page: swipe up on Home, or `HeaderMenu` -> `DrawerWidgets`.
- Settings: `HeaderMenu` -> `DrawerSettings`.

## Code
- `app/src/main/java/to/bitkit/ui/screens/wallets/HomeScreen.kt`: pager, wallet page, widgets page, top bar, suggestion tap routing, delete-widget dialog.
- `ui/screens/wallets/HomeViewModel.kt`, `HomeUiState.kt`: combines settings, widgets, balances, transfers, hardware wallets into state; banners; empty state; `onPullToRefresh` (rates + widgets).
- `ui/screens/wallets/SavingsWalletScreen.kt`, `SpendingWalletScreen.kt`: the two wallet screens.
- `ui/screens/wallets/suggestion/BuyIntroScreen.kt`: Buy bitcoin intro, button opens the exchanges URL.
- `repositories/SuggestionsRepo.kt`, `models/Suggestion.kt`, `ui/components/SuggestionCard.kt`: card selection, enum, card UI. Dismissed cards are stored in `SettingsData.dismissedSuggestions`.
- `ui/components/BalanceHeaderView.kt`, `WalletBalanceView.kt`, `Money.kt`: balance rows (tags `MoneyText`, `MoneyFiatSymbol`, `TotalBalance-primary`, `TotalBalance-secondary`).
- `usecases/DeriveBalanceStateUseCase.kt`, `models/BalanceState.kt`: balance rules from `docs/balance.md`.
- `repositories/CurrencyRepo.kt`, `viewmodels/CurrencyViewModel.kt`: rates (poll every 2 min, `Env.fxRateRefreshInterval`), unit switch. `viewmodels/WalletViewModel.kt` `onPullToRefresh`: node and wallet sync.
- `ui/components/DrawerMenu.kt`, `ui/components/TabBar.kt`: drawer and bottom bar.
- `utils/timedsheets/` (`TimedSheetManager.kt`, `sheets/*TimedSheet.kt`), `viewmodels/AppViewModel.kt` (`onHomeResumed`, `onLeftHome`), sheets in `ui/sheets/` (`UpdateSheet.kt`, `BackupSheet.kt`, `BackgroundPaymentsIntroSheet.kt`, `QuickPayIntroSheet.kt`, `HighBalanceWarningSheet.kt`).

## How to drive it
- `journeys/home/pull-to-refresh-rates.xml`: pull on Home, check the log for "Currency rates refreshed successfully" and "Updated PRICE widget successfully", then two quick pulls without a rates toast. Needs the Price widget on and network.
- Used as entry steps: `journeys/transfer/transfer-to-savings-returns-home.xml` (Spending card, `TransferToSavings`, back on Home), `journeys/amount-limits/transfer-spending-preset-delete.xml` and `journeys/transfers/closed-channel-transfer-settles.xml` (Savings card, `TransferToSpending`), `journeys/hardware-wallet/suggestion-intro-sheet.xml` (Hardware suggestion card).
- `bitkit-e2e-tests/test/specs/settings.e2e.ts` (`@settings`, CI shard `settings`): `@settings_01` tap `TotalBalance` to switch unit and change currency, `@settings_02` Bitcoin unit and `TotalBalance-primary`, `@settings_06` swipe to hide (`dragOnElement('TotalBalance', ...)`, `ShowBalance`), `@settings_12` dismiss `Suggestion-lightning` and restore it (`ResetSuggestions`).
- Also seen in other specs: `TotalBalance`/`ActivitySpending` assertions in `lightning.e2e.ts`, `onchain.e2e.ts`, `boost.e2e.ts`, `security.e2e.ts`, `onboarding.e2e.ts`; `ActivitySavings` in `backup.e2e.ts`; `Suggestion-hardware` via `openHomeWidgets()` in `bitkit-e2e-tests/test/helpers/hardware-wallet.ts` (`@hardware_wallet`, staging shard).
- Preconditions: funded wallet for non-empty cards (`./lsp` deposit and mine, `journeys/README.md`), a channel for the Spending transfer button, widgets enabled for suggestion cards.

## What proves it
- Home: `HomeScrollView` and `TotalBalance` visible; `TotalBalance-primary` -> `MoneyText` shows the amount; `ActivitySavings`/`ActivitySpending` show the split.
- Unit switch: `MoneyFiatSymbol` text changes (for example `$`, `₿`).
- Hidden balance: `ShowBalance` present.
- Refresh: log line "Currency rates refreshed successfully" (not in `android layout`).
- Suggestion removed: `Suggestion-<name>` gone after `SuggestionDismiss`; back after reset.
- Savings/Spending: `TransferToSpending` / `TransferToSavings` / `TransferFromSavings` present only under the conditions above.

## Not covered by tests
- Spending/Savings wallet screens' empty state, `IncomingTransfer` and geo-blocked case (hidden `TransferToSpending`).
- Banner tap to `SettingUp`, `PaymentRequestsBell`, `ProfileButton`.
- Suggestion cards other than Lightning (dismiss/reset) and Hardware (open): no spec or journey taps Buy, Back up, Secure, Support, Invite, Profile, Shop, QuickPay, Notifications cards, and the balance-dependent card order.
- `BuyIntro` screen (no test, no journey).
- Drawer items in general (only `DrawerSettings` and `DrawerWidgets` via helpers; `DrawerSupport` through `openSupport`).
- Timed sheets: no journey or e2e; unit tests only (`app/src/test/java/to/bitkit/utils/timedsheets`).
- Classic vs modern unit rendering beyond `@settings_02`; pull-to-refresh of wallet sync/activity.

## Gotchas
- Suggestion cards are not on page 0: reach them with `swipeFullScreen('up')` or `openHomeWidgets()`.
- iOS does not refresh rates on pull (`journeys/home/pull-to-refresh-rates.xml`). Pulling right after the last refresh skips work: the repo ignores a refresh while one runs (`CurrencyRepo.isRefreshing`).
- Stale rates raise the toast "Rates currently unavailable" (`CurrencyRepo.observeStaleData`); toasts are not in `android layout`.
- `HeaderMenu` and `ProfileButton` can be missing from one `android layout` dump and present in the next (`journeys/README.md`).
- Dismissing the last remaining card resets the dismissed list when the Suggestions widget preview opens (`SuggestionsRepo.resetDismissedSuggestionsIfEmpty`).
- `HomeViewModel.dismissEmptyState` has no caller and `HomeScreen` passes no `onClose` to `EmptyStateView`, so `WalletOnboardingClose` is not shown on Home (`migration.e2e.ts` taps it, likely in the RN app).
- Timed sheets queue is checked once per Home resume; a sheet shown is not offered again until a new manager instance (`docs/timed-sheets.md`).
Unclear: whether `WalletOnboardingClose` (`migration.e2e.ts:655,715`) targets only the RN app; no Android code sets `onClose` on Home's `EmptyStateView`.

# Send

The Send sheet takes a recipient (scan, paste, manual, contact), an amount, fee options and a swipe confirmation, then pays on-chain or over Lightning; QuickPay can skip the review; LNURL flows are in lnurl.md.

## What it does
- Recipient input is decoded by `coreService.decode` (bitkit-core) in `AppViewModel.handleScan`. Result types: on-chain/BIP21 (`Scanner.OnChain`, may carry a `lightning=` invoice = unified), Lightning, LNURL pay/withdraw/auth/channel (lnurl.md), node id (opens the external-node flow), gift code (opens `Sheet.Gift`), Pubky/Paykit keys (contacts, payment-requests.md), SamRock/BTCPay setup URLs (`Sheet.BTCPayConnection`).
- Validation toasts (testTag): `InvalidAddressToast` (bad data, wrong network), `InsufficientSavingsToast`, `InsufficientSpendingToast`, `ExpiredLightningToast`, `SelfPaymentToast`, `DuplicatedBip21Toast`, `SendAmountExceededToast`. Toasts do not appear in `android layout`; use a screenshot.
- Unified QR: the `lightning=` invoice is used only if it is not expired, not the wallet's own and the node can send it (`extractViableLightningInvoice`); otherwise the send is on-chain only. `AssetButton-<id>` shows the funding source: `savings` or `spending`, `trezor` for a hardware wallet, and `switch` when both Savings and Spending can pay (tap flips the source).
- Own-invoice guard: a Lightning invoice created by this wallet is rejected with `SelfPaymentToast` before QuickPay or Confirm (`AppViewModel.isOwnInvoice`).
- Amount screen: `send_amount_screen`, `SendNumberField`, `AvailableAmount` (also `available_balance`; tapping fills the max), `SendAmountMax` (shown for LNURL pay only), `SendNumberPadUnit`, `SendAmountNumberPad`, `ContinueAmount`. A fixed amount in the invoice skips this screen. `sync_node_view` shows while the node starts.
- Number pad (`ui/components/NumberPad.kt`, state in `viewmodels/AmountInputViewModel.kt`): keys `N0`-`N9`, `N000`, `NDecimal`, `NRemove`; unit toggle (`SendNumberPadUnit`) between the bitcoin unit and fiat; modern (sats) and classic (BTC, 8 decimals) denominations (Settings, `UnitSettings`).
- Amount limits: `setMaxAmount` caps input at the available balance (or LSP/LNURL max); a digit that would exceed it is rejected, a warning toast shows for about 1.5s, delete stays allowed, Continue stays enabled. With balance 0 the cap falls back to `MAX_AMOUNT` (999,999,999 sats). On-chain amounts must be above the dust limit (`Defaults.dustLimit`) and at most `maxSendOnchainSats`.
- Coin selection step: when Settings > Advanced > Coin selection is Manual (`coinSelectAuto` false) and the method is on-chain, Continue opens `SendCoinSelectionScreen` (`coin_selection_screen`, rows `utxo_row_<key>`, `continue_button`, `CoinSelectionLoading`, `CoinSelectionLoadError`, `CoinSelectionRetry`); Continue enables when TOTAL SELECTED covers TOTAL REQUIRED. Auto skips it.
- Confirm (`SendConfirmScreen.kt`): `SendConfirmContent`, `ReviewAmount` (tap to edit when the invoice has no amount), `SendConfirmToggleDetails`, `ReviewUri` (tap to edit the recipient), `SendConfirmAssetButton`, tag via `TagsAddSend` then `TagInputSend` / `SendTagsSubmit`, swipe slider `GRAB` (`ui/components/SwipeToConfirm.kt`).
- Fee and speed: the fee cell opens `SendFeeRateScreen` (`speed_screen`, `fee_<RATE>_button` with RATE from `models/FeeRate.kt`: INSTANT = switch to Lightning, FAST, NORMAL, SLOW, MINIMUM, CUSTOM; `continue_btn`); a rate whose fee exceeds the lower of half the on-chain balance and balance minus amount is disabled. Custom opens `SendFeeCustomScreen` (`fee_screen`): up to 3 digits, max 999 sat/vB, bounded by the balance (`SendFeeViewModel.kt`). Default speed comes from Settings > Transaction speed (settings.md).
- Sanity dialogs on swipe: `SendDialog1` (value over 100 USD, only if enabled in Settings > Security `SendAmountWarning`), `SendDialog2` (over half the balance), `SendDialog3` (fee over half the value), `SendDialog4` (fee over 10 USD).
- Auth: when PIN-for-payments is on, swipe leads to biometrics or `SendPinCheckScreen` (`AttemptsRemaining`, `LastAttempt`) before paying (security.md).
- Pay: on-chain `sendOnchain` (broadcast, then `SendSuccess`); Lightning `sendLightning` waits up to 10s (`LightningRepo.SEND_LN_TIMEOUT`) for the payment event. Result screens: `SendSuccess` (`NewTransactionSheetView`, buttons `Details`, `Close`), `SendPendingScreen` (no testTag; title "Payment Pending", when the timeout passes without an event; resolution comes from `PendingPaymentRepo`), `SendErrorScreen` (`SendFailure`, `Retry`, `Support`).
- QuickPay: when Settings > QuickPay is on and the invoice (or fixed LNURL-pay) is at or below the USD threshold and under the daily cap, the review is skipped and `SendQuickPayScreen` pays; failures fall back to Confirm or show the error screen (`repositories/QuickPayRepo.kt`, `canApply`). Not used for contact payments or amounts above the threshold.
- QR scanner: camera view in `SendRecipientScreen` and `QrScanningScreen` (full sheet via tab bar `Scan` = `Sheet.QrScanner`, `app/src/main/java/to/bitkit/ui/sheets/QrScanningSheet.kt`): flashlight, gallery image scan, paste from clipboard. A scan from the tab bar (not inside Send) opens `Sheet.Send` at the route needed; Pubky auth links are only accepted from the main scanner.

## How a user reaches it
- Tab bar `Send` (`app/src/main/java/to/bitkit/ui/components/TabBar.kt`) opens `Sheet.Send` on `SendRoute.Recipient`; if the OS camera prompt shows, allow or deny it. Options: camera preview, `RecipientContact` (contact list, contacts.md), `RecipientInvoice` (paste from clipboard), `RecipientManual` -> `SendAddressScreen` (`RecipientInput`, `AddressContinue`).
- Tab bar `Scan` (`Scan`), OS intents (`bitcoin:`, `lightning:` URIs, see deeplinks.md) and the clipboard auto-read (`ui/utils/AutoReadClipboardHandler.kt`, setting `enableAutoReadClipboard`) also start a scan and jump to Amount or Confirm.
- Dev deeplinks `bitkit://screen/send` plus `/address`, `/contact-select`, `/amount`, `/qr-scanner`, `/coin-selection`, `/add-tag`, `/coming-soon`, `/support`; `Confirm`, `FeeRate`, `FeeCustom`, `Pending`, `Error` are internal only.

## Code
- `app/src/main/java/to/bitkit/ui/sheets/SendSheet.kt`: `SendRoute`, `NavHost`, `SendEffect` navigation, sync overlay, `SendSuccess`.
- `app/src/main/java/to/bitkit/ui/screens/wallets/send/SendRecipientScreen.kt` (options and camera), `SendAddressScreen.kt` (manual input), `SendContactSelectScreen.kt` with `SendContactSelectViewModel.kt` and `SendContactTopBar.kt` (contacts).
- `app/src/main/java/to/bitkit/ui/screens/wallets/send/SendAmountScreen.kt` (amount), `SendCoinSelectionScreen.kt` with `SendCoinSelectionViewModel.kt` (manual UTXOs), `SendConfirmScreen.kt` (review, swipe, details, tags, comment).
- `app/src/main/java/to/bitkit/ui/screens/wallets/send/SendFeeRateScreen.kt`, `SendFeeCustomScreen.kt`, `SendFeeViewModel.kt` (fee choice and custom fee); `SendPinCheckScreen.kt` (PIN before pay); `SendQuickPayScreen.kt`; `SendPendingScreen.kt` with `SendPendingViewModel.kt`; `SendErrorScreen.kt`; `AddTagScreen.kt` (also used by receive).
- `app/src/main/java/to/bitkit/ui/screens/wallets/send/HwSendSignScreen.kt`, `HwSendViewModel.kt`: hardware signing (hardware-wallet.md).
- `app/src/main/java/to/bitkit/ui/screens/scanner/QrScanningScreen.kt`, `QrCodeAnalyzer.kt`, `CameraPermissionView.kt`; `app/src/main/java/to/bitkit/ui/sheets/QrScanningSheet.kt`: scanner.
- `app/src/main/java/to/bitkit/viewmodels/AppViewModel.kt`: `SendEvent`, `sendUiState`, `launchScan`, `handleScan`, `onScanOnchain`, `onScanLightning`, `validateAmount`, `handleSanityChecks`, `onSwipeToPay`, `sendOnchain`, `sendLightning`, `handleQuickPayIfApplicable`.
- `app/src/main/java/to/bitkit/viewmodels/QuickPayViewModel.kt`, `app/src/main/java/to/bitkit/repositories/QuickPayRepo.kt`, `app/src/main/java/to/bitkit/repositories/QuickPaySpendStore.kt`: QuickPay session, threshold and daily cap. `app/src/main/java/to/bitkit/repositories/PendingPaymentRepo.kt`: pending resolution.
- `app/src/main/java/to/bitkit/repositories/LightningRepo.kt` (`payInvoice`, `canSend`), `app/src/main/java/to/bitkit/repositories/WalletRepo.kt` (balances, UTXOs).
- `app/src/main/java/to/bitkit/viewmodels/AmountInputViewModel.kt`, `app/src/main/java/to/bitkit/ui/components/NumberPad.kt`, `app/src/main/java/to/bitkit/ui/components/SwipeToConfirm.kt`: number pad, caps, slider.
- `app/src/main/java/to/bitkit/ui/settings/advanced/CoinSelectPreferenceScreen.kt`, `app/src/main/java/to/bitkit/ui/settings/transactionSpeed/TransactionSpeedSettingsScreen.kt`, `CustomFeeSettingsScreen.kt` (same folder): settings that change the send flow (settings.md).

## How to drive it
- Journeys: `journeys/send/own-invoice-guard.xml` (own invoice toast, unified fallback to on-chain, QuickPay not opened; needs a channel and savings). `journeys/coin-selection/manual-coin-selection.xml` (no Auto row, TOTAL SELECTED vs REQUIRED; 3+ UTXOs via separate `./lsp` deposits), `journeys/coin-selection/manual-coin-selection-load.xml` (loads once, keeps selection).
- `journeys/amount-limits/README.md` has the setup. `journeys/amount-limits/send-amount-over-balance.xml` (send cap; fund ~100,000 sats first). Transfer parts (see transfer.md): `journeys/amount-limits/transfer-spending-over-max.xml`, `journeys/amount-limits/transfer-spending-advanced-over-max.xml`, `journeys/amount-limits/external-amount-over-max.xml`, `journeys/amount-limits/transfer-spending-preset-delete.xml` (one digit per delete after a preset).
- Deep link a payment instead of typing: `adb shell am start -a android.intent.action.VIEW -d "lightning:<invoice>" to.bitkit.dev`; the log shows `Received deeplink` and `Starting scan from 'deeplink'`.
- E2E (local `bitkit-docker` + LND, `BACKEND=local`; shard `send`): `bitkit-e2e-tests/test/specs/send.e2e.ts`. `@send_1` validation toasts, balance 0 and funded cases, unified amounts; `@send_2` on-chain, Lightning, unified (fixed, over balance, expired, no amount), max amounts, edit invoice on Confirm, QuickPay on/off; `@send_3` msat-precision invoices.
- `bitkit-e2e-tests/test/specs/lightning.e2e.ts` (`@lightning_1`, shard `lightning_security`): pays zero-amount and fixed invoices from LND, edits amount on Confirm, send tag, activity filters, restore. `bitkit-e2e-tests/test/specs/onchain.e2e.ts` (`@onchain_1/_2/_3`): send to an external address, send max, over-50% and over-100-USD dialogs, dust to fee (shard `onchain_boost_receive_widgets`).
- `bitkit-e2e-tests/test/specs/numberpad.e2e.ts` (`@numberpad_2`, `_4`, shard `onboarding_backup_numberpad`): Send number pad in both denominations (funds 500M sats first). `send_1`, `send_2` and `lnurl_1` (`bitkit-e2e-tests/test/specs/lnurl.e2e.ts`) assert `SendAmountExceededToast`.
- `bitkit-e2e-tests/test/specs/mainnet/ln.e2e.ts` (`@strike_mainnet` `@strike_1`, `@wos_mainnet` `@wos_1`): smokes paying a Lightning Address on mainnet from a restored wallet (`STRIKE_*`, `WOS_*` env vars). `bitkit-e2e-tests/test/specs/mainnet/channel-order.e2e.ts` (`@channel_order_mainnet`): smokes Transfer to Spending pricing and liquidity (transfer.md). `bitkit-e2e-tests/test/specs/mainnet/probe.e2e.ts` (`@probe_mainnet`): smokes Lightning routing through probes sent via the debug content provider `<appId>.devtools` (`app/src/debug/java/to/bitkit/dev/DevToolsProvider.kt`), not through Send UI. None run in a CI shard of the app repo.

## What proves it
- `SendSuccess` visible, then Home shows the new `ActivityShort-0` with `-` and "Sent", and the balance drops by amount plus fee.
- Over-balance or over-max input leaves `SendNumberField` at the capped value and shows `SendAmountExceededToast`.
- Own invoice: `SelfPaymentToast`, no Send sheet. Manual coin selection: `utxo_row_*` rows only, `continue_button` enabled when covered.

## Not covered by tests
- Fee screens (`speed_screen`, `fee_screen`, custom fee limits), `SendCoinSelectionScreen` Retry/error state (unit test only), `SendPinCheckScreen` and biometrics on pay, `SendDialog3`/`SendDialog4`.
- `SendPendingScreen` and `SendErrorScreen` paths (Retry, Support, routing-cache reset), pay-failure and timeout cases.
- Recipient: `RecipientContact`, `RecipientInvoice` paste, gallery and flashlight in the scanner, camera decode (E2E injects text through `ScanPrompt`).
- Hardware send (`HwSendSignScreen`): see hardware-wallet.md. Contact and subscription payments: payment-requests.md.
- QuickPay daily cap and threshold edges; only a 1000 sat invoice with QuickPay on is tested.

## Gotchas
- `ScanPrompt` / `QRDialog` / `QRInput` ("Enter QRCode String") exist only in builds with `E2E=true` (`Env.isE2eTest`).
- Two testTags exist on the available balance: `AvailableAmount` and `available_balance`; the E2E tests read `AvailableAmount`.
- Do not tap the amount on the Send amount screen in journeys: it swaps the primary unit.
- Android shows the raw `maxSendLightningSats` as Lightning max; iOS subtracts an estimated routing fee (comment in `send.e2e.ts`).
- With balance 0, amount caps are not applied; fund first (`journeys/amount-limits/README.md`).

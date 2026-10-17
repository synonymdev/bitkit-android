# Transfer: savings to spending, external channel, LNURL channel

Moves on-chain savings into Lightning spending by buying a Blocktank (LSP) channel, opening a channel to an external node, or answering an LNURL-channel request. Spending to savings, force close and the settle rules are in `transfer-savings.md`. Hardware-wallet funding is in `hardware-wallet.md`. CJIT receive is in `receive.md`.

## What it does
- Spending amount screen: user picks the amount that will become spending balance; max is the on-chain funding budget minus fees, capped by the LSP max client balance (`TransferViewModel.updateLimits`).
- Confirm screen: shows network fee, LSP fee, amount and total; swipe pays the Blocktank order from on-chain funds. Advanced lets the user choose the receiving (LSP side) capacity.
- Order flow: `blocktankRepo.createOrder`, `lightningRepo.sendOnChain` (speed Fast, `isTransfer = true`), then `TransferRepo.createTransfer(TO_SPENDING, lspOrderId)`; `watchOrder` polls the order every 2.5 s and drives `lightningSetupStep` 0 to 3 (steps shown on `SettingUpScreen`).
- If the order total grows between confirm screen and swipe, nothing is paid: the screen takes the new amounts, an info toast "Fees changed" shows, and the next swipe pays (`holdForFeesChange`, `holdForOrderFeeIncrease`).
- External node: connect to `nodeId@host:port`, pick an amount up to `channelFundableBalance`, swipe; `lightningRepo.openChannel`, wait for `ChannelPending`, then `createTransfer(MANUAL_SETUP)`.
- LNURL channel: a scanned `lnurl-channel` opens `LnurlChannelScreen`; Connect calls `lightningRepo.requestLnurlChannel`, then the external success screen.
- Amount screens cap the number pad (`AmountInputViewModel.setMaxAmount`) and toast "Spending Balance Maximum" on the first rejected key.
- Geo-blocked: `FundTransfer` and `FundReceive` are disabled; `TransferToSpending` is hidden.

## How a user reaches it
- Home card `ActivitySavings` > Savings screen > `TransferToSpending` (shown when on-chain balance > 0 and not geo-blocked). First time `SpendingIntro` (`SpendingIntro-button`, `hasSeenSpendingIntro`), then `SpendingAmount`.
- Home card `ActivitySpending` > Spending screen > `TransferFromSavings`, only while spending balance and lightning activity are both empty and on-chain > 0.
- Home suggestion `Suggestion-lightning`: `TransferIntro` (`TransferIntro-button`, `hasSeenTransferIntro`) then Funding.
- Settings > Advanced > `Channels` (`LightningConnectionsScreen`): `NavigationAction` (plus icon) or the empty-state button opens Funding.
- Funding (`FundingScreen`): `FundTransfer` (enabled when `channelFundableBalance >= Defaults.recommendedBaseFee`; at 0 an overlay with the same tag opens a "no funds" alert), `FundReceive` (home, then Receive sheet Amount), `FundManual`.
- `FundingAdvancedScreen` (scan LNURL button without tag, `FundManual`) has no in-app caller of `Routes.FundingAdvanced` (grep); the route is reachable only through the dev-gated screen deeplink `bitkit://screen/funding-advanced` (kebab-case route name, see `deeplinks.md`).
- Spending ids: `SpendingAmount` (only when the node runs, else a sync view), `SpendingAmountNumberField`, `SpendingAmountAvailable` (label), `SpendingAmountUnit` (value), `SpendingNumberPadUnit`, `SpendingAmountQuarter`, `SpendingAmountMax`, `SpendingAmountContinue`.
- Confirm ids: `SpendingConfirmMore` (opens `LiquidityScreen`, `LiquidityContinue`), `SpendingConfirmAdvanced` or `SpendingConfirmDefault`, `SpendingConfirmChannel` (advanced only), `SpendingConfirmNotificationSwitch`, swipe handle `GRAB`. Advanced ids: `SpendingAdvanced`, `SpendingAdvancedNumberField`, `SpendingAdvancedMin`/`Default`/`Max`, `SpendingAdvancedContinue`.
- A pending transfer shows `ActivityBanner` on home; tapping the spending banner opens `Routes.SettingUp` (`HomeScreen` `onNavigateToSettingUp`).
- Progress ids: `LightningSettingUp` while step < 3, then `TransferSuccess`; button `TransferSuccess-button` (`InfoScreenContent` appends `-button`).
- External ids: `NodeIdInput`, `HostInput`, `PortInput`, `ExternalContinue`, `ExternalAmount`, `ExternalAmountNumberField`, `ExternalNumberPadUnit`, `ExternalAmountQuarter`, `ExternalAmountMax`, `ExternalAmountContinue`, `GRAB` on confirm (no screen tag), `ExternalSuccess`, `ExternalSuccess-button`. LNURL: `ConnectButton`.
- A scanned node URI (`AppViewModel.onScanNodeId`) opens `ExternalConnection(scannedNodeUri)`; the Receive sheet `navigateToExternalConnection` callback also opens it.

## Code
- Routes in `app/src/main/java/to/bitkit/ui/ContentView.kt` (nav graph `Routes.TransferRoot`, around lines 926-1160): `TransferIntro`, `Funding`, `FundingAdvanced`, `SpendingIntro`, `SpendingAmount`, `SpendingConfirm`, `SpendingAdvanced`, `TransferLiquidity`, `SettingUp`, `ExternalNav` (`ExternalConnection`, `ExternalAmount`, `ExternalConfirm`, `ExternalSuccess`, `LnurlChannel`). No sheet; Receive sheet is `Sheet.Receive`.
- Screens in `app/src/main/java/to/bitkit/ui/screens/transfer/`: `TransferIntroScreen`, `FundingScreen`, `FundingAdvancedScreen`, `SpendingIntroScreen`, `SpendingAmountScreen`, `SpendingConfirmScreen`, `SpendingAdvancedScreen`, `LiquidityScreen`, `SettingUpScreen`; `external/ExternalConnectionScreen`, `ExternalAmountScreen`, `ExternalConfirmScreen`, `ExternalSuccessScreen`, `LnurlChannelScreen`.
- State: `viewmodels/TransferViewModel.kt` (quote, limits, order, pay, watch), `ui/screens/transfer/external/ExternalNodeViewModel.kt`, `LnurlChannelViewModel.kt`; scan routing in `viewmodels/AppViewModel.kt` (`onScanLnurlChannel`, `onScanNodeId`).
- Data: `repositories/TransferRepo.kt`, `repositories/BlocktankRepo.kt`, `repositories/LightningRepo.kt`, `data/entities/TransferEntity.kt`, `data/dao/TransferDao.kt`, `models/TransferType.kt`; domain doc `docs/transfer.md`.
- LSP limits: `blocktankRepo.calculateLiquidityOptions`, `estimateOrderFee`; funding budget = spendable minus sweep fee at Fast rate.

## How to drive it
- Journeys: `journeys/transfer/spending-confirm-amount-change.xml` (lower rebuilt fee pays at once); `journeys/transfers/closed-channel-transfer-settles.xml` (banner survives restart, LSP closes channel; see `transfer-savings.md`); `journeys/amount-limits/transfer-spending-over-max.xml`, `transfer-spending-advanced-over-max.xml`, `external-amount-over-max.xml`, `transfer-spending-preset-delete.xml` (25%/MAX then one digit per delete, no funds move); `journeys/notification-permission/` toggle journeys use `SpendingConfirmNotificationSwitch` (see `notifications.md`).
- E2E `bitkit-e2e-tests/test/specs/transfer.e2e.ts` (describe `@transfer`): `@transfer_1` default plus custom capacity, `@transfer_max` settled maximum, both also `@transfer_staging` (staging shard `transfer`, `BACKEND=regtest`, `e2e-staging.yml`); `@transfer_2` external LND channel, pay, close (local shard `lnurl_transfer`, `e2e.yml`, `BACKEND=local` docker LND). `bitkit-e2e-tests/test/specs/lnurl.e2e.ts` `@lnurl_1` covers `ConnectButton` (shard `lnurl_transfer`; see `lnurl.md`). `bitkit-e2e-tests/test/specs/mainnet/channel-order.e2e.ts` `@channel_order_mainnet` / `@channel_order_1` (Android only, no app CI shard; env `CHANNEL_ORDER_SEED` or `CJIT_SEED`, `CHANNEL_ORDER_AMOUNT_SATS` default 20000, `CHANNEL_ORDER_MIN_FEE_SATS`, `CHANNEL_ORDER_MIN_RECEIVING_SATS`) restores a mainnet wallet, enters the amount, reads the four `MoneyText` values on Confirm (network fee, service fee, amount, total) and the two on `SpendingConfirmMore` (spending, receiving liquidity); it never swipes.
- Preconditions: journeys need staging regtest funds (`./lsp POST /regtest/chain/deposit`, `/mine`), wait ~20 s, node connected to the LSP. `spending-confirm-amount-change.xml` needs exactly two confirmed 150 000 sat UTXOs and no channel. External journey needs a peer (`./lsp GET /info`); `@transfer_2` uses `setupLND` and `connectToLND`.
- Unit: `app/src/test/java/to/bitkit/viewmodels/TransferViewModelTest.kt` (103 tests: limits, order reuse, fee-change hold, HW), androidTest `app/src/androidTest/java/to/bitkit/ui/screens/transfer/FundingScreenTest.kt` (the `ui-tests.yml` workflow runs androidTest on push to master or by dispatch; which classes it selects was not checked).

## What proves it
- `LightningSettingUp` after the swipe, then `TransferSuccess`; toast `SpendingBalanceReadyToast` when the channel is ready; spending balance equals the amount (e2e mines blocks until it does).
- Home `ActivityShort-0` shows "Transfer" and "-"; savings balance dropped; `TotalSize` and `IsUsableYes` on the channel detail (`Channels` > `Channel`).
- External: `ExternalSuccess`, `ActivityShort-0` "Transfer"; LND `waitForActiveChannel`.
- Over-max: input never exceeds the stated maximum, `NRemove` shortens it; the toast has no tag (journeys README: screenshot only).

## Not covered by tests
- Geo-blocked states of `FundTransfer`/`FundReceive`/`TransferToSpending`: no journey or e2e; `FundingScreenTest` covers the screen alone (not read in detail).
- "No funds" alert, `FundingAdvanced` screen, manual node-URI scan, `LnurlChannel` failure toasts: none found.
- Restart while an order is pending: only the `transfers/` journey, which needs a hand-timed tap before auto-mine.
- Pay-order failure branches (`sendOnChain` fails, order expired, 5 consecutive poll errors): unit tests only.
- Higher-total "Fees changed" path: unit tests plus a manual test (journey says so).
- Notification-permission toggle outcome beyond the permission dialog: see `notifications.md`.

## Gotchas
- `SettingUpScreen` auto-mines one regtest block 5 s after it appears and cancels on leave (`Env.network == REGTEST`).
- Max starts at 0 behind `SyncNodeView`/spinner; fund first or caps fall back to the global maximum and journeys pass for the wrong reason. The cap can be below the shown Available.
- The over-max input is capped, so Continue stays enabled; do not assert it disabled.
- `SpendingAmountAvailable` is the label, `SpendingAmountUnit` holds the value (both journeys and e2e read the value from `SpendingAmountUnit`/number field).
- `docs/transfer.md` says `RETRY_INTERVAL_MS`/`GIVE_UP_MS` sit at `TransferViewModel.kt:55-56`; they are top-level constants near line 91.
- Local docker backend has no Blocktank: `@transfer_1`/`_max` cannot run there.
- Unclear: `bitkit-e2e-tests/README.md` says `@transfer_1` is not in the app `e2e-staging.yml` yet, but this repo's `e2e-staging.yml` has a `transfer` shard with grep `@transfer_staging`, which matches the `@transfer_1` title.

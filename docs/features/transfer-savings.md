# Transfer: spending to savings, channel close, settle rules

Moves Lightning spending back to on-chain savings (cooperative close, optional Boltz swap, force close), tracks every transfer in `TransferRepo`, and settles it. Funding a channel is in `transfer.md`.

## What it does
- Default path closes the selected usable channel(s) cooperatively (`TransferViewModel.closeChannels`: `awaitPeerConnected`, `lightningRepo.closeChannel`, `createTransfer(COOP_CLOSE)` per closed channel).
- Boltz reverse swap (channels stay open) runs only when `Env.isSwapSupported` (network `BITCOIN`) and the Dev Settings switch `isSavingsSwapEnabled` is on, and only with a priced quote; otherwise the swipe closes the channel. With a quote the confirm screen shows fees, a slider and a "close instead" button.
- Coop close failure: `SavingsProgressScreen` shows the interrupted state, `startCoopCloseRetries` retries every 1 min for 30 min (`RETRY_INTERVAL_MS`, `GIVE_UP_MS`), then opens `Sheet.ForceTransfer`. LSP channels are trusted peers and cannot be force closed: the user gets an error toast and the screen exits.
- Force transfer: `TransferViewModel.forceTransfer` closes with `force = true`, creates `FORCE_CLOSE` transfers, toasts, then `onComplete`.
- Counterparty-initiated close (`AppViewModel.handleChannelClosed`): creates a `COOP_CLOSE` or `FORCE_CLOSE` transfer when the balance is above 0 and shows `Sheet.ConnectionClosed`.
- Manual close from Settings (`CloseConnectionViewModel`): coop close plus `COOP_CLOSE` transfer; on failure only a warning toast (no force sheet).
- Pending transfers appear as `IncomingTransfer` on Savings/Spending screens and `ActivityBanner` on home, and are excluded from both balances until settled (`DeriveBalanceStateUseCase`).

## How a user reaches it
- Home card `ActivitySpending` > Spending screen > `TransferToSavings` (needs lightning balance > 0 and at least one channel). First time `SavingsIntro` (`SavingsIntro-button`, `hasSeenSavingsIntro`), then `SavingsAvailability` (`AvailabilityContinue`; Cancel goes home), `SavingsConfirm` (swipe `GRAB`; Advanced opens `SavingsAdvanced`, a per-channel switch list, shown only with more than one open channel; "Transfer all" replaces it once channels are selected), `SavingsProgress`.
- Progress screen tags: `TransferSettingUp`, `TransferSettling`, `TransferSuccess`; button `TransferSuccess-button`. OK button exits via `navigateOnSavingsTransferExit` to home, but only if `SavingsProgress` is still the current destination.
- `ForceTransfer` sheet tags: `ForceTransfer`, `CancelButton`, `ForceTransferButton`.
- Manual close: Settings > Advanced > `Channels` > `Channel` > channel detail (`TotalSize`, `IsUsableYes`/`IsUsableNo`) > `CloseConnection` > `CloseConnectionButton` / `CloseConnectionCancel`.
- LSP or peer close: `ConnectionClosedSheet` (`ConnectionClosedButton`) opens by itself.
- Banner "TRANSFER IN PROGRESS" has no tag (journey: screenshot); `IncomingTransfer` is shown while `balanceInTransferToSavings > 0`.

## Code
- Screens in `app/src/main/java/to/bitkit/ui/screens/transfer/`: `SavingsIntroScreen`, `SavingsAvailabilityScreen`, `SavingsConfirmScreen`, `SavingsAdvancedScreen`, `SavingsProgressScreen`; sheets `ui/sheets/ForceTransferSheet.kt`, `ui/sheets/ConnectionClosedSheet.kt`; settings `ui/settings/lightning/ChannelDetailScreen.kt`, `CloseConnectionScreen.kt`, `CloseConnectionViewModel.kt`; `ui/components/IncomingTransfer.kt`, `ActivityBanner.kt`.
- Routes (`ui/ContentView.kt`): `SavingsIntro`, `SavingsAvailability`, `SavingsConfirm`, `SavingsAdvanced`, `SavingsProgress`; sheets `Sheet.ForceTransfer`, `Sheet.ConnectionClosed`.
- Logic: `viewmodels/TransferViewModel.kt` (`onTransferToSavingsConfirm`, `loadSavingsSwapQuote`, `startSavingsSwap`, `closeChannels`, `startCoopCloseRetries`, `forceTransfer`), `services/BoltzService.kt`, `usecases/DeriveBalanceStateUseCase.kt`, `repositories/TransferRepo.kt`, `data/entities/TransferEntity.kt`, `data/dao/TransferDao.kt`, `models/TransferType.kt`.
- `TransferType`: `TO_SPENDING`, `MANUAL_SETUP` (toSpending); `TO_SAVINGS`, `COOP_CLOSE`, `FORCE_CLOSE` (toSavings). Backup category "Boosts & Transfers" per `docs/transfer.md` (see `backup.md`).
- Settle rules, `TransferRepo.syncTransferStates` (periodic, plus after order polling and close events):
  - toSpending: settled when the resolved channel `isChannelReady`; also when the channel id is in `closedChannels`, when the order is `EXPIRED`, or when the order's channel is closed (`hasClosedChannel`: state `CLOSED`, `closingTxId` or `close` set). Channel id comes from the order funding tx (`resolveChannelIdForTransfer`). Orders for pending transfers are fetched in one batch, throttled by `Env.lspOrdersRefreshInterval`.
  - toSavings and `COOP_CLOSE`: settled when the channel is no longer in LDK `lightningBalances`; skipped when balances are unavailable.
  - `FORCE_CLOSE`: when its balance is gone, settled if an on-chain activity exists for the channel, or no pending sweep remains, or the sweep txid already has an on-chain activity (batched sweep).
- `markSettled` keeps the row (needed to recover the transfer flag after a hardware re-pair, issue 1130); `TransferDao.deleteOldSettled` exists but has no caller, and the comment at `TransferRepo.kt:99-102` forbids wiring it up. `docs/transfer.md` says settled rows are cleaned up after 30 days, which the code does not do.

## How to drive it
- Journeys: `journeys/transfer/transfer-to-savings-returns-home.xml` ("transfer to savings returns home": OK lands on home, back exits the app). `journeys/transfers/closed-channel-transfer-settles.xml` ("transfer to spending banner clears when the lsp closes the channel": restart keeps the banner, `./lsp POST /regtest/channel/close`, `ConnectionClosedButton`, spending balance returns, app log lines `orders for active transfers` and `ready, settled transfer:`).
- E2E `bitkit-e2e-tests/test/specs/transfer.e2e.ts` `@transfer_2` (taps `TransferToSavings`, `SavingsIntro-button`, `AvailabilityContinue`, `GRAB`, `TransferSuccess`; local shard `lnurl_transfer`). Helper `transferSpendingToSavings` in `bitkit-e2e-tests/test/helpers/actions.ts` is used by `multiaddress.e2e.ts` `@multi_address_2` (staging shard `multi_address_staging`, `BACKEND=regtest`).
- Preconditions: an open usable channel and a positive spending balance; the savings journey closes it, so reopen one afterwards. The closed-channel journey needs staging regtest, `./lsp`, a hand-timed tap within 5 s (SettingUp auto-mines), adb log access.
- Manual test plan for to-savings, manual coop and force close: `docs/transfer.md` "Functional Tests" (uses `bitcoin-cli mine` and lowered retry constants).
- Unit: `app/src/test/java/to/bitkit/repositories/TransferRepoTest.kt` (58 tests: every settle branch above), `viewmodels/TransferViewModelTest.kt` (swap quote and run, mode selection).

## What proves it
- `TransferSuccess` then home with `ActivitySpending` at 0 and no `TransferToSavings`; savings balance rises after blocks confirm the sweep (e2e `expectSavingsBalance(0, { condition: 'gt' })`).
- e2e `@transfer_2` then opens `Channels` and asserts the text "Connection 1" is gone.
- Banner and `IncomingTransfer` disappear once settled; app log "ready, settled transfer:" or "Settled transfer".

## Not covered by tests
- Swap path end to end (mainnet plus dev switch): unit tests only.
- Coop-close retry loop, 30 min give-up, `ForceTransfer` sheet button, trusted-peer skip: no journey or e2e (manual plan in `docs/transfer.md` test 5).
- Force-close and `TO_SAVINGS` activity row labelling: `docs/transfer.md` footnote says closure txs cannot be shown as transfers (ldk-node API); no test.
- `SavingsAdvanced` per-channel selection, Boltz slider: none found.
- Counterparty-initiated close from the app side: only the `transfers/` journey, and it cannot reach the closed-channel settle path on an unmodified build (journey description; covered by `TransferRepoTest`).
- Manual close failure toast: none found.

## Gotchas
- Regtest/dev builds never offer the swap, so the e2e and journeys always close channels.
- Zero-conf: the LSP opens the channel after one confirmation of the order payment; mine a block for the transfer to settle (SettingUp mines one after 5 s).
- Closing a Blocktank channel from this flow is how journeys lose their spending balance; reopen a channel before later runs.
- `docs/transfer.md` points at `TransferViewModel.kt:55-56` for the retry constants; they are top-level constants near line 91.

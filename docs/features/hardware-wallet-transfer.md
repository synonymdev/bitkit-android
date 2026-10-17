# Hardware wallet transfer to spending

Funds a Blocktank channel from a paired watch-only hardware wallet: amount, sign on the device, broadcast, then the shared setting-up progress. Pairing, tile, settings, receive and send are in `hardware-wallet.md`; the savings-funded version is in `transfer.md`.

## What it does
- Amount screen limits come from the device's native-segwit balance minus a fee reserve (`TransferViewModel.updateHwLimits`, `hwFundingFeeReserve`, fallback rate 3 sat/vB, assumed 1 200 vB), then the LSP caps (`estimateInitialLspFees`, `estimateFinalMaxSendAmount`). MAX can be far below the device balance.
- Continue (`onConfirmAmount`) quotes the order with the device balance as funding budget; the sign screen shows network fee (offline estimate via `updateHwFundingFeeEstimate`), LSP fee, amount and total, plus Learn More and Advanced/Default.
- "Open Trezor Connect" (`onTransferToSpendingHwConfirm`): create the order if none, reconnect (`ensureHardwareConnected`, `isConnectingDevice`), compose the funding tx, sign (timeout 120 s, one reconnect and retry on a session failure), broadcast (timeout 120 s), then `fundPaidOrder(createTransferActivity = true, activityWalletId)`. The pending on-chain activity is created in the hardware wallet's scope and marked as transfer.
- If the created order costs more than shown, the fee cells refresh and the button must be tapped again (journey step).
- A signed tx whose broadcast failed is kept (`pendingHwFundingBroadcast`) and the button turns into Retry; retry broadcasts without signing again unless a new quote lands on another order address. Permanent failures clear it.
- A hidden wallet whose session is gone asks for the passphrase first (`HwPassphraseRequiredError`, `isHwPassphraseRequired`); a wrong passphrase or a device holding another wallet is refused (`HwPassphraseMismatchError`, `HwWalletMismatchError`) and nothing is signed. A pending broadcast retry skips the passphrase.
- Toasts: Bluetooth reconnect guidance for a known BLE device, "reconnect required" on firmware error, unlock prompt when the device is busy, connection warning on compose/broadcast timeouts, payment-timeout error on sign timeout (stale session is disconnected). User cancel on the device shows nothing.
- The sign screen can be left while the device is only connecting (`isConnectingDevice`); leaving cancels the job. While the device signs it cannot be left.

## How a user reaches it
- Home tile `ActivityHardware` > `HardwareWalletScreen` > `HardwareTransferToSpending` (needs a native-segwit funding balance above 0; no geo-block check found). First time `SpendingIntro` via `Routes.SpendingIntroHw` (`SpendingIntro-button`, `hasSeenSpendingIntro`), then `Routes.SpendingAmountHw(walletId)`.
- Amount ids: `HardwareTransferAmount`, `HardwareTransferAmountNumberField`, `HardwareTransferAmountNumberPad`, `HardwareTransferAmountAvailable` (label), `HardwareTransferAmountUnit` (value), `HardwareTransferAmountUnitButton`, `HardwareTransferAmountQuarter`, `HardwareTransferAmountMax`, `HardwareTransferAmountContinue`.
- Sign ids (`Routes.SpendingHwSign(walletId)`): `HardwareTransferSign`, `HardwareTransferSignLearnMore`, `HardwareTransferSignAdvanced` or `HardwareTransferSignDefault`, `HardwareTransferOpenTrezorConnect`. Advanced opens the shared `SpendingAdvanced` screen (see `transfer.md`).
- Signed (`Routes.SpendingHwSigned`): `HardwareTransferSigned`, then the shared `SettingUp` (`LightningSettingUp`, `TransferSuccess`, `TransferSuccess-button`).
- Passphrase prompt sheet: `HwTransferPassphraseSheet`, `HwTransferPassphraseInput`, `HwTransferPassphraseCancel`, `HwTransferPassphraseContinue`.
- Navigation to the signed screen comes from `TransferEffect.OnHwTxSigned` (`navigateForTransferEffect`), not from a button.

## Code
- Screens in `app/src/main/java/to/bitkit/ui/screens/transfer/hardware/`: `SpendingAmountHwScreen`, `SpendingHwSignScreen`, `SpendingHwSignedScreen`, `HwPassphrasePromptSheet`; wallet entry `ui/screens/wallets/HardwareWalletScreen.kt`.
- Routes (`ui/ContentView.kt`): `SpendingIntroHw`, `SpendingAmountHw`, `SpendingHwSign`, `SpendingHwSigned`, `SettingUp`.
- State: `viewmodels/TransferViewModel.kt` (HW region around lines 1043-1500: limits, sign job, passphrase, failure mapping, `TransferToSpendingUiState` HW fields), `repositories/HwWalletRepo.kt` (`getFundingAccount`, `composeFundingTransaction`, `signFunding`, `broadcastFunding`, `ensureConnected`, `reconnectWithPassphrase`, `needsPassphrase`, `warmUpKnownDevice`, `disconnectStaleSession`), `TrezorRepo.kt`/`JadeRepo.kt` for the device calls, `TransferRepo.createPendingToSpendingActivity`.
- Errors: `utils/HwErrorPresenter.kt`, `ext/HwExceptionExt.kt`.

## How to drive it
- Journeys in `journeys/hardware-wallet/` (setup, emulator commands and order in `hardware-wallet.md`): `transfer-to-spending.xml` (25%, sign, signed, one new blue Transfer row "From Savings", detail "TO SPENDING"); `transfer-to-spending-max-lsp-cap.xml` (MAX equals AVAILABLE, not the device balance; needs a channel or pending order consuming most LSP headroom); `transfer-to-spending-node-warmup.xml` (force-stop and relaunch, open the transfer during node start, no failure toast, reaches sign screen); `passphrase-transfer-to-spending.xml` (signs with the live session, asks again after force-stop, refuses `not-the-one`, accepts `bitkit-hidden`, greps logs and datastore for leaks via `run-as`). There is no Android hardware over-max journey (`journeys/README.md` cross-platform table: `hardware-wallet/transfer-to-spending-over-max.xml` exists only on iOS).
- E2E `bitkit-e2e-tests/test/specs/hardware-wallet.e2e.ts` `@hardware_wallet_3` (helper `transferHardwareWalletToSpending`): connect, fund 100 000 sats, transfer 20 000, press Yes on the emulator until `HardwareTransferSigned`/`LightningSettingUp`; with `BACKEND=regtest` it mines blocks until `TransferSuccess`, then expects spending balance > 0; with `BACKEND=local` there is no Blocktank, so it only taps `TransferSuccess-button`. CI: staging shard `hardware_wallet` (`e2e-staging.yml`).
- Preconditions: Trezor emulator running with Bridge (`bitkit-docker/scripts/trezor-emulator`), device account funded and mined, node connected to the LSP, staging regtest for a real channel.
- Unit: `app/src/test/java/to/bitkit/viewmodels/TransferViewModelTest.kt` (about 30 hardware cases: sign and record, retry broadcast, passphrase, mismatch, timeouts, busy device, firmware error, cancel), `HwWalletRepoTest`.

## What proves it
- `HardwareTransferSigned`, then `LightningSettingUp` and finally `TransferSuccess` plus `TransferSuccess-button`.
- Hardware wallet balance lower than before (`expectHardwareWalletBalance(fundingSats, { condition: 'lt' })`); on regtest spending balance above 0 and toast `SpendingBalanceReadyToast`.
- Exactly one new blue Transfer row in the hardware wallet's activity, detail "TO SPENDING"; the activity fee is the composed mining fee and the funding output equals the final `order.feeSat` (README QA note, checked by decoding the tx).
- Passphrase case: no sheet with the live session, `HwTransferPassphraseSheet` after the session is dropped, error toast and unchanged tile count on a wrong passphrase, `NO_PASSPHRASE_LEAK`.

## Not covered by tests
- Local backend end state (no Blocktank): e2e only taps through; the channel never opens there.
- Jade signing and Bluetooth reconnect toasts: unit tests only.
- Broadcast retry button, device-busy unlock toast, firmware-error toast, signing timeout: unit tests only; no journey injects them.
- Advanced (custom receiving capacity) from the sign screen, and Learn More: no journey or e2e.
- Geo-block for hardware transfer: Android code shows no check; not tested.
- Android hardware over-max number pad (cap and toast): no journey or e2e; the toast has no testTag; unit test `onSpendingAdvancedContinue rejects a receiving capacity the drained device account cannot fund` covers only the funding check.

## Gotchas
- `HardwareTransferAmountAvailable` is the label, the value is `HardwareTransferAmountUnit`.
- The device prompts four times (recipient, amount, locktime, summary); a passphrase session prompts once per address type while reading accounts. Approve with `send-json '{"type":"emulator-press-yes"}'`; the e2e helper presses Yes every 500 ms until a target id shows.
- The first tap on "Open Trezor Connect" may only create the order and refresh fees; tap again (journey note).
- Device balance can be much larger than AVAILABLE because of LSP headroom; MAX uses AVAILABLE.
- Leaving the sign screen mid-signing is blocked; a cancelled job can outlive the screen until the device call returns (`hwTransferAttempt` guards state).

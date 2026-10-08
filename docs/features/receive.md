# Receive

The Receive sheet shows an on-chain address, a Lightning invoice or a unified QR, lets the user set an amount, note and tag, orders just-in-time (CJIT) liquidity from the Blocktank LSP when needed, and shows the received-payment sheet when funds arrive.

## What it does
- Four tabs (`app/src/main/java/to/bitkit/ui/screens/wallets/receive/ReceiveTab.kt`): Savings (on-chain BIP21 without the `lightning` param), Auto (unified BIP21 with `lightning=<bolt11>`), Spending (Lightning only; a CJIT invoice wins over the node invoice), Hardware (paired Trezor or Jade address; see hardware-wallet.md).
- Auto exists only when a normal invoice can be created: a ready channel and inbound liquidity > 0, and a fixed amount must fit inbound liquidity (`app/src/main/java/to/bitkit/models/ReceiveLiquidityDecision.kt`). It is the default tab when present, else Savings. Tab testTags are `Tab-<name>` (`Tab-savings`, `Tab-auto`, `Tab-spending`, `Tab-hardware`).
- QR view: `QRCode`, `SpecifyInvoiceButton` (Edit), `ReceiveCopyQR`. "Show Details" (`ShowDetails`) lists `ReceiveOnchainAddress` / `ReceiveLightningAddress` / `ReceiveHardwareAddress`; the same button (`ShowDetails`) is the CJIT button on the Spending tab when no Lightning invoice is possible. `QRCode` is also the "QR Code" button in details mode. `ReceiveLightningLoading` shows while the node starts.
- Edit invoice (`EditInvoiceScreen`, `edit_invoice_screen`): amount via `ReceiveNumberPadTextField` / `ReceiveNumberPad` / `ReceiveNumberPadUnit` / `ReceiveNumberPadSubmit`, note `ReceiveNote`, tags `TagsAdd` then `TagInputReceive` / `ReceiveTagsSubmit`, back to QR with `ShowQrReceive`. `PaymentRequestSendButton` and `ReceivePaymentRequestContacts` start a Paykit payment request (payment-requests.md).
- CJIT: amount entry `ReceiveAmountScreen` (`ReceiveAmount`, `ReceiveAmountMin`, `ContinueAmount`, toast `ReceiveCjitAmountExceededToast`), fee review `ReceiveConfirmScreen` (`ReceiveConfirmNotificationSwitch`), learn-more `ReceiveLiquidityScreen` (`LiquidityContinue`, `ReceiveLiquidityNotificationSwitch`), then the CJIT invoice QR on the Spending tab. Geo-blocked users get `LocationBlockScreen` ("Advanced Setup" opens the external-node funding flow instead).
- Rules for edits, additional CJIT on an existing channel, limits and quote validation are in `docs/receive-liquidity.md`; do not restate them here.
- Tag on receive: tags typed on Edit invoice are stored as pre-activity metadata for the invoice (`PreActivityMetadataRepo`, `WalletRepo.addTagToSelected`); `lightning_1` then filters the activity list by the receive tag `rtag` after the payment arrives. Closing the sheet clears amount, note and tags.
- Received detection (foreground): `AppViewModel.handleLdkEvent` handles `PaymentReceived`, `OnchainTransactionReceived`, `OnchainTransactionConfirmed`; `NotifyPaymentReceivedHandler` returns Skip, ShowSheet (foreground) or ShowNotification (`includeNotification`, background service); it dedupes by payment id or txid. An open Receive sheet whose invoice or address was just paid is closed first (`closeSettledReceiveSheet`).
- Received sheet (`ui/sheets/NewTransactionSheet.kt`): `new_transaction_sheet`, amount `ReceivedTransaction`, dismiss `ReceivedTransactionButton`; title "Received Instant Bitcoin" (Lightning) or "Received Bitcoin" (on-chain). Same view is used for sent payments (send.md).
- On-chain rules (journeys/onchain-receive/README.md): a mempool event shows one sheet and the later confirmation shows none; a confirmed-only receive shows a sheet only if its block time is within one hour, no restore or migration runs, and its height is above the restore tip; `pendingRestoreActivitySeen` holds everything until the first post-restore sync.

## How a user reaches it
- Tab bar `Receive` (`app/src/main/java/to/bitkit/ui/components/TabBar.kt`, wired in `app/src/main/java/to/bitkit/ui/ContentView.kt` ~742-745, passes the hardware wallet id on the hardware screen) opens `Sheet.Receive`. Other entries: empty activity rows on the Savings and Spending screens (~1224, ~1243), the Funding screen "fund" action (~1087, opens `ReceiveRoute.Amount`), payment-request actions (~855, ~1435).
- Spending tab: `Tab-spending` then `ShowDetails` (CJIT) goes to `ReceiveRoute.Amount`, or `GeoBlock` when geo-blocked. Edit from Spending with a too-large amount goes to Amount or straight to `ConfirmIncreaseInbound`.
- Dev deeplink (debug builds, dev mode on): `bitkit://screen/receive` plus `/qr`, `/amount`, `/edit-invoice`, `/add-tag`, `/geo-block` (see deeplinks.md).
- Background: `LightningNodeService` posts the "Payment Received" notification when background payments are on (notifications.md).

## Code
- `app/src/main/java/to/bitkit/ui/screens/wallets/receive/ReceiveSheet.kt`: `NavHost`, `ReceiveRoute` (`QR`, `Amount`, `Confirm`, `ConfirmIncreaseInbound`, `Liquidity`, `LiquidityAdditional`, `EditInvoice`, `AddTag`, `GeoBlock`, payment-request routes); session state classes keep CJIT and edit state until the sheet closes.
- `app/src/main/java/to/bitkit/ui/screens/wallets/receive/ReceiveQrScreen.kt`: tabs, QR, details, CJIT onboarding view. `ReceiveInvoiceUtils.kt` (same folder): which string each tab encodes. `ReceiveTab.kt`: tab enum.
- `app/src/main/java/to/bitkit/ui/screens/wallets/receive/EditInvoiceScreen.kt`, `EditInvoiceVM.kt`: amount, note, tags, CJIT routing from edits.
- `app/src/main/java/to/bitkit/ui/screens/wallets/receive/ReceiveAmountScreen.kt`, `ReceiveConfirmScreen.kt` (also `CjitEntryDetails`), `ReceiveLiquidityScreen.kt`, `LocationBlockScreen.kt`, `ReceiveCjitErrorPresenter.kt`: CJIT screens and error text.
- `app/src/main/java/to/bitkit/ui/screens/wallets/receive/HwReceiveViewModel.kt`: hardware tab address (hardware-wallet.md).
- `app/src/main/java/to/bitkit/models/ReceiveLiquidityDecision.kt`, `app/src/main/java/to/bitkit/models/CjitQuoteValidator.kt`: Auto availability, additional-liquidity action, quote checks.
- `app/src/main/java/to/bitkit/repositories/BlocktankRepo.kt`: `createCjit`, `maxCjitAmountSats`, `refreshMinCjitSats`, `getDefaultLspBalance`.
- `app/src/main/java/to/bitkit/repositories/WalletRepo.kt`, `app/src/main/java/to/bitkit/viewmodels/WalletViewModel.kt`: BIP21 and bolt11 state, `updateBip21Invoice`, tags; `app/src/main/java/to/bitkit/repositories/PreActivityMetadataRepo.kt`: tags before the activity exists.
- `app/src/main/java/to/bitkit/domain/commands/NotifyPaymentReceivedHandler.kt`, `app/src/main/java/to/bitkit/domain/commands/NotifyPaymentReceived.kt`: show or skip the received sheet and notification.
- `app/src/main/java/to/bitkit/viewmodels/AppViewModel.kt`: LDK event handling, `closeSettledReceiveSheet`. `app/src/main/java/to/bitkit/ui/sheets/NewTransactionSheet.kt`: received and sent sheet. `app/src/main/java/to/bitkit/androidServices/LightningNodeService.kt`: background notification.

## How to drive it
- Journeys: `journeys/receive/receive-auto-tab-selection.xml` (opens on Auto without sliding, user tab choice kept, stale edit amount cleared; needs a usable channel, screen recordings).
- `journeys/onchain-receive/README.md` holds the rules. `journeys/onchain-receive/mempool-then-confirmed-single-sheet.xml` (one sheet only), `journeys/onchain-receive/confirmed-only-received-sheet.xml` (deposit and mine in one shell command), `journeys/onchain-receive/confirmed-only-background-notification.xml` (one notification, background payments on), `journeys/onchain-receive/restore-recent-receive-stays-silent.xml` (throwaway emulator, wipes the app). Fund with `./lsp POST /regtest/chain/deposit` and `/regtest/chain/mine`; sync runs every 10s; `adb logcat -d -s APP:V` shows `LDK event fired`.
- `journeys/cjit-notifications/` covers CJIT push notifications (notifications.md).
- E2E `bitkit-e2e-tests/test/specs/receive.e2e.ts` (`@receive_1`; shard `onchain_boost_receive_widgets`): address prefix `bcrt1`, edit amount/note/tag, data survives QR round trip, resets after close and reopen, tag list delete. Local backend (`bitkit-docker`).
- `bitkit-e2e-tests/test/specs/lightning.e2e.ts` (`@lightning_1`, shard `lightning_security`): receives 10k and 111 sats over LND from the default (Auto) tab (plain and edited invoice with note and tag), `acknowledgeReceivedPayment`. Needs `bitkit-docker` with LND, `BACKEND=local`.
- `bitkit-e2e-tests/test/specs/onchain.e2e.ts` (`@onchain_1/_2/_3`, shard `onchain_boost_receive_widgets`): receive on-chain, tag receive addresses, received sheet via `acknowledgeReceivedPayment`.
- `bitkit-e2e-tests/test/specs/numberpad.e2e.ts` (`@numberpad_1`, `_3`): Receive number pad in modern and classic units (shard `onboarding_backup_numberpad`).
- `bitkit-e2e-tests/test/specs/receive-ln-payments.e2e.ts`: utility, no tags, in no CI shard. Attaches to an installed app and pays N invoices read from `QRCode` (`PAYMENT_COUNT`, `PAYMENT_AMOUNT`, `BACKEND=regtest`).
- `bitkit-e2e-tests/test/specs/mainnet/cjit.e2e.ts` (`@cjit_mainnet`, `@cjit_1`): smokes CJIT order on mainnet: restores `CJIT_SEED`, Receive, `Tab-spending`, `ShowDetails`, min amount, fee text check, Continue, `QRCode`. Not in any CI shard of the app repo (`docs/mainnet-nightly.md` in the e2e repo says a private `bitkit-nightly` repo runs mainnet tags; only the probe suite is documented there). `send_2`/`send_3` and `lnurl_1` also receive Lightning funds as setup.

## What proves it
- Receive sheet `ReceiveScreen` visible; `QRCode` text starts with `bitcoin:bcrt1` (regtest) and, on Auto, contains `lightning=ln`.
- Received: `ReceivedTransaction` shows the amount, `ReceivedTransactionButton` closes it, balance and `ActivityShort-0` show the `+` row.
- CJIT: after Continue the Spending tab shows the CJIT invoice QR (`QRCode`), Auto tab absent.

## Not covered by tests
- CJIT end to end with payment on regtest, additional CJIT on an existing channel, `ConfirmIncreaseInbound`, `ReceiveLiquidityScreen`, geo-block screen, max-size and node-capacity errors (only unit-level logic and the mainnet smoke to the QR).
- Copy button `ReceiveCopyQR`, `Tab-savings`/`Tab-auto` switching beyond the one auto-tab journey, edit amount above inbound capacity falling back to Savings (only the journey's last steps touch it).
- Hardware tab and Paykit request buttons (see hardware-wallet.md, payment-requests.md).
- Replaced, evicted or reorged on-chain receives (`handleOnchainTransactionReplaced`, `Evicted`, `Reorged`).

## Gotchas
- The Receive sheet tab rows do carry testTags (`Tab-<name>`), although `journeys/onchain-receive/README.md` says they do not; use them.
- The Auto tab appears only after the Lightning node runs and loads channels; right after launch the sheet opens on Savings (`receive-auto-tab-selection.xml`).
- A CJIT invoice is Spending-only and immutable; editing replaces it (`docs/receive-liquidity.md`).
- Do not type long invoices with `adb shell input text`; read the URI from the `QRCode` content-desc (journeys/README.md).
- Wallet sync takes up to 10s; a deposit and a mine in separate commands may take the mempool path.
- `onchain_2` has its receive-tag activity filter commented out (bitkit-android issue 322, "receive tag does not work in local regtest"); the Lightning receive tag filter in `lightning_1` is active.

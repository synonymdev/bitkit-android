# LNURL and Lightning Addresses

LNURL pay, withdraw, auth and channel requests, and Lightning Addresses, all entered through the same scan and paste paths as other payments (see send.md).

## What it does
- Types come from `coreService.decode` (`Scanner.LnurlPay`, `LnurlWithdraw`, `LnurlAuth`, `LnurlChannel`) in `AppViewModel.handleDecodedScan`. The prefixes `lightning:`, `lnurl:`, `lnurlw:`, `lnurlc:`, `lnurlp:` are stripped before decoding (`removeLightningSchemes`, a workaround for bitkit-core issue 70).
- Lightning Address (`user@domain`): the app has no dedicated code. The decoder in bitkit-core treats it as an LNURL address (`synonymdev/bitkit-core` `src/modules/scanner/implementation.rs`, `is_lnurl_address`, read at its latest commit, which may differ from the version the app pins), so it follows the LNURL pay path.
- LNURL pay (`onScanLnurlPay`): checks outbound capacity first (warning toast "Not enough outbound/sending capacity", sheet closes). Fixed amount (min = max) skips the amount screen and goes to QuickPay or Confirm; a range opens the Send amount screen (title "Pay Bitcoin"). Over max shows `SendAmountExceededToast`; under min on Continue shows `LnurlPayAmountTooLowToast`. `SendAmountMax` fills the lower of LNURL max and balance. The invoice is fetched on Confirm (`LightningRepo.fetchLnurlInvoice`).
- Comment: if the endpoint allows comments (`commentAllowed` > 0) Confirm shows `CommentInput`, limited to that length; the text becomes the Invoice note of the sent activity (`InvoiceNote`). A fixed or comment-less request shows no `CommentInput`.
- msat precision: amounts with msat remainders are paid exactly; the UI and activity show sats rounded up (helper `msatsToCeilSatsString` in `bitkit-e2e-tests/test/specs/lnurl.e2e.ts`).
- LNURL withdraw (`onScanLnurlWithdraw`): fixed amount goes to `WithdrawConfirmScreen` (`WithdrawAmount`, `WithdrawConfirmButton`); a range opens the Send amount screen (title for withdraw, max label from `wallet__lnurl_w_max`) then the same confirm. `onConfirmWithdraw` creates a one-hour invoice and calls the LNURL callback; success shows toast "Withdraw Requested" and returns Home; any failure opens `WithdrawErrorScreen` (`scan_button`, `support_button`).
- LNURL auth (`onScanLnurlAuth`): opens `Sheet.LnurlAuth` (`LnurlAuthSheet`: "Log In", `LnurlAuthContinue`, `LnurlAuthCancel`). Continue calls `LightningRepo.requestLnurlAuth`, which signs with the wallet seed through bitkit-core `lnurlAuth`, then shows toast "Signed In" (or a warning with the error) and closes the sheet.
- LNURL channel (`onScanLnurlChannel`): closes the scan sheet and opens `Routes.LnurlChannel` (`LnurlChannelScreen`, `ConnectButton`). `LnurlChannelViewModel.onConnect` connects to the peer from the URI, calls `LightningRepo.requestLnurlChannel` (private channel), then routes to `ExternalSuccess`. The channel opens after the LSP broadcasts and confirms; the app shows `SpendingBalanceReadyToast` when usable.

## How a user reaches it
- Tab bar `Send` > `RecipientInvoice` (paste) or `RecipientManual` (type the string, `RecipientInput`, `AddressContinue`); tab bar `Scan` (camera, gallery or clipboard).
- OS links `lnurl:`, `lnurlw:`, `lnurlc:`, `lnurlp:` and `lightning:` open the app (intent filters in `AndroidManifest.xml`, see deeplinks.md): `adb shell am start -a android.intent.action.VIEW -d "lightning:<lnurl>" to.bitkit.dev`.
- Channel and auth requests do not need the Send sheet; pay and withdraw use it. In E2E builds (`E2E=true`) the scanner shows `ScanPrompt` / `QRInput` / `DialogConfirm` to inject a string.

## Code
- `app/src/main/java/to/bitkit/viewmodels/AppViewModel.kt`: `onScanLnurlPay`, `onScanLnurlWithdraw`, `onScanLnurlAuth`, `onScanLnurlChannel`, `onConfirmWithdraw`, `requestLnurlAuth`, `LnurlParams` in `sendUiState`.
- `app/src/main/java/to/bitkit/services/LnurlService.kt`: HTTP calls for withdraw and channel callbacks. `app/src/main/java/to/bitkit/services/CoreService.kt`: `decode`, `getLnurlInvoiceForPayData`.
- `app/src/main/java/to/bitkit/repositories/LightningRepo.kt`: `fetchLnurlInvoice`, `requestLnurlWithdraw`, `requestLnurlChannel`, `requestLnurlAuth`.
- `app/src/main/java/to/bitkit/ui/screens/wallets/withdraw/WithdrawConfirmScreen.kt`, `app/src/main/java/to/bitkit/ui/screens/wallets/withdraw/WithdrawErrorScreen.kt`: withdraw confirm and error.
- `app/src/main/java/to/bitkit/ui/sheets/LnurlAuthSheet.kt`: auth prompt.
- `app/src/main/java/to/bitkit/ui/screens/transfer/external/LnurlChannelScreen.kt`, `LnurlChannelViewModel.kt`, `ExternalSuccessScreen.kt` (same folder); route registered in `app/src/main/java/to/bitkit/ui/ContentView.kt`.
- Shared with send.md: `app/src/main/java/to/bitkit/ui/screens/wallets/send/SendAmountScreen.kt` (min and max messages for LNURL), `app/src/main/java/to/bitkit/ui/screens/wallets/send/SendConfirmScreen.kt` (`LnurlPayDetails`, `CommentInput`).

## How to drive it
- Journey: `journeys/lnurl/lnurl-pay-comment-note.xml` (comment kept as Invoice note, survives restart). Needs an endpoint that issues description-hash invoices, `commentAllowed` >= 12, and a range containing 1,000 sats (the e2e server's 149,500-200,999 msat range does not). The `bitkit-docker` `lnurl-server` is not usable for it: it issues memo invoices. Needs a spending balance of at least 1,000 sats plus fees.
- E2E `bitkit-e2e-tests/test/specs/lnurl.e2e.ts` (`@lnurl_1`, shard `lnurl_transfer`): starts a local `lnurl-node` server (port 30001) backed by LND (`127.0.0.1:8080`), so it needs `bitkit-docker`, LND and `BACKEND=local`. Order: fund 1000 sats, `lnurl-channel` (localAmt 100,001, push 20,001, `ConnectButton`, mine 6 blocks, `SpendingBalanceReadyToast`, external success), pay with range and comment (`SendAmountExceededToast` above max, `LnurlPayAmountTooLowToast` below min, `CommentInput`), pay fixed (no `CommentInput`), pay by manual entry, withdraw range and fixed (`WithdrawConfirmButton`), three msat pay/withdraw pairs (222538, 222222, 500500 msat), `lnurl-auth` (`LnurlAuthContinue`, text "Signed In", server `login` event).
- Setup for local manual runs: journeys/README.md capability row (`just run docker`, `adb reverse`).
- `bitkit-e2e-tests/test/specs/mainnet/ln.e2e.ts` (`@strike_1`, `@wos_1`): pays a Lightning Address on mainnet (see send.md). `bitkit-e2e-tests/test/specs/mainnet/probe.e2e.ts` (`@probe_mainnet_1`): fetches invoices from LNURL / Lightning Address targets over HTTP in the test (`bitkit-e2e-tests/test/helpers/probe.ts`) and probes routes through the debug `devtools` provider; it does not drive the LNURL screens. Neither is in a CI shard of the app repo.

## What proves it
- Pay: `SendSuccess` then `ActivityShort-0` with the amount; with comment, `InvoiceNote` equals the comment.
- Withdraw: toast "Withdraw Requested", Spending balance rises after the server pays, `ActivityShort-0` shows `+`.
- Auth: text "Signed In" and the server `login` event. Channel: external success screen, Spending balance equals the pushed amount (20,001 sats in the e2e).
- Failures: `WithdrawErrorScreen` (`scan_button`), warning toasts quoted above.

## Not covered by tests
- Withdraw failure screen, auth cancel and auth failure toast, channel request failure and `ConnectButton` with an unreachable peer.
- LNURL pay without outbound capacity (the capacity warning toast and sheet close).
- Lightning Address on regtest: no journey or spec uses one (journeys/README.md lists it as a `lnurl-server` capability; only the mainnet smoke pays one).
- LNURL from an OS intent or the QR camera (E2E injects text through `ScanPrompt`); QuickPay on a fixed-amount LNURL pay.
- The comment-on-activity check in `lnurl_1` is commented out (see Gotchas); only the journey covers it.

## Gotchas
- `lnurl_1` has its activity-comment assertion (`InvoiceComment`) commented out, citing bitkit-android issue 417 and bitkit-ios issue 277; `lnurl-pay-comment-note.xml` (tag `InvoiceNote`) is the check.
- The bitkit-docker LNURL server creates memo invoices, so the note then reads "LNURL Payment <id> - thanks" on any branch; do not read that as a pass.
- `lnurl:` inputs are decoded after prefix stripping; a string that bitkit-core cannot decode shows the generic QR error toast (`other__qr_error_header`).

Unclear: whether the bitkit-core version pinned by the app decodes Lightning Addresses as read in the sibling checkout; no app code, journey or regtest spec exercises one.

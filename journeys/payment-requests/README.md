# Payment Request journeys

Cover incoming Paykit Payment Requests from a linked issuer. The issuer contract and exact accepted/rejected data live in
[`docs/paykit-issuer-interoperability.md`](../../docs/paykit-issuer-interoperability.md) and
[`app/src/test/resources/paykit-issuer-interoperability.json`](../../app/src/test/resources/paykit-issuer-interoperability.json).

## Setup

Run Bitkit against regtest with Paykit UI enabled. Authenticate a Pubky identity, save and link the fixture issuer as a contact, and give the wallet enough on-chain balance to pay 100,000 sats. The fixture issuer must be able to publish a Paykit endpoint and send a one-time Payment Request to that linked peer. Its App ID is `paykit-server`; Bitkit uses `bitkit`.

The accepted journey uses:

- Payment Request ID: `71300000-0000-4000-8000-000000000001`
- Asset: `btc`
- Amount: `0.001`
- Accepted identifier: `btc-regtest-p2wpkh`
- Endpoint payload: `{"value":"bcrt1qissuerfixture"}`, replacing the placeholder address with a valid current receive address from the issuer

Rejected fixture shapes stay in unit tests because Bitkit intentionally does not present requests that fail the contract gate.

`request-summary.xml` uses a second Bitkit instance as the requester instead of the fixture issuer: both instances are authenticated Pubky identities, saved as each other's contacts and linked, and the payer holds enough balance to pay 21,000 sats.
Its Android-only Sent receipt checks leave the 5,000 sats request open through payment and the
existing foreground synchronization. A protocol proof changes the note-free subtitle to
"Proof submitted"; a nonblank note still takes precedence. This is not inferred from a chain
payment. iOS keeps a static note/date receipt with no lifecycle subtitle, so these receipt-status
steps do not apply there.

`contact-request-or-pay.xml` uses the same two-instance setup and starts from the payer's Contact Detail screen, opened through the `bitkit://contact` deeplink. Its timing step assumes the payer has been running for about a minute: right after launch, the Paykit session restore and link refresh hold the SDK and can push the Pay step well past the budget.

`definite-pre-broadcast-retry.xml` uses the linked fixture issuer and the local regtest LNURL server. Configure its LNURL-pay metadata endpoint normally, but make its invoice callback fail the first request and succeed after it is switched back to the healthy response. Do not republish the Paykit payment list between attempts. This makes the first send fail before Lightning dispatch and proves that the same private payment details can be opened and paid on retry.

## Foreground synchronization

Bitkit checks the private inbox and shared request state every 10 seconds while foregrounded and online.
Outbound retries, endpoint publication, and target discovery also run on startup,
explicit refreshes, and maintenance rounds after 30 seconds, then every 60 seconds.
Maintenance uses elapsed time, including slow requests; polling remains serialized and does not
start catch-up rounds. Incoming messages wait for the next poll plus synchronization time.

## Accepting install

Acceptance intent is saved before the remote operation and included in wallet backups.
An interrupted response is reconciled against the shared request before payment. Restoring
the wallet restores its pending acceptances; this does not support running the same wallet
on multiple devices concurrently.

`accepted-device-ownership.xml` extends the failing LNURL fixture to two separate installs sharing
one Pubky identity and App ID. Only the accepting install may retry after restart; the other keeps
the accepted request in history without payment controls. Confirm acceptance in shared request
state after the callback failure, before testing the second install.

## Reference evidence

The source wallet-leg run completed this path on regtest on 2026-08-22: Bitkit presented the incoming request, opened the on-chain payment, broadcast it, and confirmed transaction
`cc85df0e24b54be353a57700429d144b35264c1af97f3de41c503dc52f1e4792` at height `77318`.

That run established the issuer shapes captured by the fixture: lowercase `btc`, `btc-regtest-p2wpkh`, and a JSON object endpoint payload with a non-empty string `value`. The exact Debug binary SHA was not recorded, so the canonical fixture tests lock the same production gates on the current code.

## Identifiers used

- Pending-request bell: `PaymentRequestsBell`
- Incoming sheet: `PaymentRequestsSheet`
- Detail screen: `PaymentRequestDetailsScreen`
- Detail amount and status: `PaymentRequestDetailsAmount`, `PaymentRequestDetailsStatus`
- Request row: `PaymentRequestRow-<paymentRequestId>`
- Pay action: `PaymentRequestPay-<paymentRequestId>`
- Dismiss action: `PaymentRequestDismiss-<paymentRequestId>`
- Payment confirmation: `PaymentRequestConfirm`
- Confirmation summary: `PaymentRequestFrom`, `PaymentRequestFor`
- Confirmation invoice note: `PaymentRequestInvoiceNote`
- Confirmation details: `SendConfirmToggleDetails`
- Saved-contact recipient: `ReviewContactRecipient`
- Send failure: `SendFailure` and retry action `Retry`
- Swipe confirmation control: `GRAB`
- Contact Detail pay action: `ContactPay`
- Request or Pay sheet: `RequestOrPaySheet` (its Pay and Request buttons carry no tag; find them by text)
- Payment Request amount screen: `PaymentRequestAmount`

`android layout` can omit test tags applied to plain `Box` and `Column` containers. Use the raw UI Automator hierarchy when a documented container tag is not present in the formatted layout output.

`delete-and-readd-contact.xml` uses two Bitkit instances to verify that deleting a contact revokes private requests across restart and that explicitly adding the contact again restores a fresh private connection. It does not send funds.

`delete-contact-with-active-subscription.xml` requires an accepted open-ended payer subscription. It verifies that deletion explains why the contact must stay saved until the subscription ends, then that canceling, deleting, and readding does not revive it. No new payment is sent. Both contact-deletion journeys are mirrored on iOS and Android.

## Payment deadline history

`absolute-payment-deadline.xml` covers one-time requests with absolute payment deadlines,
including acceptance before proposal expiry, payment after proposal expiry, and a deadline
crossed while payment preparation waits. The deadline is inclusive and separate from proposal
expiry. A sent payment's proof can still be delivered after its payment deadline.

`payment-deadline-history.xml` covers expired one-time requests and recurring payment deadlines.
Bitkit keeps their lifecycle and paid-period history, and subscription cancellation,
but does not offer expired payments or support recurring payment deadlines. The journey
requires a controlled shared-runtime peer to prepare the accepted and paid records; repository
tests cover these states without sending funds. On Android, unpaid history rows show
lifecycle labels, while paid rows show subscription names, notes, or dates. Active
subscriptions are opened from Overview. The journeys record each fixture's payment
request id, check its full row identifier, and include the required back and tab
transitions. The accepted
subscription must have no end date so cancellation is available. The proposal review
must explain that its payment details are unsupported and offer no Subscribe control.

`automatic-presentation.xml` uses the same two-wallet setup and leaves the payer in the foreground. `confirmation-controls.xml` checks Android's fixed amount and confirmation footer on a compact screen with large text; it is not ported to iOS because the confirmation layout is different there.

Incoming preparation uses the existing Send confirmation sheet with saved sender, amount and note.
Its payment control stays disabled and loading until fresh resolution and wallet validation finish.
Closing during preparation leaves the request pending and suppresses automatic reopening for the
current identity's app session; Pay from the request list or details explicitly retries it.
An unfunded wallet can verify loading followed by native rejection, not an enabled payment control.

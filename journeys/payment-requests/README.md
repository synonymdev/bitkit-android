# Payment Request journeys

Cover incoming Paykit Payment Requests from a linked issuer. The issuer contract and exact accepted/rejected data live in
[`docs/paykit-issuer-interoperability.md`](../../docs/paykit-issuer-interoperability.md) and
[`app/src/test/resources/paykit-issuer-interoperability.json`](../../app/src/test/resources/paykit-issuer-interoperability.json).

## Setup

Run Bitkit against regtest with Paykit UI enabled. Authenticate a Pubky identity, link the fixture issuer on receiver path `bitkit/server`, and give the wallet enough on-chain balance to pay 100,000 sats. The fixture issuer must be able to publish a Paykit endpoint and send a one-time Payment Request to that linked peer.

The accepted journey uses:

- Payment Request ID: `71300000-0000-4000-8000-000000000001`
- Asset: `btc`
- Amount: `0.001`
- Accepted identifier: `btc-regtest-p2wpkh`
- Endpoint payload: `{"value":"bcrt1qissuerfixture"}`, replacing the placeholder address with a valid current receive address from the issuer

Rejected fixture shapes stay in unit tests because Bitkit intentionally does not present requests that fail the contract gate.

## Reference evidence

The source wallet-leg run completed this path on regtest on 2026-08-22: Bitkit presented the incoming request, opened the on-chain payment, broadcast it, and confirmed transaction
`cc85df0e24b54be353a57700429d144b35264c1af97f3de41c503dc52f1e4792` at height `77318`.

That run established the issuer shapes captured by the fixture: lowercase `btc`, `btc-regtest-p2wpkh`, and a JSON object endpoint payload with a non-empty string `value`. The exact Debug binary SHA was not recorded, so the canonical fixture tests lock the same production gates on the current code.

## Identifiers used

- Incoming sheet: `PaymentRequestsSheet`
- Request row: `PaymentRequestRow<paymentRequestId>`
- Pay action: `PaymentRequestPay<paymentRequestId>`
- Reject action: `PaymentRequestReject<paymentRequestId>`
- Payment confirmation: `PaymentRequestConfirm`

# Pubky marketplace wallet leg

This suite covers the two-wallet Bitkit leg of a Pubky marketplace purchase: a seller grants
Paykit access and a watch-only account, a linked buyer receives the Payment Request, and the buyer pays
the request on regtest through confirmation. It does not cover marketplace browsing, Locks content
delivery, fiat payment, or Hypercolor.

## Local-only fallback

Remote Shop staging remains the default. After an actual staging Paykit pairing failure, see
[`local-paykit-fallback.md`](local-paykit-fallback.md) and `local-paykit-fallback.xml` for the
conditional standalone fixture. It is unrun and does not establish Shop order-paid acceptance.
The companion journey is shared with iOS; only platform build instructions differ.

## Companion consent and reconnect

- `paykit-only-approval.xml` checks Paykit-only consent and cancellation without account creation.
- `paykit-reconnect.xml` checks Paykit-only reconnect consent and cancellation without changing the existing service account.

These visible consent and cancel checks require valid auth URL fixtures but do not authorize a
network session. The reconnect journey additionally needs a known active, tracked account in the
isolated current wallet. Do not manufacture that fixture from real wallet data.

With a live fixture, also approve each request: verify Paykit-only delivers exactly 41 unsigned
bytes and does not change account allocation or tracking. Initial combined setup delivers
124 unsigned bytes and creates a new account. Paykit-only reconnect delivers 41 unsigned bytes
with a nondecreasing Paykit generation; the server retains its xpub, account index, and allocation state.
Repeat reconnect with a rejected authorization and a cancelled local authentication prompt;
neither may change or unload the existing account. Watch-only requests deliver exactly 84 unsigned
bytes and no Paykit secret. Unknown, duplicate, mismatched, or wrong-sized claims must fail closed.

## Required integration fixture runtime

The journey needs a controlled integration fixture runtime. It must provide:

- A fresh Pubky testnet or isolated staging namespace reachable by both wallets.
- A Paykit Server using the same shared-runtime SDK and the
  [companion-claim contract](../../docs/pubky-auth-companion-claims.md), including a `/setup` auth
  URL whose payload requests `x-bitkit-claim=paykit-access-v1.watch-only-account-v1`.
- A regtest bitcoind and Electrum/Fulcrum endpoint on the same chain. Configure the endpoint in both
  wallets before their first launch so neither wallet retains a taller foreign regtest tip.
- A clean seller wallet, a separate clean funded buyer wallet, and the seller Pubky public key.
- A marketplace driver that can create one purchase for the buyer, expose its Payment Request id,
  report Paykit delivery, return the derived on-chain address and expected amount, mine one block,
  and report the transaction and purchase status.

Android emulators reach host services through `10.0.2.2`. When the Pubky testnet runtime advertises
its homeserver endpoints as localhost, map its TCP services into each emulator before creating a
profile:

```sh
adb -s <device> reverse tcp:6286 tcp:6286
adb -s <device> reverse tcp:6287 tcp:6287
adb -s <device> reverse tcp:6288 tcp:6288
adb -s <device> reverse tcp:15411 tcp:15411
adb -s <device> reverse tcp:15412 tcp:15412
```

After creating each wallet, choose **Create profile with Bitkit** to create a Bitkit-generated Pubky
identity in each wallet, or use a Ring identity whose root secret is available to Bitkit.

The request and endpoint must satisfy the
[issuer contract](../../docs/paykit-issuer-interoperability.md): lowercase `btc`, a
network-correct `btc-regtest-*` endpoint identifier, and a JSON endpoint payload with a non-empty
string `value`. The fixture must keep watch-only account material and spending authority separate.
Evidence must show the claimed account xpub and account index while omitting wallet seed material
and tokens.

The seller wallet authorizes the server; the buyer wallet receives and pays the request.
The buyer must save the seller before Bitkit's private-message poll can receive the request.
The seller must also save the buyer when the
fixture exercises bilateral private delivery.

## Periodic payout detection

Use separate seller and buyer devices. Before creating the purchase, return the seller to Home,
wait for any startup or foreground-triggered full-wallet sync to finish, and record its balance.
Keep the seller app active and the device awake while completing the purchase on the buyer device.
Do not background, restart, or manually refresh the seller before the payout appears. Capture
seller lifecycle and sync logs from before purchase creation through payout detection, alongside
the balance change and received activity for the fixture transaction. If the seller is resumed or
restarted during that interval, the run does not prove periodic payout detection and must be repeated.

## Evidence contract

Capture one timestamped evidence directory per run. Record the app commit, fixture runtime
revisions, both device identifiers, Payment Request id, transaction id, and regtest block height.
Keep these artifacts at each boundary:

| Boundary | Bitkit evidence | Fixture evidence |
| --- | --- | --- |
| Combined claim | `PubkyAuthWatchOnlyConsent`, `PubkyAuthPaykitAccess`, `PubkyAuthAuthorize`, and `PubkyAuthOK` snapshots | Setup completion and the claimed xpub/account index, with no spending key |
| Contact payments | `ContactPaymentsToggle` snapshots from both wallets | Public App Registry entries for both wallet identities |
| Linked buyer | `Contact_<seller-public-key>` snapshot | Seller and buyer peer-link state |
| Incoming request | `PaymentRequestsSheet` and `PaymentRequestRow-<payment-request-id>` snapshots showing seller, amount, and note when present | Delivery record and exact Payment Request id |
| Payment approval | `PaymentRequestPay-<payment-request-id>`, `ReviewAmount`, and `ReviewContactRecipient` snapshots | Derived regtest address and expected amount |
| Broadcast | `SendSuccess` snapshot and buyer activity details | Transaction in the fixture mempool with an amount-matched output |
| Confirmation | Confirmed buyer activity snapshot | Transaction id at one or more confirmations and completed purchase status |

`SendSuccess` is evidence of backend acceptance, not confirmation. The fixture's chain and purchase
status are the confirmation authority. `PaymentRequestPay-<payment-request-id>` is shared with iOS.

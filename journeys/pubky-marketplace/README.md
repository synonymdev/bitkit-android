# Pubky marketplace wallet leg

This suite covers the two-wallet Bitkit leg of a Pubky marketplace purchase: a seller grants a
watch-only account claim, a linked buyer receives the resulting Payment Request, and the buyer pays
the request on regtest through confirmation. It does not cover marketplace browsing, Locks content
delivery, fiat payment, or Hypercolor.

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
- A Paykit Server including the canonical request behavior from merged upstream
  [`pubky/paykit-server#2`](https://github.com/pubky/paykit-server/pull/2), plus a `/setup` flow whose
  auth URL carries `x-bitkit-claim=paykit-access-v1.watch-only-account-v1`.
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
The fixture must use the same shared-runtime SDK and the
[companion claim contract](../../docs/pubky-auth-companion-claims.md).

The request and endpoint must satisfy the issuer contract from Android issue
[#1208](https://github.com/synonymdev/bitkit-android/issues/1208): lowercase `btc`, a
network-correct `btc-regtest-*` endpoint identifier, and a JSON endpoint payload with a non-empty
string `value`. The fixture must keep watch-only account material and spending authority separate.
Evidence must show the claimed account xpub and account index while omitting wallet seed material
and tokens.

The pinned
[`BitcoinErrorLog/pubky-marketplace/payments-env`](https://github.com/BitcoinErrorLog/pubky-marketplace/tree/ed03a32ecfe02deab40ad10ae1bac7fa18465c10/payments-env)
runtime is a reference for the marketplace driver and Locks harness, not a shared-runtime
acceptance fixture. Use a fixture revision updated for the companion claim contract above and
record its exact revisions. The seller wallet fills the companion-auth role and the buyer wallet
fills the reader role; the other fixture roles remain unchanged.

## Required app changes

The full journey depends on the sibling work from the parent epic:

- [#1208](https://github.com/synonymdev/bitkit-android/issues/1208) defines the issuer interop
  contract.
- [#1209](https://github.com/synonymdev/bitkit-android/issues/1209) adds reason-specific parse
  diagnostics and terminal feedback when an open-time Pay retry is exhausted.
- [#1210](https://github.com/synonymdev/bitkit-android/issues/1210) owns the approved Payment Request
  intake policy. This journey does not implement or widen that policy.
- [#1211](https://github.com/synonymdev/bitkit-android/issues/1211) prevents an Electrum-rejected
  broadcast from reaching `SendSuccess`.
- [#1218](https://github.com/synonymdev/bitkit-android/issues/1218) tracks the incoming on-chain
  request swipe requirement. The behavior is supplied by merged
  [#1178](https://github.com/synonymdev/bitkit-android/pull/1178) at `9698dea4`.

The linked-contact prerequisite is existing Paykit behavior: the buyer must save the seller before
Bitkit's private-message poll can receive the request. The seller must also save the buyer when the
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
status are the confirmation authority. `PaymentRequestPay-<payment-request-id>` is shared with the
iOS counterpart supplied by [`bitkit-ios#721`](https://github.com/synonymdev/bitkit-ios/pull/721).

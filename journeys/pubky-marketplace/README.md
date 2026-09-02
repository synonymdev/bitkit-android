# Pubky marketplace wallet leg

This suite covers the two-wallet Bitkit leg of a Pubky marketplace purchase: a seller grants a
watch-only account claim, a linked buyer receives the resulting Payment Request, and the buyer pays
the request on regtest through confirmation. It does not cover marketplace browsing, Locks content
delivery, fiat payment, or Hypercolor.

## Required integration fixture runtime

The journey needs a controlled integration fixture runtime. It must provide:

- A fresh Pubky testnet or isolated staging namespace reachable by both wallets.
- A Paykit Server including the canonical request behavior from merged upstream
  [`pubky/paykit-server#2`](https://github.com/pubky/paykit-server/pull/2), plus a `/setup` flow whose
  auth URL carries `x-bitkit-claim=watch-only-account-v1`.
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

The request and endpoint must satisfy the issuer contract from Android issue
[#1208](https://github.com/synonymdev/bitkit-android/issues/1208): lowercase `btc`, a
network-correct `btc-regtest-*` endpoint identifier, and a JSON endpoint payload with a non-empty
string `value`. The fixture must keep watch-only account material and spending authority separate.
Evidence must show the claimed account xpub and account index while omitting wallet seed material
and tokens.

Use an isolated, disposable runtime that implements this contract. Paykit Server merge
`867fc883` supplies the canonical lowercase asset, network-correct endpoint identifier, JSON value
payload, and initial private-link retry behavior. Before running the wallets, verify the complete
Locks, Paykit, Pubky, bitcoind, and Fulcrum protocol path. The seller wallet fills the companion-auth
role and the buyer wallet fills the reader role; the runtime supplies the remaining services.

## Required app changes

The full journey depends on the sibling work from the parent epic:

- [#1208](https://github.com/synonymdev/bitkit-android/issues/1208) defines the issuer interop
  contract.
- [#1209](https://github.com/synonymdev/bitkit-android/issues/1209) preserves rejected incoming
  requests as visible history.
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

## Evidence contract

Capture one timestamped evidence directory per run. Record the app commit, fixture runtime
revisions, both device identifiers, Payment Request id, transaction id, and regtest block height.
Keep these artifacts at each boundary:

| Boundary | Bitkit evidence | Fixture evidence |
| --- | --- | --- |
| Watch-only claim | `PubkyAuthWatchOnlyConsent`, `PubkyAuthWatchOnlyApprove`, `PubkyAuthAuthorize`, and `PubkyAuthOK` snapshots | Setup completion and the claimed xpub/account index, with no spending key |
| Contact payments | `ContactPaymentsToggle` snapshots from both wallets | Public receiver markers for both wallet identities |
| Linked buyer | `Contact_<seller-public-key>` snapshot | Seller and buyer peer-link state |
| Incoming request | `PaymentRequestsSheet` and `PaymentRequestRow-<payment-request-id>` snapshots showing seller, amount, and note when present | Delivery record and exact Payment Request id |
| Payment approval | `PaymentRequestPay-<payment-request-id>`, `ReviewAmount`, and `ReviewContactRecipient` snapshots | Derived regtest address and expected amount |
| Broadcast | `SendSuccess` snapshot and buyer activity details | Transaction in the fixture mempool with an amount-matched output |
| Confirmation | Confirmed buyer activity snapshot | Transaction id at one or more confirmations and completed purchase status |

`SendSuccess` is evidence of backend acceptance, not confirmation. The fixture's chain and purchase
status are the confirmation authority.

## Release provenance

The watch-only claim entered the repository in `6f3134e1`, and the incoming Payment Request surface
entered in `4ea3dd16` and `7385376d`. No shipped Android release or tag contains both surfaces as of
2026-09-02. The first intended shipped release is the open `2.6.0` milestone; record its final tag
here when it ships.

## Acceptance run from 2026-09-02

The initial successful payment replay used an isolated runtime including upstream Paykit Server
merge `867fc883` and a pre-merge copy of the incoming-request swipe behavior now supplied by merged
[#1178](https://github.com/synonymdev/bitkit-android/pull/1178). The installed E2E APK had SHA-256
`cb494d870a255b96e3e289cbe7e659aa89fff1987308502b2fd55f121a0b0c42`, used the local backend at
`10.0.2.2`, and targeted homeserver
`8pinxxgqs41n4aididenw5apqp1urfmzdztr8jt4abrkdn435ewo`. A deployed seller completed the unchanged
watch-only consent, approval, authorization, companion-claim delivery, and `/setup` completion
path. The fixture verifier passed with lowercase `btc`, endpoint identifier
`btc-regtest-p2wpkh`, and a JSON string `value`.

Fresh Android buyer `pubkycecq8ssqnfgfwifioj7djoutnupmpjgomobistnz34zd9d5yyn4o` linked seller
`pubkyhbn4tahj71yzpmtarz5amtqqf5fmicdd7rs8ao448tzaujdapfiy`; both wallets saved the reciprocal
contact and enabled contact payments. The buyer received 1,000,000 sats at
`bcrt1q6mkkp26tu8zm4g78d58uksmvv3una04dryp7s0` in transaction
`3b48b259cd9aec8817b902a44c1609738268880cb16f25179ab87dea21a81a11`, confirmed at height
16,397 in block `5ad00a6d1d9e0e5a2348a255eae5bc83d507a759a4e9f377567af92fa3bacef6`.

The fixture delivered Locks bundle `1GC8SBDYB2HHA2E0NZ51ZVEZX4`, server invoice
`92fdee57-6a46-40f2-9714-8a0e68d7e60e`, Payment Request
`ad1a8463-59e0-4cf8-b037-99ffb9d5b6ca`, and event
`4282bad8-270d-4e5c-a5f9-bca52d1583dd`. Android displayed the incoming Seller row for 15,000
sats with an absent note, opened the payment review, resolved
`bcrt1qkuajc36azmaf9kk9ndwy9rdttl6vdlqwtvgyg5`, preserved the amount and Seller recipient, and
enabled the confirmation slider with a 141-sat fee.

One swipe broadcast transaction `a3e2801d8cf3afa6a461a30fa38bc46680602311a7728b97e5d88f3767f3d9d2`.
The frozen zero-confirmation boundary contained only that mempool transaction, whose output zero
paid exactly 15,000 sats to the derived address; Paykit reported
`detected/0/amount_matched=true` and Locks remained pending. The fixture then mined exactly one
block. Android synced height 16,398 and showed a confirmed Seller activity with a 15,000-sat
payment and 141-sat fee. The transaction confirmed in block
`5f2a356cace276a17e0759d8d34e7c196c852b4b299901e592552fb8f8f0bb19`, Paykit reported
`confirmed/1/amount_matched=true`, the Locks bundle completed, and the paid request remained as
history without Pay or Dismiss actions.

The selector-specific acceptance replay used Android buyer `emulator-5560` and seller iOS simulator
`B379B7A4-715A-427F-8CB6-A6479BC73050`. It ran a pre-merge integration build containing the journey
selectors and the incoming-request swipe behavior now supplied by merged #1178. The built and
device-installed APKs both had SHA-256
`8d62881da626a6f6eab1e243c05079305ebd3efd2f9abb820a1a6b55d6454cf4`. The retained Buyer profile
was synced at height 16,398 with 984,859 sats before the fixture created Locks bundle
`J9AKW3TNASDM6MJB0SNE08RJ0M`, server invoice `8d810705-6eeb-46f6-9c57-dd3bc4bdc0cf`, Payment
Request `b7f67854-b052-4eed-a6e7-e1ac41c31a7a`, event
`cec538cb-57f5-4c89-9d80-7584699af1ec`, and payment reference
`94f212c9-ed8c-4b85-9b0a-e9eea09f0a73`.

Android exposed the exact `PaymentRequestRow-b7f67854-b052-4eed-a6e7-e1ac41c31a7a` and
`PaymentRequestPay-b7f67854-b052-4eed-a6e7-e1ac41c31a7a` selectors, then preserved the 15,000-sat
amount, Seller recipient, and 141-sat fee with an enabled confirmation slider. One swipe broadcast
transaction `3dacd7591e0e9d1b7eab62f814f86017c41c1a1b8dda839a79b7244d9b20f997`, whose output zero paid
exactly 15,000 sats to `bcrt1qxluk70usejytg7ehgdhpsq657eshhmr8lmkcsv`. The frozen
zero-confirmation boundary contained that single mempool transaction. The fixture mined exactly one
block, `7cabfa65673a0472d70c0b1f217e93d6114e040a342381a446f9a50987d18f30`, at height 16,399. Paykit
reported `confirmed/1/amount_matched=true`, the Locks bundle completed, Android showed the Seller
payment as confirmed, and the paid request remained in history without Pay or Dismiss actions.

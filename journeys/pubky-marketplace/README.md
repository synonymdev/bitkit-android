# Pubky marketplace wallet leg

This suite covers the two-wallet Bitkit leg of a Pubky marketplace purchase: a seller grants a
watch-only account claim, a linked buyer receives the resulting Payment Request, and the buyer pays
the request on regtest through confirmation. It does not cover marketplace browsing, Locks content
delivery, fiat payment, or Hypercolor.

## Required external fixture

The journey needs a controlled marketplace fixture outside this repository. The fixture owns all
server-side state and must provide:

- A fresh Pubky testnet or isolated staging namespace reachable by both wallets.
- A Paykit Server with the marketplace wallet-interop fixes and a `/setup` flow whose auth URL
  carries `x-bitkit-claim=watch-only-account-v1`.
- A regtest bitcoind and Electrum/Fulcrum endpoint on the same chain. Configure the endpoint in both
  wallets before their first launch so neither wallet retains a taller foreign regtest tip.
- A clean seller wallet, a separate clean funded buyer wallet, and the seller Pubky public key.
- A marketplace driver that can create one purchase for the buyer, expose its Payment Request id,
  report Paykit delivery, return the derived on-chain address and expected amount, mine one block,
  and report the transaction and purchase status.

Android emulators reach host services through `10.0.2.2`. The reference Pubky testnet advertises
its homeserver endpoints as localhost, so map its TCP services into each emulator before creating a
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

The reference implementation is
[`BitcoinErrorLog/pubky-marketplace/payments-env`](https://github.com/BitcoinErrorLog/pubky-marketplace/tree/master/payments-env).
Fixture commit `ed03a32e` pins Paykit Server source `867fc883` and verifies the canonical request
contract. Its `scripts/verify.sh` proves the Locks, Paykit, Pubky, bitcoind, and Fulcrum protocol
path. For this journey, the seller wallet replaces `paykit-companion-auth` and the buyer wallet
replaces `paykit-reader-demo`; the other fixture roles remain unchanged.

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

The linked-contact prerequisite is existing Paykit behavior: the buyer must save the seller before
Bitkit's private-message poll can receive the request. The seller must also save the buyer when the
fixture exercises bilateral private delivery.

## Evidence contract

Capture one timestamped evidence directory per run. Record the app commit, fixture commit, both
device identifiers, Payment Request id, transaction id, and regtest block height. Keep these
artifacts at each boundary:

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

## Baseline from 2026-09-02

The current implementation was built from `423d4b2f`. A deployed seller completed the unchanged
watch-only consent, approval, authorization, companion-claim delivery, and `/setup` completion
path. Corrected fixture commit `ed03a32e` passed its canonical verifier with lowercase `btc`,
endpoint identifier `btc-regtest-p2wpkh`, and a JSON `value`. An isolated Android emulator created
buyer `pubky9s1fboi8r1ft1ecnzpik1wwkiuxmd85hzu6w3wpigmwdyry7rjxy`, linked seller
`pubkyhbn4tahj71yzpmtarz5amtqqf5fmicdd7rs8ao448tzaujdapfiy`, and confirmed reciprocal contact
rows and enabled contact payments.

The fixture delivered Locks bundle `YT3N7MNQ55PARNR6BK4H80MBDC`, Payment Request
`767ca32e-8f17-4763-9343-3b273f4fb699`, and event
`b19f4936-7b83-4a1d-a1ae-a571653a4b9e`. Android surfaced the exact incoming row with Seller and
15,000 sats, handled the absent note, and opened the payment confirmation. The app resolved
`bcrt1q8r8ryq9tv7yufr7gpszpgyw7lly7dl97przkv2`, retained the 15,000-sat amount, and showed Seller
as `ReviewContactRecipient` with a 141-sat fee.

The confirmation slider remained disabled because the incoming on-chain scan path does not persist
its successful amount validation into `isAmountInputValid`. No transaction was broadcast and the
fixture mempool remained empty. Android issue
[#1218](https://github.com/synonymdev/bitkit-android/issues/1218) blocks the broadcast and
confirmation boundaries; [fixture issue #1](https://github.com/BitcoinErrorLog/pubky-marketplace/issues/1)
records the corrected upstream contract work.

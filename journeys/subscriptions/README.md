# Subscriptions journeys

Cover the Paykit subscription lifecycle between two Bitkit instances: a creator proposes a
subscription, the payer reviews and subscribes, and either side later cancels or deletes it. The
Payments tab inside Subscriptions is covered here too, because it shares the screen's pinned chrome.

## Setup

Run Bitkit against regtest with Paykit UI enabled on two instances that have each other saved as
contacts and linked on receiver path `bitkit/wallet`. One instance plays the creator, the other the
payer. Fund the payer with enough on-chain or spending balance to cover the subscription amount when
the proposal is accepted with payment due on acceptance.

Both instances must be authenticated Pubky identities; a subscription proposal is delivered over the
same Paykit transport as a Payment Request, so the transport prerequisites in
[`../payment-requests/README.md`](../payment-requests/README.md) apply unchanged.

The journeys use a subscription named `Journey Sub` at 5,000 sats with Monthly frequency, because a
monthly cadence keeps the renewal date a full period away and avoids a renewal firing mid-run.

## Reference evidence

The source run drove these paths on 2026-09-15 across an Android emulator (creator, `to.bitkit.dev`)
and an iOS simulator (payer). The proposal created on Android arrived on iOS as **Review & Subscribe**
and landed in the creator's CREATED section as *Proposal sent*, confirming the round trip end to end.

## Identifiers used

- Screen root: `SubscriptionsScreen`
- Tabs: `Tab-overview`, `Tab-payments`
- Subscription row: `SubscriptionRow-<paymentRequestId>` (iOS appends the counterparty and receiver path, as the Payment Request rows do; see the identifier table in [`../README.md`](../README.md))
- Create entry point: `SubscriptionCreate`
- Create form: `CreateSubscription`, `SubscriptionName`, `SubscriptionDescription`,
  `SubscriptionIconPicker`, `SubscriptionExpiration`
- Recipient step: `SubscriptionChooseRecipient`, `SubscriptionPropose`
- Confirmation: `SubscriptionProposalSent`, `SubscriptionConfirmationBody`
- Payments tab: `PaymentRequestsScreen`, `PaymentRequestCreate`,
  `PaymentRequestRow-<paymentRequestId>`, `PaymentRequestDetailsScreen`

`android layout` can omit test tags applied to plain `Box` and `Column` containers. Use the raw UI
Automator hierarchy when a documented container tag is not present in the formatted layout output.

## Pinned chrome

The Subscriptions screen layers its top bar, tabs and footer button over the scrolling list with a
blur, so list rows pass underneath them rather than stopping short. A row that is partially covered by
the tab bar or the Create button is expected, not a layout fault; assert on row presence rather than
on a row being fully visible.

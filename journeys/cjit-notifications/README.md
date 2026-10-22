# Channel-ready notification journeys

One journey remains from the PR #787 (`fix/channelready-cjit`) notification work:
`non-cjit-channel-no-payment-notification.xml`. Opening a regular (non-CJIT) Lightning channel must
not be reported as a received payment, whether it is a "Payment Received" notification or a stack of
duplicates. When the app is in the foreground the only feedback is the in-app "Spending balance ready"
toast.

The folder keeps its name so the file path stays stable for the iOS twin.

## Retired journeys

`cjit-foreground-service-notification.xml` and `cjit-push-single-notification.xml` were retired because
Blocktank channel purchases, CJIT included, were removed (synonymdev/bitkit-android#1669). They paid a
CJIT order through `./lsp POST /cjit` and asserted the single, space-grouped "Received ₿ …" notification
from the foreground service and from an FCM push. Nothing in the app creates a CJIT order now, so there
is no way to drive the push.

## What the remaining journey asserts

- `WakeNodeWorker` only emits a notification when the channel actually has a Blocktank CJIT entry and the
  receive was not already recorded; a regular channel opening shows no payment notification.
- Received-payment notifications are built by one shared `ReceivedNotificationContent`: title "Payment
  Received", body "Received ₿ <amount> (<fiat>)".

## Mandatory setup

1. **Onboarded dev (regtest) wallet** with the node connected to the staging/regtest LSP and a positive
   on-chain Savings balance.
2. **Open a channel without CJIT.** Transfer To Spending on this build only opens a New Channels Unavailable sheet, so open a manual channel to
   the staging LSP node: Settings › Advanced › Lightning Connections › add connection › Manual Setup. See
   [Getting a Lightning channel](../README.md#getting-a-lightning-channel).

## Inspecting notifications (adb)

```sh
adb shell dumpsys notification --noredact | grep -A3 -i "to.bitkit.dev"
```

Look at the posted notifications' `android.title` / `android.text`. Count how many distinct
notifications Bitkit posted for a single channel opening.

## Test tags / references

- Foreground "Spending balance ready" toast: testTag `SpendingBalanceReadyToast`.
- Notification strings: `notification__received__title` ("Payment Received") and
  `notification__received__body_amount` ("Received %s").

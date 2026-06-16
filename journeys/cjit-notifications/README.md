# CJIT channel-ready notification journeys

These journeys reproduce the three notification issues raised in PR #787 (`fix/channelready-cjit`)
and verify the fixes. They exercise the `ChannelReady` event path across the two notification
producers:

- **`WakeNodeWorker`** — wakes a killed/background node from an FCM push, opens the channel, and is
  the *only* producer that should show a user-facing notification when nothing in-process is running.
- **`LightningNodeService`** (foreground service) + **`AppViewModel`** — handle the same
  `ChannelReady` event when the app/service is alive, via `NotifyChannelReadyHandler`.

## The bugs being reproduced

1. **🟢 Missing thousands separators.** `WakeNodeWorker` built the amount as `"₿ $sats"` (raw),
   so a CJIT receive showed `₿ 48064` instead of `₿ 48 064`.
2. **🟠 Double notification.** When the foreground service was running, a CJIT push produced
   *two* notifications — one from `LightningNodeService` (`Received ₿ 48 064 ($30.79)`) and one
   from `WakeNodeWorker` (`₿ 48064 / Via new channel`).
3. **🔴 Non-CJIT channel shows a payment notification (and multiplies).** Opening a regular
   (non-CJIT) channel produced one-or-more `₿ 45000 / Via new channel` notifications even though no
   payment was received.

## The fix (what the journeys assert)

- All received-payment notifications (foreground service, in-app, and the `WakeNodeWorker` push
  path, for both regular payments and CJIT receives) are built by one shared
  `ReceivedNotificationContent`, so they are identical: title "Payment Received", body
  "Received ₿ &lt;amount&gt; (&lt;fiat&gt;)" — space-grouped BTC plus fiat, ordered by the primary
  display setting (or hidden when notification details are off). The legacy bare "₿ &lt;sats&gt;" /
  "Via new channel" push format is gone. [#1]
- `WakeNodeWorker` only emits a CJIT notification when the channel actually has a Blocktank CJIT
  entry and the receive was not already recorded; a regular channel opening shows no payment
  notification. [#3]
- `WakeNodeWorker` skips its own user-facing notification when an in-process handler covers the
  event — i.e. when the app is in the foreground OR `LightningNodeService.isRunning` — leaving a
  single notification. It still wakes the node and records the activity. [#2]

## Mandatory setup

1. **Onboarded dev (regtest) wallet** with the node connected to the staging/regtest LSP.
2. **Create a CJIT entry and fund it** with the `blocktank-api` (`lsp`) skill so a real JIT channel
   opens on payment:
   - `./lsp POST /cjit '{"channelSizeSat":100000,"invoiceSat":50000,"invoiceDescription":"cjit","nodeId":"<your-node-id>","channelExpiryWeeks":1}'`
   - Pay the returned bolt11 invoice: `./lsp POST /regtest/lightning/pay '{"invoice":"<bolt11>"}'`
   - `./lsp POST /regtest/mine '{"count":3}'` if confirmation is needed.
3. **Background notifications** toggle (foreground service) is in Settings → Notifications; enabling
   it keeps `LightningNodeService` alive in the background (`LightningNodeService.isRunning == true`).

## Inspecting notifications (adb)

Use the notification shade dump to assert what was posted:

```sh
adb shell dumpsys notification --noredact | grep -A3 -i "to.bitkit.dev"
```

Look at the posted notifications' `android.title` / `android.text`. Count how many distinct
notifications Bitkit posted for a single channel opening.

## Test tags / references

- Foreground "Spending balance ready" toast: testTag `SpendingBalanceReadyToast`.
- Notification strings: `notification__received__title` ("Payment Received"),
  `notification__received__body_amount` ("Received %s"), `notification__received__body_hidden`
  (when notification details are off). Note: `notification__received__body_channel`
  ("Via new channel") is no longer used for the push notification.
- Amount grouping separator: `SATS_GROUPING_SEPARATOR` (a space) via `formatToModernDisplay`,
  applied inside `ReceivedNotificationContent`.

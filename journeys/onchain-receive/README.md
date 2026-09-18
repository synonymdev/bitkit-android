# Onchain receive journeys

These journeys cover the received sheet and notification for onchain deposits (issue #797).

ldk-node emits `OnchainTransactionReceived` when the wallet sync finds a transaction in the mempool
and `OnchainTransactionConfirmed` when it confirms. A transaction that is mined before any sync sees
it in the mempool produces only the confirmed event. Both events go through
`NotifyPaymentReceivedHandler`, from `AppViewModel` in the foreground and `LightningNodeService` in
the background.

A confirmed-only receive is shown only when its block timestamp is within one hour of the device
clock and no restore or migration is running. A full scan after a restore replays old confirmations
and stays silent; that case cannot be driven on a funded device and is covered by
`NotifyPaymentReceivedHandlerTest.kt`.

## Preconditions

- Onboarded regtest wallet with the node running. Fund and mine with the `lsp` helper at the repo root.
- Wallet sync runs every 10s. For the confirmed-only journeys, run the deposit and the mine in one
  shell command, then check the log: an `OnchainTransactionReceived` line for the txid means the
  sync saw the mempool first and the run tested the other path.
- The background journey needs background payments enabled (Settings > Notifications).
- Every event the node emits is logged by `LightningService` as `LDK event fired: <json>` under the
  `APP` logcat tag, so `adb logcat -d -s APP:V` is enough to tell the two paths apart.
- The Receive sheet's tabs carry no test tag; select the Savings tab by its label.

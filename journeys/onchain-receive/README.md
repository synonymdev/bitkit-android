# Onchain receive journeys

These journeys cover the received sheet and notification for onchain deposits (issue #797).

ldk-node emits `OnchainTransactionReceived` when the wallet sync finds a transaction in the mempool
and `OnchainTransactionConfirmed` when it confirms. A transaction that is mined before any sync sees
it in the mempool produces only the confirmed event. Both events go through
`NotifyPaymentReceivedHandler`, from `AppViewModel` in the foreground and `LightningNodeService` in
the background.

A confirmed-only receive is shown only when its block timestamp is within one hour of the device
clock and no restore or migration is running. A seed restore sets
`pendingRestoreActivitySeen` as it starts, which holds every onchain received sheet and notification until the
first onchain sync completes; that sync marks all unseen activities as seen and clears the flag, so
the transactions it discovered stay silent when they later confirm while new deposits notify again.
The same rule ships on iOS in bitkit-ios#588. A full scan after a restore also replays old
confirmations, which the one-hour window keeps silent. Because LDK events are handled concurrently, a
replayed confirmation can reach the handler after the hold is lifted, so that sync also records its
chain tip and confirmed-only receives at or below it stay silent (#1342). The restore journey needs a
throwaway emulator, since it wipes the app and restores a public test seed.

## Preconditions

- Onboarded regtest wallet with the node running. Fund and mine with the `lsp` helper at the repo root.
- Wallet sync runs every 10s. For the confirmed-only journeys, run the deposit and the mine in one
  shell command, then check the log: an `OnchainTransactionReceived` line for the txid means the
  sync saw the mempool first and the run tested the other path.
- The background journey needs background payments enabled (Settings > Notifications).
- Every event the node emits is logged by `LightningService` as `LDK event fired: <json>` under the
  `APP` logcat tag, so `adb logcat -d -s APP:V` is enough to tell the two paths apart.
- The Receive sheet's tabs carry no test tag; select the Savings tab by its label.

# Backup and restore journeys

These journeys exercise the VSS restore path: what a wallet gets back when it is restored from its
recovery phrase, and what the app is allowed to upload while that restore is still running.

## What the behaviour is

- The **activity backup envelope carries three Core-owned slices** — activities, activity tags and
  closed channels. Each is applied on its own, so one rejected record costs its slice and nothing
  else. The restore logs what it applied:
  `Restored 3 activities, 1 activity tags, 1 closed channels`.
- **Ordinary uploads are gated from the moment a restore starts**, not from the moment the restore
  reads the backup. The node starts and syncs long before the envelope is fetched, and the activity
  sync that follows used to upload the fresh wallet's state over the stored envelope — three
  `Backup succeeded for: 'ACTIVITY'` lines before `Full restore starting`, and a restored wallet
  without its tag and without its closed connection.
- The gate expires on its own and is held in memory only, so a restore that never returns, or an app
  killed mid-restore, cannot suppress backups for good.

## Mandatory setup

1. **Use a throwaway dev wallet.** The journey resets the wallet half way through; anything left in
   it is gone.
2. **Record the recovery phrase before funding anything.** Settings ▸ Security ▸ Back Up Your Money.
   Without it there is no second half of the journey.
3. **Fund through the staging LSP**, as the top-level README describes:
   `./lsp POST /regtest/chain/deposit '{"address":"<savings addr>","amountSat":500000}'` then
   `./lsp POST /regtest/chain/mine '{"count":3}'`. Give the wallet ~20s to sync.
4. **The channel needs the node connected to the LSP.** On the spending amount screen the max starts
   at `0` behind a spinner — wait for it to populate before entering an amount.

## Gotchas

- **Run the reset and restore twice.** The first restore passed even before the upload gate existed;
  the second is where the race was lost, because by then the wallet has a synced activity list to
  upload over the backup.
- **The Data Backups rows carry no test tags.** `BackupScrollView` is the list, and the "Transaction
  Log" row has to be read by its text and its relative time.
- **Wait for the Transaction Log to report a recent backup before resetting.** Resetting while it is
  still pending tests nothing: there is no complete envelope to restore.
- Reading the log is the cheapest confirmation of what really happened:
  `adb shell "run-as to.bitkit.dev ls -la files/logs/"`, then `cat` the newest file and look for
  `Full restore starting`, `Restored N activities, …` and any `Backup succeeded for: 'ACTIVITY'`.

## Test tags used

- Settings tabs `Tab-security` / `Tab-advanced`; rows `BackupWallet`, `BackupSettings`,
  `ResetAndRestore`, `Channels`.
- Backup flow: `BackupIntroViewContinue`, `TapToReveal`, `SeedContainer`; data backups list
  `BackupScrollView`.
- Reset: `restore_reset_button`, `restore_reset_dialog`.
- Restore: `RestoreWallet`, `Word-<index>`, `RestoreButton`, `GetStartedButton`.
- Receive: `Receive`, `ShowDetails`, `ReceiveCopyQR`.
- Activity: `ActivityShort-<index>`, `ActivityTag`, `ActivityTags`, `AddTagInput`, `AddTagSave`.
- Transfer: `ActivitySavings`, `TransferToSpending`, `TransferIntro-button`, `FundTransfer`,
  `SpendingIntro-button`, `SpendingAmount`, `SpendingAmountContinue`, `GRAB`,
  `TransferSuccess-button`.
- Lightning connections: `Channel`, `CloseConnection`, `CloseConnectionButton`, `ChannelsClosed`.

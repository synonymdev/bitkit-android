# Backup

Recovery-phrase backup flow, automatic encrypted data backups to VSS, the Data Backups status screen, and reset-and-restore.

## What it does
- Backup sheet (`Sheet.Backup`): shows the recovery phrase behind a reveal, asks the user to rebuild it from shuffled words, then a warning, success, multiple-devices and "latest backup" page. With a BIP39 passphrase it also shows and re-types the passphrase. Success sets `backupVerified = true` (`BackupNavSheetViewModel.onSuccessContinue`).
- `BackupRepo` uploads app data to VSS in categories (`models/BackupCategory.kt`): `LIGHTNING_CONNECTIONS` (display only, ldk-node manages its own), `BLOCKTANK`, `ACTIVITY`, `WALLET`, `SETTINGS`, `WIDGETS`, `METADATA` (tags etc.). Data changes mark a category required, a 5 s debounce schedules the upload, status lives in `CacheStore.backupStatuses`.
- Full restore (`performFullRestoreFromLatestBackup`) applies METADATA, SETTINGS, WIDGETS, WALLET, BLOCKTANK, ACTIVITY in that order, then sets `backupVerified = true`. The activity envelope carries activities, activity tags and closed channels, each applied on its own (`journeys/backup-restore/README.md`).
- Uploads are skipped while restoring, wiping or restore-pending (`shouldSkipBackup`). The pending gate starts at restore start, expires after 10 minutes and is memory only.
- A category overdue by 30 minutes shows an error toast, checked every minute, repeated at most every 10 minutes.
- Reset wallet wipes keychain, databases and stores (`WipeWalletUseCase`), then the app returns to onboarding (`onboarding.md`).
- A timed backup prompt (`BackupTimedSheet`) shows when `backupVerified` is false, balance > 0 and the last dismissal is older than one day (`docs/timed-sheets.md`).

## How a user reaches it
- `Settings > Security` tab (`HeaderMenu` -> `DrawerSettings` -> `Tab-security`), section "Back up or reset":
  - `BackupWallet` ("Back up your wallet") opens `Sheet.Backup()` whose default route is `ShowMnemonic`, not the intro. Pages: `TapToReveal` -> `ContinueShowMnemonic` -> [`ShowPassphrase`] -> confirm (`Word-<word>` chips, `SelectedWord-<n>`, `ContinueConfirmMnemonic`) -> [passphrase confirm] -> Warning (`OK`) -> Success (`OK`) -> MultipleDevices (`OK`) -> Metadata (`OK`).
  - `BackupSettings` ("Data Backups") opens `BackupSettingsScreen` (`BackupScrollView`).
  - `ResetAndRestore` opens `ResetAndRestoreScreen`: `restore_backup_button` (opens the backup sheet), `restore_reset_button`, then `restore_reset_dialog` with `DialogConfirm`.
- The home suggestion card `Suggestion-back_up` and the timed sheet open the sheet at the intro (`BackupIntroView`, `BackupIntroViewContinue`, `BackupIntroViewCancel`).
- Dev builds with dev mode: `bitkit://screen/backup` (intro), `bitkit://screen/backup/multiple-devices`, `bitkit://screen/backup/metadata`, `bitkit://screen/backup-settings`, `bitkit://screen/reset-and-restore-settings` (`ScreenDeepLinkRuntime` debug source set; release builds ignore them).
- Dev tools: `VssDebugScreen` (`Routes.VssDebug`, Settings > Advanced > Dev Settings > VSS).

## Code
- `app/src/main/java/to/bitkit/ui/sheets/BackupSheet.kt`: sheet nav host, `BackupRoute`. `ui/settings/backups/BackupNavSheetViewModel.kt`: flow state, `loadMnemonicData` from keychain.
- `ui/settings/backups/`: `BackupIntroScreen`, `ShowMnemonicScreen` (tap on revealed words copies them), `ShowPassphraseScreen`, `ConfirmMnemonicScreen`, `ConfirmPassphraseScreen`, `WarningScreen`, `SuccessScreen`, `MultipleDevicesScreen`, `MetadataScreen`, `ResetAndRestoreScreen`.
- `ui/settings/BackupSettingsScreen.kt` + `viewmodels/BackupsViewModel.kt`: per-category status rows, retry button, `observeAndSyncBackups` (observes while the node runs).
- `repositories/BackupRepo.kt`: observers, scheduling, `triggerBackup`, full restore, `setRestorePending`, `setWiping`. `data/backup/VssBackupClient.kt`, `VssBackupClientLdk.kt`: VSS access.
- `usecases/WipeWalletUseCase.kt`: ordered wipe; `WipeIncomplete` when the keychain wipe fails. `utils/timedsheets/sheets/BackupTimedSheet.kt`: prompt rules.
- `ui/settings/SettingsScreen.kt`: the three rows. `ui/shared/effects/BlockScreenshots.kt`: `FLAG_SECURE` on the phrase screens.

## How to drive it
- Journeys:
  - `journeys/backup/confirm-mnemonic-clear-wrong-word.xml`: wrong word turns red, can be cleared by its slot or chip, correct words lock, `ContinueConfirmMnemonic` enables at 12/12. Throwaway wallet, no passphrase, stops before Continue.
  - `journeys/backup/show-mnemonic-long-words.xml`: restores a public test phrase, sets `font_scale` 1.3, checks 12 words render one line each.
  - `journeys/backup-restore/restore-keeps-tags-and-closed-channels.xml` (+ `README.md`): fund 500000 sat with `./lsp POST /regtest/chain/deposit` and `mine`, tag it, open and close a channel, wait for Transaction Log backup, reset, restore twice, read the app log. Needs staging LSP and a throwaway wallet.
- E2E: `bitkit-e2e-tests/test/specs/backup.e2e.ts` `@backup_1` (shard `onboarding_backup_numberpad`): fund 1 BTC, tag, currency GBP, add price widget, `getSeed`, `waitForBackup` (needs `AllSynced`), `restoreWallet`, then checks `£`, `PriceWidget`, `Tag-testtag-delete`.
- Also: `settings.e2e.ts` `@settings_07` runs the confirm flow and four `OK` taps (shard `settings`); `lightning.e2e.ts` `@lightning_1` and `boost.e2e.ts` back up then restore; `migration.e2e.ts` opens `BackupSettings` to check statuses.
- Instrumented Compose tests: `app/src/androidTest/java/to/bitkit/ui/settings/backups/ConfirmMnemonicScreenTest.kt`, `BackupIntroScreenTest.kt`.
- Preconditions: funded wallet for anything beyond the phrase pages, local `bitkit-docker` for specs; staging LSP for journeys; wait for backups before resetting.

## What proves it
- Phrase flow: the `OK` pages end and Home shows; `backupVerified` removes the `Suggestion-back_up` card and the timed prompt.
- Data backups: each row says success with a relative time; with an E2E build `AllSynced` is shown when no category is required.
- Restore: activities, tag (`ActivityTags`), closed channel under `ChannelsClosed`; log lines `Full restore starting`, `Restored 3 activities, 1 activity tags, 1 closed channels`, and no `Backup succeeded for: 'ACTIVITY'` before the restore start.
- Reset: onboarding `Continue` / `TOS` visible.

## Not covered by tests
- Passphrase pages (`backup_show_passphrase_screen`, `backup_confirm_passphrase_screen`) have no journey or spec; `@onboarding_2` only reads the seed and swipes the sheet away.
- Backup intro cancel/Later, timed backup prompt rules, `restore_backup_button`, the copy-on-tap toast, back navigation inside the sheet.
- `BackupSettings` retry button (no testTag), failure state, the 30 minute failure toast, VSS unreachable, `LIGHTNING_CONNECTIONS` row.
- Wipe failure (`WipeIncomplete`), wipe while a backup runs, reset with an open channel.
- Restore from a legacy envelope (migrated by `BackupRepo.migrateCoreOwnedBackupFields`) is unit-tested only (`app/src/test/java/to/bitkit/repositories/BackupRepoTest.kt`).

## Gotchas
- Journey `restore-keeps-tags-and-closed-channels.xml` step 3 says to continue past `BackupIntroViewContinue` after `BackupWallet`; the Settings row skips the intro in this code. It calls the row "Back Up Your Money"; the string is "Back up your wallet".
- `show-mnemonic-long-words.xml` calls `SeedContainer` a reveal overlay over `backup_mnemonic_words_box`; in this code `SeedContainer` is the word grid inside that box, hidden from accessibility until revealed (`changelog.d/next/1327.fixed.md`). Its text holds the phrase: never log it.
- Confirm chips use `Word-<word>`; a phrase with a repeated word yields several elements with one tag (`settings.e2e.ts` picks by index).
- `BlockScreenshots` is a no-op in debug builds, so screenshots are only black on non-debug builds.
- Data Backups rows have no testTags; read "Transaction Log" by text. `AllSynced` exists only when `Env.isE2eTest`.
- Run the reset and restore twice in the backup-restore journey: the second restore is where the upload race was lost.

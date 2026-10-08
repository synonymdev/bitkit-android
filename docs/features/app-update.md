# App update and migration

Release-feed checks (blocking critical update screen, optional update sheet) and the one-time upgrade of an old React Native Bitkit wallet to this native app.

## What it does
- `AppUpdaterService.getReleaseInfo` GETs `Env.RELEASE_URL` (`releases/download/updater/release.json` of `bitkit-android`, or of `bitkit-e2e-tests` when `Env.isE2eTest`) and strictly decodes `ReleaseInfoDTO` (`platforms.android`: `version`, `buildNumber`, `notes`, `pub_date`, `url`, `critical`; a missing or extra field throws).
- Critical update: `AppViewModel.checkCriticalAppUpdate` runs once at view model creation. It returns early when `Env.isDebug`. If `critical` and `buildNumber > BuildConfig.VERSION_CODE` it sets `isCriticalUpdateRequired`, hides the new-transaction sheet and `showTransactionSheet` stays blocked. `RootDestination` then shows `CriticalUpdateScreen` before migration, onboarding and wallet. Fetch or decode failures only log "Failure fetching new releases".
- Optional update: `AppUpdateTimedSheet` (priority 5, highest) is eligible when the feed has a newer non-critical build; `UpdateSheet` opens the Play Store listing, Cancel dismisses with no stored state (`docs/timed-sheets.md`). It has no debug-build check.
- RN -> native migration: `WalletViewModel.checkAndPerformRNMigration` runs at view model creation. It skips when migration was already checked, when native wallet data exists, or when no RN data exists (`MigrationService.hasRNWalletData`: RN keychain mnemonic, MMKV file or LDK `channel_manager.bin`). Otherwise `MigrationLoadingScreen` shows while `MigrationService.migrateFromReactNative` moves the mnemonic (BIP39-validated), passphrase, PIN, LDK channel manager and monitors, and MMKV data (activities, closed channels, settings, address types, metadata, widgets, todos, Blocktank orders). The node then starts with the migrated channel data and a post-migration sync is flagged.
- Failures: unreadable mnemonic or any thrown step ends the loader and toasts "Migration Failed ... restore your wallet manually using your recovery phrase". Offline: the loader is dismissed and the wallet start waits (`pendingWalletStart`). The loader is also dismissed after 120 s (`MIGRATION_LOADING_TIMEOUT_MS` in `AppViewModel`); if a post-migration sync is still needed a "network required" toast shows.
- Restore from phrase also checks the RN remote backup: `WalletViewModel.restoreFromMostRecentBackup` compares `RNBackupClient` and VSS timestamps, restores the newer, and falls back to VSS if the RN restore fails (`onboarding.md`, `backup.md`).
- Native -> native upgrades have no migration step in the app; the e2e suite installs the new APK over the old one.
- `LegacyRnRecoveryScreen`: dev tool that scans for native-SegWit outputs left by the legacy RN channel-close path up to an index limit (default `10000`), prepares a sweep and broadcasts it (`WalletRepo.scanLegacyRnNativeSegwitRecoveryFunds`, `prepareLegacyRnNativeSegwitRecoverySweep`, `broadcastLegacyRnNativeSegwitRecoverySweep`).

## How a user reaches it
- Critical update: launch a non-debug build whose feed marks a higher `buildNumber` as `critical`. Single button "Update Bitkit"; back leaves the app. No testTags (journey asserts texts "Critical Update", "UPDATE", "BITKIT NOW").
- Update sheet: Home resumed for 2 s with a newer non-critical feed entry; sheet tag `AppUpdateSheet` (`AppUpdateSheetContinue`, `AppUpdateSheetCancel`).
- Migration: install this app over an RN Bitkit install and launch; the "MIGRATING WALLET" screen (text `migration__title`, no testTags) shows without user action.
- Legacy recovery: `Settings > Advanced` tab -> `DevSettings` (dev mode; five taps on `DevOptions` on the Support screen toggles it) -> button "Legacy Close Recovery" (no testTag) -> `Routes.LegacyRnRecovery`. No deeplink (`Routes.InternalOnly`).

## Code
- `app/src/main/java/to/bitkit/services/AppUpdaterService.kt`, `data/dto/AppUpdaterDTO.kt`, `env/Env.kt` (`RELEASE_URL`, `PLAY_STORE_URL`): feed access.
- `viewmodels/AppViewModel.kt`: `checkCriticalAppUpdate`, `isCriticalUpdateRequired`, migration-loader timeout. `ui/MainActivity.kt`: `RootDestination`.
- `ui/screens/CriticalUpdateScreen.kt`, `ui/screens/MigrationLoadingScreen.kt`, `ui/sheets/UpdateSheet.kt`, `utils/timedsheets/sheets/AppUpdateTimedSheet.kt`, `ui/components/SheetHost.kt` (`TimedSheetType.APP_UPDATE`).
- `services/MigrationService.kt`: RN detection, keychain decryption, LDK and MMKV import, post-migration sync, RN remote backup restore. `services/RNBackupClient.kt`, `services/MmkvParser.kt`.
- `viewmodels/WalletViewModel.kt`: `checkAndPerformRNMigration`, `restoreFromMostRecentBackup`, `buildChannelMigrationIfAvailable`.
- `ui/screens/settings/LegacyRnRecoveryScreen.kt` + `viewmodels/DevSettingsViewModel.kt` (`LegacyRnRecoveryUiState`): scan, prepare, broadcast; `ui/screens/settings/DevSettingsScreen.kt` (entry row).

## How to drive it
- Journey `journeys/app-update/critical-update-onboarding.xml`: with a critical feed the screen blocks the app before a wallet exists, back closes the app, "Update Bitkit" opens the Play Store. It cannot run on a stock build: it needs a non-debug build without `E2E`, a lowered `versionCode` (now `190` in `app/build.gradle.kts`) and a complete HTTPS feed fixture; report "not run" if the gate fails. Its code line references (`AppViewModel.kt:5598-5610`, `AppUpdaterService.kt:25`) are stale; the function is now near line 6359.
- E2E `bitkit-e2e-tests/test/specs/migration.e2e.ts`, workflow `.github/workflows/e2e_migration.yml` (nightly 02:00 cron, manual dispatch, PRs from or to `release-*` branches), not in `e2e.yml` shards. Backend is remote regtest. Procedure and baselines: `bitkit-e2e-tests/docs/migration-tests.md`.
  - Routine: `@migration_rn_restore` (RN 1.1.6 wallet, uninstall, install native, restore phrase), `@migration_rn_upgrade` (install native over RN, expects text `MIGRATING`), `@migration_native_restore`, `@migration_native_upgrade` (previous native release, pinned in `config/migration-baselines.json`). All check savings and spending balances, activity, tags, relaunch; native cases also receive a 1000 sat Lightning payment.
  - Extended (`extended_rn` input or `MIGRATION_EXTENDED=true`): `@migration_3` (RN with passphrase), `@migration_4` (RN legacy p2pkh funds).
  - Setup only (`MIGRATION_SETUP_WALLET` set, Android): `@migration_setup_standard`, `@migration_setup_passphrase`, `@migration_setup_sweep` prepare RN wallets for iOS runs.
- Preconditions: RN APK `v1.1.6` and previous native APK downloaded with `scripts/download-migration-app.py`, regtest funds via the spec helpers, a dedicated emulator.
- Unit tests: `app/src/test/java/to/bitkit/services/MigrationServiceTest.kt`, `utils/timedsheets/sheets/AppUpdateTimedSheetTest.kt`; routing: `app/src/androidTest/java/to/bitkit/ui/RootDestinationTest.kt`.

## What proves it
- Critical update: texts "Critical Update", "UPDATE", "BITKIT NOW", "Update Bitkit" visible; `TOS` not visible; back exits to the launcher.
- Migration: `MIGRATING` text, then Home with the same total, spending and savings balances (`expectMigrationBalances`), the same activity rows and tags, still true after `terminateApp` and `activateApp`.
- Update sheet: `AppUpdateSheet` visible; Play Store opens on Update.

## Not covered by tests
- `UpdateSheet` and `AppUpdateTimedSheet` have no journey or e2e step (unit test only); no spec asserts `AppUpdateSheet`.
- The critical screen on Home (wallet exists) and a transaction arriving while it shows; only the onboarding case has a journey, and it is not runnable on stock builds.
- Migration failure paths: invalid RN mnemonic, offline launch (`pendingWalletStart`), 120 s loader timeout, "Migration Failed" toast, "network required" toast.
- RN wallets with a PIN, custom widgets, or a closed channel: `setupLegacyWallet` only receives and tags, sends and tags, and transfers to spending.
- RN remote backup newer than VSS during phrase restore: balances are asserted, the chosen source is not.
- `LegacyRnRecoveryScreen`: no journey, spec or testTags.

## Gotchas
- `handleMigrationFlow` in `migration.e2e.ts` can tap `SweepButton` and `SweepToWalletButton`; these tags are not in the app, and every call passes `withSweep: false`.
- Debug builds never raise the critical flag; `E2E` builds read the e2e feed, which per the journey lists `buildNumber: 0` and `critical: false`.
- `isCriticalUpdateRequired` is never reset in code during a session; only a restart with a fixed feed or newer build clears it.
- `@migration_rn_restore` on iOS needs `RN_MNEMONIC` and `RN_BALANCE` from an Android setup run (not relevant to the Android app).
- The migration workflow runs nightly, manually and on release PRs, not on ordinary PRs.

# Onboarding

First-run flow from splash to a created or restored wallet: terms, intro slides, new wallet (optionally with a BIP39 passphrase), restore from a recovery phrase, and the multiple-devices warning.

## What it does
- The splash covers the window until `AppViewModel.splashVisible` clears, then the root screen is chosen by `RootDestination` in `app/src/main/java/to/bitkit/ui/MainActivity.kt`: critical update, then migration loader, then onboarding when no wallet exists and recovery mode is off, else the wallet.
- Onboarding is its own `NavHost` (`OnboardingNav` in `MainActivity.kt`): Terms, Intro, Slides, then New wallet, Advanced (passphrase) or Warning multiple devices followed by Restore.
- New wallet: `WalletViewModel.createWallet` generates the mnemonic, stores it (and the passphrase, if given) in the keychain and schedules a full backup (`BackupRepo.scheduleFullBackup`).
- Restore: 12 or 24 words, per-word BIP39 validation, up to 3 suggestions after 2 typed characters, checksum check, optional passphrase, paste spreading across fields, SeedQR scan (`SeedQrRepo`). Restore then reads the latest backup (VSS, or the RN remote backup when it is newer) while `InitializingWalletView` runs, and ends on `WalletRestoreSuccessView` or `WalletRestoreErrorView`.
- PIN, biometrics and screenshot blocking are in `security.md`; the backup data being restored is in `backup.md`.

## How a user reaches it
- Fresh install or after a wipe. Route order: `Terms` (`TOS`, `Check1`, `Check2`, `Continue`) -> `Intro` (`GetStarted` or `SkipIntro`) -> `Slides` (`Slide0`..`Slide3`; `SkipButton` jumps to the last; `Passphrase` button on the last slide).
- New wallet: last slide `NewWallet` (`Slide3`). With passphrase: `Passphrase` -> `PassphraseInput` -> `CreateNewWallet` (disabled while the input is blank).
- Restore: last slide `RestoreWallet` -> `WarningMultipleDevices` (`MultipleDevices`, confirm with `MultipleDevices-button`) -> `Word-0`..`Word-11` (24 on paste or when a word is added past 12) -> optional `AdvancedButton` -> `PassphraseInput` -> `RestoreButton`.
- Creation shows "Setting up your wallet" (e2e waits for text `SETTING UP\nYOUR WALLET` to vanish). Restore ends on `GetStartedButton`; failure shows `TryAgainButton`, and from the second failure `ProceedWithoutBackupButton` with `ProceedWithoutBackupDialog` (confirm tag `DialogConfirm`).
- No deeplink reaches onboarding. After a wipe (`Settings > Security > ResetAndRestore`, PIN lockout) the app returns here.

## Code
- `app/src/main/java/to/bitkit/ui/MainActivity.kt`: `RootDestination`, `OnboardingNav`, `StartupRoutes`.
- `ui/screens/SplashScreen.kt`: splash overlay. `ui/onboarding/TermsOfUseScreen.kt` + `TOS.kt`: terms text. `IntroScreen.kt`: Get started / Skip.
- `ui/onboarding/OnboardingSlidesScreen.kt`: pager with 3 info slides plus `CreateWalletScreen.kt` as the last page (`NewWallet`, `RestoreWallet`). Slide 1 shows a geo-block note when `isGeoBlocked`.
- `ui/onboarding/CreateWalletWithPassphraseScreen.kt`: passphrase create (calls `BlockScreenshots`).
- `ui/onboarding/WarningMultipleDevicesScreen.kt`: warning before restore.
- `ui/onboarding/RestoreWalletScreen.kt` + `viewmodels/RestoreWalletViewModel.kt`: word fields, paste handling (`isPastedInput`, `insertedText`), validation, scan icon (`NavigationAction`) -> `QrScanningScreen`. `repositories/SeedQrRepo.kt` decodes standard (48 digits) and compact (16 bytes) SeedQR. `services/core/Bip39Service.kt` validates words and checksum.
- `viewmodels/WalletViewModel.kt`: `createWallet`, `restoreWallet`, `RestoreState`, `restoreFromBackup`, `onBackupRestoreRetry`, `onProceedWithoutRestore`. `repositories/WalletRepo.kt`: `createWallet`, `restoreWallet` (write keychain, clear recovery mode).
- `ui/ContentView.kt`: after the wallet exists it renders `InitializingWalletView` (2 s minimum, 8 s when restoring), `WalletRestoreErrorView`, `WalletRestoreSuccessView` in `ui/onboarding/`.
- `repositories/BackupRepo.kt`: `performFullRestoreFromLatestBackup`, restore-pending upload gate (see `backup.md`).

## How to drive it
- Journeys:
  - `journeys/restore-wallet/paste-seed-fragment.xml`: pasting 3 words and then 9 words spreads them over `Word-*` fields, Backspace edits the pasted word, `RestoreButton` enables. Needs a wallet-less emulator and host clipboard (`pbcopy`, `adb shell input keyevent 279`).
  - `journeys/backup/show-mnemonic-long-words.xml` also restores a fixed public test phrase through this flow (steps 1-8); mapped in `backup.md`.
- E2E (`bitkit-e2e-tests/test/specs/onboarding.e2e.ts`, tag `@onboarding`, CI shard `onboarding_backup_numberpad` in `.github/workflows/e2e.yml`, local `bitkit-docker` backend):
  - `@onboarding_1`: Terms, `GetStarted`, swipe through `Slide0`..`Slide2`, `SkipButton`, `NewWallet`, expects text `TO GET`.
  - `@onboarding_2`: create with passphrase `supersecret`, read seed (`getSeed`), restore with passphrase (`restoreWallet`), compares receive address and `Address-0`/`Address-1` in Address Viewer.
- Helpers: `completeOnboarding` and `restoreWallet` in `bitkit-e2e-tests/test/helpers/actions.ts` (restore types the whole phrase into `Word-0`, then optional `AdvancedButton`).
- Restore also runs in `@lightning_1` (`lightning.e2e.ts`), `@boost_1` and `@boost_2` (`boost.e2e.ts`), `@backup_1`, and the migration specs (`app-update.md`).
- Instrumented Compose tests: `app/src/androidTest/java/to/bitkit/ui/onboarding/MnemonicInputFieldTest.kt`, `InitializingWalletViewTest.kt`; unit tests `viewmodels/RestoreWalletViewModelTest.kt`, `RestoreWalletSeedQrViewModelTest.kt`.
- Preconditions: no wallet (`reinstallApp` or `adb shell pm clear to.bitkit.dev`). Restore needs the recovery phrase; for backup content to come back the source wallet must have finished a backup (`backup.md`).

## What proves it
- New wallet: Home with `TotalBalance` / `TotalBalance-primary` visible.
- Restore: `GetStartedButton` appears, then Home; addresses match the original (`@onboarding_2`), balances, tags, widgets, settings return (`@backup_1`).
- Failure path: `TryAgainButton`; `ProceedWithoutBackupButton` only after more than one failure (`retryCount > 1`).

## Not covered by tests
- Terms: the privacy-policy link in `Check2`; `Check1`/`Check2` are text blocks, not toggles, and `Continue` is always enabled.
- Slide 1 geo-block note; swiping back; `Passphrase` create with an empty input (button disabled).
- Restore: invalid word (red field, explain text), checksum error text, suggestions row, 24-word layout, SeedQR scan (standard and compact), toasts for invalid SeedQR, passphrase toggle off (`AdvancedButton` clears the input).
- Restore failure and retry (`WalletRestoreErrorView`), proceed-without-backup, and a restore whose backup is empty. `show-mnemonic-long-words.xml` only describes the failure screen as a conditional branch.
- Restore with an RN remote backup newer than VSS (`WalletViewModel.restoreFromMostRecentBackup` picks the newer); no spec asserts which source was used.
- Create-wallet failure toast; no-network behaviour during creation or restore.

## Gotchas
- `StartupRoutes.LAST_SLIDE_INDEX` is 4 but the pager has 4 pages (last index 3, `OnboardingSlidesScreen`); `SkipIntro` still lands on the last slide in e2e helpers.
- `SkipButton` is reused: top-bar skip on slides and the no-biometrics skip in the PIN sheet. `PassphraseInput` exists on both the create-with-passphrase and restore screens. `Word-<n>` is the restore field; the backup confirm chips use `Word-<word>` (`backup.md`).
- `Env.isDebug` builds do not block screenshots; `BlockScreenshots` (restore, passphrase create) returns early in debug.
- Pasted whitespace in a word field is spread across fields; a typed space is dropped. Never type a long phrase with `adb shell input text`: characters are dropped (`journeys/README.md`). The e2e helper types it into `Word-0` anyway.
- Restore holds ordinary backup uploads from the moment it starts (`BackupRepo.setRestorePending`, 10 minute expiry) so the fresh wallet does not overwrite the backup.
- Recovery mode (`RecoveryModeScreen`, `RecoveryMnemonicScreen`) is not part of restore: it is a support screen opened by `bitkit://recovery-mode`, documented in `security.md`.

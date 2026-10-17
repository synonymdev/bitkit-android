# Security

App PIN, biometrics, PIN on payments, lock on background, privacy toggles, wallet wipe, and the recovery-mode screens.

## What it does
- PIN: 4 digits (`Env.PIN_LENGTH`), 8 attempts (`Env.PIN_ATTEMPTS`). The PIN and the remaining attempts are stored in the keychain (`Keychain.Key.PIN`, `PIN_ATTEMPTS_REMAINING`). `AppViewModel.validatePin` resets the counter on success; the 8th wrong PIN shows a toast and wipes the wallet (`MainScreenEffect.WipeWallet` -> `WalletViewModel.wipeWallet`).
- Lock: with a PIN set, `MainActivity.onStop` calls `AppViewModel.lockOnBackground` (skipped for configuration changes, when no wallet exists, and in recovery mode). `MainActivity` then draws `AuthCheckView` over the wallet until `setIsAuthenticated(true)`. No grace period. Scans and payment URIs received while locked are queued and run after unlock.
- Biometrics: when enabled and supported the lock shows `BiometricsView` first; the PIN pad has a "use biometrics" button. Failure falls back to the PIN pad.
- PIN for payments (`isPinForPaymentsEnabled`): on Send confirm the swipe asks for biometrics (if enabled and supported) or `SendPinCheckScreen` before paying.
- Disabling the PIN (`removePin`) also clears PIN-for-payments and biometrics (`SettingsData.resetPin`) and resets the attempt counter.
- Forgot PIN: tapping the attempts text opens `ForgotPinSheet`, whose button wipes the wallet.
- Privacy toggles in the same tab: swipe-to-hide balance, hide balance on open, auto-read clipboard, send amount warning.
- Recovery mode: `bitkit://recovery-mode` sets `LightningRepo` recovery mode (the node does not start, `RecoveryModeError`) and opens `RecoveryModeScreen`: export logs, display seed, contact support, reset network graph (relaunches the app), wipe app. Display seed and wipe ask for the PIN first when one is set. Leaving the screen clears recovery mode.
- Wipe itself (`WipeWalletUseCase`) is described in `backup.md`; after it the app shows onboarding (`onboarding.md`).

## How a user reaches it
- `HeaderMenu` -> `DrawerSettings` -> `Tab-security`, section "Safety": `PINCode` row (value Enabled/Disabled) opens `PinManagementScreen`.
  - Disabled: `EnablePin` -> `Sheet.Pin(PinRoute.Choose)` -> enter PIN -> re-enter (`WrongPIN` on mismatch) -> biometrics page (`ToggleBiometrics` + `ContinueButton`, or when unsupported `SkipButton`) -> "Wallet Secured" result (`ToggleBioForPayments`, `OK`).
  - Enabled: `ChangePIN` (sheet: current PIN, new PIN, retype, `OK`) or `DisablePin` (sheet `DisablePIN`, enter PIN).
  - Rows shown only with a PIN: `EnablePinForPayments`, and `UseBiometryInstead` when the device supports biometrics. Both go through `Routes.AuthCheck` (`AuthCheckScreen`) before toggling.
  - Other rows: `SendAmountWarning`, `SwipeBalanceToHide`, `HideBalanceOnOpen`, `AutoReadClipboard`.
- Home suggestion card `Suggestion-secure` opens `Sheet.Pin(PinRoute.Prompt(showLaterButton = true))` (`SecureWallet`, `SecureWalletContinue`, `SecureWalletCancel`).
- Lock screen: `PinPad`, numbers `N0`..`N9`, `NRemove`; `AttemptsRemaining` / `LastAttempt` appear after a wrong PIN.
- Send with PIN for payments: Send sheet confirm -> swipe `GRAB` -> text "Enter PIN Code" (`SendRoute.PinCheck`).
- Hide balance: drag `TotalBalance` on Home; `ShowBalance` appears; first hide shows toast `BalanceHiddenToast`.
- Recovery mode: `adb shell am start -a android.intent.action.VIEW -d "bitkit://recovery-mode" to.bitkit.dev` (handled in `AppViewModel.processDeeplink`, not behind dev mode).
- Deeplink (dev builds with dev mode): `bitkit://screen/pin-management`.

## Code
- `app/src/main/java/to/bitkit/viewmodels/AppViewModel.kt` (region security): `validatePin`, `addPin`, `editPin`, `removePin`, `lockOnBackground`, `resetIsAuthenticatedState`, deferred scans. `viewmodels/SettingsViewModel.kt`: toggles, `toggleHideBalanceFromSwipe`.
- `ui/MainActivity.kt`: lock overlay, `ForgotPinSheet` host, `onStop`. `ui/components/AuthCheckView.kt` (PIN pad, biometrics switch), `AuthCheckScreen.kt` (route `Routes.AuthCheck`, actions `TOGGLE_BIOMETRICS`, `TOGGLE_PIN_FOR_PAYMENTS`, `DISABLE_PIN`; no caller of `DISABLE_PIN` found), `BiometricsView.kt`, `PinDots.kt`, `NumberPad.kt`. `ui/utils/BiometricPrompt.kt`.
- `ui/settings/pin/`: `PinManagementScreen`, `PinPromptScreen`, `PinChooseScreen`, `PinConfirmScreen`, `PinBiometricsScreen`, `PinResultScreen`. `ui/sheets/`: `PinSheet`, `ChangePinSheet`, `DisablePinSheet`, `ForgotPinSheet`.
- `ui/screens/wallets/send/SendPinCheckScreen.kt` and `SendConfirmScreen.kt`: PIN or biometric gate before pay.
- `ui/settings/SettingsScreen.kt`: Security tab rows. `ui/components/BalanceHeaderView.kt`: hide balance.
- `ui/screens/recovery/`: `RecoveryModeScreen`, `RecoveryViewModel`, `RecoveryMnemonicScreen` (`backup_mnemonic_words_box`), `RecoveryMnemonicViewModel` (reads mnemonic from keychain). Routes `Routes.RecoveryMode`, `Routes.RecoveryMnemonic` in `ui/ContentView.kt`.
- `data/keychain/Keychain.kt`: Keystore-encrypted storage. `usecases/WipeWalletUseCase.kt`.

## How to drive it
- Journeys (`journeys/security/`):
  - `pin-lock-on-resume.xml`: home key then relaunch shows `PinPad`; night-mode toggle does not lock; an open Add Tag sheet is hidden while locked; a `bitcoin:` URI fired while locked waits for the PIN and replaces the open Receive sheet. Needs PIN enabled, known PIN, biometrics off, savings above 10000 sat and one activity.
  - `pin-result-long-label.xml`: Spanish locale at 1.3 font scale, "Wallet Secured" row wraps and `ToggleBioForPayments` toggles; enables then disables a PIN; no biometrics enrolled.
  - `wallet-wipe-new-profile.xml` (+ `README.md`): `ResetAndRestore` wipe, new wallet, new Pubky profile with no "Failed to create profile" toast; see `profile.md`.
- E2E: `bitkit-e2e-tests/test/specs/security.e2e.ts` `@security_1`, shard `lightning_security` (`@lightning|@security`), local `bitkit-docker`: set PIN (mismatch then match, skip biometrics, enable PIN for payments), relaunch and unlock, send 10000 sat with PIN, change PIN (wrong current PIN shows `AttemptsRemaining`, wrong confirmation shows `WrongPIN`), disable PIN, enable again, enter a wrong PIN 8 times (`AttemptsRemaining` after each of the first six, `LastAttempt` after the seventh) and expect onboarding (text `Privacy Policy`, `Continue`).
- `settings.e2e.ts` `@settings_06` (shard `settings`) covers swipe-to-hide and hide-on-open.
- Instrumented Compose tests: `app/src/androidTest/java/to/bitkit/ui/RootDestinationTest.kt`, `data/keychain/KeychainTest.kt`.
- Preconditions: onboarded wallet; for the send step funds from `receiveOnchainFunds`; never enter a wrong PIN more than once outside the lockout test.

## What proves it
- PIN set: `PINCode` row shows Enabled; relaunch shows `PinPad`; correct PIN shows `TotalBalance`.
- Pay with PIN: "Enter PIN Code", then `SendSuccess`.
- Lockout: after 8 wrong PINs the wallet is wiped and onboarding `Continue` shows.
- Disabled: relaunch goes straight to `TotalBalance`; `PINCode` shows Disabled.
- Hide balance: `ShowBalance` visible.

## Not covered by tests
- Biometrics (enable in the PIN sheet, `ToggleBiometrics`, lock-screen prompt, `UseBiometryInstead`, biometric before payment): no enrolled-biometrics environment in journeys or specs.
- `ForgotPinSheet` (`ForgotPIN`) and `AttemptsRemaining` tap target; `SecureWallet` prompt "Later"; back on each PIN page.
- Toggling `EnablePinForPayments` from Settings (only the result-sheet toggle is driven); `SendPinCheckScreen` wrong PIN; PIN for payments on non-onchain pays.
- Lock while other sheets are open beyond Receive and Add Tag; lock with the app killed.
- PIN migrated from React Native (`MigrationService.migratePin`): `migration.e2e.ts` never mentions a PIN.
- Recovery mode and recovery mnemonic: no journey or spec; `RecoveryModeScreen` buttons have no testTags.
- `AutoReadClipboard` toggle; `SendAmountWarning` is only tapped in `onchain.e2e.ts`.

## Gotchas
- Tags repeat across screens: `ChangePIN` is both the management button and the validate sheet; `ChangePIN2` is both the new and retype pages; `AttemptsRemaining`/`LastAttempt` appear on `PinPad`, both PIN sheets and send PIN check; `OK` is also the backup and result button.
- Entering the 4th digit submits immediately; a wrong PIN clears the dots. Each wrong PIN costs an attempt in the keychain, which persists across launches.
- After PIN enable, `PinManagementScreen` pops back to Settings > Security when `isPinEnabled` changes, even while the result sheet is open (`pin-result-long-label.xml`).
- The Switch inside rows has no node in `android layout`; use `adb shell uiautomator dump` bounds. Toasts do not appear in `android layout`; use a screenshot.
- Recovery mode needs no wallet and is not gated by dev mode, unlike `bitkit://screen/...` links.

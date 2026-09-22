# Timed Sheets

Timed sheets are opportunistic prompts shown from the wallet surface when a feature-specific condition is met. They are separate from the full-screen settings intro routes, even when both presentations reuse the same visual content.

The manager checks sheets two seconds after the home screen resumes and cancels a pending check when the home screen pauses. It shows at most one eligible sheet per check, using this priority order:

1. App update
2. Backup
3. Background payments
4. QuickPay
5. High balance

The selected sheet is removed from the manager after it is shown. It is registered again when the app creates a new manager instance. A standard dismissal calls the selected sheet's `onDismissed()` handler before clearing it.

## App Update

The App Update sheet is eligible when the release service reports a newer Android build that is not marked critical. Critical updates are handled separately.

- `Cancel` dismisses the sheet. No persisted dismissal state is written.
- `Update` opens the app's Play Store page.

After dismissal, the sheet can be considered again after the app creates a new timed-sheet manager and the release remains newer than the installed build.

## QuickPay

QuickPay has two intro presentations:

- Full-screen settings intro: opened from navigation routes such as the QuickPay suggestion. It shows a single `Continue` button. Pressing it marks `quickPayIntroSeen = true` and navigates to QuickPay settings.
- Timed sheet intro: opened by `QuickPayTimedSheet` when the intro has not been seen, QuickPay is not enabled, and the wallet has Lightning balance. It shows `Later` and `Learn More`.

For the timed sheet:

- `Later` marks `quickPayIntroSeen = true` and dismisses the timed sheet. The timed sheet dismissal path also writes the same value, so the sheet will not be shown again through normal app flow.
- `Learn More` marks `quickPayIntroSeen = true`, dismisses the timed sheet, and navigates to QuickPay settings. The dismissal path also writes the same value.

Once `quickPayIntroSeen = true`, `QuickPayTimedSheet.shouldShow()` returns false. The intro can appear again only if persisted settings are reset or restored with `quickPayIntroSeen = false`.

## Backup

Backup uses a snooze model instead of a permanent seen flag:

- The timed sheet is eligible when the backup is not verified, the wallet has a balance, and the last ignored timestamp is older than one day.
- `Later` dismisses the timed sheet. The timed sheet dismissal path records `backupWarningIgnoredMillis`.
- A generic timed sheet dismissal also records `backupWarningIgnoredMillis`.
- `Continue` starts the backup flow and does not immediately mark the prompt as ignored. Closing the timed backup flow through its dismissal callback records `backupWarningIgnoredMillis`.

After the one-day ask interval passes, the Backup timed sheet can appear again if the backup is still unverified and the wallet still has a balance.

## Background Payments

The Background Payments timed sheet is eligible when notification permission has not been granted, the wallet has a Lightning balance, and `notificationsIgnoredMillis` is older than one week.

- `Later` marks the background-payments intro as seen and dismisses the sheet. Dismissal records `notificationsIgnoredMillis`.
- `Enable` marks the intro as seen, dismisses the sheet, records `notificationsIgnoredMillis`, and requests notification permission.
- A generic timed sheet dismissal records `notificationsIgnoredMillis` without marking the intro as seen.

After the one-week ask interval passes, the timed sheet can appear again if notification permission is still not granted and the wallet still has a Lightning balance. The full-screen settings intro is a separate route with only an enable action.

## High Balance

The High Balance sheet is eligible when the wallet balance converts to more than USD 500, fewer than three warnings have been dismissed while above the threshold, and `balanceWarningIgnoredMillis` is older than one day.

- `Understood` dismisses the sheet.
- `Learn More` opens the configured bitcoin-storage information URL and dismisses the sheet.
- Any dismissal increments `balanceWarningTimes` and records `balanceWarningIgnoredMillis`.

The sheet can appear at most three times while the balance remains above the threshold, with at least one day between appearances. A balance at or below the threshold resets `balanceWarningTimes` to zero.

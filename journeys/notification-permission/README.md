# Notification-permission journeys

These journeys exercise the notification-permission request triggered by the
"Set up in background" toggle that backs Bitkit's background-payments setup.

The behaviour was changed on `fix/limit-system-notification-permission`: tapping the
toggle now goes through the shared `rememberRequestNotificationPermission` helper
(`ui/utils/RequestNotificationPermissions.kt`) instead of jumping straight to the
system notification settings.

## What the fix does
- **Android 13+ (API 33, TIRAMISU)**: tapping the toggle launches the OS
  `POST_NOTIFICATIONS` runtime permission dialog. Granting it flips the toggle to
  checked; the result is persisted via `SettingsViewModel.setNotificationPreference`.
- **Pre-13 (API < 33)**: there is no runtime dialog, so the toggle falls back to the
  caller's `onPreTiramisu` action — the in-app background-payments settings on the
  intro sheet, the system notification settings on the transfer/receive toggles.

The same helper backs four entry points; these journeys cover the three the user can
reach directly:
- **Transfer → Spending confirm** (`SpendingConfirmScreen`)
- **Receive → CJIT confirm** (`ReceiveConfirmScreen`)
- **Receive → CJIT liquidity** (`ReceiveLiquidityScreen`, via "Learn more")

## Mandatory setup
1. **Use an API 33+ device** to verify the runtime-dialog path. On API < 33 the dialog
   never appears — only the system-settings fallback is exercised.
2. **Start from a fresh notification-permission state.** The OS only shows the
   `POST_NOTIFICATIONS` dialog while the permission is in the "ask" state. Once granted
   or denied it will not show again, and the journey will silently pass for the wrong
   reason. Reset before each run:
   `adb shell pm revoke to.bitkit.dev android.permission.POST_NOTIFICATIONS`
   (or reinstall / clear app data).
3. **Node must be connected to the LSP (Blocktank).** Both the Transfer→Spending and
   Receive→CJIT confirm screens need a real order quoted by Blocktank before the toggle
   screen renders. With the hosted staging backend this is `api.stag0.blocktank.to`.
4. **Transfer→Spending also needs a positive on-chain Savings balance** so a real max can
   be quoted. Fund + mine via the `blocktank-api:lsp` skill, then wait for the balance to
   sync.

## Gotchas
- **The permission dialog is one-shot** — see setup #2. Always revoke/reset first.
- **Blocktank must be reachable.** If `api.stag0.blocktank.to:443` is down, CJIT/order
  creation hangs on a spinner at "Continue" and the confirm screen never appears, so the
  toggle is unreachable. Verify the host first:
  `curl -s -m 8 -o /dev/null -w '%{http_code}\n' https://api.stag0.blocktank.to/blocktank/api/v2/info`
  (`000` = down). This is infra, not the toggle.
- The system permission dialog is **OS UI**, not Compose — locate its buttons with
  `android screen --annotate` (text "Allow" / "Don't allow"), not `android layout` tags.
- On grant, the toggle reflects `notificationsGranted`; it only flips to checked once the
  `ON_RESUME` re-check or the launcher callback fires.

## Test tags
- Transfer→Spending toggle switch: `SpendingConfirmNotificationSwitch`
- Receive→CJIT confirm toggle switch: `ReceiveConfirmNotificationSwitch`
- Receive→CJIT liquidity toggle switch: `ReceiveLiquidityNotificationSwitch`
- Spending amount screen: `SpendingAmount`, continue `SpendingAmountContinue`,
  available/max `SpendingAmountAvailable` / `SpendingAmountMax`.
- The toggle label on all screens is "Set up in background".

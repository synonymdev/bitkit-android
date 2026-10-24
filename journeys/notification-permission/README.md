# Notification-permission journeys

This suite is retired. Every journey in it reached the "Set up in background" toggle through a
Blocktank channel purchase, and those were removed in synonymdev/bitkit-android#1669:

- `receive-cjit-confirm-notification-toggle.xml` and `toggle-off-opens-system-settings.xml` used the
  Receive → CJIT confirm screen (`ReceiveConfirmScreen`), reached through "Receive Lightning funds".
- `receive-cjit-liquidity-notification-toggle.xml` used the Receive → CJIT liquidity screen
  (`ReceiveLiquidityScreen`).
- `transfer-spending-confirm-notification-toggle.xml` used the Transfer → Spending confirm screen
  (`SpendingConfirmScreen`), reached through "Transfer To Spending".

No button in the UI navigates to those screens any more, so the toggle's permission behaviour
(the shared `rememberRequestNotificationPermission` helper: OFF with permission granted opens the system
notification settings, ON on API 33+ shows the `POST_NOTIFICATIONS` dialog, ON before API 33 opens the
system settings) is no longer reachable on a device through these routes. Settings › Background payments
(testTag `BackgroundPaymentSettings`) is a separate entry point that no journey covers yet.

The reset command for a fresh permission state, should a journey for another entry point be written:
`adb shell pm revoke to.bitkit.dev android.permission.POST_NOTIFICATIONS`.

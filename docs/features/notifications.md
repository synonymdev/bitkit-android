# Notifications and background payments

Local and push notifications for received payments, channel events and subscription reminders, plus the permission flow and the Background Payments settings that keep the node alive in the background.

## What it does

- Posts system notifications through `Context.pushNotification` (`app/src/main/java/to/bitkit/ui/Notifications.kt`) on channel `channel_app`; nothing is posted when Android 13+ `POST_NOTIFICATIONS` is not granted.
- Received-payment text comes from one builder, `ReceivedNotificationContent`: title "Payment Received", body "Received <amount> (<fiat>)", amount grouped with spaces and ordered by the primary display setting.
- Four producers, deduplicated so one event gives one notification:
  - `LightningNodeService` foreground service (notification channel `bitkit_notification_channel_node`, ongoing "Bitkit is running in background so you can receive Lightning payments", action "Stop App"): handles Lightning payments, on-chain receives (including confirmed-only), CJIT `ChannelReady` and resolved pending payments while the app is in the background.
  - `AppViewModel` in the foreground: shows the received sheet or toast instead (for a regular channel opening the toast `SpendingBalanceReadyToast`), never a notification.
  - `FcmService` + `WakeNodeWorker`: Blocktank push (types `incomingHtlc`, `mutualClose`, `orderPaymentConfirmed`, `cjitPaymentArrived`, `wakeToTimeout`) wakes the node, runs LDK events and posts a notification only when nothing in-process handles the event (no activity and no foreground service). It stops the node afterwards if the app is not in the foreground.
  - `PaykitSubscriptionNotificationWorker`: "Subscription Payment Due" at each billing period start (see `subscriptions.md`).
- Push registration: `LightningRepo.registerForNotifications` after node start, using the FCM token and `LspNotificationsService.registerDevice`; skipped in E2E builds (`Env.isE2eTest`).
- A regular (non-CJIT) channel opening posts no payment notification; a CJIT channel posts exactly one.
- On-chain receive: mempool then confirmation gives one sheet or notification (dedupe on txid and seen state); a confirmed-only receive shows only within one hour of the block time and not during restore or migration; after a seed restore everything stays silent until the first on-chain sync finishes (`pendingRestoreActivitySeen`).

## How a user reaches it

- Settings (Payments section) > "Background Payments" row (`BackgroundPaymentSettings`, value On or Off) opens `BackgroundPaymentsSettings` when the intro was seen or permission is granted, else `BackgroundPaymentsIntro` (button `BackgroundPaymentsIntro-enable`). Routes `Routes.BackgroundPaymentsSettings` and `Routes.BackgroundPaymentsIntro` are deeplinkable.
- Settings screen: switch "Get paid when Bitkit is closed" (opens Android notification settings, no testTag), switch "Keep Bitkit active in background" (enabled only with permission), "Customize in Android Bitkit Settings" button, notification preview.
- Timed sheet `BackgroundPaymentsIntro` (`BackgroundPaymentsIntro-later`, `BackgroundPaymentsIntro-enable`): shown from Home when permission is not granted, Lightning balance is above 0 and one week passed since the last dismissal (`docs/timed-sheets.md`). Enable asks for `POST_NOTIFICATIONS`.
- Toggle "Set up in background" on three screens: Transfer > Spending confirm (`SpendingConfirmNotificationSwitch`), Receive > CJIT confirm (`ReceiveConfirmNotificationSwitch`), Receive > CJIT liquidity via Learn more (`ReceiveLiquidityNotificationSwitch`). Granted: tap opens system notification settings; not granted on API 33+: runtime dialog; pre-13: system settings.
- Tapping a subscription reminder opens `MainActivity` with `EXTRA_PAYKIT_SUBSCRIPTION_PAYMENT_DUE`.
- Dev Settings > NOTIFICATIONS: "Register For LSP Notifications", "Test LSP Notification".

## Code

- `app/src/main/java/to/bitkit/ui/Notifications.kt`: channels, `pushNotification`, `openNotificationSettings`, `areNotificationsEnabled`, subscription extras.
- `app/src/main/java/to/bitkit/fcm/FcmService.kt`: receives FCM, decrypts the Blocktank payload (`PUSH_NOTIFICATION_PRIVATE_KEY`), enqueues `WakeNodeWorker`. `app/src/main/java/to/bitkit/fcm/WakeNodeWorker.kt`: 2 minute node wake, LDK event to notification mapping, `isHandledInProcess`.
- `app/src/main/java/to/bitkit/androidServices/LightningNodeService.kt`: foreground service (`ACTION_START_SERVICE`, `ACTION_STOP_SERVICE_AND_APP`), starts node with its own event handler; `services/NodeServiceFgState`.
- `.../domain/commands/`: `NotifyPaymentReceived*`, `NotifyChannelReady*`, `NotifyPendingPaymentResolved*`, `ReceivedNotificationContent`.
- `app/src/main/java/to/bitkit/services/LspNotificationsService.kt`, `models/BlocktankNotificationType.kt`: device registration, push types.
- `app/src/main/java/to/bitkit/ui/MainActivity.kt`: creates channels; starts or stops the service when wallet exists, `notificationsGranted` and `keepBitkitActiveInBackground` are all true and restore is idle; handles the reminder intent.
- `app/src/main/java/to/bitkit/ui/utils/RequestNotificationPermissions.kt`: `RequestNotificationPermissions`, `rememberRequestNotificationPermission`, `rememberNotificationToggleClick`. `viewmodels/SettingsViewModel.kt`: `setNotificationPreference`, `setKeepBitkitActiveInBackground`; settings fields in `data/SettingsStore.kt` (both default false).
- `app/src/main/java/to/bitkit/ui/settings/backgroundPayments/BackgroundPaymentsSettings.kt`, `BackgroundPaymentsIntroScreen.kt`; `ui/sheets/BackgroundPaymentsIntroSheet.kt`; `utils/timedsheets/sheets/NotificationsTimedSheet.kt`.
- `app/src/main/java/to/bitkit/ui/screens/transfer/SpendingConfirmScreen.kt`, `ui/screens/wallets/receive/ReceiveConfirmScreen.kt`, `ReceiveLiquidityScreen.kt`: the toggles.
- `app/src/main/java/to/bitkit/repositories/PaykitSubscriptionNotificationScheduler.kt`: WorkManager reminders (up to 32, cancelled when notifications are off).

## How to drive it

Backend: regtest with the Blocktank LSP (`api.stag0.blocktank.to`), `./lsp` helper at the repo root, adb for notification shade (`adb shell dumpsys notification --noredact | grep -A3 -i "to.bitkit.dev"`), `adb shell pm revoke|grant to.bitkit.dev android.permission.POST_NOTIFICATIONS`. Android 13+ device for dialog paths. FCM push needs a real device token; E2E builds skip registration.

- `journeys/cjit-notifications/README.md` (setup: `./lsp POST /cjit` then pay the invoice, optional `/regtest/mine`): `cjit-foreground-service-notification.xml` exactly one formatted notification with the service on; `cjit-push-single-notification.xml` app killed, `WakeNodeWorker` posts once; `non-cjit-channel-no-payment-notification.xml` regular channel opening posts no payment notification.
- `journeys/notification-permission/README.md` (revoke permission first, the dialog is one-shot): `transfer-spending-confirm-notification-toggle.xml`, `receive-cjit-confirm-notification-toggle.xml`, `receive-cjit-liquidity-notification-toggle.xml` (dialog, allow, toggle checked), `toggle-off-opens-system-settings.xml` (permission granted, tap opens system settings).
- `journeys/onchain-receive/` (setup in its `README.md`): `mempool-then-confirmed-single-sheet.xml`, `confirmed-only-received-sheet.xml`, `confirmed-only-background-notification.xml` (needs service on), `restore-recent-receive-stays-silent.xml` (throwaway emulator). See also `receive.md`.
- `journeys/paykit-clock-changes.md`: reminder steps (clock moved back, cold-launch tap).
- E2E: no spec asserts a notification. Specs only dismiss the timed sheet through `dismissBackgroundPaymentsTimedSheet` (`bitkit-e2e-tests/test/helpers/actions.ts`, tag `BackgroundPaymentsIntro-later`) in `send.e2e.ts`, `lightning.e2e.ts`, `transfer.e2e.ts`, `lnurl.e2e.ts`, `multiaddress.e2e.ts`, `migration.e2e.ts`; those specs run in the CI shards matching their tags (see `send.md`, `transfer.md`, `lnurl.md`).
- Unit tests: `app/src/test/java/to/bitkit/repositories/PaykitSubscriptionNotificationSchedulerTest.kt`.

## What proves it

- `adb shell dumpsys notification` shows one entry with `android.title` "Payment Received" and `android.text` "Received ₿ 48 064 (...)"; count distinct entries per event.
- Permission journeys: toggle (`...NotificationSwitch`) becomes checked after Allow; Settings row `BackgroundPaymentSettings` reads On; the foreground service notification "Bitkit is running in background" is present when Keep active is on.
- Foreground regular channel: `SpendingBalanceReadyToast`, no notification. Reminder: "Subscription Payment Due" then the unpaid period opens after unlock.

## Not covered by tests

- No automated test of `FcmService` decryption, `WakeNodeWorker` event mapping for `mutualClose`, `orderPaymentConfirmed` (channel opened, open failed), `PaymentFailed`, `wakeToTimeout`, or the "Channel closed" notifications.
- The "Stop App" action, the foreground service timeout on Android 15 (`onTimeout`), and the pending payment resolved notification.
- "Keep Bitkit active in background" switch and the Settings row states have no journey; the Settings switch rows carry no testTag.
- The timed sheet's one-week interval and Enable path (e2e only taps Later). Pre-API-33 behaviour (system settings fallback) is not driven.
- Real FCM delivery and `registerDevice` (skipped in E2E builds; `Test LSP Notification` in Dev Settings is manual).
- Subscription reminder delivery timing and tap handling beyond `paykit-clock-changes.md` manual steps.

## Gotchas

- The permission dialog is one-shot: revoke or reinstall before each run, else a journey passes for the wrong reason. The dialog is OS UI: find buttons with `android screen --annotate` (text "Allow"), not `android layout`.
- If Blocktank is down, CJIT order creation hangs at Continue and the confirm screens never appear; check with `curl -s -m 8 -o /dev/null -w '%{http_code}\n' https://api.stag0.blocktank.to/blocktank/api/v2/info` (`000` means down).
- The cjit-notifications README says the toggle is "Settings -> Notifications"; the row is titled "Background Payments" in Settings. It also says amounts hide when notification details are off, but `notification__received__body_hidden` and `settings__bg__include_amount` have no use in `app/src/main/java`; `ReceivedNotificationContent` always includes the amount.
- `notification__received__body_channel` ("Via new channel") is no longer used for the push path.
- `WakeNodeWorker` defers entirely to the in-process handler when an activity exists or the service runs, so a push test with the app open shows no system notification.
- Toggling the permission ON only flips the setting after the launcher result or `ON_RESUME`; the Settings switch row just opens system settings.
- `onchain-receive` timing: run deposit and mine in one command; check `adb logcat -d -s APP:V` for `LDK event fired`.

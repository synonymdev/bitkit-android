# Paykit clock changes — manual fault injection

Device-clock control is not provided by the journey runner's capability table. Run these checks on disposable test identities and test wallets using a device or isolated environment whose clock can be changed without changing the developer host clock.

## Setup

Create a fresh wallet and a matching Pubky identity, save a contact, and link a second test identity for private payments. Record the profile, contact, receiving address, and wallet balance. Cover both a local-secret identity and a Ring-adopted identity. Enable notifications and accept a recurring subscription with a known UTC billing boundary.

## Clock skew and recovery

1. Move the test clock one month forward. Relaunch Bitkit and attempt a Paykit operation. An authentication failure is allowed; the app must not treat it as authorization to erase the saved identity, contacts, or wallet.
2. Attempt to restore the session while the clock is wrong. Restore the correct clock and retry, then relaunch. Android should re-sign in with the adopted Ring credential when it is still available. If the credential is unavailable, select the same identity again from the normal identity choice flow. Do not sign out or reset the wallet as part of recovery.
3. Verify that the original contact, profile, receiving address, and balance are still present, and that private payment requests can be exchanged again. A new payment must still require normal approval.
4. Repeat with a backward clock change. After correcting the clock, verify that identity publication and payment-request presentation retry normally instead of waiting for the old future timestamp.
5. After a failed restoration, open the profile from the home header and adopt the same identity from Ring without signing out. The recovery flow must remain reachable and preserve the profile and contact labels.
6. Repeat failed restoration, then adopt a different identity from Ring. Even if its profile cannot load, the previous identity's name, avatar, and contact labels must not appear. Also verify normal explicit sign-out and identity switching.

## Travel, daylight saving, and reminders

1. Keep automatic date/time enabled and change only the timezone between America/New_York, Pacific/Kiritimati, and Pacific/Pago_Pago. Authentication and the subscription's UTC billing boundary must remain unchanged; local date/time labels may change.
2. Include a subscription spanning a daylight-saving transition. Verify the agreed UTC boundary rather than assuming the local wall-clock hour stays constant.
3. Schedule a reminder, then move the clock backward before it is due. It must not announce that payment is due while the device's current time is before that billing boundary.
4. Restore the correct clock and verify reminders still work. On Android, WorkManager delivery is best effort and may be delayed by retry backoff or OS scheduling; this check does not require exact delivery to the second.
5. While a payment request is temporarily unavailable and presentation is retrying, move the clock forward and backward. Retry intervals should remain short, while actual payment expiry and approval continue to use absolute timestamps.
6. Tap a due subscription reminder during background contact preparation, then repeat with Bitkit closed before the tap. Verify the exact unpaid billing period is presented after authentication and unlock. The notification refresh must read shared state without starting proof reconciliation or private-message maintenance. Record tap-to-sheet timing separately from identity activation, authentication, background preparation, and any SDK lock wait. Startup and foreground maintenance run independently and can still delay the cold-launch case.

7. Repeat the cold-launch reminder while the startup request refresh fails before the stored request snapshot loads. Keep the reminder pending through the failure. Restore connectivity and allow a later refresh to open the exact due period without tapping the reminder again. Clear a stale reminder only after a successful snapshot confirms that period was handled or the subscription is inactive.
8. Open a due reminder whose payment cannot be prepared. Terminal feedback must end that reminder's presentation without an automatic reopen cycle. Repeat with an insufficient-balance or pending-private-link result. The period must remain manually reopenable while pending. Android shows the preparing Send sheet only for one-time requests; use `payment-requests/requested-resolution-failure.xml` to verify that sheet stays open across retries and that closing it prevents automatic reopening.

These steps describe the remaining manual verification. Unit tests cover injected restoration failures, state preservation, retry timing, UTC recurrence, and notification scheduling; they do not replace a live Ring-session clock-change test.

## Connection loss and saved identity recovery

Network fault injection is not provided by the journey capability table. Use a disposable wallet with a saved local identity, then repeat with a Ring-adopted identity.

1. Record the profile name, public key, contacts, receiving address, and wallet balance while online.
2. Disable both Wi-Fi and mobile data on the test device, force-stop Bitkit, and reopen it. Wait for session restoration to fail. The cached name must remain, and the app must not advertise Pubky signup for this existing identity.
3. Re-enable connectivity while leaving Bitkit open. Verify that the same identity and contact list recover without signing out or restarting the app when the saved session or adopted Ring credential is still available. If the Ring credential is unavailable, select the same identity again from the normal identity choice flow.
4. Repeat the failed startup and restore connectivity while Bitkit is backgrounded. Return to the foreground from a profile/contact screen and verify the same recovery. Resume must work from any screen, not only Home.
5. Start adopting a Ring identity while recovery is pending. Verify that automatic restoration does not replace the selected identity. Explicit sign-out or wallet reset must not be undone by a pending restoration.

Both platforms retry automatically on connectivity restoration and app resume. A valid saved session must recover without a new authorization. Android can also recover with an available adopted Ring credential.

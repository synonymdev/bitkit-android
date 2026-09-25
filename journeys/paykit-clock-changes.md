# Paykit clock changes — manual fault injection

Device-clock control is not provided by the journey runner's capability table. Run these checks on disposable test identities and test wallets using a device or isolated environment whose clock can be changed without changing the developer host clock.

## Setup

Create a fresh wallet and a matching Pubky identity, save a contact, and link a second test identity for private payments. Record the profile, contact, receiving address, and wallet balance. Cover both a local-secret identity and a Ring-authorized session. Enable notifications and accept a recurring subscription with a known UTC billing boundary.

## Clock skew and recovery

1. Move the test clock one month forward. Relaunch Bitkit and attempt a Paykit operation. An authentication failure is allowed; the app must not treat it as authorization to erase the saved identity, contacts, or wallet.
2. Attempt to restore the session while the clock is wrong. Restore the correct clock and retry, then relaunch. If a grant has expired or been revoked, authorize the same identity again in Ring. Do not sign out or reset the wallet as part of recovery.
3. Verify that the original contact, profile, receiving address, and balance are still present, and that private payment requests can be exchanged again. A new payment must still require normal approval.
4. Repeat with a backward clock change. After correcting the clock, verify that identity publication and payment-request presentation retry normally instead of waiting for the old future timestamp.
5. After a failed restoration, open the profile from the home header and authorize the same identity through Ring without signing out. The recovery flow must remain reachable and preserve the profile and contact labels.
6. Repeat failed restoration, then authorize a different identity through Ring. Even if its profile cannot load, the previous identity's name, avatar, and contact labels must not appear. Also verify normal explicit sign-out and identity switching.

## Travel, daylight saving, and reminders

1. Keep automatic date/time enabled and change only the timezone between America/New_York, Pacific/Kiritimati, and Pacific/Pago_Pago. Authentication and the subscription's UTC billing boundary must remain unchanged; local date/time labels may change.
2. Include a subscription spanning a daylight-saving transition. Verify the agreed UTC boundary rather than assuming the local wall-clock hour stays constant.
3. Schedule a reminder, then move the clock backward before it is due. It must not announce that payment is due while the device's current time is before that billing boundary.
4. Restore the correct clock and verify reminders still work. On Android, WorkManager delivery is best effort and may be delayed by retry backoff or OS scheduling; this check does not require exact delivery to the second.
5. While a payment request is temporarily unavailable and presentation is retrying, move the clock forward and backward. Retry intervals should remain short, while actual payment expiry and approval continue to use absolute timestamps.

These steps describe the remaining manual verification. Unit tests cover injected restoration failures, state preservation, retry timing, UTC recurrence, and notification scheduling; they do not replace a live grant-session clock-change test.

## Connection loss and saved identity recovery

Network fault injection is not provided by the journey capability table. Use a disposable wallet with a saved local identity, then repeat with a Ring-authorized identity.

1. Record the profile name, public key, contacts, receiving address, and wallet balance while online.
2. Disable both Wi-Fi and mobile data on the test device, force-stop Bitkit, and reopen it. Wait for session restoration to fail. The cached name must remain, and the app must not advertise Pubky signup for this existing identity.
3. Re-enable connectivity while leaving Bitkit open. Verify that the same identity and contact list recover without scanning Ring again, signing out, or restarting the app when the saved grant is still valid. For an expired or revoked grant, reauthorization remains required.
4. Repeat the failed startup and restore connectivity while Bitkit is backgrounded. Return to the foreground from a profile/contact screen and verify the same recovery. Resume must work from any screen, not only Home.
5. Start a Ring authorization while recovery is pending. Verify that automatic restoration does not replace that attempt. Explicit sign-out or wallet reset must not be undone by a pending restoration.

Both platforms retry automatically on connectivity restoration and app resume. A valid saved session must recover without a new authorization; expired or revoked grants still require Ring.

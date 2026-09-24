# Paykit clock changes — manual fault injection

Device-clock control is not provided by the journey runner's capability table. Run these checks on disposable test identities and test wallets using a device or isolated environment whose clock can be changed without changing the developer host clock.

## Setup

Create a fresh wallet and a matching Pubky identity, save a contact, and link a second test identity for private payments. Record the profile, contact, receiving address, and wallet balance. Cover both a local-secret identity and a Ring-authorized session. Enable notifications and accept a recurring subscription with a known UTC billing boundary.

## Clock skew and recovery

1. Move the test clock one month forward. Relaunch Bitkit and attempt a Paykit operation. An authentication failure is allowed; the app must not treat it as authorization to erase the saved identity, contacts, or wallet.
2. Attempt to restore the session while the clock is wrong. Restore the correct clock and retry, then relaunch. If a grant has expired or been revoked, authorize the same identity again in Ring. Do not sign out or reset the wallet as part of recovery.
3. Verify that the original contact, profile, receiving address, and balance are still present, and that private payment requests can be exchanged again. A new payment must still require normal approval.
4. Repeat with a backward clock change. After correcting the clock, verify that identity publication and payment-request presentation retry normally instead of waiting for the old future timestamp.
5. Separately verify that explicitly signing out and switching identities retains the normal isolation between identities.

## Travel, daylight saving, and reminders

1. Keep automatic date/time enabled and change only the timezone between America/New_York, Pacific/Kiritimati, and Pacific/Pago_Pago. Authentication and the subscription's UTC billing boundary must remain unchanged; local date/time labels may change.
2. Include a subscription spanning a daylight-saving transition. Verify the agreed UTC boundary rather than assuming the local wall-clock hour stays constant.
3. Schedule a reminder, then move the clock backward before it is due. It must not announce that payment is due while the device's current time is before that billing boundary.
4. Restore the correct clock and verify reminders still work. On Android, WorkManager delivery is best effort and may be delayed by retry backoff or OS scheduling; this check does not require exact delivery to the second.
5. While a payment request is temporarily unavailable and presentation is retrying, move the clock forward and backward. Retry intervals should remain short, while actual payment expiry and approval continue to use absolute timestamps.

These steps describe the remaining manual verification. Unit tests cover injected restoration failures, state preservation, retry timing, UTC recurrence, and notification scheduling; they do not replace a live grant-session clock-change test.

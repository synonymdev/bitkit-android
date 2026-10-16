# Pubky authorization

## Sign-in from a link

- `signin-cold-start.xml` checks a `pubkyauth://signin_grant` link opened while Bitkit is not running:
  the loading toast while the saved session restores, the authorization sheet, and a restore that
  fails while offline, which keeps Bitkit open and the link handler enabled. Android only; iOS does
  not register the raw `pubkyauth` scheme for OS link delivery.

## Grant signup

- `grant-signup.xml` checks consent, cancellation, homeserver registration, and profile setup for a fresh identity.
- `grant-signup-existing-identity.xml` checks approval with an existing identity without opening profile setup.

Both require a fresh `pubkyauth://signup_grant` URL from a controlled requesting app, a reachable
homeserver and authorization relay, and a valid invite when required. The new-identity fixture
uses ordinary app permissions without a Bitkit companion claim.

Deliver each grant signup request through the main wallet scanner: copy the complete URL to the
device clipboard, tap **Scan**, then **Paste**, and allow clipboard access if prompted. Camera
permission is not required for Paste. Use this route on both platforms; iOS does not register the
raw `pubkyauth` scheme for OS link delivery.

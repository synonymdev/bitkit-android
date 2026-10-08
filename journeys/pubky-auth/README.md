# Pubky authorization

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

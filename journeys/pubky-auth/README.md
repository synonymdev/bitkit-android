# Pubky authorization

## Sign-in from a link

- `signin-cold-start.xml` checks a `pubkyauth://signin_grant` link opened while Bitkit is not running:
  the loading toast while the saved session restores, then the authorization sheet, which opens with
  the stored key's identity when the restore is deferred while online. The iOS
  counterpart is `open-signin-link.xml`, which opens the same flow through `bitkit://pubky-auth/signin`;
  only Android has the raw `pubkyauth` scheme, the loading toast and Authorize through to
  `PubkyAuthOK`. A restore that fails while
  the device is offline needs the device network toggled, which the journey environment does not
  provide, so that case is a manual test.
- `signin-with-second-link.xml` checks that a payment link opened while a sign-in link is pending,
  on the PIN screen in either order or over the open authorization sheet, opens only after the
  authorization sheet closes, and never drops the sign-in.

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

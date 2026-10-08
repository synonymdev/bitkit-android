# Pubky authorization

## Grant signup

- `grant-signup.xml` checks consent, cancellation, homeserver registration, and profile setup for a fresh identity.
- `grant-signup-existing-identity.xml` checks approval with an existing identity without opening profile setup.

Both require a fresh `pubkyauth://signup_grant` URL from a controlled requesting app, a reachable
homeserver and authorization relay, and a valid invite when required. The new-identity fixture
uses ordinary app permissions without a Bitkit companion claim.

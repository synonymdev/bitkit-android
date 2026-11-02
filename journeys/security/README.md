# Security journeys

`wallet-wipe-new-profile.xml` shares its action text with iOS. Android titles the Profile Error
toast "Failed to create profile". Capture a screenshot as profile creation completes to check
that this toast is absent, because `android layout` does not expose toasts.

`wallet-reset-recovery-pin.xml` covers Android's restricted reset-recovery route using an already
prepared, unfunded wallet with a PIN and unreadable reset marker. It does not reset the wallet or
display its seed. Forgot PIN returns to the recovery overview and revokes the pending read-only
authorization; it must not queue the ordinary wallet's Forgot PIN sheet. A previous queued sheet
is cleared when recovery opens. Preparing the marker and checking the shared sheet state require
an isolated fixture beyond the standard journey capabilities. This route is Android-specific;
iOS profile-removal recovery is a separate flow.

For an explicitly authorized reset in a separate disposable fixture, an unreadable marker must
attempt private and public endpoint cleanup before erasing credentials. A known interrupted
wallet wipe resumes local cleanup without automatically replaying remote operations. Capture the
marker scope and redacted operation order; component tests do not establish this network journey.

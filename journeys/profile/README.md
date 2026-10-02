# Profile

`delete-profile.xml` verifies visible progress and completion when deleting a disposable profile. Use a test identity that may be deleted.

Delayed or failed session restoration requires network fault injection, which is not a journey-runner capability; see the PR manual checks.

For the reported background-resume case, verify returning directly to Profile with the process still alive, then repeat after the OS recreates the process. A delay in session recovery must keep the saved identity on the profile loading/retry screen rather than show profile onboarding.

Also check both Contacts entry points during delayed or failed session restoration: the drawer and Continue on the Contacts intro. They must keep a saved identity on the recovery screen, including when identity lookup is still pending. A wallet without a saved identity must still reach onboarding.

With an imported grant that cannot be restored and Ring unavailable, Disconnect must forget the local identity and return to onboarding without requiring server access. The remote profile is not deleted by Disconnect. Repeat with a live session and failed endpoint cleanup or revocation: it must report failure and retain the identity. These checks require session/network fault injection beyond the journey runner capabilities.

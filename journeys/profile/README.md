# Profile

`delete-profile.xml` verifies visible progress and completion when deleting a disposable profile. Use a test identity that may be deleted.

During profile deletion, automatic contact refresh, request intake and endpoint publication must not start new work. Already admitted operations and explicit deletion cleanup still finish. After a failed deletion, normal automatic work must resume for the retained identity. Verifying SDK admission and a controlled cleanup failure requires operation gates beyond the journey runner capabilities; this remains a manual check rather than a timed deletion guarantee.

Delayed or failed session restoration requires network fault injection, which is not a journey-runner capability; see the PR manual checks.

For a saved identity, inject a temporary import failure (`concurrent_update`, `shared_state_busy`, or a transport failure) during cold start. Verify that no "Session expired" toast appears, the saved credentials remain, and recovery succeeds after the temporary failure ends. A genuinely invalid session must still produce the existing expiry feedback.

For the reported background-resume case, verify returning directly to Profile with the process still alive, then repeat after the OS recreates the process. A delay in session recovery must keep the saved identity on the profile loading/retry screen rather than show profile onboarding.

Also check both Contacts entry points during delayed or failed session restoration: the drawer and Continue on the Contacts intro. They must keep a saved identity on the recovery screen, including when identity lookup is still pending. A wallet without a saved identity must still reach onboarding.

With an imported grant that cannot be restored and Ring unavailable, Disconnect must forget the local identity and return to onboarding without requiring server access. The remote profile is not deleted by Disconnect. Repeat with a live session and failed endpoint cleanup or revocation: it must report failure and retain the identity. These checks require session/network fault injection beyond the journey runner capabilities.

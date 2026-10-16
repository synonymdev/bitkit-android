# Profile

`delete-profile.xml` verifies visible progress and completion when deleting a disposable profile. Use a test identity that may be deleted.

During profile deletion, automatic contact refresh, request intake and endpoint publication must not start new work. Already admitted operations and explicit deletion cleanup still finish. After a failed deletion, normal automatic work must resume for the retained identity. Verifying SDK admission and a controlled cleanup failure requires operation gates beyond the journey runner capabilities; this remains a manual check rather than a timed deletion guarantee.

Contact-link retention after deletion is platform-specific. Android cancels a scan awaiting a signed-in contacts refresh when deletion signs out; it does not retry that scan as the iOS retained-link flow does. This change does not alter that cancellation behavior.

If public invoice replacement is deferred during deletion, make deletion fail and the first resumed publication fail transiently. Verify that public publication resumes without waiting for contact refresh to complete, remains pending after the publication error, and is retried by normal foreground maintenance without another foreground event or payment. After sign-out or identity replacement, an older refresh must not publish for the new identity. Controlled payment events and publication failures require gates beyond the journey runner capabilities.

Hold invoice preparation or the final identity lookup before publication, begin deletion, then release it. New public/private endpoint rotation must stay deferred while matching paid-invoice invalidation and receipt attribution still complete. Already-admitted writes may finish, but an older publication must not acknowledge a newer paid-invoice invalidation. These checks require controlled payment events and SDK-admission gates beyond the journey runner capabilities.

Delayed or failed session restoration requires network fault injection, which is not a journey-runner capability; see the PR manual checks.

Hold initial session restoration with public contact payments enabled, turn contact payments off, then release restoration. No automatic endpoint preparation or publication may resume, and pending cleanup must remain available. Repeat with publication waiting for SDK admission. If a write was already admitted, let it finish and verify it cannot clear the cleanup requested by disabling sharing. These checks require restoration and SDK-operation gates beyond the journey runner capabilities.

Hold endpoint preparation before publication admission, or queue a public publication behind SDK work. Begin deletion, complete endpoint removal while session revocation remains held, then release the queued preparation/publication. The removed endpoints must stay absent. Separately, let a publication enter its SDK write before deletion: cleanup must wait for it and remove its endpoints afterward, even if its caller is cancelled. This requires controlled operation gates beyond the journey runner capabilities.

While deletion is held, make a Lightning channel usable or ready, then fail deletion while retaining the same identity. Public and private endpoint preparation must refresh the invoice rather than reuse its old route hints. A skipped or failed publication must retain that refresh for normal maintenance; a new session must not inherit it. Also hold an older public refresh before publication, pay its invoice and publish the replacement, then release the older refresh. The replacement must become pending again and be republished by normal reconciliation. These checks require channel, failure and publication gates beyond the journey runner capabilities.

Hold initial session restoration before sign-in is available, then make a Lightning channel usable. Release restoration while public publication cannot proceed, then restore readiness or connectivity without another channel event. Normal maintenance must retain the forced invoice refresh for the restored identity. A refresh that began with an existing sign-in must not transfer to a replacement session.

For a saved identity, inject a temporary import failure (`concurrent_update`, `shared_state_busy`, or a transport failure) during cold start. Verify that no "Session expired" toast appears, the saved credentials remain, and recovery succeeds after the temporary failure ends. A genuinely invalid session must still produce the existing expiry feedback.

For the reported background-resume case, verify returning directly to Profile with the process still alive, then repeat after the OS recreates the process. A delay in session recovery must keep the saved identity on the profile loading/retry screen rather than show profile onboarding.

Also check both Contacts entry points during delayed or failed session restoration: the drawer and Continue on the Contacts intro. They must keep a saved identity on the recovery screen, including when identity lookup is still pending. A wallet without a saved identity must still reach onboarding.

With an imported grant that cannot be restored and Ring unavailable, Disconnect must forget the local identity and return to onboarding without requiring server access. The remote profile is not deleted by Disconnect. Repeat with a live session and failed endpoint cleanup or revocation: it must report failure and retain the identity. These checks require session/network fault injection beyond the journey runner capabilities.

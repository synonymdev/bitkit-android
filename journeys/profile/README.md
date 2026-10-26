# Profile

`delete-profile.xml` verifies visible progress and completion when deleting a disposable profile. Use a test identity that may be deleted.

`unlink-ring-pubky.xml` verifies that a pubky adopted from Pubky Ring shows "Unlink pubky" instead of "Delete Profile" in Edit Profile, returns to profile onboarding after unlinking, and stays available in Pubky Ring. It needs Pubky Ring (`to.pubky.ring`) on the same emulator or device, signed with the same key as the Bitkit build (`app/debug.keystore` for a dev build), which is not a listed capability. Use a disposable Ring identity; a profile created in Bitkit keeps "Delete Profile", covered by `delete-profile.xml`.

During profile deletion, automatic contact refresh, request intake and endpoint publication must not start new work. Already admitted operations and explicit deletion cleanup still finish. Once destructive cleanup has begun, its durable pending state keeps authenticated work paused even after a failure or restart. Only an explicit local Disconnect may finish the interrupted local cleanup; it does not claim that the remote profile was deleted. A failure before that durable checkpoint may resume normal work for the unchanged identity. Verifying SDK admission and a controlled cleanup failure requires operation gates beyond the journey runner capabilities; this remains a manual check rather than a timed deletion guarantee.

Contact-link retention after deletion is platform-specific. Android cancels a scan awaiting a signed-in contacts refresh when deletion signs out; it does not retry that scan as the iOS retained-link flow does. This change does not alter that cancellation behavior.

If public invoice replacement is deferred before destructive deletion begins, make preparation fail before the durable cleanup checkpoint and the first resumed publication fail transiently. Verify that public publication resumes without waiting for contact refresh to complete, remains pending after the publication error, and is retried by normal foreground maintenance without another foreground event or payment. After sign-out or identity replacement, an older refresh must not publish for the new identity. Controlled payment events and publication failures require gates beyond the journey runner capabilities.

Hold invoice preparation or the final identity lookup before publication, begin deletion, then release it. New public/private endpoint rotation must stay deferred while matching paid-invoice invalidation and receipt attribution still complete. Already-admitted writes may finish, but an older publication must not acknowledge a newer paid-invoice invalidation. These checks require controlled payment events and SDK-admission gates beyond the journey runner capabilities.

Delayed or failed session restoration requires network fault injection, which is not a journey-runner capability; see the PR manual checks.

Hold initial session restoration with public contact payments enabled, turn contact payments off, then release restoration. No automatic endpoint preparation or publication may resume, and pending cleanup must remain available. Repeat with publication waiting for SDK admission. If a write was already admitted, let it finish and verify it cannot clear the cleanup requested by disabling sharing. These checks require restoration and SDK-operation gates beyond the journey runner capabilities.

Hold endpoint preparation before publication admission, or queue a public publication behind SDK work. Begin deletion, complete endpoint removal while session revocation remains held, then release the queued preparation/publication. The removed endpoints must stay absent. Separately, let a publication enter its SDK write before deletion: cleanup must wait for it and remove its endpoints afterward, even if its caller is cancelled. This requires controlled operation gates beyond the journey runner capabilities.

While preparation is held before the durable cleanup checkpoint, make a Lightning channel usable or ready, then fail preparation while retaining the same identity. Public and private endpoint preparation must refresh the invoice rather than reuse its old route hints. A skipped or failed publication must retain that refresh for normal maintenance; a new session must not inherit it. Also hold an older public refresh before publication, pay its invoice and publish the replacement, then release the older refresh. The replacement must become pending again and be republished by normal reconciliation. These checks require channel, failure and publication gates beyond the journey runner capabilities.

Hold initial session restoration before sign-in completes, make a Lightning channel usable or ready, then release restoration while public publication remains blocked. Restore readiness or connectivity without another channel event. Normal public maintenance must retain the forced invoice refresh for the restored identity, including after a deferred or failed restoration and a skipped or failed publication. Work retained for the stored identity or an existing signed-in session must not transfer to a replacement identity or a new session for the same key. These checks require restoration, channel and publication gates beyond the journey runner capabilities.

On Android, keep public publication unavailable across several maintenance attempts after a channel change. The replacement invoice must be prepared once and reused while it remains valid; publication must stay pending. A subsequent channel change, payment or invoice expiry must still prepare a replacement. Release the publication failure and verify that the current invoice is published and acknowledged. This requires failure and invoice-observation gates beyond the journey runner capabilities.

On Android, hold the saved-contact load at cold start while a Lightning channel becomes usable. When contacts load, their pending private invoice refresh must use current route hints without another channel event. Repeating the contact list must not refresh already-completed peers again, and replacing the session must discard the old request. This Android-specific check requires a contact-load gate beyond the journey runner capabilities.

For a saved identity, inject a temporary import failure (`concurrent_update`, `shared_state_busy`, or a transport failure) during cold start. Verify that no "Session expired" toast appears, the saved credentials remain, and recovery succeeds after the temporary failure ends. A genuinely invalid session must still produce the existing expiry feedback.

For the reported background-resume case, verify returning directly to Profile with the process still alive, then repeat after the OS recreates the process. A delay in session recovery must keep the saved identity on the profile loading/retry screen rather than show profile onboarding.

Also check both Contacts entry points during delayed or failed session restoration: the drawer and Continue on the Contacts intro. They must keep a saved identity on the recovery screen, including when identity lookup is still pending. A wallet without a saved identity must still reach onboarding.

With an imported grant that cannot be restored and Ring unavailable, Disconnect must forget the local identity and return to onboarding without requiring server access. The remote profile is not deleted by Disconnect. Repeat with a live session and failed endpoint cleanup or revocation: it must report failure and retain the identity. These checks require session/network fault injection beyond the journey runner capabilities.

Profile deletion withdraws private payment lists before removing the profile and performs App Registry reconciliation during final public cleanup. An intermediate registry update is deferred; the existing private cleanup marker stays pending until deletion succeeds or normal cleanup retry completes. Public endpoint removal, capability checks, session revocation and uncertain-write safety waits remain required.

For controlled fault testing, interrupt deletion after private withdrawal but before profile removal, then restart with the same identity. Verify cleanup remains pending and authenticated automatic work does not resume. Review reads the original identity again and requires a new confirmation; Disconnect only completes local cleanup and leaves remote state uncertain. Also hold a private withdrawal undelivered: it must remain pending, not be acknowledged by the deferred registry step. Check that disabling private sharing outside a pending removal still reconciles the registry. Local Disconnect does not make a registry mutation. These checks require operation gates and network fault injection beyond the journey runner capabilities.

## Interrupted removal and local recovery

`profile-removal-recovery.xml` specifies the changed failure-dialog and restart routes. The controlled
faults and identity/session interleavings require a disposable authenticated homeserver fixture with
operation gates; these are not supplied by the standard journey runner. Never apply these scenarios
to personal identities or use wallet wipe to prepare the fixture. A pending local obligation is not
proof that remote deletion or revocation succeeded.

Before confirmation, the app captures the original sign-in, local write generation, native profile
revision and contact set. A confirmation can start once. Review only re-reads the original owner and
opens a new confirmation for unchanged records remaining from the previous confirmation. Added or
changed records stop the retry; records observed absent cannot become targets again in this attempt.
The reduced target set remains in force if the new confirmation is canceled or rejected before
cleanup starts. Absence does not establish which operation removed a record. After local credential
teardown has started, Review is unavailable and
Disconnect completes only local work. A dialog or completion queued for an older identity cannot
operate on, navigate away from or report success for its replacement. Ordinary receive accounting
continues; pending state blocks authenticated setup, automatic publication and complete WALLET
capture rather than substituting stale or empty Paykit data.

Consumed payment-list versions remain until credential erasure is acknowledged. Failure to remove
credentials, settings, private state, contact attribution or the durable marker cannot report
completion. Pending state survives process restart even when all identity credentials were already
removed; Profile still offers an explicit local Disconnect. Malformed/unavailable marker storage
fails closed. Local success never asserts remote endpoint or profile removal after an uncertain
response. Native key-only contact batches do not establish cross-device edit/re-add/unblock/ABA
protection; that native conditional-mutation work remains separate.

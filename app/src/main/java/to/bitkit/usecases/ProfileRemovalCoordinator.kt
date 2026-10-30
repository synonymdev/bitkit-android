package to.bitkit.usecases

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import to.bitkit.data.ProfileRemovalStore
import to.bitkit.ext.runSuspendCatching
import to.bitkit.repositories.ContactPaymentSettingsRepo
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyProfileDeletion
import to.bitkit.repositories.PubkyProfileOwner
import to.bitkit.repositories.PubkyRepo
import to.bitkit.utils.AppError
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** One confirmation belongs to one identity and may start destructive work at most once. */
class ProfileRemovalConfirmation internal constructor(
    internal val owner: PubkyProfileOwner,
    internal val attemptId: String,
    internal val deletion: PubkyProfileDeletion?,
    internal val localOnly: Boolean,
    internal val reviewCeiling: AtomicReference<PubkyProfileDeletion?> = AtomicReference(deletion),
    internal val privateWithdrawalAcknowledged: Boolean = false,
) {
    internal val used = AtomicBoolean(false)
    internal val failure = AtomicReference<ProfileRemovalFailure?>(null)
}

class ProfileRemovalFailure internal constructor(
    cause: Throwable,
    internal val confirmation: ProfileRemovalConfirmation,
    internal val recoveryOwner: PubkyProfileOwner,
    internal val localCompletionStarted: Boolean,
    private val privateWithdrawalAcknowledged: Boolean,
) : AppError("Profile removal did not finish. Review its state or disconnect locally.", cause) {
    internal val canReview: Boolean
        get() = confirmation.deletion != null && !localCompletionStarted && privateWithdrawalAcknowledged
}

/** Remote work never resumes automatically from the durable local cleanup obligation. */
@Singleton
class ProfileRemovalCoordinator @Inject constructor(
    private val pubkyRepo: PubkyRepo,
    private val privatePaykitRepo: PrivatePaykitRepo,
    private val sharingRepo: ContactPaymentSettingsRepo,
    private val removalStore: ProfileRemovalStore,
) {
    suspend fun prepareDeletion(owner: PubkyProfileOwner): Result<ProfileRemovalConfirmation> = runSuspendCatching {
        removalStore.requireNoPending()
        check(pubkyRepo.isProfileOwnerCurrent(owner)) { "The profile identity changed" }
        val deletion = pubkyRepo.prepareProfileDeletion(owner).getOrThrow()
        removalStore.requireNoPending()
        check(pubkyRepo.isProfileOwnerCurrent(owner)) { "The profile identity changed" }
        ProfileRemovalConfirmation(owner, UUID.randomUUID().toString(), deletion, localOnly = false)
    }

    suspend fun prepareSignOut(owner: PubkyProfileOwner): Result<ProfileRemovalConfirmation> = runSuspendCatching {
        check(pubkyRepo.isProfileOwnerCurrent(owner)) { "The profile identity changed" }
        val pending = removalStore.pendingAttempt()
        check(pubkyRepo.isProfileOwnerCurrent(owner)) { "The profile identity changed" }
        ProfileRemovalConfirmation(
            owner,
            pending ?: UUID.randomUUID().toString(),
            deletion = null,
            localOnly = pending != null || owner.signIn == null,
        )
    }

    /** Re-read the original identity's remote state, then require a new user confirmation. */
    suspend fun reviewAgain(failure: ProfileRemovalFailure): Result<ProfileRemovalConfirmation> = runSuspendCatching {
        check(failure.canReview) { "Finish disconnecting this profile locally" }
        val previous = failure.confirmation
        check(previous.deletion != null) { "Disconnect this profile locally to finish cleanup" }
        var deletion: PubkyProfileDeletion? = null
        pubkyRepo.performProfileRemoval(previous.owner, previous.attemptId) {
            val fresh = pubkyRepo.prepareProfileDeletion(previous.owner).getOrThrow()
            val confirmed = checkNotNull(previous.reviewCeiling.get()).snapshot
            val remaining = fresh.snapshot
            check(remaining.publicKey == confirmed.publicKey) { "The profile identity changed" }
            check(remaining.profileRevision == null || remaining.profileRevision == confirmed.profileRevision) {
                "The profile changed after confirmation. Disconnect locally to finish cleanup."
            }
            check(confirmed.contacts.containsAll(remaining.contacts)) {
                "Contacts changed after confirmation. Disconnect locally to finish cleanup."
            }
            // Missing records leave this attempt's scope; later reviews cannot add them back.
            previous.reviewCeiling.set(fresh)
            deletion = fresh
        }.getOrThrow()
        ProfileRemovalConfirmation(
            previous.owner,
            previous.attemptId,
            deletion,
            previous.localOnly,
            previous.reviewCeiling,
            privateWithdrawalAcknowledged = true,
        )
    }

    suspend fun prepareLocalDisconnect(owner: PubkyProfileOwner): Result<ProfileRemovalConfirmation> =
        runSuspendCatching {
            check(pubkyRepo.isProfileOwnerCurrent(owner)) { "The profile identity changed" }
            removalStore.requireNoPending()
            check(pubkyRepo.isProfileOwnerCurrent(owner)) { "The profile identity changed" }
            ProfileRemovalConfirmation(owner, UUID.randomUUID().toString(), deletion = null, localOnly = true)
        }

    suspend fun prepareLocalDisconnect(failure: ProfileRemovalFailure): Result<ProfileRemovalConfirmation> =
        runSuspendCatching {
            val pending = removalStore.pendingAttempt()
            check(pending == null || pending == failure.confirmation.attemptId) {
                "The pending profile cleanup changed"
            }
            check(pubkyRepo.isProfileOwnerCurrent(failure.recoveryOwner)) { "The profile identity changed" }
            ProfileRemovalConfirmation(
                failure.recoveryOwner,
                failure.confirmation.attemptId,
                deletion = null,
                localOnly = true,
                reviewCeiling = failure.confirmation.reviewCeiling,
            )
        }

    suspend fun remove(confirmation: ProfileRemovalConfirmation): Result<PubkyProfileOwner> = runSuspendCatching {
        check(confirmation.used.compareAndSet(false, true)) { "Review this profile removal before trying again" }
        sharingRepo.withProfileRemoval {
            pubkyRepo.performProfileRemoval(confirmation.owner, confirmation.attemptId) {
                var localCompletionStarted = false
                var privateWithdrawalAcknowledged = confirmation.privateWithdrawalAcknowledged
                var removalMayHaveStarted = removalStore.pendingAttempt() != null
                try {
                    check(confirmation.deletion == null || confirmation.reviewCeiling.get() === confirmation.deletion) {
                        "This confirmation was replaced by a later review"
                    }
                    privatePaykitRepo.withProfileDeletionPaused { pause ->
                        confirmation.deletion?.let { pubkyRepo.validateProfileDeletion(it) }
                        removalMayHaveStarted = true
                        removalStore.begin(confirmation.attemptId)
                        if (!confirmation.localOnly) {
                            if (!privateWithdrawalAcknowledged) {
                                privatePaykitRepo.removePublishedEndpointsForProfileRemoval().getOrThrow()
                                privateWithdrawalAcknowledged = true
                            }
                            pubkyRepo.removeBitkitPaymentEndpoints().getOrThrow()
                            confirmation.deletion?.let { pubkyRepo.deleteProfile(it).getOrThrow() }
                            pubkyRepo.signOutForProfileRemoval()
                        }
                        localCompletionStarted = true
                        removalStore.beginLocalCleanup()
                        pubkyRepo.finishProfileRemovalLocally()
                        withContext(NonCancellable) {
                            privatePaykitRepo.finishProfileRemoval(pause).getOrThrow()
                            removalStore.complete(confirmation.attemptId)
                            privatePaykitRepo.notifyProfileRemovalCompleted(pause)
                            pubkyRepo.notifyProfileRemovalCompleted()
                        }
                    }
                } catch (error: CancellationException) {
                    if (removalMayHaveStarted) {
                        captureFailure(
                            error,
                            confirmation,
                            localCompletionStarted,
                            privateWithdrawalAcknowledged
                        )
                    }
                    throw error
                } catch (error: Throwable) {
                    if (!removalMayHaveStarted) throw error
                    throw captureFailure(error, confirmation, localCompletionStarted, privateWithdrawalAcknowledged)
                }
            }.getOrThrow()
        }
    }

    private fun captureFailure(
        error: Throwable,
        confirmation: ProfileRemovalConfirmation,
        localCompletionStarted: Boolean,
        privateWithdrawalAcknowledged: Boolean,
    ): ProfileRemovalFailure {
        // Admission still excludes replacements, even when our own cleanup has ended the sign-in.
        val failure = ProfileRemovalFailure(
            error,
            confirmation,
            pubkyRepo.profileOwner(),
            localCompletionStarted,
            privateWithdrawalAcknowledged,
        )
        confirmation.failure.set(failure)
        return failure
    }

    fun failureFor(confirmation: ProfileRemovalConfirmation): ProfileRemovalFailure? = confirmation.failure.get()

    fun isCurrent(owner: PubkyProfileOwner): Boolean = pubkyRepo.isProfileOwnerCurrent(owner)
}

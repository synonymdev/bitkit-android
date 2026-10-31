package to.bitkit.usecases

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import to.bitkit.data.ProfileRemovalStore
import to.bitkit.ext.runSuspendCatching
import to.bitkit.repositories.ContactPaymentSettingsRepo
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyProfileDeletion
import to.bitkit.repositories.PubkyProfileOwner
import to.bitkit.repositories.PubkyRepo
import to.bitkit.services.PaykitProfileLogout
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
    internal val intent: ProfileRemovalIntent? = null,
) {
    internal val used = AtomicBoolean(false)
    internal val failure = AtomicReference<ProfileRemovalFailure?>(null)

    init {
        intent?.let {
            synchronized(it) {
                check(!it.executing.get()) { "Profile removal is still in progress" }
                it.confirmation.set(this)
            }
        }
    }
}

internal class ProfileRemovalIntent(
    val owner: PubkyProfileOwner,
    val attemptId: String,
    val logout: PaykitProfileLogout,
    val deletingProfile: Boolean,
) {
    val confirmation = AtomicReference<ProfileRemovalConfirmation?>(null)
    val started = AtomicBoolean(false)
    val privateWithdrawal = AtomicBoolean(false)
    val publicEndpoints = AtomicBoolean(false)
    val privateSharingDisabled = AtomicBoolean(false)
    val profileDeleted = AtomicBoolean(false)
    val logoutStarted = AtomicBoolean(false)
    val executing = AtomicBoolean(false)
    val retryReady = AtomicBoolean(false)

    val canRetryRemoteWork: Boolean
        get() = logoutStarted.get() || (!deletingProfile && privateWithdrawal.get())
}

class ProfileRemovalFailure internal constructor(
    cause: Throwable,
    internal val confirmation: ProfileRemovalConfirmation,
    internal val recoveryOwner: PubkyProfileOwner,
    internal val localCompletionStarted: Boolean,
    private val privateWithdrawalAcknowledged: Boolean,
) : AppError("Profile removal did not finish. Review its state or disconnect locally.", cause) {
    internal val canReview: Boolean
        get() {
            val intent = confirmation.intent ?: return false
            return confirmation.deletion != null && !localCompletionStarted && privateWithdrawalAcknowledged &&
                intent.logout.isCurrent() && !intent.executing.get() &&
                (!intent.logoutStarted.get() || intent.retryReady.get()) &&
                !intent.logout.reconcileAcknowledgement()
        }
}

internal class ProfileRemovalLocalOnly(cause: Throwable) :
    AppError("Remote profile removal is unavailable. Disconnect locally to remove access from this device.", cause)

/** Remote work never resumes automatically from the durable local cleanup obligation. */
@Singleton
class ProfileRemovalCoordinator @Inject constructor(
    private val pubkyRepo: PubkyRepo,
    private val privatePaykitRepo: PrivatePaykitRepo,
    private val sharingRepo: ContactPaymentSettingsRepo,
    private val removalStore: ProfileRemovalStore,
) {
    private val retainedIntent = AtomicReference<ProfileRemovalIntent?>(null)
    private val _retryOwner = MutableStateFlow<PubkyProfileOwner?>(null)
    val retryOwner = _retryOwner.asStateFlow()

    suspend fun prepareDeletion(owner: PubkyProfileOwner): Result<ProfileRemovalConfirmation> = runSuspendCatching {
        removalStore.requireNoPending()
        check(pubkyRepo.isProfileOwnerCurrent(owner)) { "The profile identity changed" }
        val deletion = pubkyRepo.prepareProfileDeletion(owner).getOrThrow()
        removalStore.requireNoPending()
        check(pubkyRepo.isProfileOwnerCurrent(owner)) { "The profile identity changed" }
        val intent = captureIntent(owner, deletingProfile = true)
        ProfileRemovalConfirmation(owner, intent.attemptId, deletion, localOnly = false, intent = intent)
    }

    suspend fun prepareSignOut(owner: PubkyProfileOwner): Result<ProfileRemovalConfirmation> = runSuspendCatching {
        check(pubkyRepo.isProfileOwnerCurrent(owner)) { "The profile identity changed" }
        val pending = removalStore.pendingAttempt()
        check(pubkyRepo.isProfileOwnerCurrent(owner)) { "The profile identity changed" }
        if (pending != null || owner.signIn == null) {
            val intent = retainedIntent.get()?.takeIf { it.attemptId == pending && isCurrent(it.owner) }
            ProfileRemovalConfirmation(
                owner,
                pending ?: UUID.randomUUID().toString(),
                deletion = null,
                localOnly = true,
                intent = intent,
            )
        } else {
            val intent = runSuspendCatching { captureIntent(owner) }.getOrElse {
                if (it !is ProfileRemovalLocalOnly) throw it
                null
            }
            ProfileRemovalConfirmation(
                owner,
                intent?.attemptId ?: UUID.randomUUID().toString(),
                deletion = null,
                localOnly = intent == null,
                intent = intent,
            )
        }
    }

    private suspend fun captureIntent(
        owner: PubkyProfileOwner,
        deletingProfile: Boolean = false,
    ): ProfileRemovalIntent {
        retainedIntent.get()?.takeUnless { it.logout.isCurrent() }?.let {
            retainedIntent.compareAndSet(it, null)
        }
        val logout = pubkyRepo.captureProfileLogout(owner).getOrElse { throw ProfileRemovalLocalOnly(it) }
        return try {
            check(isCurrent(owner)) { "The profile identity changed" }
            removalStore.requireNoPending()
            ProfileRemovalIntent(owner, UUID.randomUUID().toString(), logout, deletingProfile).also {
                check(retainedIntent.compareAndSet(null, it)) { "A profile removal confirmation is already active" }
            }
        } catch (error: Throwable) {
            logout.revoke()
            throw error
        }
    }

    fun canRetry(owner: PubkyProfileOwner?): Boolean {
        val intent = retainedIntent.get() ?: return false
        return owner != null && isCurrent(owner) && isCurrent(intent.owner) && intent.canRetryRemoteWork &&
            intent.retryReady.get() && !intent.executing.get() &&
            intent.logout.isCurrent() && !intent.logout.reconcileAcknowledgement()
    }

    /** A new confirmation may retry the same live grant; restart state alone cannot reach this path. */
    suspend fun prepareRemoteRetry(owner: PubkyProfileOwner): Result<ProfileRemovalConfirmation> = runSuspendCatching {
        check(canRetry(owner)) { "The original remote logout is no longer available" }
        val intent = checkNotNull(retainedIntent.get())
        check(removalStore.pendingAttempt() == intent.attemptId) { "The pending profile cleanup changed" }
        synchronized(intent) {
            check(canRetry(owner)) { "The profile identity changed" }
            ProfileRemovalConfirmation(
                intent.owner,
                intent.attemptId,
                deletion = null,
                localOnly = false,
                intent = intent
            )
        }
    }

    fun discard(confirmation: ProfileRemovalConfirmation?) {
        if (confirmation == null || confirmation.used.get()) return
        val intent = confirmation.intent ?: return
        if (intent.confirmation.get() !== confirmation || intent.started.get()) return
        if (confirmation.failure.get() != null || confirmation.privateWithdrawalAcknowledged) return
        if (retainedIntent.compareAndSet(intent, null)) {
            intent.logout.revoke()
            _retryOwner.update { null }
        }
    }

    /** Re-read the original identity's remote state, then require a new user confirmation. */
    suspend fun reviewAgain(failure: ProfileRemovalFailure): Result<ProfileRemovalConfirmation> = runSuspendCatching {
        check(failure.canReview) { "Finish disconnecting this profile locally" }
        val previous = failure.confirmation
        val intent = checkNotNull(previous.intent) { "The original removal session is no longer available" }
        intent.logout.requireCurrent()
        if (intent.logoutStarted.get()) {
            check(canRetry(previous.owner) && removalStore.pendingAttempt() == intent.attemptId) {
                "The original remote logout is no longer available"
            }
            return@runSuspendCatching ProfileRemovalConfirmation(
                previous.owner,
                previous.attemptId,
                previous.deletion,
                localOnly = false,
                reviewCeiling = previous.reviewCeiling,
                privateWithdrawalAcknowledged = true,
                intent = intent,
            )
        }
        check(previous.deletion != null) { "Disconnect this profile locally to finish cleanup" }
        var deletion: PubkyProfileDeletion? = null
        pubkyRepo.performProfileRemoval(previous.owner, previous.attemptId) {
            withContext(intent.logout) {
                pubkyRepo.validateProfileLogout(intent.logout)
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
            }
        }.getOrThrow()
        ProfileRemovalConfirmation(
            previous.owner,
            previous.attemptId,
            deletion,
            previous.localOnly,
            previous.reviewCeiling,
            privateWithdrawalAcknowledged = true,
            intent = intent,
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
                intent = failure.confirmation.intent,
            )
        }

    suspend fun remove(confirmation: ProfileRemovalConfirmation): Result<PubkyProfileOwner> = runSuspendCatching {
        check(confirmation.used.compareAndSet(false, true)) { "Review this profile removal before trying again" }
        val intent = confirmation.intent
        intent?.let {
            synchronized(it) {
                check(it.confirmation.get() === confirmation) { "This confirmation was replaced" }
                check(it.executing.compareAndSet(false, true)) { "Profile removal is still in progress" }
                it.retryReady.set(false)
            }
        }
        _retryOwner.update { null }
        try {
            sharingRepo.withProfileRemoval {
                pubkyRepo.performProfileRemoval(confirmation.owner, confirmation.attemptId) {
                    var localCompletionStarted = false
                    var privateWithdrawalAcknowledged =
                        intent?.privateWithdrawal?.get() == true || confirmation.privateWithdrawalAcknowledged
                    var removalMayHaveStarted = removalStore.pendingAttempt() != null
                    try {
                        check(
                            confirmation.deletion == null || confirmation.reviewCeiling.get() === confirmation.deletion
                        ) {
                            "This confirmation was replaced by a later review"
                        }
                        privatePaykitRepo.withProfileDeletionPaused<Unit> { pause ->
                            if (!confirmation.localOnly) {
                                withContext(checkNotNull(intent).logout) {
                                    pubkyRepo.validateProfileLogout(intent.logout)
                                    if (!intent.profileDeleted.get()) {
                                        confirmation.deletion?.let { pubkyRepo.validateProfileDeletion(it) }
                                    }
                                }
                            } else if (intent?.profileDeleted?.get() != true) {
                                confirmation.deletion?.let { pubkyRepo.validateProfileDeletion(it) }
                            }
                            removalMayHaveStarted = true
                            intent?.started?.set(true)
                            removalStore.begin(confirmation.attemptId)
                            if (!confirmation.localOnly) {
                                val original = checkNotNull(intent) { "An original live logout is required" }
                                withContext(original.logout) {
                                    original.logout.requireCurrent()
                                    if (!original.logoutStarted.get()) {
                                        if (!privateWithdrawalAcknowledged) {
                                            privatePaykitRepo.removePublishedEndpointsForProfileRemoval {
                                                original.privateWithdrawal.set(true)
                                                privateWithdrawalAcknowledged = true
                                            }.getOrThrow()
                                        }
                                        pubkyRepo.removeBitkitPaymentEndpointsForRemoval(
                                            original.publicEndpoints.get(),
                                            original.privateSharingDisabled.get(),
                                            { original.publicEndpoints.set(true) },
                                            { original.privateSharingDisabled.set(true) },
                                        ).getOrThrow()
                                        if (!original.profileDeleted.get()) {
                                            confirmation.deletion?.let {
                                                pubkyRepo.deleteProfile(
                                                    it
                                                ) { original.profileDeleted.set(true) }.getOrThrow()
                                            }
                                        }
                                        check(
                                            original.privateWithdrawal.get() && original.publicEndpoints.get() &&
                                                original.privateSharingDisabled.get()
                                        ) { "Profile cleanup is not acknowledged" }
                                        original.logoutStarted.set(true)
                                    }
                                    pubkyRepo.executeProfileLogout(original.logout)
                                }
                            } else {
                                intent?.logout?.revoke()
                            }
                            localCompletionStarted = true
                            removalStore.beginLocalCleanup()
                            if (intent == null) {
                                pubkyRepo.finishProfileRemovalLocally()
                            } else {
                                pubkyRepo.finishProfileRemovalLocally(intent.logout, confirmation.localOnly)
                            }
                            withContext(NonCancellable) {
                                privatePaykitRepo.finishProfileRemoval(pause).getOrThrow()
                                removalStore.complete(confirmation.attemptId)
                                privatePaykitRepo.notifyProfileRemovalCompleted(pause)
                                pubkyRepo.notifyProfileRemovalCompleted()
                                retainedIntent.compareAndSet(intent, null)
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
        } finally {
            intent?.let {
                // Publish retry eligibility only after native work and every outer cleanup barrier have drained.
                synchronized(it) {
                    it.executing.set(false)
                    if (!it.started.get() && retainedIntent.compareAndSet(it, null)) it.logout.revoke()
                    it.retryReady.set(
                        confirmation.failure.get() != null && it.canRetryRemoteWork &&
                            it.logout.isCurrent() && !it.logout.reconcileAcknowledgement()
                    )
                }
                if (canRetry(confirmation.owner)) _retryOwner.update { confirmation.owner }
            }
        }
    }.onFailure {
        val intent = confirmation.intent
        if (intent != null && !intent.started.get() && intent.confirmation.get() === confirmation &&
            retainedIntent.compareAndSet(intent, null)
        ) {
            intent.logout.revoke()
            _retryOwner.update { null }
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

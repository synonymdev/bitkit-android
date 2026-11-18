package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import to.bitkit.async.appScope
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.hasPublicPaykitPublicationState
import to.bitkit.data.paykitDisabled
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.runSuspendCatching
import to.bitkit.flags.PaykitFeatureFlags
import to.bitkit.services.PaykitSdkOperationLock.Priority
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ContactPaymentSettingsRepo"

@Singleton
class ContactPaymentSettingsRepo @Inject constructor(
    private val settingsStore: SettingsStore,
    private val publicPaykitRepo: PublicPaykitRepo,
    private val privatePaykitRepo: PrivatePaykitRepo,
    private val pubkyRepo: PubkyRepo,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val sharingMutex = Mutex()
    private val scope = appScope(ioDispatcher, TAG)
    private var newProfileJob: Job? = null

    /**
     * Turns private and public payments on for a profile just set up in this app. Both settings are written
     * together first, so both modes count as on right away; the syncs then run in the background so profile
     * setup never waits on endpoint publishing. Each mode rolls only itself back if its sync fails, and the
     * foreground check in [to.bitkit.usecases.KeepContactPaymentsOnUseCase] turns it on again later.
     * A call while a previous one is still running is ignored.
     */
    @Synchronized
    fun enableForNewProfile() {
        if (newProfileJob?.isActive == true) return
        newProfileJob = scope.launch {
            sharingMutex.withLock { if (isPaykitUiEnabled()) enableBothForNewProfile() }
        }
    }

    /**
     * Checked under [sharingMutex] before any enable. Dev Settings stores the flag before [disablePaykit] queues on
     * the mutex, so an enable that gets the lock after a Paykit disable leaves sharing off.
     */
    private suspend fun isPaykitUiEnabled(): Boolean =
        PaykitFeatureFlags.isUiEnabled(settingsStore.isPaykitEnabled.first())

    private suspend fun enableBothForNewProfile() {
        val signIn = pubkyRepo.currentSignIn()
        val canSync = {
            signIn != null && pubkyRepo.isCurrent(signIn) && !privatePaykitRepo.isProfileDeletionInProgress.value
        }
        val previous = settingsStore.data.first()
        val contacts = pubkyRepo.contacts.value.map { it.publicKey }
        var publicCompleted = false
        try {
            runSuspendCatching {
                withContext(NonCancellable) {
                    settingsStore.update {
                        if (!canSync()) throw PublicPaykitError.SessionNotActive
                        it.copy(
                            sharesPrivatePaykitEndpoints = true,
                            hasConfirmedPublicPaykitEndpoints = true,
                            sharesPublicPaykitEndpoints = true,
                            publicPaykitCleanupPending = true,
                        )
                    }
                }
            }.onFailure {
                publicCompleted = true
                Logger.error("Failed to enable payments for new profile", it, context = TAG)
                return
            }

            finishPrivateEnable(previous, contacts, canSync).onFailure {
                Logger.error("Failed to enable private payments for new profile", it, context = TAG)
            }

            runSuspendCatching {
                currentCoroutineContext().ensureActive()
                publicPaykitRepo.syncPublishedEndpoints(true, Priority.Background, canSync).getOrThrow()
            }.onFailure {
                rollbackPublicEnabled(previous, canSync, it)
                Logger.error("Failed to enable public payments for new profile", it, context = TAG)
            }.also { publicCompleted = true }
        } finally {
            if (!publicCompleted) {
                withContext(NonCancellable) { restorePublicSettings(previous, canSync) }
            }
        }
    }

    /** Completes private sharing whose setting is already on, restoring only the private setting on failure. */
    private suspend fun finishPrivateEnable(
        previous: SettingsData,
        contacts: List<String>,
        canSync: () -> Boolean,
    ): Result<Unit> = runSuspendCatching {
        if (!privatePaykitRepo.hasPrivatePaymentAccess()) throw PrivatePaykitError.PrivateUnavailable
        publicPaykitRepo.syncPaykitApp(priority = Priority.Background, canSync = { canSync() }).getOrThrow()
        prepareCurrentSavedContacts(canSync).getOrThrow()
    }.onFailure {
        if (it is PrivatePaykitError.PrivateUnavailable) {
            restorePrivateSetting(previous, canSync, it)
        } else {
            rollbackEnabled(previous, contacts, canSync, it)
        }
    }

    private suspend fun restorePrivateSetting(previous: SettingsData, canSync: () -> Boolean, error: Throwable) {
        runSuspendCatching {
            settingsStore.update {
                if (canSync()) it.copy(sharesPrivatePaykitEndpoints = previous.sharesPrivatePaykitEndpoints) else it
            }
        }.onFailure(error::addSuppressed)
    }

    suspend fun setPublicEnabled(isEnabled: Boolean): Result<Unit> = withContext(ioDispatcher) {
        sharingMutex.withLock {
            if (isEnabled && !isPaykitUiEnabled()) return@withLock Result.failure(PublicPaykitError.PaykitDisabled)
            val signIn = pubkyRepo.currentSignIn()
            val previous = settingsStore.data.first()
            val canPublish = {
                signIn != null && pubkyRepo.isCurrent(signIn) && !privatePaykitRepo.isProfileDeletionInProgress.value
            }
            val canUpdateSettings = {
                if (signIn == null) {
                    !isEnabled && pubkyRepo.currentSignIn() == null &&
                        !privatePaykitRepo.isProfileDeletionInProgress.value
                } else {
                    canPublish()
                }
            }
            var enabledPersisted = false
            var completed = false
            try {
                runSuspendCatching {
                    currentCoroutineContext().ensureActive()
                    if (!canUpdateSettings()) throw PublicPaykitError.SessionNotActive
                    withContext(NonCancellable) {
                        settingsStore.update {
                            if (!canUpdateSettings()) throw PublicPaykitError.SessionNotActive
                            it.copy(
                                hasConfirmedPublicPaykitEndpoints = isEnabled,
                                sharesPublicPaykitEndpoints = isEnabled,
                                publicPaykitCleanupPending = true,
                            )
                        }
                        enabledPersisted = isEnabled
                    }
                    currentCoroutineContext().ensureActive()
                    if (isEnabled) {
                        publicPaykitRepo.syncPublishedEndpoints(true, Priority.Ordered, canPublish).getOrThrow()
                    } else {
                        publicPaykitRepo.syncPublishedEndpoints(false, Priority.Interactive, canPublish).getOrThrow()
                    }
                }.onFailure {
                    if (enabledPersisted) rollbackPublicEnabled(previous, canPublish, it)
                }.also { completed = true }
            } finally {
                if (enabledPersisted && !completed) {
                    withContext(NonCancellable) { restorePublicSettings(previous, canPublish) }
                }
            }
        }
    }

    private suspend fun restorePublicSettings(previous: SettingsData, canRestore: () -> Boolean) {
        settingsStore.update {
            if (canRestore()) {
                it.copy(
                    hasConfirmedPublicPaykitEndpoints = previous.hasConfirmedPublicPaykitEndpoints,
                    sharesPublicPaykitEndpoints = previous.sharesPublicPaykitEndpoints,
                    publicPaykitCleanupPending = true,
                )
            } else {
                it
            }
        }
    }

    private suspend fun rollbackPublicEnabled(
        previous: SettingsData,
        canRestore: () -> Boolean,
        error: Throwable,
    ) {
        runSuspendCatching {
            restorePublicSettings(previous, canRestore)
            if (!canRestore()) return@runSuspendCatching
            if (previous.sharesPublicPaykitEndpoints) {
                publicPaykitRepo.syncCurrentPublishedEndpoints(canPublish = canRestore).getOrThrow()
            } else {
                publicPaykitRepo.syncPublishedEndpoints(false, Priority.Interactive, canRestore).getOrThrow()
            }
        }.onFailure(error::addSuppressed)
    }

    suspend fun setEnabled(isEnabled: Boolean): Result<Unit> = withContext(ioDispatcher) {
        sharingMutex.withLock {
            if (isEnabled && !isPaykitUiEnabled()) return@withLock Result.failure(PublicPaykitError.PaykitDisabled)
            val contacts = pubkyRepo.contacts.value.map { it.publicKey }
            if (isEnabled) enable(contacts) else disable(contacts)
        }
    }

    suspend fun reconcilePendingEndpoints(reconcile: suspend () -> Unit) = withContext(ioDispatcher) {
        if (!sharingMutex.tryLock()) return@withContext
        try {
            reconcile()
        } finally {
            sharingMutex.unlock()
        }
    }

    suspend fun disablePaykit(): Result<Unit> = withContext(ioDispatcher) {
        sharingMutex.withLock {
            runSuspendCatching {
                val previous = settingsStore.data.first()
                val hadPublicState = previous.hasPublicPaykitPublicationState()
                settingsStore.update { it.paykitDisabled(markPublicCleanupPending = hadPublicState) }
                val contacts = pubkyRepo.contacts.value.map { it.publicKey }
                val privateCleanup = privatePaykitRepo.disableSharingAndPruneUnsavedContactState(contacts)
                val publicCleanup = when {
                    hadPublicState -> publicPaykitRepo.syncPublishedEndpoints(
                        publish = false,
                        appSyncPriority = Priority.Interactive,
                    )
                    previous.sharesPrivatePaykitEndpoints -> publicPaykitRepo.syncPaykitApp(
                        privateSharingEnabled = false,
                    )
                    else -> Result.success(Unit)
                }
                settingsStore.update { it.copy(publicPaykitCleanupPending = publicCleanup.isFailure) }
                publicCleanup.exceptionOrNull()?.let { error ->
                    privateCleanup.exceptionOrNull()?.let(error::addSuppressed)
                    throw error
                }
                privateCleanup.getOrThrow()
            }
        }
    }

    private suspend fun enable(contacts: List<String>): Result<Unit> {
        val signIn = pubkyRepo.currentSignIn()
        val canSync = {
            signIn != null && pubkyRepo.isCurrent(signIn) && !privatePaykitRepo.isProfileDeletionInProgress.value
        }
        val previous = settingsStore.data.first()
        val canUsePrivateContactPayments = runSuspendCatching {
            privatePaykitRepo.hasPrivatePaymentAccess()
        }.getOrElse { return Result.failure(it) }
        if (!canUsePrivateContactPayments) return Result.failure(PrivatePaykitError.PrivateUnavailable)
        return runSuspendCatching {
            settingsStore.update {
                if (!canSync()) throw PublicPaykitError.SessionNotActive
                it.copy(
                    sharesPrivatePaykitEndpoints = true,
                )
            }
            publicPaykitRepo.syncPaykitApp(canSync = { canSync() }).getOrThrow()

            prepareCurrentSavedContacts(canSync).getOrThrow()
        }.onFailure { rollbackEnabled(previous, contacts, canSync, it) }
    }

    private suspend fun prepareCurrentSavedContacts(canSync: () -> Boolean): Result<Unit> {
        if (!canSync()) return Result.success(Unit)
        return privatePaykitRepo.enableSharingAndPrepareSavedContacts(
            savedPublicKeys = { pubkyRepo.contacts.value.map { it.publicKey }.toSet() },
            isStillCurrent = canSync,
        )
    }

    private suspend fun rollbackEnabled(
        previous: SettingsData,
        contacts: List<String>,
        canSync: () -> Boolean,
        error: Throwable,
    ) {
        runSuspendCatching {
            settingsStore.update {
                if (!canSync()) {
                    it
                } else {
                    it.copy(
                        sharesPrivatePaykitEndpoints = previous.sharesPrivatePaykitEndpoints,
                    )
                }
            }
        }.onFailure(error::addSuppressed)
        if (!previous.sharesPrivatePaykitEndpoints) {
            privatePaykitRepo.disableSharingAndPruneUnsavedContactState(contacts, canSync)
                .onFailure(error::addSuppressed)
        }
        publicPaykitRepo.syncPaykitApp(priority = Priority.Interactive, canSync = { canSync() })
            .onFailure {
                error.addSuppressed(it)
                markPublicPaykitRetry(error, canSync)
            }
        if (previous.sharesPrivatePaykitEndpoints) {
            prepareCurrentSavedContacts(canSync).onFailure(error::addSuppressed)
        }
    }

    private suspend fun disable(contacts: List<String>): Result<Unit> {
        val signIn = pubkyRepo.currentSignIn()
        val canSync = {
            signIn != null && pubkyRepo.isCurrent(signIn) && !privatePaykitRepo.isProfileDeletionInProgress.value
        }
        runSuspendCatching {
            settingsStore.update {
                if (privatePaykitRepo.isProfileDeletionInProgress.value ||
                    (if (signIn == null) pubkyRepo.currentSignIn() != null else !canSync())
                ) {
                    throw PublicPaykitError.SessionNotActive
                }
                it.copy(
                    sharesPrivatePaykitEndpoints = false,
                    publicPaykitCleanupPending = it.publicPaykitCleanupPending || signIn == null,
                )
            }
        }.onFailure {
            return Result.failure(it)
        }

        var publicCleanupError: Throwable? = null
        var privateCleanupError: Throwable? = null
        privatePaykitRepo.disableSharingAndPruneUnsavedContactState(contacts, canSync)
            .onFailure { privateCleanupError = it }

        publicPaykitRepo.syncPaykitApp(priority = Priority.Interactive, canSync = { canSync() })
            .onFailure { publicCleanupError = it }

        publicCleanupError?.let { error ->
            markPublicPaykitRetry(error, canSync)
        }
        val cleanupError = publicCleanupError ?: privateCleanupError
        publicCleanupError?.let { publicError ->
            privateCleanupError?.let { publicError.addSuppressed(it) }
        }
        cleanupError?.let { return Result.failure(it) }

        return Result.success(Unit)
    }

    private suspend fun markPublicPaykitRetry(error: Throwable, canSync: () -> Boolean) {
        runSuspendCatching {
            settingsStore.update { if (canSync()) it.copy(publicPaykitCleanupPending = true) else it }
        }.onFailure(error::addSuppressed)
    }
}

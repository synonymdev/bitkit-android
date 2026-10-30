package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.areContactPaymentsEnabled
import to.bitkit.data.hasPublicPaykitPublicationState
import to.bitkit.data.paykitDisabled
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.runSuspendCatching
import to.bitkit.services.PaykitSdkOperationLock.Priority
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactPaymentSettingsRepo @Inject constructor(
    private val settingsStore: SettingsStore,
    private val publicPaykitRepo: PublicPaykitRepo,
    private val privatePaykitRepo: PrivatePaykitRepo,
    private val pubkyRepo: PubkyRepo,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val sharingMutex = Mutex()

    val isEnabled: Flow<Boolean> = settingsStore.data.map { it.areContactPaymentsEnabled() }
    val isPublicEnabled: Flow<Boolean> = settingsStore.data.map { it.sharesPublicPaykitEndpoints }

    suspend fun setPublicEnabled(isEnabled: Boolean): Result<Unit> = withContext(ioDispatcher) {
        sharingMutex.withLock {
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

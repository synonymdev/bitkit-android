package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.areContactPaymentsEnabled
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.runSuspendCatching
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
    val isEnabled: Flow<Boolean> = settingsStore.data.map { it.areContactPaymentsEnabled() }

    suspend fun setEnabled(isEnabled: Boolean): Result<Unit> = withContext(ioDispatcher) {
        val contacts = pubkyRepo.contacts.value.map { it.publicKey }
        if (isEnabled) enable(contacts) else disable(contacts)
    }

    private suspend fun enable(contacts: List<String>): Result<Unit> {
        val previous = settingsStore.data.first()
        publicPaykitRepo.syncPublishedEndpoints(publish = true)
            .onFailure {
                rollbackEnabled(previous, contacts, it)
                return Result.failure(it)
            }

        val canUsePrivateContactPayments = pubkyRepo.hasSecretKey()
        if (canUsePrivateContactPayments) {
            privatePaykitRepo.setContactSharingCleanupPending(false)
                .onFailure {
                    rollbackEnabled(previous, contacts, it)
                    return Result.failure(it)
                }
        }

        runSuspendCatching {
            settingsStore.update {
                it.copy(
                    hasConfirmedPublicPaykitEndpoints = true,
                    sharesPublicPaykitEndpoints = true,
                    sharesPrivatePaykitEndpoints = canUsePrivateContactPayments,
                    publicPaykitLightningEnabled = true,
                    publicPaykitOnchainEnabled = true,
                )
            }
        }.onFailure {
            rollbackEnabled(previous, contacts, it)
            return Result.failure(it)
        }

        if (canUsePrivateContactPayments) {
            privatePaykitRepo.prepareSavedContacts(contacts)
                .onFailure {
                    rollbackEnabled(previous, contacts, it)
                    return Result.failure(it)
                }
        }

        return Result.success(Unit)
    }

    private suspend fun rollbackEnabled(
        previous: SettingsData,
        contacts: List<String>,
        error: Throwable,
    ) {
        runSuspendCatching {
            settingsStore.update {
                it.copy(
                    hasConfirmedPublicPaykitEndpoints = previous.hasConfirmedPublicPaykitEndpoints,
                    sharesPublicPaykitEndpoints = previous.sharesPublicPaykitEndpoints,
                    sharesPrivatePaykitEndpoints = previous.sharesPrivatePaykitEndpoints,
                )
            }
        }.onFailure(error::addSuppressed)
        publicPaykitRepo.syncPublishedEndpoints(publish = previous.sharesPublicPaykitEndpoints)
            .onFailure {
                error.addSuppressed(it)
                markPublicPaykitRetry(error)
            }
        if (!previous.sharesPrivatePaykitEndpoints) {
            privatePaykitRepo.disableSharingAndPruneUnsavedContactState(contacts)
                .onFailure(error::addSuppressed)
        }
    }

    private suspend fun disable(contacts: List<String>): Result<Unit> {
        val previous = settingsStore.data.first()
        runSuspendCatching {
            settingsStore.update {
                it.copy(
                    hasConfirmedPublicPaykitEndpoints = true,
                    sharesPublicPaykitEndpoints = false,
                    sharesPrivatePaykitEndpoints = false,
                    publicPaykitLightningEnabled = true,
                    publicPaykitOnchainEnabled = true,
                )
            }
        }.onFailure {
            return Result.failure(it)
        }

        var publicCleanupError: Throwable? = null
        var privateCleanupError: Throwable? = null
        publicPaykitRepo.syncPublishedEndpoints(publish = false)
            .onFailure { publicCleanupError = it }

        privatePaykitRepo.disableSharingAndPruneUnsavedContactState(contacts)
            .onFailure { privateCleanupError = it }

        publicCleanupError?.let { error ->
            runSuspendCatching {
                settingsStore.update { settings ->
                    settings.copy(sharesPublicPaykitEndpoints = previous.sharesPublicPaykitEndpoints)
                }
            }.onFailure(error::addSuppressed)
            publicPaykitRepo.syncPublishedEndpoints(publish = previous.sharesPublicPaykitEndpoints)
                .onFailure {
                    error.addSuppressed(it)
                    markPublicPaykitRetry(error)
                }
        }
        privateCleanupError?.let { error ->
            if (previous.sharesPrivatePaykitEndpoints) {
                restorePrivate(contacts, error)
            } else {
                updatePrivatePreference(isEnabled = false, error = error)
            }
        }

        val cleanupError = publicCleanupError ?: privateCleanupError
        publicCleanupError?.let { publicError ->
            privateCleanupError?.let { publicError.addSuppressed(it) }
        }
        cleanupError?.let { return Result.failure(it) }

        privatePaykitRepo.setContactSharingCleanupPending(false)
            .onFailure { return Result.failure(it) }

        return Result.success(Unit)
    }

    private suspend fun restorePrivate(
        contacts: List<String>,
        error: Throwable,
    ) {
        if (!updatePrivatePreference(isEnabled = true, error = error)) return

        privatePaykitRepo.prepareSavedContacts(
            publicKeys = contacts,
            requireImmediatePublication = true,
        ).exceptionOrNull()?.let {
            error.addSuppressed(it)
            updatePrivatePreference(isEnabled = false, error = error)
            return
        }

        privatePaykitRepo.setContactSharingCleanupPending(false).exceptionOrNull()?.let {
            error.addSuppressed(it)
            updatePrivatePreference(isEnabled = false, error = error)
        }
    }

    private suspend fun markPublicPaykitRetry(error: Throwable) {
        runSuspendCatching {
            settingsStore.update {
                it.copy(
                    sharesPublicPaykitEndpoints = false,
                    publicPaykitCleanupPending = true,
                )
            }
        }.onFailure(error::addSuppressed)
    }

    private suspend fun updatePrivatePreference(
        isEnabled: Boolean,
        error: Throwable,
    ): Boolean = runSuspendCatching {
        settingsStore.update { it.copy(sharesPrivatePaykitEndpoints = isEnabled) }
    }.onFailure(error::addSuppressed).isSuccess
}

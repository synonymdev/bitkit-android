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
        val canUsePrivateContactPayments = privatePaykitRepo.hasPrivatePaymentAccess()
        return runSuspendCatching {
            settingsStore.update {
                it.copy(
                    hasConfirmedPublicPaykitEndpoints = true,
                    sharesPublicPaykitEndpoints = true,
                    sharesPrivatePaykitEndpoints = canUsePrivateContactPayments,
                    publicPaykitLightningEnabled = true,
                    publicPaykitOnchainEnabled = true,
                )
            }
            publicPaykitRepo.syncPublishedEndpoints(publish = true).getOrThrow()

            if (canUsePrivateContactPayments) {
                privatePaykitRepo.enableSharingAndPrepareSavedContacts(
                    publicKeys = contacts,
                ).getOrThrow()
            }
        }.onFailure { rollbackEnabled(previous, contacts, it) }
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
                    publicPaykitLightningEnabled = previous.publicPaykitLightningEnabled,
                    publicPaykitOnchainEnabled = previous.publicPaykitOnchainEnabled,
                )
            }
        }.onFailure(error::addSuppressed)
        publicPaykitRepo.syncPublishedEndpoints(publish = previous.sharesPublicPaykitEndpoints)
            .onFailure {
                error.addSuppressed(it)
                markPublicPaykitRetry(error)
            }
        if (previous.sharesPrivatePaykitEndpoints) {
            privatePaykitRepo.enableSharingAndPrepareSavedContacts(
                publicKeys = contacts,
            ).onFailure(error::addSuppressed)
        } else {
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
        val cleanupError = publicCleanupError ?: privateCleanupError
        publicCleanupError?.let { publicError ->
            privateCleanupError?.let { publicError.addSuppressed(it) }
        }
        cleanupError?.let { return Result.failure(it) }

        return Result.success(Unit)
    }

    private suspend fun markPublicPaykitRetry(error: Throwable) {
        runSuspendCatching {
            settingsStore.update { it.copy(publicPaykitCleanupPending = true) }
        }.onFailure(error::addSuppressed)
    }
}

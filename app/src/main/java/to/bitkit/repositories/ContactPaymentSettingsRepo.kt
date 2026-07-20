package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val transitionScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val transitionMutex = Mutex()

    val isEnabled: Flow<Boolean> = settingsStore.data.map { it.areContactPaymentsEnabled() }

    suspend fun setEnabled(isEnabled: Boolean): Result<Unit> = runTransition {
        if (!isEnabled && !hasDisableWork()) return@runTransition
        val expectedPublicKey = if (isEnabled) {
            pubkyRepo.publicKey.value
                ?: pubkyRepo.currentPublicKey().getOrThrow()
                ?: throw PublicPaykitError.SessionNotActive
        } else {
            null
        }
        settingsStore.update { it.copy(pendingContactPaymentsEnabled = isEnabled) }
        val contacts = pubkyRepo.contacts.value.map { it.publicKey }
        if (isEnabled) {
            enable(contacts, checkNotNull(expectedPublicKey))
        } else {
            completeDisable(contacts)
        }
    }

    private suspend fun hasDisableWork(): Boolean {
        val settings = settingsStore.data.first()
        return settings.pendingContactPaymentsEnabled != null ||
            settings.sharesPublicPaykitEndpoints ||
            settings.sharesPrivatePaykitEndpoints ||
            settings.publicPaykitCleanupPending ||
            settings.publicPaykitBolt11.isNotBlank() ||
            settings.publicPaykitBolt11PaymentHash.isNotBlank() ||
            settings.publicPaykitBolt11ExpiresAtMillis > 0L ||
            privatePaykitRepo.hasPendingEndpointCleanup()
    }

    suspend fun recoverPendingTransition(): Result<Unit> = runTransition {
        settingsStore.data.first().pendingContactPaymentsEnabled ?: return@runTransition
        val contacts = pubkyRepo.contacts.value.map { it.publicKey }
        completeDisable(contacts)
    }

    private suspend fun enable(contacts: List<String>, expectedPublicKey: String) {
        runSuspendCatching {
            completeEnable(contacts, expectedPublicKey)
        }.onFailure { error ->
            runSuspendCatching {
                settingsStore.update { it.copy(pendingContactPaymentsEnabled = false) }
            }.onFailure(error::addSuppressed)
            runSuspendCatching { completeDisable(contacts) }
                .onFailure(error::addSuppressed)
            throw error
        }
    }

    private suspend fun completeEnable(contacts: List<String>, expectedPublicKey: String) {
        requireActiveProfile(expectedPublicKey)
        publicPaykitRepo.syncPublishedEndpoints(publish = true).getOrThrow()
        requireActiveProfile(expectedPublicKey)
        val canUsePrivateContactPayments = pubkyRepo.hasSecretKey()
        if (canUsePrivateContactPayments) {
            privatePaykitRepo.setContactSharingCleanupPending(false).getOrThrow()
        }
        settingsStore.update {
            it.copy(
                hasConfirmedPublicPaykitEndpoints = true,
                sharesPublicPaykitEndpoints = true,
                sharesPrivatePaykitEndpoints = canUsePrivateContactPayments,
                publicPaykitLightningEnabled = true,
                publicPaykitOnchainEnabled = true,
            )
        }
        if (canUsePrivateContactPayments) {
            privatePaykitRepo.prepareSavedContacts(
                publicKeys = contacts,
                requireImmediatePublication = true,
            ).getOrThrow()
        }
        requireActiveProfile(expectedPublicKey)
        settingsStore.update { it.copy(pendingContactPaymentsEnabled = null) }
    }

    private suspend fun requireActiveProfile(expectedPublicKey: String) {
        val currentPublicKey = pubkyRepo.publicKey.value
            ?: pubkyRepo.currentPublicKey().getOrThrow()
        if (currentPublicKey != expectedPublicKey) throw PublicPaykitError.SessionNotActive
    }

    private suspend fun completeDisable(contacts: List<String>) {
        val errors = mutableListOf<Throwable>()
        privatePaykitRepo.setContactSharingCleanupPending(true).onFailure(errors::add)
        runSuspendCatching {
            settingsStore.update {
                it.copy(
                    hasConfirmedPublicPaykitEndpoints = true,
                    sharesPublicPaykitEndpoints = false,
                    sharesPrivatePaykitEndpoints = false,
                    pendingContactPaymentsEnabled = false,
                    publicPaykitCleanupPending = true,
                    publicPaykitLightningEnabled = true,
                    publicPaykitOnchainEnabled = true,
                )
            }
        }.onFailure(errors::add)

        publicPaykitRepo.syncPublishedEndpoints(publish = false).onFailure(errors::add)
        privatePaykitRepo.disableSharingAndPruneUnsavedContactState(contacts).onFailure(errors::add)

        errors.firstOrNull()?.let { error ->
            privatePaykitRepo.setContactSharingCleanupPending(true).onFailure(errors::add)
            errors.drop(1).forEach(error::addSuppressed)
            throw error
        }

        settingsStore.update { it.copy(pendingContactPaymentsEnabled = null) }
    }

    private suspend fun runTransition(block: suspend () -> Unit): Result<Unit> =
        transitionScope.async(start = CoroutineStart.UNDISPATCHED) {
            transitionMutex.withLock {
                runSuspendCatching { block() }
            }
        }.await()
}

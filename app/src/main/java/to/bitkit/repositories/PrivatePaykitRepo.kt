package to.bitkit.repositories

import com.synonym.bitkitcore.Scanner
import com.synonym.paykit.FfiPaymentEndpoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import to.bitkit.App
import to.bitkit.data.PrivatePaykitCacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.toHex
import to.bitkit.models.PrivatePaykitContactLinkBackupV1
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.services.CoreService
import to.bitkit.services.PubkyService
import to.bitkit.utils.Logger
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

private data class PrivatePaymentAttempt(
    val result: Result<PublicPaykitPaymentResult>,
    val shouldDeferPublicFallback: Boolean,
)

private fun StoredPaymentEntry.toFfiPaymentEndpoint() = FfiPaymentEndpoint(
    paymentEndpointIdentifier = methodId,
    paymentEndpointPayload = endpointData,
)

private fun FfiPaymentEndpoint.toStoredPaymentEntry() = StoredPaymentEntry(
    methodId = paymentEndpointIdentifier,
    endpointData = paymentEndpointPayload,
)

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
@Singleton
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
class PrivatePaykitRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val pubkyService: PubkyService,
    private val keychain: Keychain,
    private val cacheStore: PrivatePaykitCacheStore,
    private val settingsStore: SettingsStore,
    private val addressReservationRepo: PrivatePaykitAddressReservationRepo,
    private val lightningRepo: LightningRepo,
    private val walletRepo: WalletRepo,
    private val publicPaykitRepo: PublicPaykitRepo,
    private val coreService: CoreService,
    private val clock: Clock,
) {
    companion object {
        private const val TAG = "PrivatePaykitRepo"
        private const val MAX_RECEIVED_INVOICE_HASHES_PER_CONTACT = 100
        private const val STALE_LINK_FAILURE_THRESHOLD = 3
        private const val HANDSHAKE_COMPLETE = "complete"
        private const val RECOVERY_MARKER_STAGE_INIT = "init"
        private const val RECOVERY_MARKER_STAGE_RESPONSE = "response"
        private const val RECOVERY_MARKER_STAGE_FINAL = "final"
        private const val FRESH_LINK_INITIAL_PUBLISH_DELAY_SECONDS = 8L
        private const val PENDING_PUBLICATION_RETRY_ATTEMPTS = 60
        private const val PRIVATE_PAYMENT_RECOVERY_RETRY_ATTEMPTS = 12
        private val privateInvoiceExpiry = 24.hours
        private val invoiceRefreshBuffer = 30.minutes
        private val pendingPublicationRetryDelay = 5.seconds
        private val privatePaymentRecoveryRetryDelay = 2.seconds

        fun shouldInitiate(ownPublicKey: String, remotePublicKey: String): Boolean {
            val own = PubkyPublicKeyFormat.normalized(ownPublicKey) ?: ownPublicKey
            val remote = PubkyPublicKeyFormat.normalized(remotePublicKey) ?: remotePublicKey
            return own > remote
        }

        fun isDuplicatePaymentError(error: Throwable): Boolean =
            PrivatePaykitErrorClassifier.isDuplicatePaymentError(error)
    }

    private val stateStore = PrivatePaykitStateStore(keychain, cacheStore)
    private val recoveryStore = PrivatePaykitRecoveryStore(pubkyService, keychain) { ensureState() }
    private val activeHandlesByContact = mutableMapOf<String, ContactPaykitHandles>()
    private val knownSavedContactKeys = mutableSetOf<String>()
    private val linkEstablishmentMutex = Mutex()
    private val publicationMutex = Mutex()
    private val serializedDispatcher = ioDispatcher.limitedParallelism(1)
    private val retryScope = CoroutineScope(SupervisorJob() + serializedDispatcher)
    private val pendingPublicationRetryJobs = mutableMapOf<String, Job>()
    private val stateGeneration = AtomicLong(0L)

    private val _backupStateVersion = MutableStateFlow(0L)
    val backupStateVersion: StateFlow<Long> = _backupStateVersion.asStateFlow()

    suspend fun reconcileReservedReceiveIndexes(): Result<Unit> =
        addressReservationRepo.reconcileReservedIndexesWithLdk()

    suspend fun prepareSavedContacts(
        publicKeys: Collection<String>,
        requireImmediatePublication: Boolean = false,
    ): Result<Unit> = withContext(serializedDispatcher) {
        runCatching {
            val keys = rememberSavedContacts(publicKeys, replacing = true)
            if (!canPublishPrivateEndpoints()) {
                if (requireImmediatePublication && keys.isNotEmpty()) throw PrivatePaykitError.PrivateUnavailable
                return@runCatching
            }
            if (isProfileRecoveryPending() && keys.isNotEmpty()) {
                recoverSavedContactsAfterProfileRecreation(
                    publicKeys = keys,
                    requireImmediatePublication = requireImmediatePublication,
                ).getOrThrow()
                return@runCatching
            }
            addressReservationRepo.reconcileReservedIndexesWithLdk().getOrThrow()
            publishLocalEndpoints(
                publicKeys = keys,
                maxAdvanceSteps = 3,
                reason = "prepare",
                requireImmediatePublication = requireImmediatePublication,
            ).getOrThrow()
        }
    }

    private suspend fun recoverSavedContactsAfterProfileRecreation(
        publicKeys: Collection<String>,
        requireImmediatePublication: Boolean,
    ): Result<Unit> = withContext(serializedDispatcher) {
        runCatching {
            val keys = rememberSavedContacts(publicKeys, replacing = true)
            if (keys.isEmpty()) return@runCatching
            if (!canPublishPrivateEndpoints()) return@runCatching

            advanceStateGeneration()
            resetInFlightWork()
            val didPurgeStaleTransport = recoveryStore.purgePrivatePaymentOutboxForProfileRecovery("profile recovery")
            if (!didPurgeStaleTransport) {
                updateProfileRecoveryPending(true)
                if (requireImmediatePublication) throw PrivatePaykitError.PrivateUnavailable
                return@runCatching
            }
            markContactsForProfileRecovery(keys, clock.now().epochSeconds)
            persistState(markWalletBackup = true)
            updateProfileRecoveryPending(false)

            addressReservationRepo.reconcileReservedIndexesWithLdk().getOrThrow()
            publishLocalEndpoints(
                publicKeys = keys,
                maxAdvanceSteps = 3,
                reason = "profile recovery",
                forceLocalPublishWhenRemoteEmpty = true,
                requireImmediatePublication = requireImmediatePublication,
            ).getOrThrow()
        }
    }

    suspend fun enableSharingAndPrepareSavedContacts(
        publicKeys: Collection<String>,
        requireImmediatePublication: Boolean = false,
    ): Result<Unit> =
        withContext(serializedDispatcher) {
            runCatching {
                val wasCleanupPending = isContactSharingCleanupPending()
                if (wasCleanupPending && !canPublishPrivateEndpoints()) {
                    if (requireImmediatePublication) throw PrivatePaykitError.PrivateUnavailable
                    return@runCatching
                }
                updateContactSharingCleanupPending(false)
                prepareSavedContacts(publicKeys, requireImmediatePublication).onFailure {
                    if (wasCleanupPending) {
                        runCatching { updateContactSharingCleanupPending(true) }
                            .onFailure(it::addSuppressed)
                    }
                }.getOrThrow()
            }
        }

    suspend fun refreshSavedContactEndpoints(publicKeys: Collection<String>): Result<Unit> =
        withContext(serializedDispatcher) {
            runCatching {
                val keys = rememberSavedContacts(publicKeys, replacing = true)
                if (!canPublishPrivateEndpoints()) return@runCatching
                if (isProfileRecoveryPending() && keys.isNotEmpty()) {
                    recoverSavedContactsAfterProfileRecreation(keys, requireImmediatePublication = false).getOrThrow()
                    return@runCatching
                }
                publishLocalEndpoints(keys, maxAdvanceSteps = 1, reason = "refresh").getOrThrow()
            }
        }

    suspend fun refreshKnownSavedContactEndpoints(
        reason: String,
        forceRefreshLightning: Boolean = false,
    ): Result<Unit> = withContext(serializedDispatcher) {
        runCatching {
            if (!canPublishPrivateEndpoints()) return@runCatching
            if (isProfileRecoveryPending() && knownSavedContactKeys.isNotEmpty()) {
                recoverSavedContactsAfterProfileRecreation(
                    publicKeys = knownSavedContactKeys.toList(),
                    requireImmediatePublication = false,
                ).getOrThrow()
                return@runCatching
            }
            publishLocalEndpoints(
                publicKeys = knownSavedContactKeys.toList(),
                maxAdvanceSteps = 1,
                reason = reason,
                forceRefreshLightning = forceRefreshLightning,
            ).getOrThrow()
        }.onFailure {
            Logger.warn("Failed to refresh private Paykit endpoints for '$reason'", it, context = TAG)
        }
    }

    suspend fun retryPendingEndpointRemoval(
        savedPublicKeys: Collection<String>,
    ): Result<Unit> = withContext(serializedDispatcher) {
        runCatching {
            if (isContactSharingCleanupPending()) {
                removePublishedEndpoints().getOrThrow()
                clearUnsavedContactState(savedPublicKeys).getOrThrow()
                updateContactSharingCleanupPending(false)
            }
            retryPendingDeletedContactEndpointRemoval(savedPublicKeys).getOrThrow()
        }.onFailure {
            Logger.warn("Failed to retry pending Paykit contact endpoint removal", it, context = TAG)
        }
    }

    suspend fun pruneUnsavedContactState(
        savedPublicKeys: Collection<String>,
    ): Result<Unit> = withContext(serializedDispatcher) {
        runCatching {
            val savedKeys = rememberSavedContacts(savedPublicKeys, replacing = true).toSet()
            val staleKeys = ensureState().contacts.keys.filter { it !in savedKeys }
            staleKeys.forEach { removeSavedContact(it).getOrThrow() }
            addressReservationRepo.clearContactAssignments(excludingPublicKeys = savedKeys)
        }
    }

    suspend fun removeSavedContact(publicKey: String): Result<Unit> = withContext(serializedDispatcher) {
        runCatching {
            val normalizedKey = normalizedPublicKey(publicKey) ?: return@runCatching
            knownSavedContactKeys.remove(normalizedKey)
            cancelPendingPublicationRetry(normalizedKey)
            advanceStateGeneration()
            removePublishedEndpoints(normalizedKey).onFailure {
                updateDeletedContactCleanupPending(normalizedKey, true)
                Logger.warn(
                    "Failed to tombstone private Paykit endpoints for '${redacted(normalizedKey)}'",
                    it,
                    context = TAG,
                )
            }.getOrThrow()
            clearContactState(normalizedKey)
            addressReservationRepo.clearContactAssignment(normalizedKey)
            updateDeletedContactCleanupPending(normalizedKey, false)
        }
    }

    suspend fun disableSharingAndPruneUnsavedContactState(savedPublicKeys: Collection<String>): Result<Unit> =
        withContext(serializedDispatcher) {
            runCatching {
                resetInFlightWork()
                val removalError = removePublishedEndpoints().exceptionOrNull()
                if (removalError != null) {
                    updateContactSharingCleanupPending(true)
                    Logger.warn(
                        "Deferred private Paykit endpoint cleanup after disable failed",
                        removalError,
                        context = TAG,
                    )
                    throw removalError
                } else {
                    clearUnsavedContactState(savedPublicKeys).getOrThrow()
                    updateContactSharingCleanupPending(false)
                }
            }
        }

    suspend fun setContactSharingCleanupPending(isPending: Boolean): Result<Unit> =
        withContext(serializedDispatcher) {
            runCatching {
                updateContactSharingCleanupPending(isPending)
            }
        }

    suspend fun removePublishedEndpointsBestEffort(context: String): Result<Unit> = withContext(serializedDispatcher) {
        removePublishedEndpoints()
            .onFailure {
                Logger.warn("Failed to remove private Paykit endpoints during '$context'", it, context = TAG)
            }
    }

    suspend fun closeAndClear(
        markProfileRecoveryPending: Boolean = false,
    ): Result<Unit> = withContext(serializedDispatcher) {
        runCatching {
            publicationMutex.withLock {
                linkEstablishmentMutex.withLock {
                    val hadPrivateContactState =
                        ensureState().contacts.isNotEmpty()
                    val wasProfileRecoveryPending = isProfileRecoveryPending()
                    resetInFlightWork()
                    closeActiveHandles()
                    activeHandlesByContact.clear()
                    knownSavedContactKeys.clear()
                    stateStore.replaceState(PrivatePaykitState())
                    keychain.delete(Keychain.Key.PRIVATE_PAYKIT_SECRET_STATE.name)
                    cacheStore.reset()
                    if (markProfileRecoveryPending && (hadPrivateContactState || wasProfileRecoveryPending)) {
                        updateProfileRecoveryPending(true)
                    }
                    addressReservationRepo.clearContactAssignments(excludingPublicKeys = emptySet())
                    notifyBackupStateChanged()
                }
            }
        }
    }

    suspend fun beginSavedContactPayment(publicKey: String): Result<PublicPaykitPaymentResult> =
        withContext(serializedDispatcher) {
            runCatching {
                val normalizedKey = knownSavedContact(publicKey)
                    ?: return@runCatching publicPaykitRepo.beginPayment(publicKey).getOrThrow()
                if (!hasLocalSecretKeyForCurrentProfile()) {
                    return@runCatching publicPaykitRepo.beginPayment(normalizedKey).getOrThrow()
                }

                val privateAttempt = beginPrivatePaymentWithRecoveryRetry(normalizedKey)
                val privateResult = privateAttempt.result
                    .onFailure {
                        if (it is CancellationException) throw it
                    }
                    .getOrNull()

                if (privateResult is PublicPaykitPaymentResult.Opened) return@runCatching privateResult
                if (
                    privateAttempt.shouldDeferPublicFallback ||
                    shouldDeferPublicFallbackForPrivateRecovery(normalizedKey)
                ) {
                    privateAttempt.result.exceptionOrNull()?.let {
                        Logger.warn(
                            "Deferring public Paykit fallback for '${redacted(normalizedKey)}' " +
                                "while private payment recovery completes",
                            it,
                            context = TAG,
                        )
                    }
                    return@runCatching privateResult ?: PublicPaykitPaymentResult.NoEndpoint
                }
                privateAttempt.result.exceptionOrNull()?.let {
                    Logger.warn(
                        "Falling back to public Paykit for '${redacted(normalizedKey)}'",
                        it,
                        context = TAG,
                    )
                }
                publicPaykitRepo.beginPayment(normalizedKey).getOrThrow()
            }
        }

    suspend fun discardRemoteLightningEndpoints(
        publicKey: String,
        paymentHashes: Set<String>,
    ): Result<Unit> = withContext(serializedDispatcher) {
        runCatching {
            if (paymentHashes.isEmpty()) return@runCatching
            val normalizedKey = normalizedPublicKey(publicKey) ?: return@runCatching
            val contactState = ensureState().contacts[normalizedKey] ?: return@runCatching
            val normalizedHashes = paymentHashes.map { it.lowercase() }.toSet()
            val filteredEntries = contactState.remoteEndpoints.filterNot {
                shouldDiscardRemoteLightningEntry(it, normalizedHashes)
            }
            if (filteredEntries.size == contactState.remoteEndpoints.size) return@runCatching

            contactState.remoteEndpoints = filteredEntries
            persistState(markWalletBackup = true)
        }
    }

    suspend fun discardRemoteOnchainEndpoints(
        publicKey: String,
        addresses: Set<String>,
    ): Result<Unit> = withContext(serializedDispatcher) {
        runCatching {
            if (addresses.isEmpty()) return@runCatching
            val normalizedKey = normalizedPublicKey(publicKey) ?: return@runCatching
            val contactState = ensureState().contacts[normalizedKey] ?: return@runCatching
            val filteredEntries = contactState.remoteEndpoints.filterNot {
                shouldDiscardRemoteOnchainEntry(it, addresses)
            }
            if (filteredEntries.size == contactState.remoteEndpoints.size) return@runCatching

            contactState.remoteEndpoints = filteredEntries
            persistState(markWalletBackup = true)
        }
    }

    suspend fun handleReceivedPayment(paymentHash: String): Result<Unit> = withContext(serializedDispatcher) {
        runCatching {
            val matchingContacts = ensureState().contacts
                .filter { (publicKey, contactState) ->
                    publicKey in knownSavedContactKeys && contactState.localInvoice?.paymentHash == paymentHash
                }
                .keys
            if (matchingContacts.isEmpty()) return@runCatching

            matchingContacts.forEach { rememberReceivedInvoicePaymentHash(paymentHash, it) }
            if (!canPublishPrivateEndpoints()) return@runCatching

            val generation = currentStateGeneration()
            matchingContacts.forEach { publicKey ->
                val linkId = establishedLinkId(publicKey, maxAdvanceSteps = 1, generation = generation)
                    .getOrNull() ?: return@forEach
                publishLocalEndpoints(publicKey, linkId, force = true, generation = generation).onFailure {
                    schedulePendingPublicationRetry(publicKey)
                    Logger.warn(
                        "Failed to rotate private Paykit invoice for '${redacted(publicKey)}'",
                        it,
                        context = TAG,
                    )
                }
            }
        }
    }

    suspend fun reconcileReceivedPayments(): Result<Unit> = withContext(serializedDispatcher) {
        runCatching {
            settledPrivateInvoicePaymentHashes().forEach {
                handleReceivedPayment(it).getOrThrow()
            }
        }
    }

    suspend fun handleOnchainActivity(receivedAddresses: Collection<String> = emptyList()): Result<Unit> =
        withContext(serializedDispatcher) {
            runCatching {
                if (!canPublishPrivateEndpoints()) return@runCatching
                val publicKeys = if (receivedAddresses.isEmpty()) {
                    addressReservationRepo.contactsWithUsedReservedAddresses()
                } else {
                    receivedAddresses.mapNotNull {
                        addressReservationRepo.currentContactPublicKeyForReservedAddress(it)
                    }
                }.filter { it in knownSavedContactKeys }.distinct()
                if (publicKeys.isEmpty()) return@runCatching

                publicKeys.forEach {
                    addressReservationRepo.rotateAddress(it).getOrThrow()
                }
                publishLocalEndpoints(publicKeys, maxAdvanceSteps = 1, reason = "on-chain rotation").getOrThrow()
            }
        }

    suspend fun contactPublicKeyForPrivateInvoicePaymentHash(paymentHash: String): String? =
        withContext(serializedDispatcher) {
            if (paymentHash.isBlank()) return@withContext null
            ensureState().contacts.firstNotNullOfOrNull { (publicKey, contactState) ->
                publicKey.takeIf {
                    contactState.localInvoice?.paymentHash == paymentHash ||
                        paymentHash in contactState.receivedInvoicePaymentHashes
                }
            }
        }

    suspend fun contactPublicKeyForPrivateOnchainAddresses(addresses: Collection<String>): String? =
        withContext(serializedDispatcher) {
            addresses.firstNotNullOfOrNull {
                addressReservationRepo.contactPublicKeyForReservedAddress(it)
            }
        }

    suspend fun backupSnapshot(): Result<Map<String, PrivatePaykitContactLinkBackupV1>?> =
        withContext(serializedDispatcher) {
            runCatching {
                ensureState().contacts.mapNotNull { (publicKey, contactState) ->
                    if (!contactState.hasBackupState) return@mapNotNull null
                    publicKey to PrivatePaykitContactLinkBackupV1(
                        publicKey = publicKey,
                        linkSnapshotHex = contactState.linkSnapshotHex,
                        handshakeSnapshotHex = contactState.handshakeSnapshotHex,
                        remoteEndpoints = contactState.remoteEndpoints.associate { it.methodId to it.endpointData },
                        linkCompletedAt = contactState.linkCompletedAt,
                        handshakeUpdatedAt = contactState.handshakeUpdatedAt,
                        recoveryStartedAt = contactState.recoveryStartedAt,
                        mainRecoveryAttemptId = contactState.mainRecoveryAttemptId,
                        responderRecoveryAttemptId = contactState.responderRecoveryAttemptId,
                        awaitingRecoveredRemoteEndpoints = contactState.awaitingRecoveredRemoteEndpoints,
                    )
                }.toMap().takeIf { it.isNotEmpty() }
            }
        }

    suspend fun restoreBackup(backup: Map<String, PrivatePaykitContactLinkBackupV1>?): Result<Unit> =
        withContext(serializedDispatcher) {
            runCatching {
                publicationMutex.withLock {
                    linkEstablishmentMutex.withLock {
                        resetInFlightWork()
                        closeActiveHandles()
                        activeHandlesByContact.clear()
                        knownSavedContactKeys.clear()

                        if (backup == null) {
                            stateStore.replaceState(PrivatePaykitState())
                            persistState(preserveCleanupMarkers = false)
                            notifyBackupStateChanged()
                            return@runCatching
                        }

                        val contacts = backup.mapNotNull { (publicKey, contactBackup) ->
                            val normalizedKey = normalizedPublicKey(publicKey) ?: return@mapNotNull null
                            val linkSnapshotHex = validatedSnapshot(
                                contactBackup.linkSnapshotHex,
                                normalizedKey,
                                pubkyService::encryptedLinkSnapshotRecipient,
                            )
                            val handshakeSnapshotHex = validatedSnapshot(
                                contactBackup.handshakeSnapshotHex,
                                normalizedKey,
                                pubkyService::encryptedLinkHandshakeSnapshotRecipient,
                            )
                            normalizedKey to ContactState(
                                linkSnapshotHex = linkSnapshotHex,
                                handshakeSnapshotHex = handshakeSnapshotHex,
                                remoteEndpoints = PrivatePaykitPayloads.storedPaymentEntries(
                                    contactBackup.remoteEndpoints,
                                ),
                                linkCompletedAt = contactBackup.linkCompletedAt,
                                handshakeUpdatedAt = contactBackup.handshakeUpdatedAt,
                                recoveryStartedAt = contactBackup.recoveryStartedAt,
                                mainRecoveryAttemptId = contactBackup.mainRecoveryAttemptId,
                                responderRecoveryAttemptId = contactBackup.responderRecoveryAttemptId,
                                awaitingRecoveredRemoteEndpoints = contactBackup.awaitingRecoveredRemoteEndpoints,
                            )
                        }.toMap()

                        stateStore.replaceState(PrivatePaykitState(contacts = contacts.toMutableMap()))
                    }
                }
                persistState(preserveCleanupMarkers = false)
                notifyBackupStateChanged()
            }
        }

    private suspend fun beginPrivatePayment(publicKey: String): Result<PublicPaykitPaymentResult> =
        withContext(serializedDispatcher) {
            runCatching {
                val generation = currentStateGeneration()
                val linkId = establishedLinkId(publicKey, maxAdvanceSteps = 5, generation = generation).getOrThrow()
                    ?: throw PrivatePaykitError.PrivateUnavailable

                if (ensureState().contacts[publicKey]?.lastLocalPayloadHash == null) {
                    publishLocalEndpointsBestEffort(
                        publicKey = publicKey,
                        linkId = linkId,
                        fetchedRemoteCount = 0,
                        context = "payment",
                        generation = generation,
                    )
                }

                var staleFetchError: Throwable? = null
                val fetchedCount = fetchRemoteEndpoints(publicKey, linkId, generation).getOrElse {
                    if (PrivatePaykitErrorClassifier.shouldCountAsStaleLinkFailure(it)) {
                        Logger.warn(
                            "Private Paykit link is stale for '${redacted(publicKey)}'; using cached private endpoints",
                            it,
                            context = TAG,
                        )
                        staleFetchError = it
                        schedulePendingPublicationRetry(publicKey)
                    } else {
                        Logger.warn(
                            "Failed to refresh private Paykit endpoints for '${redacted(publicKey)}'",
                            it,
                            context = TAG,
                        )
                    }
                    0
                }
                if (staleFetchError == null) {
                    val publishLinkId = activeHandlesByContact[publicKey]?.linkId ?: linkId
                    publishLocalEndpointsBestEffort(
                        publicKey = publicKey,
                        linkId = publishLinkId,
                        fetchedRemoteCount = fetchedCount,
                        context = "payment",
                        generation = generation,
                        respectInitialPublishDelay = false,
                    )
                }

                val cachedResult = cachedPrivatePaymentResult(publicKey)
                if (cachedResult is PublicPaykitPaymentResult.Opened) {
                    clearAwaitingRecoveredRemoteEndpoints(publicKey)
                    return@runCatching cachedResult
                }
                staleFetchError?.let { throw it }

                cachedResult
            }
        }

    private suspend fun beginPrivatePaymentWithRecoveryRetry(publicKey: String): PrivatePaymentAttempt {
        var shouldDeferPublicFallback = shouldDeferPublicFallbackForPrivateRecovery(publicKey)
        var result = runCatching { beginPrivatePayment(publicKey).getOrThrow() }
        repeat(PRIVATE_PAYMENT_RECOVERY_RETRY_ATTEMPTS) {
            shouldDeferPublicFallback = shouldDeferPublicFallback ||
                shouldDeferPublicFallbackForPrivateRecovery(publicKey)
            if (!shouldRetryPrivatePaymentBeforePublicFallback(publicKey, result, shouldDeferPublicFallback)) {
                return PrivatePaymentAttempt(result, shouldDeferPublicFallback)
            }
            delay(privatePaymentRecoveryRetryDelay)
            result = runCatching { beginPrivatePayment(publicKey).getOrThrow() }
        }
        shouldDeferPublicFallback = shouldDeferPublicFallback ||
            shouldDeferPublicFallbackForPrivateRecovery(publicKey)
        return PrivatePaymentAttempt(result, shouldDeferPublicFallback)
    }

    private suspend fun shouldRetryPrivatePaymentBeforePublicFallback(
        publicKey: String,
        result: Result<PublicPaykitPaymentResult>,
        shouldDeferPublicFallback: Boolean,
    ): Boolean {
        if (result.getOrNull() is PublicPaykitPaymentResult.Opened) return false
        result.exceptionOrNull()?.let {
            if (it is CancellationException) throw it
            if (it is PrivatePaykitError.PrivateUnavailable) {
                return shouldDeferPublicFallback || shouldDeferPublicFallbackForPrivateRecovery(publicKey)
            }
        }
        return shouldDeferPublicFallback || shouldDeferPublicFallbackForPrivateRecovery(publicKey)
    }

    private suspend fun shouldDeferPublicFallbackForPrivateRecovery(publicKey: String): Boolean {
        val contactState = ensureState().contacts[publicKey] ?: return false
        return contactState.recoveryStartedAt != null ||
            contactState.mainRecoveryAttemptId != null ||
            contactState.responderRecoveryAttemptId != null ||
            contactState.awaitingRecoveredRemoteEndpoints
    }

    private suspend fun clearAwaitingRecoveredRemoteEndpoints(publicKey: String) {
        val contactState = ensureState().contacts[publicKey] ?: return
        if (!contactState.awaitingRecoveredRemoteEndpoints) return

        contactState.awaitingRecoveredRemoteEndpoints = false
        persistState(markWalletBackup = true)
    }

    private suspend fun cachedPrivatePaymentResult(publicKey: String): PublicPaykitPaymentResult {
        val cachedEntries = ensureState().contacts[publicKey]?.remoteEndpoints.orEmpty()
        val endpoints = cachedEntries.mapNotNull {
            PublicPaykitRepo.parseEndpoint(it.methodId, it.endpointData)
        }
        val payable = privatePayableEndpoints(endpoints, publicKey)
        if (payable.isEmpty()) {
            return when {
                cachedEntries.isEmpty() -> PublicPaykitPaymentResult.NoEndpoint
                else -> PublicPaykitPaymentResult.NotOpened
            }
        }

        return PublicPaykitPaymentResult.Opened(PublicPaykitRepo.paymentRequest(payable))
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun publishLocalEndpoints(
        publicKeys: Collection<String>,
        maxAdvanceSteps: Int,
        reason: String,
        scheduleRetries: Boolean = true,
        forceLocalPublishWhenRemoteEmpty: Boolean = false,
        forceRefreshLightning: Boolean = false,
        requireImmediatePublication: Boolean = false,
    ): Result<Unit> = withContext(serializedDispatcher) {
        runCatching {
            val generation = currentStateGeneration()
            var firstError: Throwable? = null
            publicKeys.forEach { publicKey ->
                val normalizedKey = knownSavedContact(publicKey) ?: return@forEach
                val redactedKey = redacted(normalizedKey)
                val linkId = establishedLinkIdForPublish(
                    publicKey = normalizedKey,
                    redactedKey = redactedKey,
                    maxAdvanceSteps = maxAdvanceSteps,
                    generation = generation,
                    scheduleRetries = scheduleRetries,
                ) ?: run {
                    if (firstError == null && requireImmediatePublication) {
                        firstError = PrivatePaykitError.PrivateUnavailable
                    }
                    return@forEach
                }

                if (publishLocalEndpointsBeforeFetch(normalizedKey, linkId, reason, scheduleRetries, generation)) {
                    return@forEach
                }

                val fetchedCount = fetchRemoteEndpointCountForPublish(
                    publicKey = normalizedKey,
                    linkId = linkId,
                    reason = reason,
                    scheduleRetries = scheduleRetries,
                    generation = generation,
                ) ?: run {
                    if (firstError == null && requireImmediatePublication) {
                        firstError = PrivatePaykitError.PrivateUnavailable
                    }
                    return@forEach
                }
                val contactState = ensureState().contacts[normalizedKey]
                val shouldForcePublish = forceLocalPublishWhenRemoteEmpty &&
                    fetchedCount == 0 &&
                    contactState?.remoteEndpoints.isNullOrEmpty()
                val publishLinkId = activeHandlesByContact[normalizedKey]?.linkId ?: linkId
                val publishResult = publishLocalEndpoints(
                    publicKey = normalizedKey,
                    linkId = publishLinkId,
                    force = shouldForcePublish,
                    generation = generation,
                    forceRefreshLightning = forceRefreshLightning,
                ).onFailure {
                    if (scheduleRetries) schedulePendingPublicationRetry(normalizedKey)
                    Logger.warn(
                        "Failed to publish private Paykit endpoints during '$reason' for '$redactedKey'",
                        it,
                        context = TAG,
                    )
                    if (firstError == null && requireImmediatePublication) firstError = it
                }
                val updatedContactState = ensureState().contacts[normalizedKey]
                val needsRetry = publishResult.isFailure ||
                    updatedContactState?.linkCompletedAt == null ||
                    updatedContactState.lastLocalPayloadHash == null ||
                    (fetchedCount == 0 && updatedContactState.remoteEndpoints.isEmpty()) ||
                    shouldRetryMissingPrivateLightningEndpoint(normalizedKey)
                if (scheduleRetries && needsRetry) {
                    schedulePendingPublicationRetry(normalizedKey)
                } else {
                    cancelPendingPublicationRetry(normalizedKey)
                }
            }
            firstError?.let { throw it }
            Unit
        }
    }

    private suspend fun establishedLinkIdForPublish(
        publicKey: String,
        redactedKey: String,
        maxAdvanceSteps: Int,
        generation: Long,
        scheduleRetries: Boolean,
    ): String? =
        establishedLinkId(publicKey, maxAdvanceSteps, generation).fold(
            onSuccess = {
                if (it == null) {
                    if (scheduleRetries) schedulePendingPublicationRetry(publicKey)
                    Logger.debug(
                        "Deferred private Paykit endpoint publish for '$redactedKey'",
                        context = TAG,
                    )
                }
                it
            },
            onFailure = {
                val shouldRetry = PrivatePaykitErrorClassifier.shouldRetryLinkEstablishmentFailure(it)
                if (scheduleRetries && shouldRetry) schedulePendingPublicationRetry(publicKey)
                Logger.debug(
                    if (shouldRetry) {
                        "Deferred private Paykit endpoint publish for '$redactedKey'"
                    } else {
                        "Skipped private Paykit endpoint publish for '$redactedKey'"
                    },
                    context = TAG,
                )
                null
            },
        )

    private suspend fun publishLocalEndpointsBeforeFetch(
        publicKey: String,
        linkId: String,
        reason: String,
        scheduleRetries: Boolean,
        generation: Long,
    ): Boolean {
        if (!contactStateShouldPublishBeforeFetch(publicKey)) return false

        val publishResult = publishLocalEndpoints(
            publicKey = publicKey,
            linkId = linkId,
            generation = generation,
        ).onFailure {
            if (scheduleRetries) schedulePendingPublicationRetry(publicKey)
            Logger.warn(
                "Failed to publish private Paykit endpoints during '$reason' for '${redacted(publicKey)}'",
                it,
                context = TAG,
            )
        }
        if (publishResult.isFailure) return false

        if (scheduleRetries) schedulePendingPublicationRetry(publicKey)
        return true
    }

    private suspend fun fetchRemoteEndpointCountForPublish(
        publicKey: String,
        linkId: String,
        reason: String,
        scheduleRetries: Boolean,
        generation: Long,
    ): Int? = fetchRemoteEndpoints(publicKey, linkId, generation).fold(
        onSuccess = { it },
        onFailure = {
            if (scheduleRetries) {
                schedulePendingPublicationRetry(publicKey)
            }
            Logger.warn(
                "Failed to fetch private Paykit endpoints during '$reason' for '${redacted(publicKey)}'",
                it,
                context = TAG,
            )
            if (PrivatePaykitErrorClassifier.shouldCountAsStaleLinkFailure(it)) null else 0
        },
    )

    private suspend fun publishLocalEndpointsBestEffort(
        publicKey: String,
        linkId: String,
        fetchedRemoteCount: Int,
        context: String,
        generation: Long = currentStateGeneration(),
        respectInitialPublishDelay: Boolean = true,
        forceRefreshLightning: Boolean = false,
    ) {
        if (!canPublishPrivateEndpoints()) return
        if (!shouldPublishLocalEndpoints(publicKey, fetchedRemoteCount)) return
        if (respectInitialPublishDelay && shouldDeferInitialLocalPublish(publicKey, fetchedRemoteCount)) return

        publishLocalEndpoints(
            publicKey = publicKey,
            linkId = linkId,
            generation = generation,
            forceRefreshLightning = forceRefreshLightning,
        ).onFailure {
            schedulePendingPublicationRetry(publicKey)
            Logger.warn(
                "Failed to publish private Paykit endpoints during '$context' for '${redacted(publicKey)}'",
                it,
                context = TAG,
            )
        }
    }

    private fun schedulePendingPublicationRetry(
        publicKey: String,
        remainingAttempts: Int = PENDING_PUBLICATION_RETRY_ATTEMPTS,
    ) {
        if (remainingAttempts <= 0) return
        if (publicKey !in knownSavedContactKeys) return
        if (pendingPublicationRetryJobs[publicKey] != null) return

        pendingPublicationRetryJobs[publicKey] = retryScope.launch {
            delay(pendingPublicationRetryDelay)
            pendingPublicationRetryJobs.remove(publicKey)
            if (publicKey !in knownSavedContactKeys) return@launch
            if (!canPublishPrivateEndpoints()) return@launch

            publishLocalEndpoints(
                publicKeys = listOf(publicKey),
                maxAdvanceSteps = 3,
                reason = "retry",
                scheduleRetries = false,
                forceLocalPublishWhenRemoteEmpty = true,
            ).onFailure {
                Logger.warn(
                    "Failed to retry private Paykit endpoints for '${redacted(publicKey)}'",
                    it,
                    context = TAG,
                )
            }

            val contactState = ensureState().contacts[publicKey]
            val needsRetry = contactState?.linkCompletedAt == null ||
                contactState.lastLocalPayloadHash == null ||
                contactState.remoteEndpoints.isEmpty() ||
                shouldRetryMissingPrivateLightningEndpoint(publicKey)
            if (needsRetry) schedulePendingPublicationRetry(publicKey, remainingAttempts - 1)
        }
    }

    private fun cancelPendingPublicationRetry(publicKey: String) {
        pendingPublicationRetryJobs.remove(publicKey)?.cancel()
    }

    private fun resetInFlightWork() {
        advanceStateGeneration()
        pendingPublicationRetryJobs.values.forEach { it.cancel() }
        pendingPublicationRetryJobs.clear()
    }

    private fun advanceStateGeneration() {
        stateGeneration.incrementAndGet()
    }

    private fun currentStateGeneration(): Long = stateGeneration.get()

    private fun ensureCurrentGeneration(generation: Long) {
        if (stateGeneration.get() != generation) throw PrivatePaykitError.PrivateUnavailable
    }

    private suspend fun publishLocalEndpoints(
        publicKey: String,
        linkId: String,
        force: Boolean = false,
        generation: Long = currentStateGeneration(),
        forceRefreshLightning: Boolean = false,
    ): Result<Unit> = withContext(serializedDispatcher) {
        runCatching {
            publicationMutex.withLock {
                ensureCurrentGeneration(generation)
                if (!canPublishPrivateEndpoints() || knownSavedContact(publicKey) == null) return@withLock

                val endpoints = buildLocalEndpoints(publicKey, forceRefreshLightning).getOrThrow()
                if (endpoints.isEmpty()) throw PublicPaykitError.NoSupportedEndpoint
                ensureCurrentGeneration(generation)
                val payloadSelection = PrivatePaykitPayloads.entriesWithinNoiseLimit(endpoints)
                if (payloadSelection.droppedLightning) {
                    Logger.warn(
                        "Published private Paykit on-chain only for '${redacted(publicKey)}'",
                        context = TAG,
                    )
                }
                val entries = payloadSelection.entries
                val payloadHash = PrivatePaykitPayloads.localPayloadHash(entries)
                val contactState = ensureState().contacts.getOrPut(publicKey) { ContactState() }
                if (!force && contactState.lastLocalPayloadHash == payloadHash) return@withLock
                ensureCurrentGeneration(generation)
                if (!canPublishPrivateEndpoints() || knownSavedContact(publicKey) == null) return@withLock

                pubkyService.setPrivatePayments(linkId, entries.map { it.toFfiPaymentEndpoint() })
                ensureCurrentGeneration(generation)
                persistLinkSnapshot(linkId, publicKey, linkWasReplaced = false, generation = generation).getOrThrow()
                contactState.lastLocalPayloadHash = payloadHash
                persistState(markWalletBackup = false)
            }
        }.onFailure {
            recordLinkFailure(publicKey, it, generation)
        }
    }

    private suspend fun buildLocalEndpoints(
        publicKey: String,
        forceRefreshLightning: Boolean = false,
    ): Result<List<Endpoint>> =
        withContext(serializedDispatcher) {
            runCatching {
                val settings = settingsStore.data.first()
                val endpoints = mutableListOf<Endpoint>()
                if (PublicPaykitRepo.isOnchainPaymentOptionEnabled(settings)) {
                    val reservedAddress = addressReservationRepo.currentOrRotatedAddress(publicKey).getOrThrow()
                    walletRepo.refreshReusableReceiveAddressIfReserved().getOrThrow()
                    endpoints += Endpoint(
                        methodId = PublicPaykitRepo.onchainMethodId(reservedAddress),
                        value = reservedAddress,
                        rawPayload = PublicPaykitRepo.serializePayload(reservedAddress),
                    )
                }

                if (PublicPaykitRepo.isLightningPaymentOptionEnabled(settings) && lightningRepo.canReceive()) {
                    currentOrRotatedInvoice(publicKey, forceRefresh = forceRefreshLightning).onSuccess { invoice ->
                        endpoints += Endpoint(
                            methodId = MethodId.Bolt11,
                            value = invoice.bolt11,
                            rawPayload = PublicPaykitRepo.serializePayload(invoice.bolt11),
                        )
                    }.onFailure {
                        if (it is PrivatePaykitError.RouteHintsUnavailable) {
                            schedulePendingPublicationRetry(publicKey)
                        }
                        Logger.warn(
                            "Failed to prepare private Paykit invoice for '${redacted(publicKey)}'",
                            it,
                            context = TAG,
                        )
                    }
                }

                endpoints
            }
        }

    private suspend fun currentOrRotatedInvoice(
        publicKey: String,
        forceRefresh: Boolean = false,
    ): Result<StoredInvoice> =
        withContext(serializedDispatcher) {
            runCatching {
                if (!forceRefresh) reusablePrivateInvoice(publicKey)?.let { return@runCatching it }

                val bolt11 = lightningRepo.createInvoice(
                    amountSats = null,
                    description = "",
                    expirySeconds = privateInvoiceExpiry.inWholeSeconds.toUInt(),
                ).getOrThrow()
                if (!forceRefresh) reusablePrivateInvoice(publicKey)?.let { return@runCatching it }

                val decoded = (coreService.decode(bolt11) as? Scanner.Lightning)?.invoice
                    ?: throw PublicPaykitError.InvalidPayload
                if (!PublicPaykitRepo.hasLightningRouteHints(bolt11)) {
                    throw PrivatePaykitError.RouteHintsUnavailable
                }
                val expiresAt = decoded.timestampSeconds.toLong() + decoded.expirySeconds.toLong()
                val invoice = StoredInvoice(
                    bolt11 = bolt11,
                    paymentHash = decoded.paymentHash.toHex(),
                    expiresAt = expiresAt,
                )
                ensureState().contacts.getOrPut(publicKey) { ContactState() }.localInvoice = invoice
                persistState()
                invoice
            }
        }

    @Suppress("ReturnCount")
    private suspend fun reusablePrivateInvoice(publicKey: String): StoredInvoice? {
        val invoice = ensureState().contacts[publicKey]?.localInvoice ?: return null
        val refreshAt = clock.now().epochSeconds + invoiceRefreshBuffer.inWholeSeconds
        if (invoice.expiresAt <= refreshAt) return null
        if (isReceivedInvoiceSettled(invoice.paymentHash)) return null
        val decoded = (coreService.decode(invoice.bolt11) as? Scanner.Lightning)?.invoice ?: return null
        if (decoded.isExpired || decoded.amountSatoshis != 0uL) return null
        if (!PublicPaykitRepo.hasLightningRouteHints(invoice.bolt11)) return null
        return invoice
    }

    private suspend fun shouldRetryMissingPrivateLightningEndpoint(publicKey: String): Boolean {
        val settings = settingsStore.data.first()
        if (!PublicPaykitRepo.isLightningPaymentOptionEnabled(settings)) return false
        if (!lightningRepo.canReceive()) return false

        return reusablePrivateInvoice(publicKey) == null
    }

    private suspend fun fetchRemoteEndpoints(
        publicKey: String,
        linkId: String,
        generation: Long = currentStateGeneration(),
    ): Result<Int> =
        withContext(serializedDispatcher) {
            runCatching {
                readRemoteEndpoints(publicKey, linkId, generation).getOrElse { error ->
                    if (!PrivatePaykitErrorClassifier.shouldCountAsStaleLinkFailure(error)) throw error

                    val restoredLinkId = restoreLinkHandleForReadRetry(publicKey, generation).getOrNull()
                        ?: throw error

                    Logger.info(
                        "Retrying private Paykit endpoint fetch for '${redacted(publicKey)}'",
                        context = TAG,
                    )
                    readRemoteEndpoints(publicKey, restoredLinkId, generation).getOrElse {
                        throw it
                    }
                }
            }.onFailure {
                recordLinkFailure(publicKey, it, generation)
            }
        }

    private suspend fun readRemoteEndpoints(
        publicKey: String,
        linkId: String,
        generation: Long,
    ): Result<Int> =
        withContext(serializedDispatcher) {
            runCatching {
                ensureCurrentGeneration(generation)
                val remotePayload = pubkyService.getPrivatePayments(linkId)
                ensureCurrentGeneration(generation)
                recordLinkSuccess(publicKey)
                persistLinkSnapshot(linkId, publicKey, linkWasReplaced = false, generation = generation).getOrThrow()
                ensureCurrentGeneration(generation)
                if (remotePayload == null) return@runCatching 0

                val remoteEntries = remotePayload.paymentEndpoints
                val contactState = ensureState().contacts.getOrPut(publicKey) { ContactState() }
                contactState.remoteEndpoints = remoteEntries.map { it.toStoredPaymentEntry() }
                persistState(markWalletBackup = true)
                remoteEntries.count()
            }
        }

    private suspend fun restoreLinkHandleForReadRetry(
        publicKey: String,
        generation: Long,
    ): Result<String?> =
        withContext(serializedDispatcher) {
            runCatching {
                ensureCurrentGeneration(generation)
                val contactState = ensureState().contacts[publicKey] ?: return@runCatching null
                val snapshot = contactState.linkSnapshotHex ?: return@runCatching null
                val secretKeyHex = keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)
                    ?.takeIf { it.isNotBlank() }
                    ?: return@runCatching null

                activeHandlesByContact[publicKey]?.linkId?.let {
                    runCatching { pubkyService.closeEncryptedLink(it) }
                }
                activeHandlesByContact[publicKey] = ContactPaykitHandles()

                ensureCurrentGeneration(generation)
                validateSnapshot(snapshot, publicKey, pubkyService::encryptedLinkSnapshotRecipient)
                val restoredLinkId = pubkyService.restoreEncryptedLink(secretKeyHex, snapshot)
                ensureCurrentGeneration(generation)
                activeHandlesByContact[publicKey] = ContactPaykitHandles(linkId = restoredLinkId)
                restoredLinkId
            }
        }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    private suspend fun establishedLinkId(
        publicKey: String,
        maxAdvanceSteps: Int,
        generation: Long = currentStateGeneration(),
    ): Result<String?> =
        withContext(serializedDispatcher) {
            runCatching {
                linkEstablishmentMutex.withLock {
                    establishedLinkIdUnlocked(publicKey, maxAdvanceSteps, generation)
                }
            }
        }

    @Suppress(
        "LongMethod",
        "CyclomaticComplexMethod",
        "ReturnCount",
        "NestedBlockDepth",
        "ComplexCondition",
        "ThrowsCount",
    )
    private suspend fun establishedLinkIdUnlocked(
        publicKey: String,
        maxAdvanceSteps: Int,
        generation: Long,
    ): String? {
        ensureCurrentGeneration(generation)
        val normalizedKey = normalizedPublicKey(publicKey) ?: throw PrivatePaykitError.PrivateUnavailable

        val secretKeyHex = keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)
            ?: throw PrivatePaykitError.PrivateUnavailable
        val ownPublicKey = pubkyService.currentPublicKey()
            ?.let { PubkyPublicKeyFormat.normalized(it) }
            ?: throw PrivatePaykitError.PrivateUnavailable
        ensureCurrentGeneration(generation)

        val contactState = ensureState().contacts.getOrPut(normalizedKey) { ContactState() }
        activeHandlesByContact[normalizedKey]?.linkId?.let { linkId ->
            val remoteRecoveryMarker = recoveryStore.freshRecoveryMarker(
                from = normalizedKey,
                to = ownPublicKey,
                stages = setOf(RECOVERY_MARKER_STAGE_INIT),
            )
            if (remoteRecoveryMarker != null && shouldReplaceUsableLink(remoteRecoveryMarker, normalizedKey)) {
                if (!discardLinkForRecovery(normalizedKey, linkId, remoteRecoveryMarker.createdAt)) return null
            } else {
                return linkId
            }
        }

        contactState.linkSnapshotHex?.let { snapshot ->
            val shouldRestoreSnapshot = runCatching {
                snapshotRecipientMatches(snapshot, normalizedKey, pubkyService::encryptedLinkSnapshotRecipient)
            }.getOrElse {
                Logger.warn(
                    "Failed to inspect private Paykit link snapshot for '${redacted(normalizedKey)}'",
                    it,
                    context = TAG,
                )
                clearInvalidLinkSnapshotState(contactState)
                false
            }

            if (!shouldRestoreSnapshot) {
                if (contactState.linkSnapshotHex != null) {
                    Logger.warn(
                        "Dropped private Paykit link snapshot with mismatched recipient for " +
                            "'${redacted(normalizedKey)}'",
                        context = TAG,
                    )
                    clearInvalidLinkSnapshotState(contactState)
                }
            }

            val restoredLinkId = if (shouldRestoreSnapshot) {
                runCatching {
                    val linkId = pubkyService.restoreEncryptedLink(secretKeyHex, snapshot)
                    ensureCurrentGeneration(generation)
                    activeHandlesByContact[normalizedKey] = ContactPaykitHandles(linkId = linkId)
                    linkId
                }.onFailure {
                    if (it is PrivatePaykitError.PrivateUnavailable) throw it
                    Logger.warn(
                        "Failed to restore private Paykit link for '${redacted(normalizedKey)}'",
                        it,
                        context = TAG,
                    )
                    contactState.linkSnapshotHex = null
                    contactState.handshakeSnapshotHex = null
                    contactState.lastLocalPayloadHash = null
                    contactState.mainRecoveryAttemptId = null
                    contactState.responderRecoveryAttemptId = null
                    persistState(markWalletBackup = true)
                }.getOrNull()
            } else {
                null
            }
            if (restoredLinkId != null) {
                val remoteRecoveryMarker = recoveryStore.freshRecoveryMarker(
                    from = normalizedKey,
                    to = ownPublicKey,
                    stages = setOf(RECOVERY_MARKER_STAGE_INIT),
                )
                if (remoteRecoveryMarker != null && shouldReplaceUsableLink(remoteRecoveryMarker, normalizedKey)) {
                    val didDiscard = discardLinkForRecovery(
                        publicKey = normalizedKey,
                        linkId = restoredLinkId,
                        startedAt = remoteRecoveryMarker.createdAt,
                    )
                    if (!didDiscard) return null
                } else {
                    return restoredLinkId
                }
            }
        }

        val isRecovering = shouldStartRecoveryHandshake(normalizedKey)
        val fetchedRemoteRecoveryInitMarker = recoveryStore.freshRecoveryMarker(
            from = normalizedKey,
            to = ownPublicKey,
            stages = setOf(RECOVERY_MARKER_STAGE_INIT),
        )
        val remoteRecoveryInitMarker = fetchedRemoteRecoveryInitMarker
            ?.takeUnless { isCompletedRecoveryMarker(it, normalizedKey) }
        val remoteRecoveryFinalForResponder = contactState.responderRecoveryAttemptId?.let {
            recoveryStore.freshRecoveryMarker(
                from = normalizedKey,
                to = ownPublicKey,
                stages = setOf(RECOVERY_MARKER_STAGE_FINAL),
                attemptId = it,
            )
        }
        val remoteRecoveryMarker = remoteRecoveryInitMarker ?: remoteRecoveryFinalForResponder

        val initialMainRecoveryAttemptId = contactState.mainRecoveryAttemptId
        val localMainRecoveryMarker = initialMainRecoveryAttemptId?.let {
            recoveryStore.freshRecoveryMarker(
                from = ownPublicKey,
                to = normalizedKey,
                stages = setOf(RECOVERY_MARKER_STAGE_INIT, RECOVERY_MARKER_STAGE_FINAL),
                attemptId = it,
            )
        }
        val shouldAcceptRemoteRecovery = if (remoteRecoveryFinalForResponder != null) {
            true
        } else {
            remoteRecoveryMarker?.let {
                shouldAcceptRemoteRecoveryMarker(
                    remoteMarker = it,
                    localMarker = localMainRecoveryMarker,
                    ownPublicKey = ownPublicKey,
                    remotePublicKey = normalizedKey,
                )
            } ?: false
        }

        if (shouldAcceptRemoteRecovery && remoteRecoveryMarker != null) {
            val isNewResponderAttempt = contactState.responderRecoveryAttemptId != remoteRecoveryMarker.attemptId
            if (isNewResponderAttempt) {
                if (!recoveryStore.purgePrivatePaymentOutbox(normalizedKey, "recovery responder")) return null
                ensureCurrentGeneration(generation)
                activeHandlesByContact[normalizedKey]?.handshakeId?.let {
                    runCatching { pubkyService.dropEncryptedLinkHandshake(it) }
                }
                activeHandlesByContact[normalizedKey] = ContactPaykitHandles()
                contactState.handshakeSnapshotHex = null
                contactState.mainRecoveryAttemptId = null
                contactState.responderRecoveryAttemptId = remoteRecoveryMarker.attemptId
                contactState.recoveryStartedAt = remoteRecoveryMarker.createdAt
                contactState.lastLocalPayloadHash = null
                contactState.remoteEndpoints = emptyList()
                contactState.awaitingRecoveredRemoteEndpoints = false
                persistState(markWalletBackup = true)
            }
            recoveryStore.publishRecoveryMarker(
                from = ownPublicKey,
                to = normalizedKey,
                stage = RECOVERY_MARKER_STAGE_RESPONSE,
                attemptId = remoteRecoveryMarker.attemptId,
                createdAt = clock.now().epochSeconds,
            )
        }

        val shouldInitiateRecovery = isRecovering && !shouldAcceptRemoteRecovery
        if (shouldInitiateRecovery && contactState.mainRecoveryAttemptId == null) {
            if (!recoveryStore.purgePrivatePaymentOutbox(normalizedKey, "recovery initiator")) return null
            ensureCurrentGeneration(generation)
            activeHandlesByContact[normalizedKey]?.handshakeId?.let {
                runCatching { pubkyService.dropEncryptedLinkHandshake(it) }
            }
            activeHandlesByContact[normalizedKey] = ContactPaykitHandles()
            val attemptId = UUID.randomUUID().toString()
            val createdAt = clock.now().epochSeconds
            contactState.handshakeSnapshotHex = null
            contactState.mainRecoveryAttemptId = attemptId
            contactState.responderRecoveryAttemptId = null
            contactState.recoveryStartedAt = createdAt
            contactState.lastLocalPayloadHash = null
            contactState.remoteEndpoints = emptyList()
            contactState.awaitingRecoveredRemoteEndpoints = false
            persistState(markWalletBackup = true)
            recoveryStore.publishRecoveryMarker(
                from = ownPublicKey,
                to = normalizedKey,
                stage = RECOVERY_MARKER_STAGE_INIT,
                attemptId = attemptId,
                createdAt = createdAt,
            )
        }

        if (
            shouldInitiateRecovery &&
            initialMainRecoveryAttemptId != null &&
            contactState.mainRecoveryAttemptId != null &&
            localMainRecoveryMarker == null
        ) {
            recoveryStore.publishRecoveryMarker(
                from = ownPublicKey,
                to = normalizedKey,
                stage = RECOVERY_MARKER_STAGE_INIT,
                attemptId = checkNotNull(contactState.mainRecoveryAttemptId),
                createdAt = clock.now().epochSeconds,
            )
        }

        if (isRecovering && !shouldAcceptRemoteRecovery && contactState.responderRecoveryAttemptId != null) {
            contactState.responderRecoveryAttemptId = null
            persistState(markWalletBackup = true)
        }

        if (
            shouldInitiateRecovery &&
            contactState.mainRecoveryAttemptId != null &&
            contactState.handshakeSnapshotHex != null
        ) {
            val attemptId = checkNotNull(contactState.mainRecoveryAttemptId)
            recoveryStore.publishRecoveryMarker(
                from = ownPublicKey,
                to = normalizedKey,
                stage = RECOVERY_MARKER_STAGE_INIT,
                attemptId = attemptId,
                createdAt = clock.now().epochSeconds,
            )
            val hasPeerProgress = recoveryStore.freshRecoveryMarker(
                from = normalizedKey,
                to = ownPublicKey,
                stages = setOf(RECOVERY_MARKER_STAGE_RESPONSE, RECOVERY_MARKER_STAGE_FINAL),
                attemptId = attemptId,
            ) != null
            if (!hasPeerProgress) return null
        }

        if (
            shouldAcceptRemoteRecovery &&
            contactState.responderRecoveryAttemptId != null &&
            contactState.handshakeSnapshotHex != null
        ) {
            val attemptId = checkNotNull(contactState.responderRecoveryAttemptId)
            val hasPeerFinal = recoveryStore.freshRecoveryMarker(
                from = normalizedKey,
                to = ownPublicKey,
                stages = setOf(RECOVERY_MARKER_STAGE_FINAL),
                attemptId = attemptId,
            ) != null
            if (!hasPeerFinal) {
                recoveryStore.publishRecoveryMarker(
                    from = ownPublicKey,
                    to = normalizedKey,
                    stage = RECOVERY_MARKER_STAGE_RESPONSE,
                    attemptId = attemptId,
                    createdAt = clock.now().epochSeconds,
                )
                return null
            }
        }

        var handshakeId = activeHandlesByContact[normalizedKey]?.handshakeId
        if (handshakeId == null) {
            contactState.handshakeSnapshotHex?.let { snapshot ->
                val shouldRestoreSnapshot = runCatching {
                    snapshotRecipientMatches(
                        snapshotHex = snapshot,
                        publicKey = normalizedKey,
                        recipient = pubkyService::encryptedLinkHandshakeSnapshotRecipient,
                    )
                }.getOrElse {
                    Logger.warn(
                        "Failed to inspect private Paykit handshake snapshot for '${redacted(normalizedKey)}'",
                        it,
                        context = TAG,
                    )
                    clearInvalidHandshakeSnapshotState(contactState)
                    false
                }

                if (!shouldRestoreSnapshot) {
                    if (contactState.handshakeSnapshotHex != null) {
                        Logger.warn(
                            "Dropped private Paykit handshake snapshot with mismatched recipient for " +
                                "'${redacted(normalizedKey)}'",
                            context = TAG,
                        )
                        clearInvalidHandshakeSnapshotState(contactState)
                    }
                    return@let
                }

                runCatching {
                    handshakeId = pubkyService.restoreEncryptedLinkHandshake(secretKeyHex, snapshot)
                    ensureCurrentGeneration(generation)
                }.onFailure {
                    if (it is PrivatePaykitError.PrivateUnavailable) throw it
                    Logger.warn(
                        "Failed to restore private Paykit handshake for '${redacted(normalizedKey)}'",
                        it,
                        context = TAG,
                    )
                    contactState.handshakeSnapshotHex = null
                    contactState.mainRecoveryAttemptId = null
                    persistState(markWalletBackup = true)
                }
            }
        }

        if (handshakeId == null) {
            val shouldInitiate = shouldInitiateRecovery ||
                (!shouldAcceptRemoteRecovery && shouldInitiate(ownPublicKey, normalizedKey))
            handshakeId = if (shouldInitiate) {
                pubkyService.initiateEncryptedLink(secretKeyHex, normalizedKey)
            } else {
                pubkyService.acceptEncryptedLink(secretKeyHex, normalizedKey)
            }
            ensureCurrentGeneration(generation)
            if (isRecovering) {
                contactState.recoveryStartedAt = clock.now().epochSeconds
                persistState(markWalletBackup = true)
            }
        }

        val isRecoveryHandshake = shouldInitiateRecovery || shouldAcceptRemoteRecovery
        activeHandlesByContact[normalizedKey] = ContactPaykitHandles(handshakeId = handshakeId)
        repeat(maxAdvanceSteps) {
            val progress = runCatching { pubkyService.advanceHandshake(checkNotNull(handshakeId)) }
                .getOrElse {
                    if (PrivatePaykitErrorClassifier.isEncryptedHandshakePendingError(it)) {
                        val snapshot = pubkyService.serializeEncryptedLinkHandshake(checkNotNull(handshakeId))
                        ensureCurrentGeneration(generation)
                        contactState.handshakeSnapshotHex = snapshot
                        contactState.handshakeUpdatedAt = clock.now().epochSeconds
                        activeHandlesByContact[normalizedKey] = ContactPaykitHandles(handshakeId = handshakeId)
                        persistState(markWalletBackup = true)
                        return null
                    }
                    if (PrivatePaykitErrorClassifier.isEncryptedHandshakeStateFailure(it)) {
                        ensureCurrentGeneration(generation)
                        activeHandlesByContact[normalizedKey] = ContactPaykitHandles()
                        contactState.handshakeSnapshotHex = null
                        contactState.mainRecoveryAttemptId = null
                        persistState(markWalletBackup = true)
                    }
                    throw it
                }
            ensureCurrentGeneration(generation)

            if (progress.status == HANDSHAKE_COMPLETE) {
                val linkId = progress.handleId
                val attemptId = contactState.mainRecoveryAttemptId ?: contactState.responderRecoveryAttemptId
                activeHandlesByContact[normalizedKey] = ContactPaykitHandles(linkId = linkId)
                contactState.handshakeSnapshotHex = null
                contactState.recoveryStartedAt = null
                persistLinkSnapshot(
                    linkId = linkId,
                    publicKey = normalizedKey,
                    linkWasReplaced = true,
                    generation = generation,
                ).getOrThrow()
                if (isRecoveryHandshake && attemptId != null) {
                    recoveryStore.publishRecoveryMarker(
                        from = ownPublicKey,
                        to = normalizedKey,
                        stage = RECOVERY_MARKER_STAGE_FINAL,
                        attemptId = attemptId,
                        createdAt = clock.now().epochSeconds,
                    )
                }
                return linkId
            }

            handshakeId = progress.handleId
            activeHandlesByContact[normalizedKey] = ContactPaykitHandles(handshakeId = handshakeId)
            contactState.handshakeSnapshotHex =
                pubkyService.serializeEncryptedLinkHandshake(checkNotNull(handshakeId))
            ensureCurrentGeneration(generation)
            contactState.handshakeUpdatedAt = clock.now().epochSeconds
            persistState(markWalletBackup = true)

            if (isRecoveryHandshake) {
                val createdAt = clock.now().epochSeconds
                if (shouldInitiateRecovery && contactState.mainRecoveryAttemptId != null) {
                    recoveryStore.publishRecoveryMarker(
                        from = ownPublicKey,
                        to = normalizedKey,
                        stage = RECOVERY_MARKER_STAGE_INIT,
                        attemptId = checkNotNull(contactState.mainRecoveryAttemptId),
                        createdAt = createdAt,
                    )
                } else if (shouldAcceptRemoteRecovery && contactState.responderRecoveryAttemptId != null) {
                    recoveryStore.publishRecoveryMarker(
                        from = ownPublicKey,
                        to = normalizedKey,
                        stage = RECOVERY_MARKER_STAGE_RESPONSE,
                        attemptId = checkNotNull(contactState.responderRecoveryAttemptId),
                        createdAt = createdAt,
                    )
                }
                return null
            }
        }

        return null
    }

    private suspend fun removePublishedEndpoints(): Result<Unit> = withContext(serializedDispatcher) {
        runCatching {
            resetInFlightWork()
            var firstError: Throwable? = null
            ensureState().contacts.keys.toList().forEach {
                removePublishedEndpoints(it).onFailure { error ->
                    if (firstError == null) firstError = error
                    Logger.warn(
                        "Failed to remove private Paykit endpoints for '${redacted(it)}'",
                        error,
                        context = TAG,
                    )
                }
            }
            firstError?.let { throw it }
            Unit
        }
    }

    private suspend fun removePublishedEndpoints(publicKey: String): Result<Unit> = withContext(serializedDispatcher) {
        val generation = currentStateGeneration()
        runCatching {
            publicationMutex.withLock {
                linkEstablishmentMutex.withLock {
                    ensureCurrentGeneration(generation)
                    val activeLinkId = activeHandlesByContact[publicKey]?.linkId
                    val restoredLinkId = ensureState().contacts[publicKey]?.linkSnapshotHex
                        ?.let {
                            val secretKey = keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)
                                ?: return@let null
                            validateSnapshot(it, publicKey, pubkyService::encryptedLinkSnapshotRecipient)
                            pubkyService.restoreEncryptedLink(secretKey, it).also { linkId ->
                                ensureCurrentGeneration(generation)
                                activeHandlesByContact[publicKey] = ContactPaykitHandles(linkId = linkId)
                            }
                        }
                    val linkId = activeLinkId ?: restoredLinkId
                        ?: runCatching {
                            establishedLinkIdUnlocked(
                                publicKey = publicKey,
                                maxAdvanceSteps = 5,
                                generation = generation,
                            )
                        }.getOrNull()
                        ?: run {
                            if (shouldRequirePrivateEndpointRemoval(publicKey)) {
                                throw PrivatePaykitError.PrivateUnavailable
                            }
                            null
                        }
                    if (linkId == null) return@withLock

                    val entries = PrivatePaykitPayloads.privateEndpointRemovalEntries()
                    PrivatePaykitPayloads.validateNoisePayload(entries)
                    pubkyService.setPrivatePayments(
                        linkId,
                        entries.map { it.toFfiPaymentEndpoint() },
                    )
                    ensureCurrentGeneration(generation)
                    ensureState().contacts[publicKey]?.lastLocalPayloadHash = null
                    ensureState().contacts[publicKey]?.localInvoice = null
                    persistLinkSnapshot(
                        linkId = linkId,
                        publicKey = publicKey,
                        linkWasReplaced = false,
                        generation = generation,
                    ).getOrThrow()
                    pubkyService.currentPublicKey()
                        ?.let { PubkyPublicKeyFormat.normalized(it) }
                        ?.let { recoveryStore.clearRecoveryMarker(from = it, to = publicKey) }
                }
            }
            Unit
        }.onFailure {
            recordLinkFailure(publicKey, it, generation)
        }
    }

    private suspend fun clearUnsavedContactState(savedPublicKeys: Collection<String>): Result<Unit> =
        withContext(serializedDispatcher) {
            runCatching {
                val savedKeys = savedPublicKeys.mapNotNull { normalizedPublicKey(it) }.toSet()
                val staleKeys = ensureState().contacts.keys.filter { it !in savedKeys }
                if (staleKeys.isNotEmpty()) advanceStateGeneration()
                staleKeys.forEach {
                    clearContactState(it)
                }
                addressReservationRepo.clearContactAssignments(excludingPublicKeys = savedKeys)
            }
        }

    private suspend fun clearContactState(publicKey: String) {
        cancelPendingPublicationRetry(publicKey)
        pubkyService.currentPublicKey()
            ?.let { PubkyPublicKeyFormat.normalized(it) }
            ?.let { recoveryStore.clearRecoveryMarker(from = it, to = publicKey) }
        activeHandlesByContact[publicKey]?.linkId?.let { runCatching { pubkyService.closeEncryptedLink(it) } }
        activeHandlesByContact[publicKey]?.handshakeId?.let {
            runCatching { pubkyService.dropEncryptedLinkHandshake(it) }
        }
        activeHandlesByContact.remove(publicKey)
        ensureState().contacts.remove(publicKey)
        persistState(markWalletBackup = true)
    }

    private suspend fun markContactsForProfileRecovery(publicKeys: Collection<String>, startedAt: Long) {
        val state = ensureState()
        publicKeys.forEach { publicKey ->
            cancelPendingPublicationRetry(publicKey)
            activeHandlesByContact[publicKey]?.linkId?.let { runCatching { pubkyService.closeEncryptedLink(it) } }
            activeHandlesByContact[publicKey]?.handshakeId?.let {
                runCatching { pubkyService.dropEncryptedLinkHandshake(it) }
            }
            activeHandlesByContact[publicKey] = ContactPaykitHandles()
            state.contacts[publicKey] = ContactState(recoveryStartedAt = startedAt)
        }
    }

    private suspend fun closeActiveHandles() {
        activeHandlesByContact.values.forEach { handles ->
            handles.linkId?.let { runCatching { pubkyService.closeEncryptedLink(it) } }
            handles.handshakeId?.let { runCatching { pubkyService.dropEncryptedLinkHandshake(it) } }
        }
    }

    private suspend fun persistLinkSnapshot(
        linkId: String,
        publicKey: String,
        linkWasReplaced: Boolean,
        generation: Long = currentStateGeneration(),
    ): Result<Unit> = withContext(serializedDispatcher) {
        runCatching {
            ensureCurrentGeneration(generation)
            if (activeHandlesByContact[publicKey]?.linkId != linkId) throw PrivatePaykitError.StaleLinkState
            val snapshotHex = pubkyService.serializeEncryptedLink(linkId)
            ensureCurrentGeneration(generation)
            val contactState = ensureState().contacts.getOrPut(publicKey) { ContactState() }
            val completedAttemptId = contactState.mainRecoveryAttemptId ?: contactState.responderRecoveryAttemptId
            contactState.linkSnapshotHex = snapshotHex
            contactState.handshakeSnapshotHex = null
            contactState.recoveryStartedAt = null
            contactState.mainRecoveryAttemptId = null
            contactState.responderRecoveryAttemptId = null
            if (completedAttemptId != null) {
                contactState.lastCompletedRecoveryAttemptId = completedAttemptId
                contactState.awaitingRecoveredRemoteEndpoints = true
            }
            if (linkWasReplaced || contactState.linkCompletedAt == null) {
                contactState.linkCompletedAt = clock.now().epochSeconds
            }
            if (linkWasReplaced) {
                contactState.lastLocalPayloadHash = null
            }
            persistState(markWalletBackup = true)
        }
    }

    private suspend fun privatePayableEndpoints(endpoints: List<Endpoint>, publicKey: String): List<Endpoint> {
        val payable = publicPaykitRepo.payableEndpoints(endpoints)
        val attemptedHashes = attemptedOutboundBolt11PaymentHashes()
        val staleLightningHashes = mutableSetOf<String>()
        val reusable = payable.filter { endpoint ->
            when {
                endpoint.methodId == MethodId.Bolt11 -> {
                    val paymentHash = paymentHashForBolt11(endpoint.value)?.lowercase() ?: return@filter false
                    if (!PublicPaykitRepo.hasLightningRouteHints(endpoint.value)) {
                        staleLightningHashes += paymentHash
                        Logger.warn(
                            "Ignoring private Paykit invoice without route hints for '${redacted(publicKey)}'",
                            context = TAG,
                        )
                        false
                    } else if (paymentHash in attemptedHashes) {
                        staleLightningHashes += paymentHash
                        Logger.warn(
                            "Ignoring already-attempted private Paykit invoice for '${redacted(publicKey)}'",
                            context = TAG,
                        )
                        false
                    } else {
                        true
                    }
                }
                endpoint.methodId.isOnchain -> {
                    val isUsed = runCatching { coreService.isAddressUsed(endpoint.value) }
                        .onFailure {
                            Logger.warn(
                                "Failed to check private Paykit endpoint usage for '${redacted(publicKey)}'",
                                it,
                                context = TAG,
                            )
                        }
                        .getOrDefault(true)
                    !isUsed
                }
                else -> true
            }
        }

        if (staleLightningHashes.isNotEmpty()) {
            discardRemoteLightningEndpoints(publicKey, staleLightningHashes).onFailure {
                Logger.warn(
                    "Failed to discard already-attempted private Paykit invoice for '${redacted(publicKey)}'",
                    it,
                    context = TAG,
                )
            }
        }
        return reusable
    }

    private suspend fun shouldDiscardRemoteLightningEntry(
        entry: StoredPaymentEntry,
        paymentHashes: Set<String>,
    ): Boolean {
        if (entry.methodId != MethodId.Bolt11.rawValue) return false
        val endpoint = PublicPaykitRepo.parseEndpoint(entry.methodId, entry.endpointData) ?: return false
        val paymentHash = paymentHashForBolt11(endpoint.value)?.lowercase() ?: return false
        return paymentHash in paymentHashes
    }

    private fun shouldDiscardRemoteOnchainEntry(
        entry: StoredPaymentEntry,
        addresses: Set<String>,
    ): Boolean {
        val endpoint = PublicPaykitRepo.parseEndpoint(entry.methodId, entry.endpointData) ?: return false
        if (!endpoint.methodId.isOnchain) return false
        return endpoint.value in addresses
    }

    private suspend fun canPublishPrivateEndpoints(): Boolean {
        val settings = settingsStore.data.first()
        return settings.sharesPrivatePaykitEndpoints &&
            hasLocalSecretKeyForCurrentProfile() &&
            App.currentActivity?.value != null &&
            walletRepo.walletExists() &&
            lightningRepo.lightningState.value.nodeLifecycleState.isRunning()
    }

    private suspend fun hasLocalSecretKeyForCurrentProfile(): Boolean = runCatching {
        val ownPublicKey = pubkyService.currentPublicKey() ?: return@runCatching false
        val secretKeyHex = keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)
            ?: return@runCatching false
        val derivedPublicKey = pubkyService.publicKeyFromSecret(secretKeyHex)
        PubkyPublicKeyFormat.matches(derivedPublicKey, ownPublicKey)
    }.getOrDefault(false)

    private suspend fun isContactSharingCleanupPending(): Boolean =
        cacheStore.data.first().cleanupPending

    private suspend fun updateContactSharingCleanupPending(isPending: Boolean) {
        cacheStore.update { it.copy(cleanupPending = isPending) }
    }

    private suspend fun isProfileRecoveryPending(): Boolean =
        cacheStore.data.first().profileRecoveryPending

    private suspend fun updateProfileRecoveryPending(isPending: Boolean) {
        cacheStore.update { it.copy(profileRecoveryPending = isPending) }
    }

    private suspend fun pendingDeletedContactCleanupPublicKeys(): Set<String> =
        cacheStore.data.first().deletedContactCleanupPendingPublicKeys

    private suspend fun updateDeletedContactCleanupPending(publicKey: String, isPending: Boolean) {
        cacheStore.update {
            val pendingKeys = if (isPending) {
                it.deletedContactCleanupPendingPublicKeys + publicKey
            } else {
                it.deletedContactCleanupPendingPublicKeys - publicKey
            }
            it.copy(deletedContactCleanupPendingPublicKeys = pendingKeys)
        }
    }

    private suspend fun retryPendingDeletedContactEndpointRemoval(
        savedPublicKeys: Collection<String>,
    ): Result<Unit> = withContext(serializedDispatcher) {
        runCatching {
            val savedKeys = savedPublicKeys.mapNotNull { normalizedPublicKey(it) }.toSet()
            pendingDeletedContactCleanupPublicKeys().forEach { publicKey ->
                if (publicKey in savedKeys) {
                    updateDeletedContactCleanupPending(publicKey, false)
                    return@forEach
                }
                removePublishedEndpoints(publicKey).getOrThrow()
                clearContactState(publicKey)
                addressReservationRepo.clearContactAssignment(publicKey)
                updateDeletedContactCleanupPending(publicKey, false)
            }
        }
    }

    private fun shouldRequirePrivateEndpointRemoval(publicKey: String): Boolean {
        val contactState = stateStore.currentState()?.contacts?.get(publicKey) ?: return false
        return contactState.linkSnapshotHex != null ||
            contactState.lastLocalPayloadHash != null ||
            contactState.localInvoice != null ||
            contactState.linkCompletedAt != null ||
            contactState.recoveryStartedAt != null
    }

    private suspend fun shouldPublishLocalEndpoints(publicKey: String, fetchedRemoteCount: Int): Boolean {
        val contactState = ensureState().contacts[publicKey]
        if (contactState?.lastLocalPayloadHash != null) return true
        if (fetchedRemoteCount > 0 || contactState?.remoteEndpoints?.isNotEmpty() == true) return true
        val ownPublicKey = pubkyService.currentPublicKey() ?: return false
        return shouldInitiate(ownPublicKey, publicKey)
    }

    private suspend fun contactStateShouldPublishBeforeFetch(publicKey: String): Boolean {
        if (!shouldPublishLocalEndpoints(publicKey, fetchedRemoteCount = 0)) return false
        return !shouldDeferInitialLocalPublish(publicKey, fetchedRemoteCount = 0)
    }

    private suspend fun shouldDeferInitialLocalPublish(publicKey: String, fetchedRemoteCount: Int): Boolean {
        val contactState = ensureState().contacts[publicKey] ?: return false
        val linkCompletedAt = contactState.linkCompletedAt ?: return false
        return fetchedRemoteCount == 0 &&
            contactState.lastLocalPayloadHash == null &&
            contactState.remoteEndpoints.isEmpty() &&
            clock.now().epochSeconds <= linkCompletedAt + FRESH_LINK_INITIAL_PUBLISH_DELAY_SECONDS
    }

    @Suppress("ReturnCount")
    private suspend fun shouldStartRecoveryHandshake(publicKey: String): Boolean {
        val contactState = ensureState().contacts[publicKey] ?: return false
        if (contactState.linkSnapshotHex != null) return false
        if (contactState.recoveryStartedAt != null || contactState.mainRecoveryAttemptId != null) return true
        if (contactState.handshakeSnapshotHex != null) return false
        if (contactState.linkCompletedAt != null || contactState.handshakeUpdatedAt != null) return true
        return addressReservationRepo.hasContactAssignment(publicKey)
    }

    private suspend fun discardLinkForRecovery(publicKey: String, linkId: String?, startedAt: Long): Boolean {
        linkId?.let { runCatching { pubkyService.closeEncryptedLink(it) } }
        activeHandlesByContact[publicKey] = ContactPaykitHandles()
        ensureState().contacts[publicKey]?.apply {
            linkSnapshotHex = null
            handshakeSnapshotHex = null
            lastLocalPayloadHash = null
            remoteEndpoints = emptyList()
            recoveryStartedAt = startedAt
            mainRecoveryAttemptId = null
            responderRecoveryAttemptId = null
            awaitingRecoveredRemoteEndpoints = false
        }
        persistState(markWalletBackup = true)
        return true
    }

    private fun shouldAcceptRemoteRecoveryMarker(
        remoteMarker: RecoveryMarker,
        localMarker: RecoveryMarker?,
        ownPublicKey: String,
        remotePublicKey: String,
    ): Boolean {
        if (localMarker == null) return true
        if (remoteMarker.createdAt != localMarker.createdAt) return remoteMarker.createdAt < localMarker.createdAt
        if (remoteMarker.attemptId != localMarker.attemptId) return remoteMarker.attemptId < localMarker.attemptId
        return remotePublicKey < ownPublicKey
    }

    private fun isCompletedRecoveryMarker(marker: RecoveryMarker, publicKey: String): Boolean =
        stateStore.currentState()?.contacts?.get(publicKey)?.lastCompletedRecoveryAttemptId == marker.attemptId

    private fun shouldReplaceUsableLink(marker: RecoveryMarker, publicKey: String): Boolean {
        if (isCompletedRecoveryMarker(marker, publicKey)) return false
        val linkCompletedAt = stateStore.currentState()?.contacts?.get(publicKey)?.linkCompletedAt ?: return true
        return marker.createdAt > linkCompletedAt
    }

    private suspend fun settledPrivateInvoicePaymentHashes(): List<String> {
        val settled = receivedSettledPaymentHashes()
        return ensureState().contacts.values.mapNotNull { it.localInvoice?.paymentHash?.takeIf(settled::contains) }
    }

    private suspend fun paymentHashForBolt11(bolt11: String): String? =
        runCatching {
            (coreService.decode(bolt11) as? Scanner.Lightning)?.invoice?.paymentHash?.toHex()
        }.getOrNull()

    private suspend fun attemptedOutboundBolt11PaymentHashes(): Set<String> =
        lightningRepo.getPayments().getOrDefault(emptyList())
            .filter {
                it.direction == PaymentDirection.OUTBOUND &&
                    it.status != PaymentStatus.FAILED &&
                    it.kind is PaymentKind.Bolt11
            }
            .map { it.id.lowercase() }
            .toSet()

    private suspend fun isReceivedInvoiceSettled(paymentHash: String): Boolean =
        paymentHash in receivedSettledPaymentHashes()

    private suspend fun receivedSettledPaymentHashes(): Set<String> =
        lightningRepo.getPayments().getOrDefault(emptyList())
            .filter {
                it.direction == PaymentDirection.INBOUND &&
                    it.status == PaymentStatus.SUCCEEDED &&
                    it.kind is PaymentKind.Bolt11
            }
            .map { it.id }
            .toSet()

    private suspend fun rememberReceivedInvoicePaymentHash(paymentHash: String, publicKey: String) {
        if (paymentHash.isBlank()) return
        val contactState = ensureState().contacts.getOrPut(publicKey) { ContactState() }
        if (paymentHash in contactState.receivedInvoicePaymentHashes) return
        contactState.receivedInvoicePaymentHashes =
            (contactState.receivedInvoicePaymentHashes + paymentHash)
                .takeLast(MAX_RECEIVED_INVOICE_HASHES_PER_CONTACT)
        persistState()
    }

    private suspend fun recordLinkFailure(publicKey: String, error: Throwable, generation: Long? = null) {
        if (generation != null && stateGeneration.get() != generation) return
        if (!PrivatePaykitErrorClassifier.shouldCountAsStaleLinkFailure(error)) return
        val contactState = ensureState().contacts.getOrPut(publicKey) { ContactState() }
        contactState.linkFailureCount += 1
        if (contactState.linkFailureCount < STALE_LINK_FAILURE_THRESHOLD) {
            persistState()
            return
        }

        advanceStateGeneration()
        activeHandlesByContact[publicKey]?.linkId?.let { runCatching { pubkyService.closeEncryptedLink(it) } }
        activeHandlesByContact[publicKey] = ContactPaykitHandles()
        contactState.linkSnapshotHex = null
        contactState.handshakeSnapshotHex = null
        contactState.lastLocalPayloadHash = null
        contactState.remoteEndpoints = emptyList()
        contactState.linkFailureCount = 0
        contactState.recoveryStartedAt = clock.now().epochSeconds
        contactState.mainRecoveryAttemptId = null
        contactState.responderRecoveryAttemptId = null
        contactState.awaitingRecoveredRemoteEndpoints = false
        persistState(markWalletBackup = true)
    }

    private suspend fun recordLinkSuccess(publicKey: String) {
        val contactState = ensureState().contacts[publicKey] ?: return
        if (contactState.linkFailureCount == 0) return
        contactState.linkFailureCount = 0
        persistState()
    }

    private fun clearInvalidLinkSnapshotState(contactState: ContactState) {
        contactState.linkSnapshotHex = null
        contactState.handshakeSnapshotHex = null
        contactState.remoteEndpoints = emptyList()
        contactState.lastLocalPayloadHash = null
        contactState.linkCompletedAt = null
        contactState.handshakeUpdatedAt = null
        contactState.recoveryStartedAt = null
        contactState.mainRecoveryAttemptId = null
        contactState.responderRecoveryAttemptId = null
        contactState.awaitingRecoveredRemoteEndpoints = false
        contactState.linkFailureCount = 0
    }

    private fun clearInvalidHandshakeSnapshotState(contactState: ContactState) {
        contactState.handshakeSnapshotHex = null
        contactState.lastLocalPayloadHash = null
        contactState.handshakeUpdatedAt = null
        contactState.recoveryStartedAt = null
        contactState.mainRecoveryAttemptId = null
        contactState.responderRecoveryAttemptId = null
        contactState.linkFailureCount = 0
    }

    private suspend fun validatedSnapshot(
        snapshotHex: String?,
        publicKey: String,
        recipient: suspend (String) -> String,
    ): String? {
        if (snapshotHex == null) return null
        return runCatching {
            validateSnapshot(snapshotHex, publicKey, recipient)
            snapshotHex
        }.onFailure {
            Logger.warn(
                "Dropped private Paykit snapshot with mismatched recipient for '${redacted(publicKey)}'",
                it,
                context = TAG,
            )
        }.getOrNull()
    }

    private suspend fun validateSnapshot(
        snapshotHex: String,
        publicKey: String,
        recipient: suspend (String) -> String,
    ) {
        if (!snapshotRecipientMatches(snapshotHex, publicKey, recipient)) {
            throw PrivatePaykitError.SnapshotRecipientMismatch
        }
    }

    private suspend fun snapshotRecipientMatches(
        snapshotHex: String,
        publicKey: String,
        recipient: suspend (String) -> String,
    ): Boolean {
        val snapshotRecipient = recipient(snapshotHex)
        return PubkyPublicKeyFormat.normalized(snapshotRecipient) == PubkyPublicKeyFormat.normalized(publicKey)
    }

    private fun rememberSavedContacts(publicKeys: Collection<String>, replacing: Boolean): List<String> {
        val normalizedKeys = publicKeys.mapNotNull { normalizedPublicKey(it) }.distinct()
        if (replacing) {
            knownSavedContactKeys.clear()
            knownSavedContactKeys += normalizedKeys
        } else {
            knownSavedContactKeys += normalizedKeys
        }
        return normalizedKeys
    }

    private fun knownSavedContact(publicKey: String): String? {
        val normalizedKey = normalizedPublicKey(publicKey) ?: return null
        return normalizedKey.takeIf { it in knownSavedContactKeys }
    }

    private fun normalizedPublicKey(publicKey: String): String? = PubkyPublicKeyFormat.normalized(publicKey)

    private fun redacted(publicKey: String): String = PubkyPublicKeyFormat.redacted(publicKey)

    private suspend fun ensureState(): PrivatePaykitState = stateStore.ensureState()

    private suspend fun persistState(
        markWalletBackup: Boolean = false,
        preserveCleanupMarkers: Boolean = true,
    ) {
        stateStore.persistState(markWalletBackup, ::notifyBackupStateChanged, preserveCleanupMarkers)
    }

    private fun notifyBackupStateChanged() {
        _backupStateVersion.update { it + 1 }
    }
}

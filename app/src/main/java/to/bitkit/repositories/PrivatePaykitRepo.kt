package to.bitkit.repositories

import com.synonym.bitkitcore.Scanner
import com.synonym.paykit.LinkedPeerState
import com.synonym.paykit.PaykitException
import com.synonym.paykit.PaymentAmountContext
import com.synonym.paykit.PrivatePaymentEndpointReservationInput
import com.synonym.paykit.PrivatePaymentListDeliveryReport
import com.synonym.paykit.PrivatePaymentListReservationUpdateInput
import com.synonym.paykit.PrivatePaymentResolutionState
import com.synonym.paykit.PrivatePaymentResolutionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import to.bitkit.App
import to.bitkit.async.appScope
import to.bitkit.data.PrivatePaykitCacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.di.IoDispatcher
import to.bitkit.di.json
import to.bitkit.ext.isPaykitRecoveryRequired
import to.bitkit.ext.runSuspendCatching
import to.bitkit.ext.toHex
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.services.CoreService
import to.bitkit.services.PaykitPreparedPrivateContactPayment
import to.bitkit.services.PaykitPrivateContactPaymentResolution
import to.bitkit.services.PaykitSdkService
import to.bitkit.services.PubkyService
import to.bitkit.utils.Logger
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
@Singleton
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
class PrivatePaykitRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val paykitSdkService: PaykitSdkService,
    private val pubkyService: PubkyService,
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
        private val privateInvoiceExpiry = 24.hours
        private val invoiceRefreshBuffer = 30.minutes

        // Private links can finish after a contact is added on the other device;
        // keep draining long enough for staggered mutual adds.
        private val privateMessageDrainRetryDelays = listOf(
            1.seconds,
            3.seconds,
            8.seconds,
            20.seconds,
            45.seconds,
            90.seconds,
        )
        private val initialLinkBurstRetryDelays = List(14) { 2.seconds }
        private val privatePaymentResolutionRetryDelays = listOf(1.seconds, 3.seconds, 8.seconds)

        fun isDuplicatePaymentError(error: Throwable): Boolean =
            PrivatePaykitErrorClassifier.isDuplicatePaymentError(error)
    }

    private val publicationMutex = Mutex()
    private val serializedDispatcher = ioDispatcher.limitedParallelism(1)
    private val retryScope = appScope(serializedDispatcher, TAG)
    private val paymentPublishJobs = ConcurrentHashMap<String, Job>()
    private val knownSavedContactKeys = mutableSetOf<String>()
    private var state: PrivatePaykitState? = null
    private val pendingMessageDrainRetryLock = Any()
    private val pendingMessageDrainRetryKeys = mutableSetOf<String>()
    private var pendingMessageDrainRetryJob: Job? = null
    private var pendingMessageDrainRetryGeneration = 0
    private val _initialLinkBurstStarted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val initialLinkBurstStarted: SharedFlow<Unit> = _initialLinkBurstStarted.asSharedFlow()
    private val initialLinkBurstLock = Any()
    private val initialLinkBurstPublicKeys = mutableSetOf<String>()
    private var initialLinkBurstJob: Job? = null
    private var initialLinkBurstGeneration = 0

    private data class PrivatePublicationPreparation(
        val updates: List<PrivatePaymentListReservationUpdateInput>,
        val linkRetryKeys: List<String>,
        val firstError: Throwable?,
    )

    private data class PrivateEndpointCleanupPreparation(
        val clearedRetryKeys: List<String>,
        val failedPublicKeys: Set<String>,
        val firstError: Throwable?,
    )

    private data class NormalizedPublicKeyBatch(
        val normalizedKeys: List<String>,
        val invalidKeys: Set<String>,
    )

    private data class PublishedEndpointCleanupState(
        val remoteEndpoints: List<StoredPaymentEntry>,
        val localInvoice: StoredInvoice?,
        val hasPublishedPrivatePaymentList: Boolean,
    )

    private val _backupStateVersion = MutableStateFlow(0L)
    val backupStateVersion: StateFlow<Long> = _backupStateVersion.asStateFlow()

    suspend fun reconcileReservedReceiveIndexes(): Result<Unit> =
        addressReservationRepo.reconcileReservedIndexesWithLdk()

    suspend fun hasPrivatePaymentAccess(): Boolean = hasPrivatePaymentAccessForCurrentProfile()

    suspend fun prepareSavedContacts(
        publicKeys: Collection<String>,
        requireImmediatePublication: Boolean = false,
    ): Result<Unit> = withContext(serializedDispatcher) {
        runSuspendCatching {
            val keys = rememberSavedContacts(publicKeys, replacing = true)
            if (!canPublishPrivateEndpoints()) {
                prepareRelevantPrivateLinksIfAvailable(keys, "prepare")
                if (requireImmediatePublication && keys.isNotEmpty()) throw PrivatePaykitError.PrivateUnavailable
                return@runSuspendCatching
            }

            addressReservationRepo.reconcileReservedIndexesWithLdk().getOrThrow()
            publishLocalEndpoints(
                publicKeys = keys,
                reason = "prepare",
                requireImmediatePublication = requireImmediatePublication,
            ).getOrThrow()
        }
    }

    suspend fun enableSharingAndPrepareSavedContacts(
        publicKeys: Collection<String>,
    ): Result<Unit> = withContext(serializedDispatcher) {
        runSuspendCatching {
            val wasCleanupPending = isContactSharingCleanupPending()
            updateContactSharingCleanupPending(false)
            prepareSavedContacts(publicKeys).onFailure {
                if (wasCleanupPending) {
                    runSuspendCatching { updateContactSharingCleanupPending(true) }.onFailure(it::addSuppressed)
                }
            }.getOrThrow()
        }
    }

    suspend fun refreshSavedContactEndpoints(
        publicKey: String,
        savedPublicKeys: Collection<String>,
    ): Result<Unit> =
        withContext(serializedDispatcher) {
            runSuspendCatching {
                val normalizedKey = normalizedPublicKey(publicKey) ?: return@runSuspendCatching
                rememberSavedContacts(savedPublicKeys + normalizedKey, replacing = false)
                val keys = listOf(normalizedKey)
                if (!canPublishPrivateEndpoints()) {
                    prepareRelevantPrivateLinksIfAvailable(keys, "refresh")
                    return@runSuspendCatching
                }
                publishLocalEndpoints(keys, reason = "refresh").getOrThrow()
            }
        }

    suspend fun refreshKnownSavedContactEndpoints(
        reason: String,
        forceRefreshLightning: Boolean = false,
    ): Result<Unit> = withContext(serializedDispatcher) {
        runSuspendCatching {
            if (!canPublishPrivateEndpoints()) {
                prepareRelevantPrivateLinksIfAvailable(knownSavedContactKeys, reason)
                return@runSuspendCatching
            }
            publishLocalEndpoints(
                publicKeys = knownSavedContactKeys.toList(),
                reason = reason,
                forceRefreshLightning = forceRefreshLightning,
            ).getOrThrow()
        }.onFailure {
            Logger.warn("Failed to refresh private Paykit endpoints for '$reason'", it, context = TAG)
        }
    }

    fun startInitialLinkBurst(publicKeys: Collection<String>, reason: String) {
        val publicKeys = normalizedPublicKeyBatch(publicKeys).normalizedKeys

        synchronized(initialLinkBurstLock) {
            initialLinkBurstGeneration += 1
            initialLinkBurstJob?.cancel()
            initialLinkBurstJob = null
            initialLinkBurstPublicKeys.clear()
            initialLinkBurstPublicKeys += publicKeys
            if (publicKeys.isEmpty()) return

            val generation = initialLinkBurstGeneration
            _initialLinkBurstStarted.tryEmit(Unit)

            initialLinkBurstJob = retryScope.launch {
                for (retryDelay in listOf(kotlin.time.Duration.ZERO) + initialLinkBurstRetryDelays) {
                    delay(retryDelay)
                    val keys = synchronized(initialLinkBurstLock) {
                        if (generation != initialLinkBurstGeneration) return@launch
                        initialLinkBurstPublicKeys.toList()
                    }
                    refreshSavedContactEndpointsDuringInitialLinkBurst(keys, reason)
                    if (pendingPrivateMessageDrainKeys(keys, retryMissingPeers = true).isEmpty()) break
                }
                synchronized(initialLinkBurstLock) {
                    if (generation != initialLinkBurstGeneration) return@launch
                    initialLinkBurstJob = null
                    initialLinkBurstPublicKeys.clear()
                }
            }
        }
    }

    suspend fun retryPendingEndpointRemoval(
        savedPublicKeys: Collection<String>,
    ): Result<Unit> = withContext(serializedDispatcher) {
        runSuspendCatching {
            val settings = settingsStore.data.first()
            val hasDisabledPublications = !settings.sharesPrivatePaykitEndpoints && hasPublishedPrivateEndpoints()
            val cleanupPending = isContactSharingCleanupPending()
            if (cleanupPending || hasDisabledPublications) {
                if (!cleanupPending) updateContactSharingCleanupPending(true)
                removePublishedEndpoints().getOrThrow()
                clearUnsavedContactState(savedPublicKeys).getOrThrow()
                updateContactSharingCleanupPending(false)
                publicPaykitRepo.syncPaykitApp().onFailure {
                    updateContactSharingCleanupPending(true)
                }.getOrThrow()
            }
            retryPendingDeletedContactEndpointRemoval(savedPublicKeys).getOrThrow()
        }.onFailure {
            Logger.warn("Failed to retry pending Paykit contact endpoint removal", it, context = TAG)
        }
    }

    suspend fun pruneUnsavedContactState(
        savedPublicKeys: Collection<String>,
    ): Result<Unit> = withContext(serializedDispatcher) {
        runSuspendCatching {
            val savedKeys = rememberSavedContacts(savedPublicKeys, replacing = true).toSet()
            val staleKeys = ensureState().contacts.keys.filter { it !in savedKeys }
            staleKeys.forEach { removeSavedContact(it).getOrThrow() }
            addressReservationRepo.clearContactAssignments(excludingPublicKeys = savedKeys)
        }
    }

    suspend fun removeSavedContact(publicKey: String): Result<Unit> = withContext(serializedDispatcher) {
        runSuspendCatching {
            val normalizedKey = normalizedPublicKey(publicKey) ?: return@runSuspendCatching
            knownSavedContactKeys.remove(normalizedKey)
            removePublishedEndpoints(normalizedKey).onFailure {
                updateDeletedContactCleanupPending(normalizedKey, true)
                Logger.warn(
                    "Failed to remove private Paykit endpoints for '${redacted(normalizedKey)}'",
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
            runSuspendCatching {
                updateContactSharingCleanupPending(true)
                removePublishedEndpoints().getOrThrow()
                clearUnsavedContactState(savedPublicKeys).getOrThrow()
                updateContactSharingCleanupPending(false)
                publicPaykitRepo.syncPaykitApp().onFailure {
                    updateContactSharingCleanupPending(true)
                }.getOrThrow()
            }
        }

    suspend fun setContactSharingCleanupPending(isPending: Boolean): Result<Unit> =
        withContext(serializedDispatcher) {
            runSuspendCatching {
                updateContactSharingCleanupPending(isPending)
            }
        }

    suspend fun removePublishedEndpointsForCleanup(context: String): Result<Unit> = withContext(serializedDispatcher) {
        clearInitialLinkBurst()
        runSuspendCatching {
            updateContactSharingCleanupPending(true)
            removePublishedEndpoints().getOrThrow()
            updateContactSharingCleanupPending(false)
            publicPaykitRepo.syncPaykitApp().getOrThrow()
        }.onFailure {
            updateContactSharingCleanupPending(true)
            Logger.warn("Failed to remove private Paykit endpoints during '$context'", it, context = TAG)
        }
    }

    suspend fun closeAndClear(): Result<Unit> = withContext(serializedDispatcher) {
        runSuspendCatching {
            publicationMutex.withLock {
                clearInitialLinkBurst()
                clearPendingMessageDrainRetries()
                knownSavedContactKeys.clear()
                state = PrivatePaykitState()
                cacheStore.reset()
                addressReservationRepo.clearContactAssignments(excludingPublicKeys = emptySet())
                paykitSdkService.clearState()
                notifyBackupStateChanged()
            }
        }
    }

    suspend fun beginSavedContactPayment(publicKey: String): Result<PublicPaykitPaymentResult> =
        withContext(serializedDispatcher) {
            runSuspendCatching {
                val normalizedKey = knownSavedContact(publicKey)
                    ?: return@runSuspendCatching publicPaykitRepo.beginPayment(publicKey).getOrThrow()
                beginSavedContactPaymentWithRetry(normalizedKey)
            }
        }

    suspend fun beginPaymentRequest(request: PaykitPaymentRequest): Result<PublicPaykitPaymentResult> =
        withContext(serializedDispatcher) {
            runSuspendCatching {
                if (request.isExpired(clock.now())) throw PaykitPaymentRequestError.RequestExpired
                val publicKey = normalizedPublicKey(request.counterparty) ?: throw PrivatePaykitError.InvalidPublicKey
                beginContactPayment(publicKey, request).getOrThrow()
            }
        }

    suspend fun beginPaymentRequestWaitingForUpdatedList(
        request: PaykitPaymentRequest,
    ): Result<PublicPaykitPaymentResult> = runSuspendCatching {
        var result = beginPaymentRequest(request).getOrThrow()
        for (retryDelay in initialLinkBurstRetryDelays) {
            if (result != PublicPaykitPaymentResult.WaitingForUpdatedPaymentList) return@runSuspendCatching result
            delay(retryDelay)
            result = beginPaymentRequest(request).getOrThrow()
        }
        result
    }

    suspend fun consumePrivatePaymentList(
        publicKey: String,
        context: PrivatePaykitPaymentContext,
    ): Result<Unit> = withContext(serializedDispatcher) {
        runSuspendCatching {
            val normalizedKey = normalizedPublicKey(publicKey) ?: throw PrivatePaykitError.InvalidPublicKey
            val paymentListVersion = context.paymentListVersion ?: return@runSuspendCatching
            val contactState = ensureState().contacts.getOrPut(normalizedKey) { ContactState() }
            val consumedVersion = contactState.consumedPrivatePaymentListVersion
            if (consumedVersion != null && paymentListVersion <= consumedVersion) {
                throw PrivatePaykitError.PaymentListAlreadyConsumed
            }

            contactState.consumedPrivatePaymentListVersion = paymentListVersion
            contactState.remoteEndpoints = emptyList()
            persistState(markWalletBackup = true)
            Logger.info(
                "Consumed private Paykit payment list version $paymentListVersion " +
                    "for '${redacted(normalizedKey)}'",
                context = TAG,
            )
        }
    }.onFailure {
        Logger.warn("Failed to consume private Paykit payment details", it, context = TAG)
    }

    suspend fun releasePrivatePaymentList(
        publicKey: String,
        context: PrivatePaykitPaymentContext,
    ): Result<Unit> = withContext(serializedDispatcher) {
        runSuspendCatching {
            val normalizedKey = normalizedPublicKey(publicKey) ?: throw PrivatePaykitError.InvalidPublicKey
            val paymentListVersion = context.paymentListVersion ?: return@runSuspendCatching
            val contactState = ensureState().contacts[normalizedKey] ?: return@runSuspendCatching
            val consumedVersion = contactState.consumedPrivatePaymentListVersion
            if (consumedVersion != paymentListVersion) return@runSuspendCatching

            contactState.consumedPrivatePaymentListVersion = null
            persistState(markWalletBackup = true)
            Logger.info(
                "Released private Paykit payment list version '$paymentListVersion' " +
                    "for '${redacted(normalizedKey)}'",
                context = TAG,
            )
        }
    }.onFailure {
        Logger.warn("Failed to release private Paykit payment details", it, context = TAG)
    }

    suspend fun discardRemoteLightningEndpoints(
        publicKey: String,
        paymentHashes: Set<String>,
    ): Result<Unit> = withContext(serializedDispatcher) {
        runSuspendCatching {
            if (paymentHashes.isEmpty()) return@runSuspendCatching
            val normalizedKey = normalizedPublicKey(publicKey) ?: return@runSuspendCatching
            val contactState = ensureState().contacts[normalizedKey] ?: return@runSuspendCatching
            val normalizedHashes = paymentHashes.map { it.lowercase() }.toSet()
            val filteredEntries = contactState.remoteEndpoints.filterNot {
                shouldDiscardRemoteLightningEntry(it, normalizedHashes)
            }
            if (filteredEntries.size == contactState.remoteEndpoints.size) return@runSuspendCatching

            contactState.remoteEndpoints = filteredEntries
            persistState(markWalletBackup = true)
        }
    }

    suspend fun handleReceivedPayment(paymentHash: String): Result<Unit> =
        refreshReceivedPrivateInvoices(setOf(paymentHash), reason = "invoice rotation")

    suspend fun reconcileReceivedPayments(): Result<Unit> = withContext(serializedDispatcher) {
        runSuspendCatching {
            refreshReceivedPrivateInvoices(
                paymentHashes = settledPrivateInvoicePaymentHashes().toSet(),
                reason = "invoice reconciliation",
            ).getOrThrow()
        }
    }

    private suspend fun refreshReceivedPrivateInvoices(
        paymentHashes: Set<String>,
        reason: String,
    ): Result<Unit> = withContext(serializedDispatcher) {
        runSuspendCatching {
            val matches = buildList {
                ensureState().contacts.forEach { (publicKey, contactState) ->
                    if (publicKey !in knownSavedContactKeys) return@forEach
                    contactState.localInvoice?.let { invoice ->
                        if (invoice.paymentHash in paymentHashes) add(publicKey to invoice.paymentHash)
                    }
                }
            }
            if (matches.isEmpty()) return@runSuspendCatching

            matches.forEach { (publicKey, paymentHash) -> rememberReceivedInvoicePaymentHash(paymentHash, publicKey) }
            if (!canPublishPrivateEndpoints()) return@runSuspendCatching

            publishLocalEndpoints(matches.map { it.first }.distinct(), reason = reason)
                .onFailure { Logger.warn("Failed to rotate private Paykit invoice", it, context = TAG) }
                .getOrThrow()
        }
    }

    suspend fun handleOnchainActivity(receivedAddresses: Collection<String> = emptyList()): Result<Unit> =
        withContext(serializedDispatcher) {
            runSuspendCatching {
                if (!canPublishPrivateEndpoints()) return@runSuspendCatching
                val publicKeys = if (receivedAddresses.isEmpty()) {
                    addressReservationRepo.contactsWithUsedReservedAddresses()
                } else {
                    receivedAddresses.mapNotNull {
                        addressReservationRepo.currentContactPublicKeyForReservedAddress(it)
                    }
                }.filter { it in knownSavedContactKeys }.distinct()
                if (publicKeys.isEmpty()) return@runSuspendCatching

                publishLocalEndpoints(publicKeys, reason = "on-chain rotation").getOrThrow()
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

    suspend fun backupSnapshot(): Result<String?> =
        withContext(serializedDispatcher) {
            runSuspendCatching {
                pubkyService.currentPublicKey() ?: return@runSuspendCatching null
                json.encodeToString(
                    PrivatePaykitBackup(
                        sdkState = paykitSdkService.exportBackupState(),
                        consumedPrivatePaymentListVersions = ensureState().contacts
                            .mapNotNull { (publicKey, contactState) ->
                                contactState.consumedPrivatePaymentListVersion?.let { publicKey to it }
                            }.toMap(),
                    )
                )
            }
        }

    suspend fun restoreBackup(backup: String?): Result<Unit> =
        withContext(serializedDispatcher) {
            runSuspendCatching {
                val decoded = backup?.let { json.decodeFromString<PrivatePaykitBackup>(it) }
                decoded?.let { paykitSdkService.retainRecoveryBackup(it.sdkState) }
                clearPendingMessageDrainRetries()
                state = PrivatePaykitState()
                knownSavedContactKeys.clear()
                if (backup == null) {
                    paykitSdkService.clearState()
                } else {
                    requireNotNull(decoded).consumedPrivatePaymentListVersions.forEach { (publicKey, versions) ->
                        ensureState().contacts.getOrPut(publicKey) { ContactState() }
                            .consumedPrivatePaymentListVersion = versions
                    }
                }
                persistState(preserveCleanupMarkers = false)
                notifyBackupStateChanged()
            }
        }

    private suspend fun beginContactPayment(
        publicKey: String,
        paymentRequest: PaykitPaymentRequest?,
    ): Result<PublicPaykitPaymentResult> =
        withContext(serializedDispatcher) {
            runSuspendCatching {
                val consumedVersion = ensureState().contacts[publicKey]?.consumedPrivatePaymentListVersion
                val amount = paymentRequest?.let {
                    PaymentAmountContext(it.amountValue, PaykitIssuerInterop.BITCOIN_ASSET)
                }
                val prepared = runSuspendCatching {
                    preparePrivateContactPayment(
                        publicKey = publicKey,
                        consumedVersion = consumedVersion,
                        amount = amount,
                        paymentRequest = paymentRequest,
                    )
                }.getOrElse {
                    if (paymentRequest == null || !it.isPaykitRecoveryRequired()) throw it
                    if (paymentRequest.isExpired(clock.now())) throw PaykitPaymentRequestError.RequestExpired
                    return@runSuspendCatching privateLinkPendingResult(publicKey)
                } ?: return@runSuspendCatching publicPaykitRepo.beginPayment(publicKey).getOrThrow()
                val resolution = prepared.resolution
                val linkState = currentLinkState(publicKey, prepared.linkState)
                if (paymentRequest == null && canUsePublicPayment(linkState, resolution.status, resolution.state)) {
                    return@runSuspendCatching publicPaykitRepo.beginPayment(publicKey).getOrThrow()
                }

                val result = unresolvedPrivateLinkResult(
                    publicKey = publicKey,
                    paymentRequest = paymentRequest,
                    resolution = resolution,
                    linkState = linkState,
                ) ?: privatePaymentResult(
                    publicKey = publicKey,
                    resolution = resolution,
                    consumedVersion = consumedVersion,
                    paymentRequest = paymentRequest,
                )
                if (paymentRequest?.isExpired(clock.now()) == true) {
                    throw PaykitPaymentRequestError.RequestExpired
                }
                result
            }
        }

    private suspend fun beginSavedContactPaymentWithRetry(publicKey: String): PublicPaykitPaymentResult {
        paymentPublishJobs.compute(publicKey) { _, job ->
            job?.takeIf { it.isActive } ?: retryScope.launch { refreshPrivateEndpointsBeforePayment(publicKey) }
        }
        var result = beginContactPayment(publicKey, paymentRequest = null).getOrThrow()
        for (retryDelay in privatePaymentResolutionRetryDelays) {
            if (result != PublicPaykitPaymentResult.WaitingForUpdatedPaymentList) return result
            delay(retryDelay)
            result = beginContactPayment(publicKey, paymentRequest = null).getOrThrow()
        }
        return result
    }

    private suspend fun refreshPrivateEndpointsBeforePayment(publicKey: String) {
        if (!canPublishPrivateEndpoints()) return
        publishLocalEndpoints(
            publicKeys = listOf(publicKey),
            reason = "payment",
        ).onFailure {
            Logger.warn(
                "Failed to refresh private Paykit endpoints before payment for '${redacted(publicKey)}'",
                it,
                context = TAG,
            )
        }
    }

    private suspend fun preparePrivateContactPayment(
        publicKey: String,
        consumedVersion: ULong?,
        amount: PaymentAmountContext?,
        paymentRequest: PaykitPaymentRequest?,
    ): PaykitPreparedPrivateContactPayment? {
        val result = runSuspendCatching {
            if (paymentRequest != null) {
                paykitSdkService.prepareAndResolvePrivatePaymentRequest(
                    counterparty = publicKey,
                    paymentRequestId = paymentRequest.paymentRequestId,
                    afterPrivatePaymentListVersion = consumedVersion,
                )
            } else {
                paykitSdkService.prepareAndResolvePrivateContactPayment(
                    counterparty = publicKey,
                    afterPrivatePaymentListVersion = consumedVersion,
                    amount = amount,
                )
            }
        }
        val error = result.exceptionOrNull() ?: return result.getOrThrow()
        if (paymentRequest != null) throw error
        if (!canUsePublicPayment(currentLinkState(publicKey))) throw error

        Logger.warn(
            "Using public Paykit resolution for '${redacted(publicKey)}'",
            error,
            context = TAG,
        )
        return null
    }

    private suspend fun privatePaymentResult(
        publicKey: String,
        resolution: PaykitPrivateContactPaymentResolution,
        consumedVersion: ULong?,
        paymentRequest: PaykitPaymentRequest?,
    ): PublicPaykitPaymentResult {
        val privateEndpoints = resolution.payableEndpoints
            .mapNotNull { PublicPaykitRepo.parseEndpoint(it.identifier, it.payload)?.copy(appId = it.appId) }
        if (resolution.privatePaymentListVersion != null) {
            cacheResolvedPrivateEndpoints(publicKey, privateEndpoints)
        }
        val acceptedEndpointIdentifiers = paymentRequest?.acceptedPaymentEndpointIdentifiers?.toSet()
        val acceptedEndpoints = privateEndpoints.filter {
            acceptedEndpointIdentifiers?.contains(it.methodId.rawValue) ?: true
        }

        val privatePayable = privatePayableEndpoints(
            acceptedEndpoints,
            publicKey,
            allowUsedOnchainAddress = paymentRequest?.billingPeriod != null &&
                resolution.privatePaymentListVersion == null,
        )
        val paymentListVersion = resolution.privatePaymentListVersion
        if (privatePayable.isNotEmpty() && (paymentListVersion != null || paymentRequest != null)) {
            Logger.info(
                "Opened private Paykit payment for '${redacted(publicKey)}' using payment list version " +
                    "${paymentListVersion ?: "none"} after ${consumedVersion ?: "none"}",
                context = TAG,
            )
            return PublicPaykitPaymentResult.Opened(
                paymentRequest = PublicPaykitRepo.paymentRequest(privatePayable),
                privatePaymentContext = PrivatePaykitPaymentContext(
                    paymentAppsByEndpoint = privatePayable.distinctBy { it.methodId }
                        .associate { it.methodId.rawValue to requireNotNull(it.appId) },
                    paymentListVersion = paymentListVersion,
                ),
            )
        }

        if (resolution.status == PrivatePaymentResolutionStatus.WAITING_FOR_UPDATED_PAYMENT_LIST) {
            schedulePendingPrivateMessageDrainRetries(
                reason = "payment recovery",
                retryKeys = listOf(publicKey),
            )
            Logger.info(
                "Waiting for a private Paykit payment list newer than ${consumedVersion ?: "none"} " +
                    "for '${redacted(publicKey)}'; public resolution is disabled for this request",
                context = TAG,
            )
            return PublicPaykitPaymentResult.WaitingForUpdatedPaymentList
        }

        return if (acceptedEndpoints.isEmpty()) {
            PublicPaykitPaymentResult.NoEndpoint
        } else {
            PublicPaykitPaymentResult.NotOpened
        }
    }

    private fun unresolvedPrivateLinkResult(
        publicKey: String,
        paymentRequest: PaykitPaymentRequest?,
        resolution: PaykitPrivateContactPaymentResolution,
        linkState: LinkedPeerState?,
    ): PublicPaykitPaymentResult? = when {
        resolution.state == PrivatePaymentResolutionState.RECOVERY_PENDING ->
            privateLinkPendingResult(publicKey)
        paymentRequest == null -> null
        linkState == LinkedPeerState.LINKING || linkState == LinkedPeerState.RECOVERY_REQUIRED ->
            privateLinkPendingResult(publicKey)
        linkState != LinkedPeerState.LINKED -> PublicPaykitPaymentResult.NoEndpoint
        else -> null
    }

    private fun privateLinkPendingResult(
        publicKey: String,
    ): PublicPaykitPaymentResult {
        schedulePendingPrivateMessageDrainRetries(
            reason = "payment link recovery",
            retryKeys = listOf(publicKey),
        )
        Logger.info(
            "Waiting for private Paykit link recovery for '${redacted(publicKey)}'",
            context = TAG,
        )
        return PublicPaykitPaymentResult.PrivateLinkPending
    }

    private suspend fun currentLinkState(
        publicKey: String,
        preparedState: LinkedPeerState? = null,
    ): LinkedPeerState? = preparedState ?: paykitSdkService.linkedPeers().firstOrNull {
        PubkyPublicKeyFormat.matches(it.counterparty, publicKey)
    }?.state

    private fun canUsePublicPayment(
        linkState: LinkedPeerState?,
        resolutionStatus: PrivatePaymentResolutionStatus? = null,
        resolutionState: PrivatePaymentResolutionState? = null,
    ): Boolean {
        if (
            resolutionStatus == PrivatePaymentResolutionStatus.WAITING_FOR_UPDATED_PAYMENT_LIST ||
            resolutionState != null && resolutionState != PrivatePaymentResolutionState.NO_PRIVATE_ENDPOINT
        ) {
            return false
        }

        return when (linkState) {
            null, LinkedPeerState.NOT_LINKED, LinkedPeerState.LINKING -> true
            LinkedPeerState.LINKED,
            LinkedPeerState.RECOVERY_REQUIRED,
            LinkedPeerState.BLOCKED,
            LinkedPeerState.UNKNOWN,
            -> false
        }
    }

    private suspend fun publishLocalEndpoints(
        publicKeys: Collection<String>,
        reason: String,
        forceRefreshLightning: Boolean = false,
        requireImmediatePublication: Boolean = false,
    ): Result<Unit> = withContext(serializedDispatcher) {
        runSuspendCatching {
            val keys = publicKeys.mapNotNull { normalizedPublicKey(it) }.distinct()
            if (keys.isEmpty()) return@runSuspendCatching

            publicationMutex.withLock {
                if (!canPublishPrivateEndpoints()) {
                    if (requireImmediatePublication) throw PrivatePaykitError.PrivateUnavailable
                    return@withLock
                }

                pubkyService.currentPublicKey() ?: throw PublicPaykitError.SessionNotActive
                val preparation = preparePrivatePaymentListReservations(
                    publicKeys = keys,
                    reason = reason,
                    forceRefreshLightning = forceRefreshLightning,
                )

                if (preparation.updates.isEmpty()) {
                    drainAndSchedulePrivateLinkRetries(reason, preparation.linkRetryKeys)
                    if (requireImmediatePublication) preparation.firstError?.let { throw it }
                    return@withLock
                }

                val report = paykitSdkService.syncPrivatePaymentListsWithReservations(
                    updates = preparation.updates,
                    clearUnlistedLinkedPeers = false,
                )
                val deliveryError = applyPrivatePaymentListDeliveryReport(report, reason)
                val firstError = preparation.firstError ?: deliveryError
                val retryKeys = (preparation.linkRetryKeys + privatePaymentListDeliveryRetryKeys(report)).distinct()
                drainAndSchedulePrivateLinkRetries(reason, retryKeys)

                if (firstError != null) {
                    if (requireImmediatePublication) throw firstError
                    Logger.warn(
                        "Deferred private Paykit endpoint publish during '$reason'",
                        firstError,
                        context = TAG,
                    )
                }
            }
        }
    }

    private suspend fun preparePrivatePaymentListReservations(
        publicKeys: Collection<String>,
        reason: String,
        forceRefreshLightning: Boolean,
    ): PrivatePublicationPreparation {
        var firstError: Throwable? = null
        val updates = mutableListOf<PrivatePaymentListReservationUpdateInput>()
        val linkRetryKeys = mutableListOf<String>()
        for (publicKey in publicKeys.distinct()) {
            val link = runSuspendCatching { paykitSdkService.ensureLinkWithPeer(publicKey) }
                .onFailure { Logger.warn("Failed to prepare private Paykit link during '$reason'", it, context = TAG) }
            if (link.isFailure) {
                if (link.exceptionOrNull() !is PaykitException.NotFound) linkRetryKeys += publicKey
                continue
            }
            if (link.getOrThrow().state != LinkedPeerState.LINKED) linkRetryKeys += publicKey
            runSuspendCatching { privatePaymentListUpdate(publicKey, forceRefreshLightning) }
                .onSuccess { updates += it }
                .onFailure {
                    firstError = firstError ?: it
                    logPrivatePublicationPreparationFailure(publicKey, reason, it)
                }
        }
        return PrivatePublicationPreparation(updates, linkRetryKeys, firstError)
    }

    private suspend fun prepareRelevantPrivateLinksIfAvailable(publicKeys: Collection<String>, reason: String) {
        if (!hasPrivatePaymentAccessForCurrentProfile()) return
        drainAndSchedulePrivateLinkRetries(reason, publicKeys.distinct())
    }

    private suspend fun drainAndSchedulePrivateLinkRetries(
        reason: String,
        retryKeys: Collection<String>,
    ) {
        val pendingKeys = pendingPrivateMessageDrainKeys(retryKeys, retryMissingPeers = true)
        if (pendingKeys.isEmpty()) return

        drainPendingPrivateMessages(reason, advancingLinksFor = pendingKeys.toList())
        val pendingRetryKeys = pendingPrivateMessageDrainKeys(pendingKeys)
        if (pendingRetryKeys.isNotEmpty()) {
            schedulePendingPrivateMessageDrainRetries(reason, retryKeys = pendingRetryKeys)
        }
    }

    private suspend fun privatePaymentListUpdate(
        publicKey: String,
        forceRefreshLightning: Boolean,
    ): PrivatePaymentListReservationUpdateInput {
        val endpoints = buildLocalEndpoints(publicKey, forceRefreshLightning).getOrThrow()
        if (endpoints.isEmpty()) throw PrivatePaykitError.PrivateUnavailable
        return PrivatePaymentListReservationUpdateInput(
            counterparty = publicKey,
            reservations = endpoints.map { endpoint -> privateReservation(publicKey, endpoint) },
        )
    }

    private fun logPrivatePublicationPreparationFailure(
        publicKey: String,
        reason: String,
        error: Throwable,
    ) {
        if (error is PrivatePaykitError.PrivateUnavailable) {
            Logger.warn(
                "Skipped private Paykit endpoint publish for '${redacted(publicKey)}' during '$reason'",
                context = TAG,
            )
        } else {
            Logger.warn(
                "Failed to prepare private Paykit endpoints for '${redacted(publicKey)}' during '$reason'",
                error,
                context = TAG,
            )
        }
    }

    private suspend fun applyPrivatePaymentListDeliveryReport(
        report: PrivatePaymentListDeliveryReport,
        reason: String,
    ): Throwable? {
        report.failedToQueue.forEach {
            Logger.warn(
                "Failed to queue private Paykit endpoints for '${redacted(it.counterparty)}' during '$reason': " +
                    (it.error?.redactedContext() ?: "unknown error"),
                context = TAG,
            )
        }
        report.failedToDeliver.forEach {
            Logger.warn(
                "Failed to deliver private Paykit endpoints for '${redacted(it.counterparty)}' during '$reason': " +
                    it.error.redactedContext(),
                context = TAG,
            )
        }

        var didUpdateCache = false
        for (change in report.queued) {
            val publicKey = normalizedPublicKey(change.counterparty) ?: continue
            recordPublishedPrivatePaymentListCache(publicKey)
            didUpdateCache = true
        }

        for (change in report.cleared) {
            didUpdateCache = clearPublishedPrivatePaymentListCache(
                counterparty = change.counterparty,
            ) || didUpdateCache
        }

        if (didUpdateCache) {
            persistState(markWalletBackup = true)
        }

        return PrivatePaykitError.PrivateUnavailable.takeIf {
            report.failedToQueue.isNotEmpty() || report.failedToDeliver.isNotEmpty()
        }
    }

    private fun privatePaymentListDeliveryRetryKeys(
        report: PrivatePaymentListDeliveryReport,
    ): List<String> {
        return (
            report.queued.map { it.counterparty } +
                report.cleared.map { it.counterparty } +
                report.failedToDeliver.map { it.counterparty }
            )
            .mapNotNull(::normalizedPublicKey)
            .distinct()
    }

    private suspend fun drainPendingPrivateMessages(
        reason: String,
        advancingLinksFor: List<String> = emptyList(),
    ) {
        runSuspendCatching {
            advancingLinksFor.distinct().forEach { retryKey ->
                runSuspendCatching {
                    paykitSdkService.ensureLinkWithPeer(
                        counterparty = retryKey,
                    )
                }.onFailure {
                    Logger.warn(
                        "Failed to advance private Paykit link for '${redacted(retryKey)}' during '$reason'",
                        it,
                        context = TAG,
                    )
                }
            }
            paykitSdkService.processPendingPrivateMessages()
            paykitSdkService.receivePrivateMessagesFromLinkedPeers()
            paykitSdkService.processPendingPrivateMessages()
            paykitSdkService.receivePrivateMessagesFromLinkedPeers()
        }.onFailure {
            Logger.warn("Failed to process pending private Paykit messages during '$reason'", it, context = TAG)
        }
    }

    private fun schedulePendingPrivateMessageDrainRetries(
        reason: String,
        retryKeys: Collection<String>,
    ) {
        val retryKeys = retryKeys.toSet()
        if (retryKeys.isEmpty()) return

        synchronized(pendingMessageDrainRetryLock) {
            pendingMessageDrainRetryKeys.addAll(retryKeys)
            pendingMessageDrainRetryGeneration += 1
            val retryGeneration = pendingMessageDrainRetryGeneration
            pendingMessageDrainRetryJob?.cancel()

            pendingMessageDrainRetryJob = retryScope.launch {
                var retryIndex = 0
                while (true) {
                    val retryDelay = privateMessageDrainRetryDelays[
                        retryIndex.coerceAtMost(privateMessageDrainRetryDelays.lastIndex),
                    ]
                    delay(retryDelay)
                    drainPendingPrivateMessageRetryKeys("$reason retry")
                    if (!hasPendingMessageDrainRetryKeys(retryGeneration)) break
                    retryIndex += 1
                }
                finishPendingMessageDrainRetries(retryGeneration)
            }
        }
    }

    private suspend fun drainPendingPrivateMessageRetryKeys(reason: String) = withContext(serializedDispatcher) {
        val retryKeys = synchronized(pendingMessageDrainRetryLock) {
            pendingMessageDrainRetryKeys.toList()
        }
        if (retryKeys.isEmpty()) return@withContext
        val pendingKeys = pendingPrivateMessageDrainKeys(retryKeys)
        if (pendingKeys.isNotEmpty()) {
            drainPendingPrivateMessages(reason, advancingLinksFor = pendingKeys.toList())
        }
        updatePendingMessageDrainRetryKeys(retryKeys)
    }

    private fun hasPendingMessageDrainRetryKeys(generation: Int): Boolean =
        synchronized(pendingMessageDrainRetryLock) {
            generation == pendingMessageDrainRetryGeneration && pendingMessageDrainRetryKeys.isNotEmpty()
        }

    private fun finishPendingMessageDrainRetries(generation: Int) {
        synchronized(pendingMessageDrainRetryLock) {
            if (generation != pendingMessageDrainRetryGeneration) return
            pendingMessageDrainRetryJob = null
            pendingMessageDrainRetryKeys.clear()
        }
    }

    private suspend fun updatePendingMessageDrainRetryKeys(retryKeys: Collection<String>) {
        val remainingKeys = pendingPrivateMessageDrainKeys(retryKeys)
        synchronized(pendingMessageDrainRetryLock) {
            pendingMessageDrainRetryKeys.removeAll(retryKeys.toSet())
            pendingMessageDrainRetryKeys.addAll(remainingKeys)
        }
    }

    private suspend fun pendingPrivateMessageDrainKeys(
        retryKeys: Collection<String>,
        retryMissingPeers: Boolean = false,
    ): Set<String> {
        val retryKeys = retryKeys.toSet()
        if (retryKeys.isEmpty()) return emptySet()

        val linkedPeers = runSuspendCatching { paykitSdkService.linkedPeers() }
            .getOrElse {
                Logger.warn("Failed to inspect private Paykit link state", it, context = TAG)
                return retryKeys
            }
            .mapNotNull { peer ->
                normalizedPublicKey(peer.counterparty)?.let { publicKey ->
                    publicKey to peer.state
                }
            }
            .toMap()
        val pendingOutbound = runSuspendCatching { paykitSdkService.pendingOutboundPrivateCounterparties() }
            .getOrElse {
                Logger.warn("Failed to inspect pending private Paykit messages", it, context = TAG)
                return retryKeys
            }
            .mapNotNull(::normalizedPublicKey)
            .toSet()

        return retryKeys.filterTo(mutableSetOf()) { retryKey ->
            when (linkedPeers[retryKey]) {
                LinkedPeerState.LINKED -> retryKey in pendingOutbound
                null -> retryMissingPeers || retryKey in pendingOutbound
                LinkedPeerState.BLOCKED, LinkedPeerState.UNKNOWN -> false
                else -> true
            }
        }
    }

    private fun clearPendingMessageDrainRetries() {
        synchronized(pendingMessageDrainRetryLock) {
            pendingMessageDrainRetryJob?.cancel()
            pendingMessageDrainRetryJob = null
            pendingMessageDrainRetryKeys.clear()
            pendingMessageDrainRetryGeneration += 1
        }
    }

    private suspend fun recordPublishedPrivatePaymentListCache(publicKey: String) {
        val contactState = ensureState().contacts.getOrPut(publicKey) { ContactState() }
        contactState.hasPublishedPrivatePaymentList = true
    }

    private suspend fun clearPublishedPrivatePaymentListCache(
        counterparty: String,
    ): Boolean {
        val publicKey = normalizedPublicKey(counterparty) ?: return false
        ensureState().contacts[publicKey]?.let { contactState ->
            contactState.hasPublishedPrivatePaymentList = false
            contactState.localInvoice = null
            if (!contactState.hasCacheState) {
                state?.contacts?.remove(publicKey)
            }
        }
        return true
    }

    private suspend fun buildLocalEndpoints(
        publicKey: String,
        forceRefreshLightning: Boolean = false,
    ): Result<List<Endpoint>> = withContext(serializedDispatcher) {
        runSuspendCatching {
            val settings = settingsStore.data.first()
            val endpoints = mutableListOf<Endpoint>()
            if (PublicPaykitRepo.isOnchainPaymentOptionEnabled(settings)) {
                val reservedAddress = addressReservationRepo.currentOrRotatedAddress(
                    publicKey,
                ).getOrThrow()
                walletRepo.refreshReusableReceiveAddressIfReserved().getOrThrow()
                endpoints += Endpoint(
                    methodId = PublicPaykitRepo.onchainMethodId(reservedAddress),
                    value = reservedAddress,
                    rawPayload = PublicPaykitRepo.serializePayload(reservedAddress),
                )
            }

            if (PublicPaykitRepo.isLightningPaymentOptionEnabled(settings) && lightningRepo.canReceive()) {
                currentOrRotatedInvoice(
                    publicKey,
                    forceRefresh = forceRefreshLightning,
                ).onSuccess { invoice ->
                    endpoints += Endpoint(
                        methodId = MethodId.Bolt11,
                        value = invoice.bolt11,
                        rawPayload = PublicPaykitRepo.serializePayload(invoice.bolt11),
                    )
                }.onFailure {
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
    ): Result<StoredInvoice> = withContext(serializedDispatcher) {
        runSuspendCatching {
            if (!forceRefresh) reusablePrivateInvoice(publicKey)?.let { return@runSuspendCatching it }

            val bolt11 = lightningRepo.createInvoice(
                amountSats = null,
                description = "",
                expirySeconds = privateInvoiceExpiry.inWholeSeconds.toUInt(),
            ).getOrThrow()
            if (!forceRefresh) reusablePrivateInvoice(publicKey)?.let { return@runSuspendCatching it }

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
            setLocalInvoice(publicKey, invoice)
            persistState()
            invoice
        }
    }

    private suspend fun reusablePrivateInvoice(
        publicKey: String,
    ): StoredInvoice? {
        val invoice = localInvoice(publicKey) ?: return null
        val refreshAt = clock.now().epochSeconds + invoiceRefreshBuffer.inWholeSeconds
        val decoded = (coreService.decode(invoice.bolt11) as? Scanner.Lightning)?.invoice ?: return null
        val isReusable = invoice.expiresAt > refreshAt &&
            !isReceivedInvoiceSettled(invoice.paymentHash) &&
            !decoded.isExpired &&
            decoded.amountSatoshis == 0uL &&
            PublicPaykitRepo.hasLightningRouteHints(invoice.bolt11)
        return invoice.takeIf { isReusable }
    }

    private fun privateReservation(
        publicKey: String,
        endpoint: Endpoint,
    ): PrivatePaymentEndpointReservationInput {
        val contactState = state?.contacts?.get(publicKey)
        val attribution = if (endpoint.methodId == MethodId.Bolt11) {
            val paymentHash = localInvoice(publicKey)?.takeIf { it.bolt11 == endpoint.value }?.paymentHash
            mapOf(
                "type" to "private_paykit",
                "counterparty" to publicKey,
            ) + listOfNotNull(paymentHash?.let { "payment_hash" to it }).toMap()
        } else {
            mapOf(
                "type" to "private_paykit",
                "counterparty" to publicKey,
            )
        }
        val expiresAt = contactState
            ?.let { localInvoice(publicKey) }
            ?.takeIf { endpoint.methodId == MethodId.Bolt11 && it.bolt11 == endpoint.value }
            ?.let { Instant.ofEpochSecond(it.expiresAt).toString() }

        return PrivatePaymentEndpointReservationInput(
            reservationId = privateReservationId(publicKey, endpoint),
            identifier = endpoint.methodId.rawValue,
            payload = endpoint.rawPayload,
            expiresAt = expiresAt,
            attribution = attribution,
        )
    }

    private fun privateReservationId(publicKey: String, endpoint: Endpoint): String {
        val payloadHashPrefix = MessageDigest.getInstance("SHA-256")
            .digest(endpoint.rawPayload.toByteArray(Charsets.UTF_8))
            .copyOfRange(0, 8)
            .toHex()
        return "$publicKey:${endpoint.methodId.rawValue}:$payloadHashPrefix"
    }

    private suspend fun cacheResolvedPrivateEndpoints(publicKey: String, endpoints: List<Endpoint>) {
        val contactState = ensureState().contacts.getOrPut(publicKey) { ContactState() }
        contactState.remoteEndpoints = endpoints.map { StoredPaymentEntry(it.methodId.rawValue, it.rawPayload) }
        persistState(markWalletBackup = true)
    }

    private suspend fun removePublishedEndpoints(): Result<Unit> = withContext(serializedDispatcher) {
        runSuspendCatching {
            publicationMutex.withLock {
                removePublishedEndpointsLocked().getOrThrow()
            }
        }
    }

    private suspend fun removePublishedEndpoints(publicKey: String): Result<Unit> =
        removePublishedEndpoints(listOf(publicKey))

    private suspend fun removePublishedEndpoints(publicKeys: Collection<String>): Result<Unit> =
        withContext(serializedDispatcher) {
            publicationMutex.withLock {
                removePublishedEndpointsLocked(publicKeys)
            }
        }

    private suspend fun removePublishedEndpointsLocked(publicKeys: Collection<String>? = null): Result<Unit> =
        runSuspendCatching {
            val linkedPublicKeys = paykitSdkService.linkedPeers()
                .filter { it.state != LinkedPeerState.NOT_LINKED }
                .mapNotNull { normalizedPublicKey(it.counterparty) }
                .toSet()
            val keys = publicKeys ?: (
                knownSavedContactKeys + ensureState().contacts.keys + pendingDeletedContactCleanupPublicKeys() +
                    linkedPublicKeys
                )
            val normalizedBatch = normalizedPublicKeyBatch(keys)
            discardInvalidCleanupKeys(normalizedBatch.invalidKeys)
            val normalizedKeys = normalizedBatch.normalizedKeys
            if (normalizedKeys.isEmpty()) return@runSuspendCatching

            ensureState()
            val cleanupStateByPublicKey = normalizedKeys.associateWith(::publishedEndpointCleanupState)
            val preparation = clearPrivatePaymentLists(normalizedKeys, linkedPublicKeys)
            val failedPublicKeys = preparation.failedPublicKeys.toMutableSet()
            var firstError = preparation.firstError

            if (preparation.clearedRetryKeys.isNotEmpty()) {
                drainPendingPrivateMessages(
                    reason = "private endpoint cleanup",
                    advancingLinksFor = preparation.clearedRetryKeys,
                )
                val pendingRetryKeys = pendingPrivateMessageDrainKeys(preparation.clearedRetryKeys)
                if (pendingRetryKeys.isNotEmpty()) {
                    failedPublicKeys += pendingRetryKeys
                    firstError = firstError ?: PrivatePaykitError.PrivateUnavailable
                }
            }

            normalizedKeys.filterNot { it in failedPublicKeys }.forEach { publicKey ->
                if (publishedEndpointCleanupState(publicKey) != cleanupStateByPublicKey[publicKey]) {
                    failedPublicKeys += publicKey
                    firstError = firstError ?: PrivatePaykitError.PrivateUnavailable
                    Logger.warn(
                        "Deferred private Paykit cache cleanup for '${redacted(publicKey)}' because its state changed",
                        context = TAG,
                    )
                }
            }

            clearPublishedEndpointCache(normalizedKeys.filterNot { it in failedPublicKeys })
            firstError?.let { throw it }
            publicPaykitRepo.syncPaykitApp().getOrThrow()
        }.onFailure {
            runSuspendCatching { settingsStore.update { it.copy(publicPaykitCleanupPending = true) } }
                .onFailure(it::addSuppressed)
        }

    private suspend fun clearPrivatePaymentLists(
        publicKeys: Collection<String>,
        linkedPublicKeys: Set<String>,
    ): PrivateEndpointCleanupPreparation {
        val failedPublicKeys = mutableSetOf<String>()
        val clearedRetryKeys = mutableListOf<String>()
        var firstError: Throwable? = null
        publicKeys.forEach { publicKey ->
            if (publicKey !in linkedPublicKeys &&
                state?.contacts?.get(publicKey)?.hasPublishedPrivatePaymentList != true
            ) {
                return@forEach
            }
            runSuspendCatching {
                val report = paykitSdkService.clearPrivatePaymentList(publicKey)
                    ?: return@runSuspendCatching false
                if (report.failedToQueue.isNotEmpty() || report.failedToDeliver.isNotEmpty()) {
                    throw PrivatePaykitError.PrivateUnavailable
                }
                true
            }.onSuccess {
                if (it) clearedRetryKeys += publicKey
            }.onFailure {
                failedPublicKeys += publicKey
                firstError = firstError ?: it
            }
        }
        return PrivateEndpointCleanupPreparation(clearedRetryKeys, failedPublicKeys, firstError)
    }

    private suspend fun clearPublishedEndpointCache(publicKeys: Collection<String>) {
        if (publicKeys.isEmpty()) return

        publicKeys.forEach { publicKey ->
            state?.contacts?.get(publicKey)?.let { contactState ->
                contactState.remoteEndpoints = emptyList()
                contactState.localInvoice = null
                contactState.hasPublishedPrivatePaymentList = false
                if (!contactState.hasCacheState) {
                    state?.contacts?.remove(publicKey)
                }
            }
        }

        persistState(markWalletBackup = true)
        updateDeletedContactCleanupPending(publicKeys, isPending = false)
    }

    private suspend fun discardInvalidCleanupKeys(publicKeys: Collection<String>) {
        if (publicKeys.isEmpty()) return

        val contactState = ensureState().contacts
        var didRemoveContactState = false
        publicKeys.forEach { publicKey ->
            Logger.warn("Dropped invalid private Paykit cleanup key '${redacted(publicKey)}'", context = TAG)
            didRemoveContactState = contactState.remove(publicKey) != null || didRemoveContactState
        }
        if (didRemoveContactState) {
            persistState(markWalletBackup = true)
        }
        updateDeletedContactCleanupPending(publicKeys, isPending = false)
    }

    private fun publishedEndpointCleanupState(publicKey: String): PublishedEndpointCleanupState {
        val contactState = state?.contacts?.get(publicKey)
        return PublishedEndpointCleanupState(
            remoteEndpoints = contactState?.remoteEndpoints.orEmpty(),
            localInvoice = contactState?.localInvoice,
            hasPublishedPrivatePaymentList = contactState?.hasPublishedPrivatePaymentList == true,
        )
    }

    private suspend fun refreshSavedContactEndpointsDuringInitialLinkBurst(
        publicKeys: Collection<String>,
        reason: String,
    ) = withContext(serializedDispatcher) {
        if (!canPublishPrivateEndpoints()) {
            prepareRelevantPrivateLinksIfAvailable(publicKeys, "$reason initial link burst")
            return@withContext
        }
        publishLocalEndpoints(publicKeys.toList(), reason = "$reason initial link burst")
            .onFailure { Logger.warn("Failed initial private Paykit sync for '$reason'", it, context = TAG) }
    }

    private fun clearInitialLinkBurst() {
        synchronized(initialLinkBurstLock) {
            initialLinkBurstJob?.cancel()
            initialLinkBurstJob = null
            initialLinkBurstPublicKeys.clear()
            initialLinkBurstGeneration += 1
        }
    }

    private fun normalizedPublicKeyBatch(publicKeys: Collection<String>): NormalizedPublicKeyBatch {
        val invalidKeys = mutableSetOf<String>()
        val normalizedKeys = publicKeys.mapNotNull { publicKey ->
            normalizedPublicKey(publicKey) ?: run {
                invalidKeys += publicKey
                null
            }
        }.distinct()
        return NormalizedPublicKeyBatch(normalizedKeys, invalidKeys)
    }

    private suspend fun clearUnsavedContactState(savedPublicKeys: Collection<String>): Result<Unit> =
        withContext(serializedDispatcher) {
            runSuspendCatching {
                val savedKeys = savedPublicKeys.mapNotNull { normalizedPublicKey(it) }.toSet()
                ensureState().contacts.keys.filter { it !in savedKeys }.forEach {
                    clearContactState(it)
                }
                addressReservationRepo.clearContactAssignments(excludingPublicKeys = savedKeys)
                persistState(markWalletBackup = true)
            }
        }

    private suspend fun clearContactState(publicKey: String) {
        clearContactStates(listOf(publicKey))
    }

    private suspend fun clearContactStates(publicKeys: Collection<String>) {
        if (publicKeys.isEmpty()) return

        val contacts = ensureState().contacts
        publicKeys.forEach(contacts::remove)
        persistState(markWalletBackup = true)
    }

    private suspend fun privatePayableEndpoints(
        endpoints: List<Endpoint>,
        publicKey: String,
        allowUsedOnchainAddress: Boolean,
    ): List<Endpoint> {
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
                endpoint.methodId.isOnchain && !allowUsedOnchainAddress -> {
                    val isUsed = runSuspendCatching { coreService.isAddressUsed(endpoint.value) }
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
                if (it is CancellationException) throw it
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

    private suspend fun canPublishPrivateEndpoints(): Boolean {
        val settings = settingsStore.data.first()
        return settings.sharesPrivatePaykitEndpoints &&
            hasPrivatePaymentAccessForCurrentProfile() &&
            App.currentActivity?.value != null &&
            walletRepo.walletExists() &&
            lightningRepo.lightningState.value.nodeLifecycleState.isRunning()
    }

    private suspend fun hasPrivatePaymentAccessForCurrentProfile(): Boolean = runSuspendCatching {
        pubkyService.currentPublicKey() ?: return@runSuspendCatching false
        paykitSdkService.hasPrivatePaymentAccess()
    }.getOrDefault(false)

    private suspend fun isContactSharingCleanupPending(): Boolean =
        cacheStore.data.first().cleanupPending

    private suspend fun hasPublishedPrivateEndpoints(): Boolean =
        ensureState().contacts.values.any { it.hasPublishedPrivatePaymentList }

    private suspend fun updateContactSharingCleanupPending(isPending: Boolean) {
        cacheStore.update { it.copy(cleanupPending = isPending) }
    }

    private suspend fun pendingDeletedContactCleanupPublicKeys(): Set<String> =
        cacheStore.data.first().deletedContactCleanupPendingPublicKeys

    private suspend fun updateDeletedContactCleanupPending(publicKey: String, isPending: Boolean) =
        updateDeletedContactCleanupPending(listOf(publicKey), isPending)

    private suspend fun updateDeletedContactCleanupPending(publicKeys: Collection<String>, isPending: Boolean) {
        if (publicKeys.isEmpty()) return

        cacheStore.update {
            val pendingKeys = if (isPending) {
                it.deletedContactCleanupPendingPublicKeys + publicKeys
            } else {
                it.deletedContactCleanupPendingPublicKeys - publicKeys.toSet()
            }
            it.copy(deletedContactCleanupPendingPublicKeys = pendingKeys)
        }
    }

    private suspend fun retryPendingDeletedContactEndpointRemoval(
        savedPublicKeys: Collection<String>,
    ): Result<Unit> = withContext(serializedDispatcher) {
        runSuspendCatching {
            val savedKeys = savedPublicKeys.mapNotNull { normalizedPublicKey(it) }.toSet()
            val pendingKeys = pendingDeletedContactCleanupPublicKeys()
            updateDeletedContactCleanupPending(pendingKeys.intersect(savedKeys), isPending = false)
            val cleanupKeys = pendingKeys - savedKeys
            if (cleanupKeys.isEmpty()) return@runSuspendCatching

            val removalResult = removePublishedEndpoints(cleanupKeys)
            val remainingPendingKeys = pendingDeletedContactCleanupPublicKeys()
            val successfulKeys = cleanupKeys
                .mapNotNull(::normalizedPublicKey)
                .filterNot { it in remainingPendingKeys }
            clearContactStates(successfulKeys)
            successfulKeys.forEach { publicKey ->
                addressReservationRepo.clearContactAssignment(publicKey)
            }
            removalResult.getOrThrow()
        }
    }

    private suspend fun settledPrivateInvoicePaymentHashes(): List<String> {
        val settled = receivedSettledPaymentHashes()
        return ensureState().contacts.values
            .mapNotNull { it.localInvoice }
            .map { it.paymentHash }
            .filter(settled::contains)
    }

    private suspend fun paymentHashForBolt11(bolt11: String): String? =
        runSuspendCatching {
            (coreService.decode(bolt11) as? Scanner.Lightning)?.invoice?.paymentHash?.toHex()
        }.getOrNull()

    private suspend fun attemptedOutboundBolt11PaymentHashes(): Set<String> =
        lightningRepo.getPayments().getOrElse {
            if (it is CancellationException) throw it
            emptyList()
        }
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
        lightningRepo.getPayments().getOrElse {
            if (it is CancellationException) throw it
            emptyList()
        }
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

    private fun localInvoice(publicKey: String): StoredInvoice? {
        val contactState = state?.contacts?.get(publicKey) ?: return null
        return contactState.localInvoice
    }

    private suspend fun setLocalInvoice(publicKey: String, invoice: StoredInvoice) {
        val contactState = ensureState().contacts.getOrPut(publicKey) { ContactState() }
        contactState.localInvoice = invoice
    }

    private fun rememberSavedContacts(publicKeys: Collection<String>, replacing: Boolean): List<String> {
        val normalizedKeys = publicKeys.mapNotNull { normalizedPublicKey(it) }.distinct()
        if (replacing) {
            knownSavedContactKeys.clear()
        }
        knownSavedContactKeys.addAll(normalizedKeys)
        return normalizedKeys
    }

    private fun knownSavedContact(publicKey: String): String? {
        val normalizedKey = normalizedPublicKey(publicKey) ?: return null
        return normalizedKey.takeIf { it in knownSavedContactKeys }
    }

    private fun normalizedPublicKey(publicKey: String): String? = PubkyPublicKeyFormat.normalized(publicKey)

    private fun redacted(publicKey: String): String = PubkyPublicKeyFormat.redacted(publicKey)

    private suspend fun ensureState(): PrivatePaykitState {
        state?.let { return it }
        return PrivatePaykitState(cacheStore.data.first()).also { state = it }
    }

    private suspend fun persistState(
        markWalletBackup: Boolean = false,
        preserveCleanupMarkers: Boolean = true,
    ) {
        val currentState = state ?: PrivatePaykitState()
        cacheStore.update {
            currentState.cacheState(
                cleanupPending = if (preserveCleanupMarkers) it.cleanupPending else false,
                deletedContactCleanupPendingPublicKeys = if (preserveCleanupMarkers) {
                    it.deletedContactCleanupPendingPublicKeys
                } else {
                    emptySet()
                },
            )
        }
        if (markWalletBackup) notifyBackupStateChanged()
    }

    private fun notifyBackupStateChanged() {
        _backupStateVersion.update { it + 1 }
    }
}

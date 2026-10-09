package to.bitkit.repositories

import com.synonym.bitkitcore.Scanner
import com.synonym.paykit.IdentityStatus
import com.synonym.paykit.LinkedPeerState
import com.synonym.paykit.PaykitException
import com.synonym.paykit.PaymentAmountContext
import com.synonym.paykit.PrivatePaymentEndpointReservationInput
import com.synonym.paykit.PrivatePaymentListDeliveryReport
import com.synonym.paykit.PrivatePaymentListReservationUpdateInput
import com.synonym.paykit.PrivatePaymentResolutionState
import com.synonym.paykit.PrivatePaymentResolutionStatus
import com.synonym.paykit.PubkyIdentityCapability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import to.bitkit.services.PaykitSdkOperationLock.Priority
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
import kotlin.time.Instant as KotlinInstant

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
    private val usdtRepo: UsdtRepo,
    private val coreService: CoreService,
    private val clock: Clock,
) {
    companion object {
        private const val TAG = "PrivatePaykitRepo"
        private const val MAX_RECEIVED_INVOICE_HASHES_PER_CONTACT = 100
        private val privateInvoiceExpiry = 24.hours
        private val invoiceRefreshBuffer = 30.minutes
        private val unavailableLinkRetryDelay = 5.minutes

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
        private val paymentListRetryDelays = List(14) { 2.seconds }
        private val privatePaymentResolutionRetryDelays = listOf(1.seconds, 3.seconds, 8.seconds)

        fun isDuplicatePaymentError(error: Throwable): Boolean =
            PrivatePaykitErrorClassifier.isDuplicatePaymentError(error)
    }

    private val publicationMutex = Mutex()
    private val serializedDispatcher = ioDispatcher.limitedParallelism(1)
    private val retryScope = appScope(serializedDispatcher, TAG)
    private val isContactPreparationActive = MutableStateFlow(false)
    private val paymentPublishJobs = ConcurrentHashMap<String, Job>()
    private val knownSavedContactKeys = mutableSetOf<String>()
    private val pendingPreparationKeys = mutableSetOf<String>()
    private var activePreparationKeys = emptySet<String>()
    private val activeLinkPreparationKeys = mutableSetOf<String>()
    private var preparationJob: Job? = null
    private var preparationGeneration = 0
    private var isDeletingProfile = false
    private var pendingForceRefreshLightning = false
    private val unavailableLinkRetryAt = mutableMapOf<String, KotlinInstant>()
    private var state: PrivatePaykitState? = null
    private val pendingMessageDrainRetryLock = Any()
    private val pendingMessageDrainRetryKeys = mutableSetOf<String>()
    private var pendingMessageDrainRetryJob: Job? = null
    private var pendingMessageDrainRetryGeneration = 0

    private data class PrivateLinkPreparation(
        val publicKeys: List<String>,
        val linkRetryKeys: List<String>,
    )

    private data class PrivatePublicationPreparation(
        val updates: List<PrivatePaymentListReservationUpdateInput>,
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

    suspend fun hasPrivatePaymentAccess(): Boolean = paykitSdkService.hasPrivatePaymentAccess()

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
            scheduleSavedContactPreparation(publicKeys).onFailure {
                if (wasCleanupPending) {
                    runSuspendCatching { updateContactSharingCleanupPending(true) }.onFailure(it::addSuppressed)
                }
            }.getOrThrow()
        }
    }

    suspend fun scheduleSavedContactPreparation(publicKeys: Collection<String>): Result<Unit> =
        withContext(serializedDispatcher) {
            runSuspendCatching {
                val keys = rememberSavedContacts(publicKeys, replacing = true)
                scheduleContactPreparation(keys)
            }
        }

    suspend fun awaitContactPreparation(): Unit = withContext(serializedDispatcher) {
        preparationJob?.join()
    }

    fun setContactPreparationActive(active: Boolean) {
        isContactPreparationActive.update { active }
    }

    private suspend fun awaitContactPreparationActive(priority: Priority) {
        if (priority == Priority.Background) isContactPreparationActive.first { it }
    }

    private fun scheduleContactPreparation(publicKeys: Collection<String>, forceRefreshLightning: Boolean = false) {
        if (isDeletingProfile) return
        pendingPreparationKeys.addAll(publicKeys.filter { forceRefreshLightning || it !in activePreparationKeys })
        pendingForceRefreshLightning = pendingForceRefreshLightning || forceRefreshLightning
        if (preparationJob?.isActive == true || pendingPreparationKeys.isEmpty()) return
        preparationJob = retryScope.launch {
            try {
                while (pendingPreparationKeys.isNotEmpty()) {
                    awaitContactPreparationActive(Priority.Background)
                    if (pendingPreparationKeys.isEmpty()) break
                    val keys = pendingPreparationKeys.intersect(knownSavedContactKeys)
                    val forceRefresh = pendingForceRefreshLightning
                    pendingPreparationKeys.clear()
                    pendingForceRefreshLightning = false
                    activePreparationKeys = keys
                    val generation = preparationGeneration
                    runSuspendCatching {
                        if (isContactSharingCleanupPending()) return@runSuspendCatching
                        if (canPublishPrivateEndpoints()) {
                            addressReservationRepo.reconcileReservedIndexesWithLdk().getOrThrow()
                            if (generation != preparationGeneration) return@runSuspendCatching
                            publishLocalEndpoints(
                                keys,
                                "contact preparation",
                                forceRefresh,
                                priority = Priority.Background,
                            ).getOrThrow()
                        } else {
                            prepareRelevantPrivateLinksIfAvailable(keys, "contact preparation", Priority.Background)
                        }
                    }.onFailure {
                        Logger.warn("Failed to prepare private Paykit contacts", it, context = TAG)
                    }
                    activePreparationKeys = emptySet()
                }
            } finally {
                activePreparationKeys = emptySet()
                preparationJob = null
            }
        }
    }

    private fun invalidateContactPreparation() {
        preparationGeneration += 1
        pendingPreparationKeys.clear()
        activePreparationKeys = emptySet()
        pendingForceRefreshLightning = false
        activeLinkPreparationKeys.clear()
        clearPendingMessageDrainRetries()
    }

    suspend fun beginProfileDeletion() = withContext(serializedDispatcher) {
        isDeletingProfile = true
        invalidateContactPreparation()
    }

    suspend fun endProfileDeletion() = withContext(serializedDispatcher) {
        isDeletingProfile = false
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
            scheduleContactPreparation(knownSavedContactKeys.toList(), forceRefreshLightning)
        }.onFailure {
            Logger.warn("Failed to refresh private Paykit endpoints for '$reason'", it, context = TAG)
        }
    }

    suspend fun retryPendingEndpointRemoval(
        savedPublicKeys: Collection<String>,
    ): Result<Unit> = withContext(serializedDispatcher) {
        runSuspendCatching {
            if (isDeletingProfile) return@runSuspendCatching
            val settings = settingsStore.data.first()
            val hasDisabledPublications = !settings.sharesPrivatePaykitEndpoints && hasPublishedPrivateEndpoints()
            val cleanupPending = isContactSharingCleanupPending()
            if (cleanupPending || hasDisabledPublications) {
                if (!cleanupPending) updateContactSharingCleanupPending(true)
                removePublishedEndpoints().getOrThrow()
                clearUnsavedContactState(savedPublicKeys).getOrThrow()
                syncPaykitAppAfterCleanup().getOrThrow()
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
        runSuspendCatching {
            val savedKeys = rememberSavedContacts(savedPublicKeys, replacing = true).toSet()
            val staleKeys = ensureState().contacts.keys.filter { it !in savedKeys }
            removeSavedContacts(staleKeys).getOrThrow()
            addressReservationRepo.clearContactAssignments(excludingPublicKeys = savedKeys)
        }
    }

    suspend fun removeSavedContact(publicKey: String): Result<Unit> = removeSavedContacts(listOf(publicKey))

    suspend fun removeSavedContacts(publicKeys: Collection<String>): Result<Unit> = withContext(serializedDispatcher) {
        runSuspendCatching {
            val keys = publicKeys.mapNotNull(::normalizedPublicKey).toSet()
            if (keys.isEmpty()) return@runSuspendCatching
            knownSavedContactKeys.removeAll(keys)
            pendingPreparationKeys.removeAll(keys)
            keys.forEach(unavailableLinkRetryAt::remove)
            if (!isDeletingProfile) {
                removePublishedEndpoints(keys).onFailure {
                    updateDeletedContactCleanupPending(keys, true)
                    Logger.warn("Failed to remove private Paykit endpoints for deleted contacts", it, context = TAG)
                }.getOrThrow()
            }
            clearContactStates(keys)
            addressReservationRepo.removeContactAssignments(keys)
            if (!isDeletingProfile) updateDeletedContactCleanupPending(keys, false)
        }
    }

    suspend fun disableSharingAndPruneUnsavedContactState(savedPublicKeys: Collection<String>): Result<Unit> =
        withContext(serializedDispatcher) {
            runSuspendCatching {
                updateContactSharingCleanupPending(true)
                removePublishedEndpoints().getOrThrow()
                clearUnsavedContactState(savedPublicKeys).getOrThrow()
                syncPaykitAppAfterCleanup().getOrThrow()
                updateContactSharingCleanupPending(false)
            }
        }

    suspend fun setContactSharingCleanupPending(isPending: Boolean): Result<Unit> =
        withContext(serializedDispatcher) {
            runSuspendCatching {
                updateContactSharingCleanupPending(isPending)
            }
        }

    suspend fun removePublishedEndpointsForCleanup(context: String): Result<Unit> = withContext(serializedDispatcher) {
        runSuspendCatching {
            updateContactSharingCleanupPending(true)
            removePublishedEndpoints().getOrThrow()
            syncPaykitAppAfterCleanup().getOrThrow()
            updateContactSharingCleanupPending(false)
        }.onFailure {
            updateContactSharingCleanupPending(true)
            Logger.warn("Failed to remove private Paykit endpoints during '$context'", it, context = TAG)
        }
    }

    suspend fun closeAndClear(): Result<Unit> = withContext(serializedDispatcher) {
        runSuspendCatching {
            invalidateContactPreparation()
            publicationMutex.withLock {
                clearPendingMessageDrainRetries()
                knownSavedContactKeys.clear()
                unavailableLinkRetryAt.clear()
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
        for (retryDelay in paymentListRetryDelays) {
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
                val publicKeys = if (receivedAddresses.isEmpty()) {
                    addressReservationRepo.contactsWithUsedReservedAddresses()
                } else {
                    receivedAddresses.mapNotNull {
                        addressReservationRepo.currentContactPublicKeyForReservedAddress(it)
                    }
                }.filter { it in knownSavedContactKeys }.distinct()
                if (publicKeys.isEmpty()) return@runSuspendCatching
                if (!canPublishPrivateEndpoints()) return@runSuspendCatching

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
                        // This snapshot can be required before broadcast; it must not wait for payment to finish.
                        sdkState = paykitSdkService.exportBackupState(Priority.Interactive),
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
                invalidateContactPreparation()
                state = PrivatePaykitState()
                knownSavedContactKeys.clear()
                unavailableLinkRetryAt.clear()
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
        var result = beginContactPayment(publicKey, paymentRequest = null).getOrThrow()
        paymentPublishJobs.compute(publicKey) { _, job ->
            job?.takeIf { it.isActive } ?: retryScope.launch { refreshPrivateEndpointsBeforePayment(publicKey) }
        }
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
                endpoints = privatePayable,
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
        priority: Priority = Priority.Ordered,
    ): Result<Unit> = withContext(serializedDispatcher) {
        runSuspendCatching {
            val keys = publicKeys.mapNotNull { normalizedPublicKey(it) }.distinct()
            if (keys.isEmpty()) return@runSuspendCatching
            val generation = preparationGeneration
            val identity = pubkyService.currentPublicKey() ?: throw PublicPaykitError.SessionNotActive
            val preparation = preparePrivateLinks(
                publicKeys = keys,
                reason = reason,
                generation = generation,
                retryUnavailableLinks = requireImmediatePublication,
                priority = priority,
            )

            runSuspendCatching {
                awaitContactPreparationActive(priority)
                publicationMutex.withLock {
                    val status = paykitSdkService.identityStatus()
                    if (!isCurrentPublication(generation, identity, requireImmediatePublication, status)) {
                        return@withLock
                    }
                    if (!canPublishPrivateEndpoints(status)) {
                        if (requireImmediatePublication) throw PrivatePaykitError.PrivateUnavailable
                        return@withLock
                    }

                    val publication = preparePrivatePaymentListReservations(
                        preparation.publicKeys,
                        reason,
                        forceRefreshLightning,
                        generation,
                    )
                    if (!isCurrentPublication(generation, identity, requireImmediatePublication)) return@withLock
                    if (publication.updates.isEmpty()) {
                        schedulePendingPrivateMessageDrainRetries(reason, preparation.linkRetryKeys)
                        if (requireImmediatePublication) publication.firstError?.let { throw it }
                        return@withLock
                    }

                    val report = paykitSdkService.syncPrivatePaymentListsWithReservations(
                        updates = publication.updates,
                        clearUnlistedLinkedPeers = false,
                    )
                    val deliveryError = applyPrivatePaymentListDeliveryReport(report, reason)
                    val firstError = publication.firstError ?: deliveryError
                    val retryKeys = (preparation.linkRetryKeys + privatePaymentListDeliveryRetryKeys(report)).distinct()
                    schedulePendingPrivateMessageDrainRetries(reason, retryKeys)

                    if (firstError != null) {
                        if (requireImmediatePublication) throw firstError
                        Logger.warn(
                            "Deferred private Paykit endpoint publish during '$reason'",
                            firstError,
                            context = TAG,
                        )
                    }
                }
            }.onFailure {
                schedulePreparedLinkRetries(preparation.linkRetryKeys, reason, generation, identity)
            }.getOrThrow()
        }
    }

    private suspend fun schedulePreparedLinkRetries(
        keys: Collection<String>,
        reason: String,
        generation: Int,
        identity: String,
    ) {
        if (keys.isEmpty()) return
        val cleanupPending = isContactSharingCleanupPending()
        val currentIdentity = pubkyService.currentPublicKey()
        currentCoroutineContext().ensureActive()
        if (generation == preparationGeneration && currentIdentity == identity && !cleanupPending) {
            schedulePendingPrivateMessageDrainRetries(reason, keys.filter { it in knownSavedContactKeys })
        }
    }

    private suspend fun isCurrentPublication(
        generation: Int,
        identity: String,
        requireImmediatePublication: Boolean,
        status: IdentityStatus? = null,
    ): Boolean {
        val isCurrent = generation == preparationGeneration &&
            (status?.publicKey ?: pubkyService.currentPublicKey()) == identity
        if (!isCurrent && requireImmediatePublication) throw PrivatePaykitError.PrivateUnavailable
        return isCurrent
    }

    private suspend fun preparePrivatePaymentListReservations(
        publicKeys: Collection<String>,
        reason: String,
        forceRefreshLightning: Boolean,
        generation: Int,
    ): PrivatePublicationPreparation {
        var firstError: Throwable? = null
        val updates = mutableListOf<PrivatePaymentListReservationUpdateInput>()
        for (publicKey in publicKeys) {
            if (generation != preparationGeneration) break
            if (publicKey in knownSavedContactKeys) {
                runSuspendCatching { privatePaymentListUpdate(publicKey, forceRefreshLightning) }
                    .onSuccess { updates += it }
                    .onFailure {
                        firstError = firstError ?: it
                        logPrivatePublicationPreparationFailure(publicKey, reason, it)
                    }
            }
        }
        return PrivatePublicationPreparation(updates.filter { it.counterparty in knownSavedContactKeys }, firstError)
    }

    private suspend fun preparePrivateLinks(
        publicKeys: Collection<String>,
        reason: String,
        generation: Int,
        retryUnavailableLinks: Boolean,
        priority: Priority,
    ): PrivateLinkPreparation {
        val preparedKeys = mutableListOf<String>()
        val linkRetryKeys = mutableListOf<String>()
        awaitContactPreparationActive(priority)
        if (generation != preparationGeneration) return PrivateLinkPreparation(emptyList(), emptyList())
        val peerStates = paykitSdkService.linkedPeers(priority).associate { it.counterparty to it.state }
        for (publicKey in publicKeys.distinct()) {
            awaitContactPreparationActive(priority)
            if (generation != preparationGeneration) break
            if (canPreparePrivateLink(publicKey, peerStates[publicKey], retryUnavailableLinks)) {
                runSuspendCatching {
                    if (peerStates[publicKey] == LinkedPeerState.LINKED) {
                        LinkedPeerState.LINKED
                    } else {
                        advanceLinkIfIdle(publicKey, priority)
                    }
                }.onSuccess {
                    unavailableLinkRetryAt.remove(publicKey)
                    if (it != LinkedPeerState.LINKED) linkRetryKeys += publicKey
                    preparedKeys += publicKey
                }.onFailure {
                    Logger.warn("Failed to prepare private Paykit link during '$reason'", it, context = TAG)
                    awaitContactPreparationActive(priority)
                    if (generation != preparationGeneration) return@onFailure
                    val isUnavailable = isPrivateLinkUnavailable(publicKey, it, priority)
                    if (generation != preparationGeneration) return@onFailure
                    if (isUnavailable) {
                        unavailableLinkRetryAt[publicKey] = clock.now() + unavailableLinkRetryDelay
                    } else if (it is PaykitException.Transport) {
                        unavailableLinkRetryAt.remove(publicKey)
                    }
                    if (it !is PaykitException.NotFound) linkRetryKeys += publicKey
                }
            }
        }
        return PrivateLinkPreparation(preparedKeys, linkRetryKeys)
    }

    private suspend fun isPrivateLinkUnavailable(
        publicKey: String,
        error: Throwable,
        priority: Priority,
    ): Boolean = when (error) {
        is PaykitException.NotFound -> true
        is PaykitException.Transport -> runSuspendCatching {
            val state = paykitSdkService.linkedPeers(priority).firstOrNull { it.counterparty == publicKey }?.state
            state == null || state == LinkedPeerState.NOT_LINKED
        }.getOrDefault(false)
        else -> false
    }

    private suspend fun advanceLinkIfIdle(
        publicKey: String,
        priority: Priority = Priority.Ordered,
    ): LinkedPeerState? {
        currentCoroutineContext().ensureActive()
        if (!activeLinkPreparationKeys.add(publicKey)) return null
        val generation = preparationGeneration
        return try {
            paykitSdkService.ensureLinkWithPeer(publicKey, priority = priority).state
        } finally {
            if (generation == preparationGeneration) activeLinkPreparationKeys.remove(publicKey)
        }
    }

    private suspend fun canPreparePrivateLink(
        publicKey: String,
        state: LinkedPeerState?,
        retryUnavailableLinks: Boolean,
    ): Boolean = publicKey in knownSavedContactKeys &&
        !isContactSharingCleanupPending() && state != LinkedPeerState.BLOCKED &&
        (retryUnavailableLinks || unavailableLinkRetryAt[publicKey]?.let { it > clock.now() } != true)

    private suspend fun prepareRelevantPrivateLinksIfAvailable(
        publicKeys: Collection<String>,
        reason: String,
        priority: Priority = Priority.Ordered,
    ) {
        if (isContactSharingCleanupPending() || !hasPrivatePaymentAccessForCurrentProfile()) return
        drainAndSchedulePrivateLinkRetries(reason, publicKeys.distinct(), priority)
    }

    private suspend fun drainAndSchedulePrivateLinkRetries(
        reason: String,
        retryKeys: Collection<String>,
        priority: Priority = Priority.Ordered,
    ) {
        val newKeys = synchronized(pendingMessageDrainRetryLock) {
            retryKeys.toSet() - pendingMessageDrainRetryKeys
        }
        val pendingKeys = pendingPrivateMessageDrainKeys(newKeys, retryMissingPeers = true, priority = priority)
        if (pendingKeys.isEmpty()) return

        drainPendingPrivateMessages(reason, retryKeys = pendingKeys, priority = priority)
        val pendingRetryKeys = pendingPrivateMessageDrainKeys(pendingKeys, priority = priority)
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
        logPrivatePaymentListDeliveryFailures(report, reason)

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

    private fun logPrivatePaymentListDeliveryFailures(report: PrivatePaymentListDeliveryReport, reason: String) {
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
        retryKeys: Collection<String>,
        includeUnsavedPeers: Boolean = false,
        priority: Priority = Priority.Ordered,
    ) {
        val retryKeys = retryKeys.mapNotNull(::normalizedPublicKey).toSet()
        currentCoroutineContext().ensureActive()
        if (retryKeys.isEmpty()) return
        runSuspendCatching {
            val generation = preparationGeneration
            advancePendingPrivateLinks(retryKeys, includeUnsavedPeers, priority, generation, reason)
            awaitContactPreparationActive(priority)
            currentCoroutineContext().ensureActive()
            if (generation != preparationGeneration) return@runSuspendCatching
            val pendingKeys = paykitSdkService.pendingOutboundPrivateCounterparties(priority)
                .mapNotNull(::normalizedPublicKey).toSet().intersect(retryKeys)
            pendingKeys.forEach { publicKey ->
                awaitContactPreparationActive(priority)
                currentCoroutineContext().ensureActive()
                if (generation != preparationGeneration) return@runSuspendCatching
                runSuspendCatching {
                    paykitSdkService.processOutboundPrivateMessages(publicKey, priority)
                }.onFailure {
                    Logger.warn("Failed to send private Paykit messages during '$reason'", it, context = TAG)
                }
            }
            awaitContactPreparationActive(priority)
            currentCoroutineContext().ensureActive()
            if (generation != preparationGeneration) return@runSuspendCatching
            val linkedKeys = paykitSdkService.linkedPeers(priority).filter { it.state == LinkedPeerState.LINKED }
                .mapNotNull { normalizedPublicKey(it.counterparty) }.toSet().intersect(retryKeys)
            linkedKeys.forEach { publicKey ->
                awaitContactPreparationActive(priority)
                currentCoroutineContext().ensureActive()
                if (generation != preparationGeneration) return@runSuspendCatching
                runSuspendCatching {
                    paykitSdkService.receivePrivateMessages(publicKey, priority)
                }.onFailure {
                    Logger.warn("Failed to receive private Paykit messages during '$reason'", it, context = TAG)
                }
            }
        }.onFailure {
            Logger.warn("Failed to process pending private Paykit messages during '$reason'", it, context = TAG)
        }
    }

    private suspend fun advancePendingPrivateLinks(
        retryKeys: Set<String>,
        includeUnsavedPeers: Boolean,
        priority: Priority,
        generation: Int,
        reason: String,
    ) {
        awaitContactPreparationActive(priority)
        if (generation != preparationGeneration) return
        val alreadyLinkedKeys = paykitSdkService.linkedPeers(priority).filter { it.state == LinkedPeerState.LINKED }
            .mapNotNull { normalizedPublicKey(it.counterparty) }.toSet()
        (retryKeys - alreadyLinkedKeys).forEach { retryKey ->
            awaitContactPreparationActive(priority)
            currentCoroutineContext().ensureActive()
            if (generation != preparationGeneration) return
            if (!includeUnsavedPeers && retryKey !in knownSavedContactKeys) return@forEach
            if (unavailableLinkRetryAt[retryKey]?.let { it > clock.now() } == true) return@forEach
            runSuspendCatching {
                advanceLinkIfIdle(retryKey, priority)
            }.onFailure {
                Logger.warn(
                    "Failed to advance private Paykit link for '${redacted(retryKey)}' during '$reason'",
                    it,
                    context = TAG,
                )
            }
        }
    }

    private fun schedulePendingPrivateMessageDrainRetries(
        reason: String,
        retryKeys: Collection<String>,
    ) {
        val retryKeys = retryKeys.toSet()
        if (retryKeys.isEmpty()) return

        synchronized(pendingMessageDrainRetryLock) {
            val hasActiveRetry =
                pendingMessageDrainRetryJob?.isActive == true && pendingMessageDrainRetryKeys.isNotEmpty()
            pendingMessageDrainRetryKeys.addAll(retryKeys)
            if (hasActiveRetry) return
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
        val pendingKeys = pendingPrivateMessageDrainKeys(retryKeys, priority = Priority.Background)
        if (pendingKeys.isNotEmpty()) {
            drainPendingPrivateMessages(reason, retryKeys = pendingKeys, priority = Priority.Background)
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
        val remainingKeys = pendingPrivateMessageDrainKeys(retryKeys, priority = Priority.Background)
        synchronized(pendingMessageDrainRetryLock) {
            pendingMessageDrainRetryKeys.removeAll(retryKeys.toSet())
            pendingMessageDrainRetryKeys.addAll(remainingKeys)
        }
    }

    private suspend fun pendingPrivateMessageDrainKeys(
        retryKeys: Collection<String>,
        retryMissingPeers: Boolean = false,
        priority: Priority = Priority.Ordered,
    ): Set<String> {
        val retryKeys = retryKeys.toSet()
        val generation = preparationGeneration
        if (retryKeys.isNotEmpty()) awaitContactPreparationActive(priority)
        if (retryKeys.isEmpty() || generation != preparationGeneration) return emptySet()

        val linkedPeers = runSuspendCatching { paykitSdkService.linkedPeers(priority) }
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
        awaitContactPreparationActive(priority)
        if (generation != preparationGeneration) return emptySet()
        val pendingOutbound = runSuspendCatching { paykitSdkService.pendingOutboundPrivateCounterparties(priority) }
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
            if (PublicPaykitRepo.isUsdtPaymentOptionEnabled(settings)) {
                endpoints += usdtRepo.paymentEndpoint().getOrThrow()
            }
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

    private suspend fun removePublishedEndpoints(publicKeys: Collection<String>): Result<Unit> =
        withContext(serializedDispatcher) {
            publicationMutex.withLock {
                removePublishedEndpointsLocked(publicKeys)
            }
        }

    private suspend fun removePublishedEndpointsLocked(publicKeys: Collection<String>? = null): Result<Unit> =
        runSuspendCatching {
            val peers = paykitSdkService.linkedPeers()
            val linkedPublicKeys = peers
                .filter {
                    it.state == LinkedPeerState.LINKED || it.state == LinkedPeerState.LINKING ||
                        it.state == LinkedPeerState.RECOVERY_REQUIRED
                }
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
                var pendingRetryKeys = pendingPrivateMessageDrainKeys(preparation.clearedRetryKeys)
                if (pendingRetryKeys.isNotEmpty()) {
                    drainPendingPrivateMessages(
                        reason = "private endpoint cleanup",
                        retryKeys = pendingRetryKeys,
                        includeUnsavedPeers = true,
                    )
                    pendingRetryKeys = pendingPrivateMessageDrainKeys(preparation.clearedRetryKeys)
                }
                if (pendingRetryKeys.isNotEmpty()) {
                    Logger.warn(
                        "Private Paykit endpoint withdrawal remains pending for ${pendingRetryKeys.map(::redacted)}",
                        context = TAG,
                    )
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
            if (publicKeys != null) publicPaykitRepo.syncPaykitApp().getOrThrow()
        }.onFailure {
            runSuspendCatching { settingsStore.update { it.copy(publicPaykitCleanupPending = true) } }
                .onFailure(it::addSuppressed)
        }

    private suspend fun syncPaykitAppAfterCleanup(): Result<Unit> =
        publicPaykitRepo.syncPaykitApp().onFailure {
            runSuspendCatching { settingsStore.update { it.copy(publicPaykitCleanupPending = true) } }
                .onFailure(it::addSuppressed)
        }

    private suspend fun clearPrivatePaymentLists(
        publicKeys: Collection<String>,
        linkedPublicKeys: Set<String>,
    ): PrivateEndpointCleanupPreparation {
        val cleanupKeys = publicKeys.filter {
            it in linkedPublicKeys || state?.contacts?.get(it)?.hasPublishedPrivatePaymentList == true
        }
        if (cleanupKeys.isEmpty()) return PrivateEndpointCleanupPreparation(emptyList(), emptySet(), null)

        return runSuspendCatching {
            val report = paykitSdkService.clearPrivatePaymentLists(cleanupKeys)
                ?: return@runSuspendCatching PrivateEndpointCleanupPreparation(emptyList(), emptySet(), null)
            logPrivatePaymentListDeliveryFailures(report, "cleanup")
            val failedPublicKeys = (
                report.failedToQueue.map { it.counterparty } +
                    report.failedToDeliver.map { it.counterparty }
                ).mapNotNull(::normalizedPublicKey).toSet()
            val clearedRetryKeys = report.cleared.mapNotNull { normalizedPublicKey(it.counterparty) }
                .filterNot { it in failedPublicKeys }
            PrivateEndpointCleanupPreparation(
                clearedRetryKeys,
                failedPublicKeys,
                PrivatePaykitError.PrivateUnavailable.takeIf { failedPublicKeys.isNotEmpty() },
            )
        }.getOrElse {
            Logger.warn("Failed to clear private Paykit endpoints: ${it::class.simpleName}", context = TAG)
            PrivateEndpointCleanupPreparation(emptyList(), cleanupKeys.toSet(), it)
        }
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
                clearContactStates(ensureState().contacts.keys.filter { it !in savedKeys })
                addressReservationRepo.clearContactAssignments(excludingPublicKeys = savedKeys)
            }
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

    private suspend fun canPublishPrivateEndpoints(status: IdentityStatus? = null): Boolean {
        val settings = settingsStore.data.first()
        val locallyEligible = settings.sharesPrivatePaykitEndpoints &&
            !isContactSharingCleanupPending() &&
            App.currentActivity?.value != null &&
            walletRepo.walletExists() &&
            lightningRepo.lightningState.value.nodeLifecycleState.isRunning()
        if (!locallyEligible) return false
        return if (status?.publicKey != null) {
            status.capability == PubkyIdentityCapability.PRIVATE_LINK_CAPABLE
        } else {
            hasPrivatePaymentAccessForCurrentProfile()
        }
    }

    private suspend fun hasPrivatePaymentAccessForCurrentProfile(): Boolean = runSuspendCatching {
        paykitSdkService.hasPrivatePaymentAccess()
    }.getOrDefault(false)

    private suspend fun isContactSharingCleanupPending(): Boolean =
        cacheStore.data.first().cleanupPending

    private suspend fun hasPublishedPrivateEndpoints(): Boolean =
        ensureState().contacts.values.any { it.hasPublishedPrivatePaymentList }

    private suspend fun updateContactSharingCleanupPending(isPending: Boolean) {
        if (isPending) invalidateContactPreparation()
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
            addressReservationRepo.removeContactAssignments(successfulKeys)
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
            unavailableLinkRetryAt.keys.retainAll(normalizedKeys.toSet())
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

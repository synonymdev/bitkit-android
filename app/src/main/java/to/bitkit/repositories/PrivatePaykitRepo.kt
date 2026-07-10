package to.bitkit.repositories

import com.synonym.bitkitcore.Scanner
import com.synonym.paykit.ContactPaymentResolutionPrivateState
import com.synonym.paykit.LinkedPeerState
import com.synonym.paykit.PaymentEndpointReservationInput
import com.synonym.paykit.PaymentEndpointSource
import com.synonym.paykit.PrivatePaymentListDeliveryReport
import com.synonym.paykit.PrivatePaymentListReservationUpdateInput
import kotlinx.coroutines.CancellationException
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
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.runSuspendCatching
import to.bitkit.ext.toHex
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.services.CoreService
import to.bitkit.services.PaykitReceiverPaths
import to.bitkit.services.PaykitSdkService
import to.bitkit.services.PubkyService
import to.bitkit.utils.Logger
import java.security.MessageDigest
import java.time.Instant
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

        fun isDuplicatePaymentError(error: Throwable): Boolean =
            PrivatePaykitErrorClassifier.isDuplicatePaymentError(error)
    }

    private val publicationMutex = Mutex()
    private val serializedDispatcher = ioDispatcher.limitedParallelism(1)
    private val retryScope = CoroutineScope(serializedDispatcher + SupervisorJob())
    private val knownSavedContactKeys = mutableSetOf<String>()
    private var state: PrivatePaykitState? = null
    private val pendingMessageDrainRetryLock = Any()
    private val pendingMessageDrainRetryKeys = mutableSetOf<PrivateMessageDrainRetryKey>()
    private var pendingMessageDrainRetryJob: Job? = null
    private var pendingMessageDrainRetryGeneration = 0

    private data class PrivatePublicationPreparation(
        val updates: List<PrivatePaymentListReservationUpdateInput>,
        val linkRetryKeys: List<PrivateMessageDrainRetryKey>,
        val firstError: Throwable?,
    )

    private data class PrivateMessageDrainRetryKey(
        val publicKey: String,
        val receiverPath: String,
    )

    private val _backupStateVersion = MutableStateFlow(0L)
    val backupStateVersion: StateFlow<Long> = _backupStateVersion.asStateFlow()

    suspend fun reconcileReservedReceiveIndexes(): Result<Unit> =
        addressReservationRepo.reconcileReservedIndexesWithLdk()

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
        requireImmediatePublication: Boolean = false,
    ): Result<Unit> = withContext(serializedDispatcher) {
        runSuspendCatching {
            val wasCleanupPending = isContactSharingCleanupPending()
            if (wasCleanupPending && !canPublishPrivateEndpoints()) {
                if (requireImmediatePublication) throw PrivatePaykitError.PrivateUnavailable
                return@runSuspendCatching
            }

            updateContactSharingCleanupPending(false)
            prepareSavedContacts(publicKeys, requireImmediatePublication).onFailure {
                if (wasCleanupPending) {
                    runSuspendCatching { updateContactSharingCleanupPending(true) }.onFailure(it::addSuppressed)
                }
            }.getOrThrow()
        }
    }

    suspend fun refreshSavedContactEndpoints(publicKeys: Collection<String>): Result<Unit> =
        withContext(serializedDispatcher) {
            runSuspendCatching {
                val keys = rememberSavedContacts(publicKeys, replacing = true)
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

    suspend fun retryPendingEndpointRemoval(
        savedPublicKeys: Collection<String>,
    ): Result<Unit> = withContext(serializedDispatcher) {
        runSuspendCatching {
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
                val removalError = removePublishedEndpoints().exceptionOrNull()
                if (removalError != null) {
                    updateContactSharingCleanupPending(true)
                    Logger.warn(
                        "Deferred private Paykit endpoint cleanup after disable failed",
                        removalError,
                        context = TAG,
                    )
                    throw removalError
                }

                clearUnsavedContactState(savedPublicKeys).getOrThrow()
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
        removePublishedEndpoints()
            .onSuccess {
                updateContactSharingCleanupPending(false)
            }
            .onFailure {
                updateContactSharingCleanupPending(true)
                Logger.warn("Failed to remove private Paykit endpoints during '$context'", it, context = TAG)
            }
    }

    suspend fun closeAndClear(): Result<Unit> = withContext(serializedDispatcher) {
        runSuspendCatching {
            publicationMutex.withLock {
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

                val result = beginContactPayment(normalizedKey).getOrElse {
                    if (it is CancellationException) throw it
                    Logger.warn(
                        "Failed to resolve Paykit contact payment for '${redacted(normalizedKey)}'",
                        it,
                        context = TAG,
                    )
                    return@runSuspendCatching publicPaykitRepo.beginPayment(normalizedKey).getOrThrow()
                }
                result
            }
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

    suspend fun discardRemoteOnchainEndpoints(
        publicKey: String,
        addresses: Set<String>,
    ): Result<Unit> = withContext(serializedDispatcher) {
        runSuspendCatching {
            if (addresses.isEmpty()) return@runSuspendCatching
            val normalizedKey = normalizedPublicKey(publicKey) ?: return@runSuspendCatching
            val contactState = ensureState().contacts[normalizedKey] ?: return@runSuspendCatching
            val filteredEntries = contactState.remoteEndpoints.filterNot {
                shouldDiscardRemoteOnchainEntry(it, addresses)
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
                    contactState.localInvoicesByReceiverPath.values.forEach { invoice ->
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
                    contactState.localInvoices().any { invoice -> invoice.paymentHash == paymentHash } ||
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

    suspend fun backupSnapshot(): Result<String?> =
        withContext(serializedDispatcher) {
            runSuspendCatching {
                pubkyService.currentPublicKey() ?: return@runSuspendCatching null
                paykitSdkService.exportBackupState()
            }
        }

    suspend fun restoreBackup(backup: String?): Result<Unit> =
        withContext(serializedDispatcher) {
            runSuspendCatching {
                clearPendingMessageDrainRetries()
                state = PrivatePaykitState()
                knownSavedContactKeys.clear()
                if (backup == null) {
                    paykitSdkService.clearState()
                } else {
                    paykitSdkService.restoreBackupState(backup)
                }
                persistState(preserveCleanupMarkers = false)
                notifyBackupStateChanged()
            }
        }

    private suspend fun beginContactPayment(publicKey: String): Result<PublicPaykitPaymentResult> =
        withContext(serializedDispatcher) {
            runSuspendCatching {
                pubkyService.currentPublicKey() ?: throw PublicPaykitError.SessionNotActive
                if (canPublishPrivateEndpoints()) {
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

                val resolution = paykitSdkService.prepareAndResolveContactPayment(
                    counterparty = publicKey,
                    receiverPath = PaykitReceiverPaths.WALLET,
                    includePublicEndpoints = true,
                )
                val privateEndpoints = resolution.payableEndpoints
                    .filter { it.source == PaymentEndpointSource.PRIVATE_PAYMENT_LIST }
                    .mapNotNull { PublicPaykitRepo.parseEndpoint(it.identifier, it.payload) }

                cacheResolvedPrivateEndpoints(publicKey, privateEndpoints)

                val privatePayable = privatePayableEndpoints(privateEndpoints, publicKey)
                if (privatePayable.isNotEmpty()) {
                    return@runSuspendCatching PublicPaykitPaymentResult.Opened(
                        PublicPaykitRepo.paymentRequest(privatePayable),
                    )
                }

                if (resolution.privateState == ContactPaymentResolutionPrivateState.RECOVERY_PENDING) {
                    schedulePendingPrivateMessageDrainRetries(
                        reason = "payment recovery",
                        retryKeys = listOf(PrivateMessageDrainRetryKey(publicKey, PaykitReceiverPaths.WALLET)),
                    )
                }

                val publicEndpoints = resolution.payableEndpoints
                    .filter { it.source == PaymentEndpointSource.PUBLIC_PAYMENT_ENDPOINT }
                    .mapNotNull { PublicPaykitRepo.parseEndpoint(it.identifier, it.payload) }
                val publicPayable = publicPaykitRepo.payableEndpoints(publicEndpoints)
                if (publicPayable.isNotEmpty()) {
                    return@runSuspendCatching PublicPaykitPaymentResult.Opened(
                        PublicPaykitRepo.paymentRequest(publicPayable),
                    )
                }

                if (privateEndpoints.isEmpty() && publicEndpoints.isEmpty()) {
                    PublicPaykitPaymentResult.NoEndpoint
                } else {
                    PublicPaykitPaymentResult.NotOpened
                }
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
        val linkRetryKeys = mutableListOf<PrivateMessageDrainRetryKey>()

        for (publicKey in publicKeys) {
            val receiverPaths = receiverPathsForSavedContact(publicKey)
            val receiverPathSelection = paykitSdkService.privateReceiverPathSelection(publicKey, receiverPaths)
            val linkableReceiverPaths = receiverPathSelection.linkableReceiverPaths
            val publicationReceiverPaths = receiverPathSelection.publishableReceiverPaths
            receiverPathSelection.error?.let {
                firstError = firstError ?: it
                Logger.warn(
                    "Failed to inspect private Paykit receiver markers for '${redacted(publicKey)}' during '$reason'",
                    it,
                    context = TAG,
                )
            }
            val cleanupReceiverPaths = receiverPathsForPrivateEndpointCleanup(
                publicKey = publicKey,
                excludedReceiverPaths = publicationReceiverPaths + receiverPathSelection.cleanupProtectedReceiverPaths,
            )

            (linkableReceiverPaths + cleanupReceiverPaths).distinct().forEach { receiverPath ->
                linkRetryKeys += PrivateMessageDrainRetryKey(publicKey, receiverPath)
                runSuspendCatching { paykitSdkService.ensureLinkWithPeer(publicKey, receiverPath) }.onFailure {
                    Logger.warn(
                        "Failed to prepare private Paykit link for '${redacted(publicKey)}' during '$reason'",
                        it,
                        context = TAG,
                    )
                }
            }

            cleanupReceiverPaths.forEach { receiverPath ->
                updates += PrivatePaymentListReservationUpdateInput(
                    counterparty = publicKey,
                    counterpartyReceiverPath = receiverPath,
                    reservations = emptyList(),
                )
            }

            publicationReceiverPaths.forEach { receiverPath ->
                runSuspendCatching {
                    privatePaymentListUpdate(publicKey, receiverPath, forceRefreshLightning)
                }.onSuccess {
                    updates += it
                }.onFailure {
                    firstError = firstError ?: it
                    logPrivatePublicationPreparationFailure(publicKey, reason, it)
                }
            }
        }

        return PrivatePublicationPreparation(updates, linkRetryKeys.distinct(), firstError)
    }

    private suspend fun prepareRelevantPrivateLinksIfAvailable(publicKeys: Collection<String>, reason: String) {
        if (!hasLocalSecretKeyForCurrentProfile()) return

        val retryKeys = publicKeys.flatMap { publicKey ->
            val receiverPaths = receiverPathsForSavedContact(publicKey)
            val selection = paykitSdkService.privateReceiverPathSelection(publicKey, receiverPaths)
            selection.error?.let {
                Logger.warn(
                    "Failed to inspect private Paykit receiver markers for '${redacted(publicKey)}' during '$reason'",
                    it,
                    context = TAG,
                )
            }
            selection.linkableReceiverPaths.map { PrivateMessageDrainRetryKey(publicKey, it) }
        }.distinct()

        drainAndSchedulePrivateLinkRetries(reason, retryKeys)
    }

    private suspend fun drainAndSchedulePrivateLinkRetries(
        reason: String,
        retryKeys: Collection<PrivateMessageDrainRetryKey>,
    ) {
        if (retryKeys.isEmpty()) return

        drainPendingPrivateMessages(reason, advancingLinksFor = retryKeys.toList())
        val pendingRetryKeys = pendingPrivateMessageDrainKeys(retryKeys)
        if (pendingRetryKeys.isNotEmpty()) {
            schedulePendingPrivateMessageDrainRetries(reason, retryKeys = pendingRetryKeys)
        }
    }

    private suspend fun privatePaymentListUpdate(
        publicKey: String,
        receiverPath: String,
        forceRefreshLightning: Boolean,
    ): PrivatePaymentListReservationUpdateInput {
        val endpoints = buildLocalEndpoints(publicKey, receiverPath, forceRefreshLightning).getOrThrow()
        if (endpoints.isEmpty()) throw PrivatePaykitError.PrivateUnavailable
        return PrivatePaymentListReservationUpdateInput(
            counterparty = publicKey,
            counterpartyReceiverPath = receiverPath,
            reservations = endpoints.map { endpoint -> privateReservation(publicKey, receiverPath, endpoint) },
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
                    (it.error ?: "unknown error"),
                context = TAG,
            )
        }
        report.failedToDeliver.forEach {
            Logger.warn(
                "Failed to deliver private Paykit endpoints for '${redacted(it.counterparty)}' during '$reason': " +
                    it.error,
                context = TAG,
            )
        }

        var didUpdateCache = false
        for (change in report.queued) {
            val publicKey = normalizedPublicKey(change.counterparty) ?: continue
            recordPublishedPrivatePaymentListCache(publicKey, change.counterpartyReceiverPath)
            didUpdateCache = true
        }

        for (change in report.cleared) {
            didUpdateCache = clearPublishedPrivatePaymentListCache(
                counterparty = change.counterparty,
                receiverPath = change.counterpartyReceiverPath,
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
    ): List<PrivateMessageDrainRetryKey> {
        val changes = report.queued.map { it.counterparty to it.counterpartyReceiverPath } +
            report.cleared.map { it.counterparty to it.counterpartyReceiverPath } +
            report.failedToDeliver.map { it.counterparty to it.counterpartyReceiverPath }

        return changes
            .mapNotNull { (counterparty, receiverPath) ->
                normalizedPublicKey(counterparty)?.let { PrivateMessageDrainRetryKey(it, receiverPath) }
            }
            .distinct()
    }

    private suspend fun drainPendingPrivateMessages(
        reason: String,
        advancingLinksFor: List<PrivateMessageDrainRetryKey> = emptyList(),
    ) {
        runSuspendCatching {
            advancingLinksFor.distinct().forEach { retryKey ->
                runSuspendCatching {
                    paykitSdkService.ensureLinkWithPeer(
                        counterparty = retryKey.publicKey,
                        receiverPath = retryKey.receiverPath,
                    )
                }.onFailure {
                    Logger.warn(
                        "Failed to advance private Paykit link for '${redacted(retryKey.publicKey)}' during '$reason'",
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
        retryKeys: Collection<PrivateMessageDrainRetryKey>,
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
        drainPendingPrivateMessages(reason, advancingLinksFor = retryKeys)
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

    private suspend fun updatePendingMessageDrainRetryKeys(retryKeys: Collection<PrivateMessageDrainRetryKey>) {
        val remainingKeys = pendingPrivateMessageDrainKeys(retryKeys)
        synchronized(pendingMessageDrainRetryLock) {
            pendingMessageDrainRetryKeys.removeAll(retryKeys.toSet())
            pendingMessageDrainRetryKeys.addAll(remainingKeys)
        }
    }

    private suspend fun pendingPrivateMessageDrainKeys(
        retryKeys: Collection<PrivateMessageDrainRetryKey>,
    ): Set<PrivateMessageDrainRetryKey> {
        val retryKeys = retryKeys.toSet()
        if (retryKeys.isEmpty()) return emptySet()

        val linkedPeers = runSuspendCatching { paykitSdkService.linkedPeers() }
            .getOrElse {
                Logger.warn("Failed to inspect private Paykit link state", it, context = TAG)
                return retryKeys
            }
            .mapNotNull { peer ->
                normalizedPublicKey(peer.counterparty)?.let { publicKey ->
                    PrivateMessageDrainRetryKey(publicKey, peer.counterpartyReceiverPath) to peer.state
                }
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        val pendingOutbound = runSuspendCatching { paykitSdkService.pendingOutboundPrivateCounterparties() }
            .getOrElse {
                Logger.warn("Failed to inspect pending private Paykit messages", it, context = TAG)
                return retryKeys
            }
            .mapNotNull { receiver ->
                normalizedPublicKey(receiver.counterparty)?.let {
                    PrivateMessageDrainRetryKey(it, receiver.counterpartyReceiverPath)
                }
            }
            .toSet()

        return retryKeys.filterTo(mutableSetOf()) { retryKey ->
            val states = linkedPeers[retryKey].orEmpty()
            when {
                LinkedPeerState.LINKED in states -> retryKey in pendingOutbound
                states.isEmpty() -> retryKey in pendingOutbound
                states.all { it == LinkedPeerState.BLOCKED || it == LinkedPeerState.UNKNOWN } -> false
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

    private suspend fun recordPublishedPrivatePaymentListCache(publicKey: String, receiverPath: String) {
        val contactState = ensureState().contacts.getOrPut(publicKey) { ContactState() }
        contactState.publishedPrivatePaymentReceiverPaths =
            (contactState.publishedPrivatePaymentReceiverPaths + receiverPath).toSortedSet()
    }

    private suspend fun clearPublishedPrivatePaymentListCache(
        counterparty: String,
        receiverPath: String,
    ): Boolean {
        val publicKey = normalizedPublicKey(counterparty) ?: return false
        ensureState().contacts[publicKey]?.let { contactState ->
            contactState.publishedPrivatePaymentReceiverPaths =
                contactState.publishedPrivatePaymentReceiverPaths.filterTo(mutableSetOf()) { it != receiverPath }
            contactState.localInvoicesByReceiverPath = contactState.localInvoicesByReceiverPath - receiverPath
            if (!contactState.hasCacheState) {
                state?.contacts?.remove(publicKey)
            }
        }
        return true
    }

    private suspend fun buildLocalEndpoints(
        publicKey: String,
        receiverPath: String,
        forceRefreshLightning: Boolean = false,
    ): Result<List<Endpoint>> = withContext(serializedDispatcher) {
        runSuspendCatching {
            val settings = settingsStore.data.first()
            val endpoints = mutableListOf<Endpoint>()
            if (PublicPaykitRepo.isOnchainPaymentOptionEnabled(settings)) {
                val reservedAddress = addressReservationRepo.currentOrRotatedAddress(
                    publicKey,
                    receiverPath,
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
                    receiverPath,
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
        receiverPath: String,
        forceRefresh: Boolean = false,
    ): Result<StoredInvoice> = withContext(serializedDispatcher) {
        runSuspendCatching {
            if (!forceRefresh) reusablePrivateInvoice(publicKey, receiverPath)?.let { return@runSuspendCatching it }

            val bolt11 = lightningRepo.createInvoice(
                amountSats = null,
                description = "",
                expirySeconds = privateInvoiceExpiry.inWholeSeconds.toUInt(),
            ).getOrThrow()
            if (!forceRefresh) reusablePrivateInvoice(publicKey, receiverPath)?.let { return@runSuspendCatching it }

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
            setLocalInvoice(publicKey, receiverPath, invoice)
            persistState()
            invoice
        }
    }

    private suspend fun reusablePrivateInvoice(
        publicKey: String,
        receiverPath: String,
    ): StoredInvoice? {
        val invoice = localInvoice(publicKey, receiverPath) ?: return null
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
        receiverPath: String,
        endpoint: Endpoint,
    ): PaymentEndpointReservationInput {
        val contactState = state?.contacts?.get(publicKey)
        val attribution = if (endpoint.methodId == MethodId.Bolt11) {
            val paymentHash = localInvoice(publicKey, receiverPath)?.takeIf { it.bolt11 == endpoint.value }?.paymentHash
            mapOf(
                "type" to "private_paykit",
                "counterparty" to publicKey,
                "receiver_path" to receiverPath,
            ) + listOfNotNull(paymentHash?.let { "payment_hash" to it }).toMap()
        } else {
            mapOf(
                "type" to "private_paykit",
                "counterparty" to publicKey,
                "receiver_path" to receiverPath,
            )
        }
        val expiresAt = contactState
            ?.let { localInvoice(publicKey, receiverPath) }
            ?.takeIf { endpoint.methodId == MethodId.Bolt11 && it.bolt11 == endpoint.value }
            ?.let { Instant.ofEpochSecond(it.expiresAt).toString() }

        return PaymentEndpointReservationInput(
            reservationId = privateReservationId(publicKey, receiverPath, endpoint),
            identifier = endpoint.methodId.rawValue,
            payload = endpoint.rawPayload,
            expiresAt = expiresAt,
            attribution = attribution,
        )
    }

    private fun privateReservationId(publicKey: String, receiverPath: String, endpoint: Endpoint): String {
        val payloadHashPrefix = MessageDigest.getInstance("SHA-256")
            .digest(endpoint.rawPayload.toByteArray(Charsets.UTF_8))
            .copyOfRange(0, 8)
            .toHex()
        return "$publicKey:$receiverPath:${endpoint.methodId.rawValue}:$payloadHashPrefix"
    }

    private suspend fun cacheResolvedPrivateEndpoints(publicKey: String, endpoints: List<Endpoint>) {
        val contactState = ensureState().contacts.getOrPut(publicKey) { ContactState() }
        contactState.remoteEndpoints = endpoints.map { StoredPaymentEntry(it.methodId.rawValue, it.rawPayload) }
        persistState(markWalletBackup = true)
    }

    private suspend fun removePublishedEndpoints(): Result<Unit> = withContext(serializedDispatcher) {
        runSuspendCatching {
            val keys = (knownSavedContactKeys + ensureState().contacts.keys + pendingDeletedContactCleanupPublicKeys())
                .distinct()
            val firstError = keys.mapNotNull { publicKey ->
                removePublishedEndpoints(publicKey).exceptionOrNull()
            }.firstOrNull()
            if (firstError != null) throw firstError
        }
    }

    private suspend fun removePublishedEndpoints(publicKey: String): Result<Unit> = withContext(serializedDispatcher) {
        runSuspendCatching {
            var firstError: Throwable? = null
            receiverPathsForCleanup(publicKey).forEach { receiverPath ->
                val result = runSuspendCatching {
                    val report = paykitSdkService.clearPrivatePaymentList(
                        counterparty = publicKey,
                        receiverPath = receiverPath,
                    )
                    if (report.failedToQueue.isNotEmpty() || report.failedToDeliver.isNotEmpty()) {
                        throw PrivatePaykitError.PrivateUnavailable
                    }
                    val retryKey = PrivateMessageDrainRetryKey(publicKey, receiverPath)
                    drainPendingPrivateMessages("private endpoint cleanup", advancingLinksFor = listOf(retryKey))
                    if (retryKey in pendingPrivateMessageDrainKeys(listOf(retryKey))) {
                        throw PrivatePaykitError.PrivateUnavailable
                    }
                }
                if (result.isFailure) {
                    firstError = firstError ?: result.exceptionOrNull()
                }
            }

            firstError?.let { throw it }

            state?.contacts?.get(publicKey)?.let { contactState ->
                contactState.remoteEndpoints = emptyList()
                contactState.localInvoicesByReceiverPath = emptyMap()
                contactState.publishedPrivatePaymentReceiverPaths = emptySet()
                if (!contactState.hasCacheState) {
                    state?.contacts?.remove(publicKey)
                }
            }
            updateDeletedContactCleanupPending(publicKey, isPending = false)
            persistState(markWalletBackup = true)
        }
    }

    private suspend fun receiverPathsForSavedContact(publicKey: String): List<String> {
        val paths = paykitSdkService.contactRecord(publicKey)
            ?.receiverPaths
            ?.filter { it in PaykitReceiverPaths.supported }
            .orEmpty()
        return paths.ifEmpty { listOf(PaykitReceiverPaths.WALLET) }
    }

    private suspend fun receiverPathsForPrivateEndpointCleanup(
        publicKey: String,
        excludedReceiverPaths: List<String>,
    ): List<String> {
        val publishedPaths = publishedPrivatePaymentReceiverPaths(publicKey)
        val linkedPaths = linkedReceiverPaths(publicKey)
        return (publishedPaths + linkedPaths)
            .filter { it in PaykitReceiverPaths.supported }
            .filterNot { it in excludedReceiverPaths }
            .distinct()
            .sorted()
    }

    private suspend fun receiverPathsForCleanup(publicKey: String): List<String> {
        val paths = (
            linkedReceiverPaths(publicKey) +
                publishedPrivatePaymentReceiverPaths(publicKey)
            )
            .filter { it in PaykitReceiverPaths.supported }
            .distinct()
            .sorted()
        return paths
    }

    private suspend fun linkedReceiverPaths(publicKey: String): List<String> {
        val normalizedKey = normalizedPublicKey(publicKey) ?: return emptyList()
        val paths = paykitSdkService.linkedPeers()
            .mapNotNull { peer ->
                val peerKey = normalizedPublicKey(peer.counterparty)
                peer.counterpartyReceiverPath.takeIf {
                    peerKey == normalizedKey && it in PaykitReceiverPaths.supported
                }
            }
            .distinct()
            .sorted()
        return paths
    }

    private fun publishedPrivatePaymentReceiverPaths(publicKey: String): List<String> {
        val contactState = state?.contacts?.get(publicKey) ?: return emptyList()
        return contactState.publishedPrivatePaymentReceiverPaths.toList()
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
        ensureState().contacts.remove(publicKey)
        persistState(markWalletBackup = true)
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

    private suspend fun hasLocalSecretKeyForCurrentProfile(): Boolean = runSuspendCatching {
        pubkyService.currentPublicKey() ?: return@runSuspendCatching false
        val status = paykitSdkService.identityStatus() ?: return@runSuspendCatching false
        status.privateLinkCapable
    }.getOrDefault(false)

    private suspend fun isContactSharingCleanupPending(): Boolean =
        cacheStore.data.first().cleanupPending

    private suspend fun updateContactSharingCleanupPending(isPending: Boolean) {
        cacheStore.update { it.copy(cleanupPending = isPending) }
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
        runSuspendCatching {
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

    private suspend fun settledPrivateInvoicePaymentHashes(): List<String> {
        val settled = receivedSettledPaymentHashes()
        return ensureState().contacts.values
            .flatMap { it.localInvoices() }
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

    private fun localInvoice(publicKey: String, receiverPath: String): StoredInvoice? {
        val contactState = state?.contacts?.get(publicKey) ?: return null
        return contactState.localInvoicesByReceiverPath[receiverPath]
    }

    private suspend fun setLocalInvoice(publicKey: String, receiverPath: String, invoice: StoredInvoice) {
        val contactState = ensureState().contacts.getOrPut(publicKey) { ContactState() }
        contactState.localInvoicesByReceiverPath = contactState.localInvoicesByReceiverPath + (receiverPath to invoice)
    }

    private fun ContactState.localInvoices(): List<StoredInvoice> {
        return localInvoicesByReceiverPath.values.toList()
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

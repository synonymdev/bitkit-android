package to.bitkit.services

import android.content.Context
import com.synonym.bitkitcore.mnemonicToSeed
import com.synonym.paykit.ContactProfileResolution
import com.synonym.paykit.ContactRecord
import com.synonym.paykit.ContactUpdate
import com.synonym.paykit.CounterpartyReceiver
import com.synonym.paykit.EndpointSyncReport
import com.synonym.paykit.IdentityStatus
import com.synonym.paykit.LinkedPeerRecord
import com.synonym.paykit.LinkedPeerState
import com.synonym.paykit.OutboundPrivateCounterpartySendReport
import com.synonym.paykit.PaykitAndroid
import com.synonym.paykit.PaykitException
import com.synonym.paykit.PaykitProfile
import com.synonym.paykit.PaykitProfileRecord
import com.synonym.paykit.PaykitReceiverCapabilities
import com.synonym.paykit.PaykitReceiverMarker
import com.synonym.paykit.PaykitSdk
import com.synonym.paykit.PaykitSdkDefaults
import com.synonym.paykit.PaymentAmountContext
import com.synonym.paykit.PaymentPayload
import com.synonym.paykit.PaymentProofSubmission
import com.synonym.paykit.PaymentReference
import com.synonym.paykit.PaymentRequestAmount
import com.synonym.paykit.PaymentRequestFilter
import com.synonym.paykit.PaymentRequestRecord
import com.synonym.paykit.PaymentRequestTerms
import com.synonym.paykit.PaymentTarget
import com.synonym.paykit.PrivateContactPaymentResolution
import com.synonym.paykit.PrivateJsonObject
import com.synonym.paykit.PrivatePaymentEndpointCandidate
import com.synonym.paykit.PrivatePaymentEndpointReservationCancellation
import com.synonym.paykit.PrivatePaymentEndpointSelectionRequest
import com.synonym.paykit.PrivatePaymentListDeliveryReport
import com.synonym.paykit.PrivatePaymentListReservationUpdateInput
import com.synonym.paykit.PrivatePaymentResolutionState
import com.synonym.paykit.PrivatePaymentResolutionStatus
import com.synonym.paykit.PrivateReceivingDetail
import com.synonym.paykit.PrivateReceivingDetailReservationResponse
import com.synonym.paykit.PrivateReceivingDetailReservationResponseKind
import com.synonym.paykit.PrivateStreamCounterpartyIntakeReport
import com.synonym.paykit.PubkyAuthCompanionClaim
import com.synonym.paykit.PubkyAuthRequest
import com.synonym.paykit.PubkyClientConfig
import com.synonym.paykit.PubkyLocalSecretKey
import com.synonym.paykit.PubkyProfile
import com.synonym.paykit.PubkySessionAccess
import com.synonym.paykit.PubkySessionBootstrap
import com.synonym.paykit.PubkySessionBootstrapResult
import com.synonym.paykit.PublicContactPaymentResolution
import com.synonym.paykit.PublicPaymentEndpointCandidate
import com.synonym.paykit.PublicPaymentEndpointSelectionRequest
import com.synonym.paykit.PublicReceivingDetail
import com.synonym.paykit.ReceiverNoiseSecretKey
import com.synonym.paykit.SdkPaymentAdapter
import com.synonym.paykit.SdkPubkySessionProvider
import com.synonym.paykit.SdkStateBlob
import com.synonym.paykit.SdkStateBlobSnapshot
import com.synonym.paykit.SdkStateBlobStore
import com.synonym.paykit.decodeSdkStateBlobSnapshot
import com.synonym.paykit.defaultConfig
import com.synonym.paykit.defaultPubkyClientConfig
import com.synonym.paykit.encodeSdkStateBlobSnapshot
import com.synonym.paykit.parsePubkyAuthUrl
import com.synonym.paykit.pubkyPublicKeyFromSecret
import com.synonym.paykit.pubkySecretKeyFromBip39Mnemonic
import com.synonym.paykit.requiredSessionCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.Network
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.ext.fromHex
import to.bitkit.ext.runSuspendCatching
import to.bitkit.ext.toHex
import to.bitkit.models.PubkyAuthRequestError
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.repositories.Endpoint
import to.bitkit.repositories.PublicPaykitRepo
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

data class PaykitPreparedPrivateContactPayment(
    val resolution: PaykitPrivateContactPaymentResolution,
    val linkState: LinkedPeerState?,
)

data class PaykitPrivateContactPaymentResolution(
    val status: PrivatePaymentResolutionStatus,
    val state: PrivatePaymentResolutionState,
    val privatePaymentListVersion: ULong?,
    val payableEndpoints: List<PaykitResolvedPaymentEndpoint>,
)

data class PaykitPublicContactPaymentResolution(
    val payableEndpoints: List<PaykitResolvedPaymentEndpoint>,
)

data class PaykitResolvedPaymentEndpoint(
    val identifier: String,
    val payload: String,
)

data class PaykitPaymentRequestProposalTerms(
    val amountValue: String,
    val paymentReference: String,
    val proposalExpiresAt: String,
    val acceptedPaymentEndpointIdentifiers: List<String>,
    val metadataJson: String,
)

data class PaykitPrivateReceiverPathSelection(
    val linkableReceiverPaths: List<String>,
    val publishableReceiverPaths: List<String>,
    val cleanupProtectedReceiverPaths: List<String>,
    val error: Throwable?,
)

internal object PaykitReceiverPaths {
    const val WALLET = "bitkit/wallet"
    const val SERVER = "bitkit/server"

    /** Current Bitkit flows only route its own receivers; cross-wallet routing can broaden this allowlist. */
    val ordered = listOf(WALLET, SERVER)
    val supported = ordered.toSet()
}

@Singleton
@Suppress("TooManyFunctions", "LargeClass")
class PaykitSdkService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keychain: Keychain,
) {
    private val stateStore = PaykitSdkStateBlobStore(keychain)
    private val sessionProvider = PaykitSdkSessionProvider(keychain)
    private val paymentAdapter = PaykitSdkPaymentAdapter()
    private val pubkyClientConfig by lazy { paykitPubkyClientConfig() }
    private val handleMutex = Mutex()
    private val operationMutex = Mutex()
    private val setupMutex = Mutex()
    private var isSetup = CompletableDeferred<Unit>()
    private var setupFailed = false
    private var sdk: PaykitSdk? = null
    private var activeAuthRequest: PubkyAuthRequest? = null
    private val _backupStateVersion = MutableStateFlow(0L)
    val backupStateVersion: StateFlow<Long> = _backupStateVersion.asStateFlow()

    @Suppress("TooGenericExceptionCaught")
    suspend fun initialize() {
        setupMutex.withLock {
            if (isSetup.isCompleted && !setupFailed) return
            if (setupFailed) {
                isSetup = CompletableDeferred()
                setupFailed = false
            }

            try {
                PaykitAndroid.initializeOrThrow(context)
                operationMutex.withLock {
                    var handle = handle()
                    try {
                        handle.initialize()
                    } catch (e: PaykitException.Identity) {
                        if (!sessionProvider.canDeferStaleSession(e.context)) throw e

                        Logger.warn(
                            "Deferring stale Paykit session restoration until SDK setup completes",
                            e,
                            context = TAG,
                        )
                        sessionProvider.suspendStoredSessionAccess()
                        resetRuntime()
                        try {
                            handle = handle()
                            handle.initialize()
                        } finally {
                            sessionProvider.resumeStoredSessionAccess()
                        }
                    }
                    publishReceiverMarkerIfLiveSessionAvailable(handle)
                }
                isSetup.complete(Unit)
            } catch (t: Throwable) {
                setupFailed = true
                isSetup.completeExceptionally(t)
                throw t
            }
        }
    }

    suspend fun currentPublicKey(): String? {
        isSetup.await()
        return operationMutex.withLock {
            val handle = handle()
            handle.identityStatus()?.publicKey?.let { return@withLock it }
            handle.initialize().identity.publicKey
        }
    }

    suspend fun hasPrivatePaymentAccess(): Boolean {
        isSetup.await()
        return operationMutex.withLock {
            handle().identityStatus()?.liveSessionAvailable == true
        }
    }

    suspend fun importSession(
        secret: String,
        includeLocalSecret: Boolean = true,
    ): PubkySessionBootstrapResult {
        isSetup.await()
        val previousPublicKey = operationMutex.withLock { currentSdkStatePublicKeyLocked() }
        val result = bootstrap().importSession(
            sessionSecret = secret,
            localSecretKey = if (includeLocalSecret) sessionProvider.loadLocalSecretKey() else null,
            receiverNoiseSecretKey = sessionProvider.loadOrDeriveReceiverNoiseSecretKey(),
            requiredCapabilities = requiredSessionCapabilities(paykitSdkConfig()),
        )
        operationMutex.withLock {
            activateBootstrapResult(
                result = result,
                previousPublicKey = previousPublicKey,
                shouldStoreLocalSecret = includeLocalSecret,
            )
        }
        notifyBackupStateChanged()
        return result
    }

    suspend fun signUp(
        secretKeyHex: String,
        homeserverPublicKey: String,
        signupCode: String?,
    ): PubkySessionBootstrapResult {
        isSetup.await()
        val previousPublicKey = operationMutex.withLock { currentSdkStatePublicKeyLocked() }
        val result = bootstrap().signUp(
            localSecretKey = localSecretKey(secretKeyHex),
            receiverNoiseSecretKey = sessionProvider.loadOrDeriveReceiverNoiseSecretKey(),
            homeserverPublicKey = homeserverPublicKey,
            signupCode = signupCode,
            requiredCapabilities = requiredCapabilities(),
        )
        operationMutex.withLock {
            activateBootstrapResult(
                result = result,
                previousPublicKey = previousPublicKey,
                shouldStoreLocalSecret = true,
            )
        }
        notifyBackupStateChanged()
        return result
    }

    suspend fun registerIdentity(
        secretKeyHex: String,
        homeserverPublicKey: String,
        signupCode: String?,
    ): PubkySessionBootstrapResult {
        isSetup.await()
        return bootstrap().signUp(
            localSecretKey = localSecretKey(secretKeyHex),
            receiverNoiseSecretKey = sessionProvider.loadOrDeriveReceiverNoiseSecretKey(),
            homeserverPublicKey = homeserverPublicKey,
            signupCode = signupCode,
            requiredCapabilities = requiredCapabilities(),
        )
    }

    suspend fun activateRegisteredIdentity(result: PubkySessionBootstrapResult) {
        isSetup.await()
        val previousPublicKey = operationMutex.withLock { currentSdkStatePublicKeyLocked() }
        operationMutex.withLock {
            var activated = false
            try {
                activateBootstrapResult(
                    result = result,
                    previousPublicKey = previousPublicKey,
                    shouldStoreLocalSecret = true,
                )
                activated = true
            } finally {
                if (!activated) clearRegisteredIdentityActivationLocked()
            }
        }
        notifyBackupStateChanged()
    }

    suspend fun signIn(secretKeyHex: String): PubkySessionBootstrapResult {
        isSetup.await()
        val previousPublicKey = operationMutex.withLock { currentSdkStatePublicKeyLocked() }
        val result = bootstrap().signIn(
            localSecretKey = localSecretKey(secretKeyHex),
            receiverNoiseSecretKey = sessionProvider.loadOrDeriveReceiverNoiseSecretKey(),
            requiredCapabilities = requiredCapabilities(),
        )
        operationMutex.withLock {
            activateBootstrapResult(
                result = result,
                previousPublicKey = previousPublicKey,
                shouldStoreLocalSecret = true,
            )
        }
        notifyBackupStateChanged()
        return result
    }

    suspend fun startAuth(): String {
        isSetup.await()
        return operationMutex.withLock {
            val request = bootstrap().startSignInAuth(requiredCapabilities())
            activeAuthRequest = request
            request.authorizationUrl()
        }
    }

    suspend fun completeAuth(): PubkySessionBootstrapResult {
        isSetup.await()
        return operationMutex.withLock {
            val request = requireNotNull(activeAuthRequest) { "No active Pubky auth request" }
            val previousPublicKey = currentSdkStatePublicKeyLocked()
            var completed = false
            try {
                request.complete(
                    localSecretKey = null,
                    receiverNoiseSecretKey = sessionProvider.loadOrDeriveReceiverNoiseSecretKey(),
                    requiredCapabilities = requiredCapabilities(),
                ).also {
                    activateBootstrapResult(
                        result = it,
                        previousPublicKey = previousPublicKey,
                        shouldStoreLocalSecret = false,
                    )
                    notifyBackupStateChanged()
                    completed = true
                }
            } finally {
                activeAuthRequest = null
                if (!completed) resetRuntime()
            }
        }
    }

    suspend fun cancelAuth() {
        isSetup.await()
        operationMutex.withLock {
            activeAuthRequest = null
        }
    }

    suspend fun approveAuth(
        authUrl: String,
        expectedCapabilities: String,
        approvedClientId: String,
        secretKeyHex: String,
    ) {
        isSetup.await()
        approvalBootstrap(authUrl, approvedClientId).approveAuth(
            authUrl = authUrl,
            expectedCapabilities = expectedCapabilities,
            localSecretKey = localSecretKey(secretKeyHex),
        )
    }

    suspend fun approveAuthWithCompanionClaim(
        authUrl: String,
        expectedCapabilities: String,
        approvedClientId: String,
        secretKeyHex: String,
        claim: PubkyAuthCompanionClaim,
    ) {
        isSetup.await()
        approvalBootstrap(authUrl, approvedClientId).approveAuthWithCompanionClaim(
            authUrl = authUrl,
            expectedCapabilities = expectedCapabilities,
            localSecretKey = localSecretKey(secretKeyHex),
            claim = claim,
        )
    }

    suspend fun fetchFile(uri: String): ByteArray {
        isSetup.await()
        return operationMutex.withLock {
            handle().fetchPubkyFile(uri) ?: throw AppError("Pubky file not found")
        }
    }

    suspend fun publishPaykitProfile(profile: PaykitProfile): PaykitProfileRecord {
        isSetup.await()
        return operationMutex.withLock {
            handle().publishPaykitProfile(profile).also {
                notifyBackupStateChanged()
            }
        }
    }

    suspend fun uploadProfileAvatar(bytes: ByteArray, contentType: String): String {
        isSetup.await()
        return operationMutex.withLock {
            handle().uploadProfileAvatar(bytes, contentType).uri.also {
                notifyBackupStateChanged()
            }
        }
    }

    suspend fun deletePaykitProfile() {
        isSetup.await()
        operationMutex.withLock {
            handle().deletePaykitProfile()
            notifyBackupStateChanged()
        }
    }

    suspend fun fetchPubkyProfile(publicKey: String): PubkyProfile? {
        isSetup.await()
        return operationMutex.withLock {
            handle().fetchPubkyProfile(publicKey)?.profile
        }
    }

    suspend fun fetchPubkyFollows(publicKey: String): List<String> {
        isSetup.await()
        return operationMutex.withLock {
            handle().fetchPubkyFollows(publicKey)
        }
    }

    suspend fun contactRecords(): List<ContactRecord> {
        isSetup.await()
        return operationMutex.withLock {
            handle().contactRecords()
        }
    }

    suspend fun contactRecord(publicKey: String): ContactRecord? {
        isSetup.await()
        return operationMutex.withLock {
            handle().contactRecord(publicKey)
        }
    }

    suspend fun saveContact(
        publicKey: String,
        label: String?,
        receiverPaths: List<String>? = null,
    ): ContactRecord {
        isSetup.await()
        return operationMutex.withLock {
            withStateRevisionTracking { handle ->
                val existingPaths = handle.contactRecord(publicKey)?.receiverPaths.orEmpty()
                val contactPaths = mergedReceiverPaths(existingPaths + receiverPaths.orEmpty())
                handle.saveContact(ContactUpdate(publicKey, contactPaths, label))
            }
        }
    }

    suspend fun removeContact(publicKey: String): ContactRecord? {
        isSetup.await()
        return operationMutex.withLock {
            handle().removeContact(publicKey).also {
                notifyBackupStateChanged()
            }
        }
    }

    suspend fun resolveContactProfile(
        publicKey: String,
        allowPubkyProfileFallback: Boolean,
    ): ContactProfileResolution? {
        isSetup.await()
        return operationMutex.withLock {
            handle().resolveContactProfile(publicKey, PaykitReceiverPaths.WALLET, allowPubkyProfileFallback)
        }
    }

    suspend fun discoverRelevantReceiverPaths(publicKey: String): List<String> {
        isSetup.await()
        return operationMutex.withLock {
            val handle = handle()
            val discovered = handle.paykitReceiverPaths(publicKey)
                .filter { it in PaykitReceiverPaths.supported }
                .filter {
                    it == PaykitReceiverPaths.WALLET ||
                        handle.paykitReceiverMarker(publicKey, it)?.requiresPrivateLink() == true
                }
            mergedReceiverPaths(discovered)
        }
    }

    suspend fun privateReceiverPathSelection(
        publicKey: String,
        savedReceiverPaths: List<String>,
    ): PaykitPrivateReceiverPathSelection {
        isSetup.await()
        return operationMutex.withLock {
            val handle = handle()
            val linkable = mutableListOf<String>()
            val publishable = mutableListOf<String>()
            val cleanupProtected = mutableListOf<String>()
            var firstError: Throwable? = null

            mergedReceiverPaths(savedReceiverPaths).forEach { receiverPath ->
                runSuspendCatching { handle.paykitReceiverMarker(publicKey, receiverPath) }
                    .onSuccess { marker ->
                        if (marker?.requiresPrivateLink() == true) {
                            linkable += receiverPath
                        }
                        if (marker.canReceivePrivatePaymentDetails()) {
                            publishable += receiverPath
                        }
                    }
                    .onFailure {
                        cleanupProtected += receiverPath
                        firstError = firstError ?: it
                    }
            }

            PaykitPrivateReceiverPathSelection(
                linkableReceiverPaths = linkable,
                publishableReceiverPaths = publishable,
                cleanupProtectedReceiverPaths = cleanupProtected,
                error = firstError,
            )
        }
    }

    suspend fun syncLocalReceiverMarker(
        isDiscoverable: Boolean,
    ) {
        isSetup.await()
        operationMutex.withLock {
            withStateRevisionTracking { handle ->
                if (!isDiscoverable) {
                    handle.removePaykitReceiverMarker()
                    return@withStateRevisionTracking
                }

                handle.publishPaykitReceiverMarker(receiverCapabilities(handle))
            }
        }
    }

    suspend fun syncPublicEndpoints(endpoints: List<Endpoint>): EndpointSyncReport {
        isSetup.await()
        return operationMutex.withLock {
            withStateRevisionTracking { handle ->
                handle.syncPublicEndpointsWithReceivingDetails(endpoints.map { it.toPublicReceivingDetail() })
            }
        }
    }

    fun requiredCapabilities(): String = requiredSessionCapabilities(paykitSdkConfig())

    suspend fun syncPrivatePaymentListsWithReservations(
        updates: List<PrivatePaymentListReservationUpdateInput>,
        clearUnlistedLinkedPeers: Boolean,
    ): PrivatePaymentListDeliveryReport {
        isSetup.await()
        return operationMutex.withLock {
            withStateRevisionTracking { handle ->
                handle.syncPrivatePaymentListsWithReservationsAndProcessOutbound(
                    updates = updates,
                    clearUnlistedLinkedPeers = clearUnlistedLinkedPeers,
                )
            }
        }
    }

    suspend fun ensureLinkWithPeer(
        counterparty: String,
        receiverPath: String,
        maxAdvanceSteps: UInt = 8u,
    ) = run {
        isSetup.await()
        operationMutex.withLock {
            withStateRevisionTracking { handle ->
                handle.ensureLinkWithPeer(counterparty, receiverPath, maxAdvanceSteps)
            }
        }
    }

    suspend fun clearPrivatePaymentList(
        counterparty: String,
        receiverPath: String,
    ): PrivatePaymentListDeliveryReport {
        isSetup.await()
        return operationMutex.withLock {
            withStateRevisionTracking { handle ->
                handle.clearPrivatePaymentListAndProcessOutbound(counterparty, receiverPath)
            }
        }
    }

    suspend fun receivePrivateMessagesFromLinkedPeers(): List<PrivateStreamCounterpartyIntakeReport> {
        isSetup.await()
        return operationMutex.withLock {
            withStateRevisionTracking { handle ->
                handle.receivePrivateMessagesFromLinkedPeers()
            }
        }
    }

    suspend fun processPendingPrivateMessages(): List<OutboundPrivateCounterpartySendReport> {
        isSetup.await()
        return operationMutex.withLock {
            withStateRevisionTracking { handle ->
                handle.processPendingPrivateMessages()
            }
        }
    }

    suspend fun paymentRequests(): List<PaymentRequestRecord> {
        isSetup.await()
        return operationMutex.withLock {
            handle().listPaymentRequests(
                PaymentRequestFilter(
                    counterparty = null,
                    counterpartyReceiverPath = null,
                    localRole = null,
                    states = emptyList(),
                    recurring = null,
                    receivedOnly = false,
                )
            )
        }
    }

    suspend fun identityStatus(): IdentityStatus? {
        isSetup.await()
        return operationMutex.withLock {
            handle().identityStatus()
        }
    }

    suspend fun paymentRequestReceiverPaths(publicKey: String): List<String> {
        isSetup.await()
        return operationMutex.withLock {
            val handle = handle()
            handle.paykitReceiverPaths(publicKey)
                .filter { it in PaykitReceiverPaths.supported }
                .filter { handle.paykitReceiverMarker(publicKey, it)?.capabilities?.paymentRequests == true }
        }
    }

    suspend fun proposePaymentRequest(
        counterparty: String,
        counterpartyReceiverPath: String,
        proposal: PaykitPaymentRequestProposalTerms,
        expectedIdentity: String,
    ): PaymentRequestRecord {
        isSetup.await()
        return operationMutex.withLock {
            withStateRevisionTracking { handle ->
                val identityStatus = handle.identityStatus()
                check(
                    identityStatus?.liveSessionAvailable == true &&
                        PubkyPublicKeyFormat.matches(identityStatus.publicKey, expectedIdentity)
                ) { "Paykit identity changed before proposing the payment request" }
                val terms = PaymentRequestTerms(
                    amount = PaymentRequestAmount(proposal.amountValue, "btc"),
                    paymentReference = PaymentReference(proposal.paymentReference),
                    proposalExpiresAt = proposal.proposalExpiresAt,
                    recurrence = null,
                    acceptedPaymentEndpointIdentifiers = proposal.acceptedPaymentEndpointIdentifiers,
                    metadata = PrivateJsonObject(proposal.metadataJson),
                )
                handle.proposePaymentRequest(counterparty, counterpartyReceiverPath, terms)
            }
        }
    }

    suspend fun acceptPaymentRequest(
        counterparty: String,
        counterpartyReceiverPath: String,
        paymentRequestId: String,
    ): PaymentRequestRecord {
        isSetup.await()
        return operationMutex.withLock {
            withStateRevisionTracking { handle ->
                handle.acceptPaymentRequest(counterparty, counterpartyReceiverPath, paymentRequestId)
            }
        }
    }

    suspend fun submitPaymentProof(
        counterparty: String,
        counterpartyReceiverPath: String,
        paymentRequestId: String,
        paymentEndpointIdentifier: String,
        proofJson: String,
    ): PaymentRequestRecord {
        isSetup.await()
        return operationMutex.withLock {
            withStateRevisionTracking { handle ->
                handle.submitPaymentProof(
                    counterparty,
                    counterpartyReceiverPath,
                    paymentRequestId,
                    PaymentProofSubmission(
                        billingPeriod = null,
                        paymentEndpointIdentifier = paymentEndpointIdentifier,
                        proof = PrivateJsonObject(proofJson),
                    ),
                )
            }
        }
    }

    suspend fun rejectPaymentRequest(
        counterparty: String,
        counterpartyReceiverPath: String,
        paymentRequestId: String,
        reason: String? = null,
    ): PaymentRequestRecord {
        isSetup.await()
        return operationMutex.withLock {
            withStateRevisionTracking { handle ->
                handle.rejectPaymentRequest(counterparty, counterpartyReceiverPath, paymentRequestId, reason)
            }
        }
    }

    suspend fun linkedPeers(): List<LinkedPeerRecord> {
        isSetup.await()
        return operationMutex.withLock {
            handle().linkedPeers()
        }
    }

    suspend fun pendingOutboundPrivateCounterparties(): List<CounterpartyReceiver> {
        isSetup.await()
        return operationMutex.withLock {
            handle().pendingOutboundPrivateCounterparties()
        }
    }

    suspend fun prepareAndResolvePrivateContactPayment(
        counterparty: String,
        receiverPath: String,
        afterPrivatePaymentListVersion: ULong?,
        amount: PaymentAmountContext? = null,
    ): PaykitPreparedPrivateContactPayment {
        isSetup.await()
        val prepared = operationMutex.withLock {
            withStateRevisionTracking { handle ->
                handle.prepareAndResolvePrivateContactPayment(
                    counterparty = counterparty,
                    counterpartyReceiverPath = receiverPath,
                    amount = amount,
                    afterPrivatePaymentListVersion = afterPrivatePaymentListVersion,
                    maxAdvanceSteps = 8u,
                )
            }
        }
        return PaykitPreparedPrivateContactPayment(
            resolution = prepared.resolution.toPaykitPrivateContactPaymentResolution(),
            linkState = prepared.linkReport?.state,
        )
    }

    suspend fun resolvePublicContactPayment(
        counterparty: String,
        receiverPath: String,
    ): PaykitPublicContactPaymentResolution {
        isSetup.await()
        val resolution = operationMutex.withLock {
            handle().resolvePublicContactPayment(counterparty, receiverPath, amount = null)
        }
        return resolution.toPaykitPublicContactPaymentResolution()
    }

    private fun PrivateContactPaymentResolution.toPaykitPrivateContactPaymentResolution() =
        PaykitPrivateContactPaymentResolution(
            status = status,
            state = state,
            privatePaymentListVersion = privatePaymentListVersion,
            payableEndpoints = payableEndpoints.map {
                PaykitResolvedPaymentEndpoint(
                    identifier = it.identifier,
                    payload = it.target.payload.exportText(),
                )
            },
        )

    private fun PublicContactPaymentResolution.toPaykitPublicContactPaymentResolution() =
        PaykitPublicContactPaymentResolution(
            payableEndpoints = payableEndpoints.map {
                PaykitResolvedPaymentEndpoint(
                    identifier = it.identifier,
                    payload = it.target.payload.exportText(),
                )
            },
        )

    suspend fun exportBackupState(): String {
        isSetup.await()
        return operationMutex.withLock {
            handle().exportBackupString()
        }
    }

    suspend fun restoreBackupState(backup: String) {
        isSetup.await()
        operationMutex.withLock {
            withStateRevisionTracking { handle ->
                handle.restoreBackupString(backup)
            }
            resetRuntime()
        }
    }

    suspend fun signOut() {
        isSetup.await()
        operationMutex.withLock {
            withStateRevisionTracking { handle ->
                handle.signOut()
            }
            resetRuntime()
        }
    }

    suspend fun forgetSessionAccess() {
        isSetup.await()
        operationMutex.withLock {
            activeAuthRequest = null
            try {
                withStateRevisionTracking { handle ->
                    handle.forgetSessionAccess()
                }
            } finally {
                resetRuntime()
            }
        }
    }

    suspend fun clearState() {
        operationMutex.withLock {
            clearStateLocked()
        }
    }

    private suspend fun clearStateLocked() {
        keychain.delete(Keychain.Key.PAYKIT_SDK_STATE.name)
        activeAuthRequest = null
        resetRuntime()
        notifyBackupStateChanged()
    }

    private suspend fun currentSdkStatePublicKeyLocked(): String? {
        return runSuspendCatching { handle().identityStatus()?.publicKey }
            .getOrElse {
                keychain.delete(Keychain.Key.PAYKIT_SDK_STATE.name)
                resetRuntime()
                null
            }
    }

    private suspend fun persistSessionAccess(
        access: PubkySessionAccess,
        shouldStoreLocalSecret: Boolean,
    ) {
        keychain.upsertString(Keychain.Key.PAYKIT_SESSION.name, access.exportSessionSecret())
        sessionProvider.persistReceiverNoiseSecretKey(access.exportReceiverNoiseSecretKey())
        val localSecret = access.exportLocalSecretKey()
        if (shouldStoreLocalSecret && localSecret != null) {
            keychain.upsertString(Keychain.Key.PUBKY_SECRET_KEY.name, secretKeyHex(localSecret))
        } else {
            keychain.delete(Keychain.Key.PUBKY_SECRET_KEY.name)
        }
    }

    private suspend fun activateBootstrapResult(
        result: PubkySessionBootstrapResult,
        previousPublicKey: String?,
        shouldStoreLocalSecret: Boolean,
    ) {
        persistSessionAccess(result.sessionAccess, shouldStoreLocalSecret)
        sessionProvider.setLiveSessionAccess(result.sessionAccess)
        if (!PubkyPublicKeyFormat.matches(previousPublicKey, result.publicKey)) {
            keychain.delete(Keychain.Key.PAYKIT_SDK_STATE.name)
        }
        resetRuntime()
        val handle = handle()
        handle.initialize()
        publishReceiverMarkerIfLiveSessionAvailable(handle)
    }

    private suspend fun clearRegisteredIdentityActivationLocked() = withContext(NonCancellable) {
        runSuspendCatching { sessionProvider.clearSessionAccess() }
            .onFailure { Logger.warn("Failed to clear incomplete Pubky signup session", it, context = TAG) }
        runSuspendCatching { keychain.delete(Keychain.Key.PAYKIT_SDK_STATE.name) }
            .onFailure { Logger.warn("Failed to clear incomplete Pubky signup state", it, context = TAG) }
        resetRuntime()
        notifyBackupStateChanged()
    }

    private suspend fun publishReceiverMarkerIfLiveSessionAvailable(handle: PaykitSdk) {
        runSuspendCatching {
            val capabilities = receiverCapabilities(handle)
            if (capabilities.privatePayments) {
                handle.publishPaykitReceiverMarker(capabilities)
            }
        }.onFailure {
            Logger.warn("Failed to publish Paykit receiver marker", it, context = TAG)
        }
    }

    private suspend fun receiverCapabilities(handle: PaykitSdk): PaykitReceiverCapabilities {
        val hasPrivatePaymentAccess = handle.identityStatus()?.liveSessionAvailable == true
        return PaykitReceiverCapabilities(
            privatePayments = hasPrivatePaymentAccess,
            paymentRequests = hasPrivatePaymentAccess,
            receipts = false,
            outgoingPayments = true,
        )
    }

    private fun notifyBackupStateChanged() {
        _backupStateVersion.update { it + 1 }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> withStateRevisionTracking(block: suspend (PaykitSdk) -> T): T {
        val handle = handle()
        val previousRevision = runCatching { handle.stateRevision() }.getOrNull()
        return try {
            block(handle).also {
                notifyBackupStateChangedIfNeeded(previousRevision, handle)
            }
        } catch (error: Throwable) {
            notifyBackupStateChangedIfNeeded(previousRevision, handle)
            throw error
        }
    }

    private fun notifyBackupStateChangedIfNeeded(previousRevision: String?, handle: PaykitSdk) {
        val nextRevision = runCatching { handle.stateRevision() }.getOrNull()
        if (previousRevision != nextRevision) {
            notifyBackupStateChanged()
        }
    }

    private suspend fun handle(): PaykitSdk = handleMutex.withLock {
        sdk?.let { return@withLock it }
        PaykitSdk.withPaymentAdapterAndPubkyClientConfig(
            stateStore = stateStore,
            sessionProvider = sessionProvider,
            paymentAdapter = paymentAdapter,
            config = paykitSdkConfig(),
            pubkyClient = pubkyClientConfig,
        ).also { sdk = it }
    }

    private fun bootstrap() = PubkySessionBootstrap.withPubkyClientConfig(
        clientId = BitkitPaykitSdkConfig.clientId,
        pubkyClient = pubkyClientConfig,
    )

    private fun approvalBootstrap(authUrl: String, approvedClientId: String): PubkySessionBootstrap {
        val requestClientId = parsePubkyAuthUrl(authUrl).clientId.orEmpty()
        return PubkySessionBootstrap.withPubkyClientConfig(
            clientId = validatedApprovalClientId(requestClientId, approvedClientId),
            pubkyClient = pubkyClientConfig,
        )
    }

    private fun resetRuntime() {
        sdk = null
    }

    private fun mergedReceiverPaths(paths: List<String>): List<String> {
        return (listOf(PaykitReceiverPaths.WALLET) + paths)
            .filter { it in PaykitReceiverPaths.supported }
            .distinct()
    }

    private fun PaykitReceiverMarker.requiresPrivateLink(): Boolean =
        capabilities.privatePayments || capabilities.paymentRequests || capabilities.receipts

    private fun PaykitReceiverMarker?.canReceivePrivatePaymentDetails(): Boolean =
        this?.capabilities?.let { it.privatePayments && it.outgoingPayments } == true

    companion object {
        private const val TAG = "PaykitSdkService"

        fun localSecretKey(secretKeyHex: String): PubkyLocalSecretKey =
            PubkyLocalSecretKey(secretKeyHex.fromHex())

        fun secretKeyHex(secretKey: PubkyLocalSecretKey): String =
            secretKey.exportBytes().toHex()

        fun deriveSecretKey(mnemonic: String): String =
            secretKeyHex(pubkySecretKeyFromBip39Mnemonic(mnemonicPhrase = mnemonic))

        fun publicKeyFromSecret(secretKeyHex: String): String =
            pubkyPublicKeyFromSecret(localSecretKey(secretKeyHex))

        fun parseAuthUrl(authUrl: String) =
            parsePubkyAuthUrl(authUrl)
    }
}

internal object BitkitPaykitSdkConfig {
    val clientId: String
        get() = profileNamespace
    val profileNamespace: String
        get() = if (Env.network == Network.BITCOIN) "bitkit.to" else "staging.bitkit.to"
    val endpointManagementScope = PaykitSdkDefaults.DEFAULT_ENDPOINT_MANAGEMENT_SCOPE
    val encryptedLinkRecoveryMarkers = PaykitSdkDefaults.DEFAULT_ENCRYPTED_LINK_RECOVERY_MARKER_POLICY
    val publicContactSharing = PaykitSdkDefaults.DEFAULT_PUBLIC_CONTACT_SHARING_POLICY
}

internal fun paykitSdkConfig() = defaultConfig(PaykitReceiverPaths.WALLET).copy(
    profileNamespace = BitkitPaykitSdkConfig.profileNamespace,
    endpointManagementScope = BitkitPaykitSdkConfig.endpointManagementScope,
    encryptedLinkRecoveryMarkers = BitkitPaykitSdkConfig.encryptedLinkRecoveryMarkers,
    publicContactSharing = BitkitPaykitSdkConfig.publicContactSharing,
)

internal fun paykitPubkyClientConfig(
    isLocalE2eBackend: Boolean = Env.isLocalE2eBackend,
    localTestnetHost: String = Env.e2eLocalHost,
    baseConfig: PubkyClientConfig = defaultPubkyClientConfig(),
) =
    if (isLocalE2eBackend) {
        baseConfig.copy(
            localTestnetHost = localTestnetHost,
        )
    } else {
        baseConfig
    }

internal fun validatedApprovalClientId(requestClientId: String, approvedClientId: String): String {
    if (approvedClientId.isBlank() || approvedClientId != requestClientId) {
        throw PubkyAuthRequestError.RequesterChanged
    }
    return requestClientId
}

private class PaykitSdkStateBlobStore(
    private val keychain: Keychain,
) : SdkStateBlobStore {
    private val lock = Any()

    override fun loadStateBlob(): SdkStateBlobSnapshot? = synchronized(lock) {
        val data = keychain.accessBlocking {
            load(Keychain.Key.PAYKIT_SDK_STATE.name)
        } ?: return@synchronized null
        decodeSdkStateBlobSnapshot(data)
    }

    override fun saveStateBlobAtomically(
        blob: SdkStateBlob,
        expectedRevision: String?,
    ): String = synchronized(lock) {
        val currentRevision = keychain.accessBlocking {
            load(Keychain.Key.PAYKIT_SDK_STATE.name)
        }
            ?.let { decodeSdkStateBlobSnapshot(it).revision }
        if (currentRevision != expectedRevision) {
            throw PaykitException.Storage(
                code = "revision_conflict",
                context = "SDK state revision changed",
            )
        }

        val nextRevision = UUID.randomUUID().toString()
        val snapshot = SdkStateBlobSnapshot(blob = blob, revision = nextRevision)
        keychain.accessBlocking {
            upsert(Keychain.Key.PAYKIT_SDK_STATE.name, encodeSdkStateBlobSnapshot(snapshot))
        }
        nextRevision
    }
}

internal class PaykitSdkSessionProvider(
    private val keychain: Keychain,
) : SdkPubkySessionProvider {
    private val lock = Any()
    private val receiverNoiseKeyStore = PaykitReceiverNoiseKeyStore(keychain)
    private var liveSessionAccess: PubkySessionAccess? = null
    private var isStoredSessionAccessSuspended = false

    fun setLiveSessionAccess(access: PubkySessionAccess) = synchronized(lock) {
        liveSessionAccess = access
    }

    fun clearLiveSessionAccess() = synchronized(lock) {
        liveSessionAccess = null
    }

    override fun loadSessionAccess(): PubkySessionAccess? {
        return synchronized(lock) {
            if (isStoredSessionAccessSuspended) return null

            val sessionSecret = keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            liveSessionAccess
                ?.takeIf { it.exportSessionSecret() == sessionSecret }
                ?.let { return it }

            PubkySessionAccess(
                clientId = BitkitPaykitSdkConfig.clientId,
                sessionSecret = sessionSecret,
                localSecretKey = loadLocalSecretKey(),
                receiverNoiseSecretKey = loadOrDeriveReceiverNoiseSecretKey(),
            )
        }
    }

    override fun publicStorageAvailable(): Boolean = true

    fun hasSessionAccess(): Boolean =
        keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)?.isNotBlank() == true

    fun canDeferStaleSession(errorContext: String): Boolean =
        errorContext == STALE_SESSION_RESTORE_CONTEXT && hasSessionAccess()

    fun suspendStoredSessionAccess() = synchronized(lock) {
        liveSessionAccess = null
        isStoredSessionAccessSuspended = true
    }

    fun resumeStoredSessionAccess() = synchronized(lock) {
        isStoredSessionAccessSuspended = false
    }

    override fun clearSessionAccess() {
        clearLiveSessionAccess()
        keychain.accessBlocking {
            clearPubkySessionCredentials(::delete)
        }
    }

    private companion object {
        const val STALE_SESSION_RESTORE_CONTEXT = "restore Pubky grant session from platform provider"
    }

    fun loadLocalSecretKey(): PubkyLocalSecretKey? {
        val secretKeyHex = keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return PaykitSdkService.localSecretKey(secretKeyHex)
    }

    fun loadOrDeriveReceiverNoiseSecretKey(): ReceiverNoiseSecretKey =
        receiverNoiseKeyStore.loadOrDerive()

    fun persistReceiverNoiseSecretKey(key: ReceiverNoiseSecretKey) {
        receiverNoiseKeyStore.persist(key)
    }
}

internal object PaykitReceiverNoiseKeyDerivation {
    private const val DOMAIN = "bitkit/paykit/receiver-noise-key"
    private const val VERSION = "v1"

    fun deriveFromWalletSeed(
        mnemonic: String,
        passphrase: String?,
        network: String,
        receiverPath: String,
    ): ByteArray {
        val seed = mnemonicToSeed(mnemonic, passphrase?.takeIf { it.isNotEmpty() })
        return try {
            derive(seed, network, receiverPath)
        } finally {
            seed.fill(0)
        }
    }

    fun derive(seed: ByteArray, network: String, receiverPath: String): ByteArray {
        val salt = MessageDigest.getInstance("SHA-256").digest(DOMAIN.encodeToByteArray())
        val prk = hmacSha256(key = salt, data = seed)
        return try {
            val info = "$VERSION\u0000$network\u0000$receiverPath".encodeToByteArray() + byteArrayOf(0x01)
            hmacSha256(key = prk, data = info)
        } finally {
            prk.fill(0)
        }
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
}

internal fun clearPubkySessionCredentials(deleteKeychainValue: (String) -> Unit) {
    val sessionResult = runCatching { deleteKeychainValue(Keychain.Key.PAYKIT_SESSION.name) }
    val localSecretResult = runCatching { deleteKeychainValue(Keychain.Key.PUBKY_SECRET_KEY.name) }
    sessionResult.getOrThrow()
    localSecretResult.getOrThrow()
}

internal class PaykitReceiverNoiseKeyStore(
    private val loadBytes: () -> ByteArray?,
    private val upsertBytes: (ByteArray) -> Unit,
    private val deriveBytes: () -> ByteArray,
) {
    constructor(keychain: Keychain) : this(
        loadBytes = {
            keychain.accessBlocking {
                load(Keychain.Key.PAYKIT_RECEIVER_NOISE_SECRET_KEY.name)
            }
        },
        upsertBytes = { bytes ->
            keychain.accessBlocking {
                upsert(Keychain.Key.PAYKIT_RECEIVER_NOISE_SECRET_KEY.name, bytes)
            }
        },
        deriveBytes = {
            val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)
                ?: throw AppError("Mnemonic not found while deriving the Paykit receiver Noise key")
            val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)
            PaykitReceiverNoiseKeyDerivation.deriveFromWalletSeed(
                mnemonic = mnemonic,
                passphrase = passphrase,
                network = Env.network.name.lowercase(),
                receiverPath = PaykitReceiverPaths.WALLET,
            )
        },
    )

    @Synchronized
    fun loadOrDerive(): ReceiverNoiseSecretKey =
        ReceiverNoiseSecretKey(validatedKeyBytes().copyOf())

    @Synchronized
    fun persist(key: ReceiverNoiseSecretKey) {
        persistBytes(key.exportBytes())
    }

    @Synchronized
    internal fun loadOrDeriveBytes(): ByteArray =
        validatedKeyBytes().copyOf()

    @Synchronized
    internal fun persistBytes(bytes: ByteArray) {
        if (!validatedKeyBytes().contentEquals(bytes)) {
            throw AppError("Paykit receiver Noise key changed unexpectedly")
        }
    }

    private fun validatedKeyBytes(): ByteArray {
        loadBytes()?.let {
            checkKeyLength(it, "Stored Paykit receiver Noise key is invalid")
            return it
        }

        val derivedBytes = deriveBytes()
        checkKeyLength(derivedBytes, "Derived Paykit receiver Noise key is invalid")
        upsertBytes(derivedBytes.copyOf())

        return derivedBytes
    }

    private fun checkKeyLength(bytes: ByteArray, message: String) {
        if (bytes.size != RECEIVER_NOISE_KEY_LENGTH) throw AppError(message)
    }

    private companion object {
        const val RECEIVER_NOISE_KEY_LENGTH = 32
    }
}

class PaykitSdkPaymentAdapter : SdkPaymentAdapter {
    override fun currentPublicReceivingDetails(): List<PublicReceivingDetail> = emptyList()

    override fun currentPrivateReceivingDetails(
        counterparty: String,
        counterpartyReceiverPath: String,
    ): List<PrivateReceivingDetail> = emptyList()

    override fun reservePrivateReceivingDetails(
        counterparty: String,
        counterpartyReceiverPath: String,
    ): PrivateReceivingDetailReservationResponse =
        PrivateReceivingDetailReservationResponse(
            kind = PrivateReceivingDetailReservationResponseKind.USE_CURRENT_RECEIVING_DETAILS,
            reservations = emptyList(),
        )

    override fun cancelPrivateReceivingDetailReservation(
        cancellation: PrivatePaymentEndpointReservationCancellation,
    ) = Unit

    override fun selectPublicPaymentEndpointIds(request: PublicPaymentEndpointSelectionRequest): List<String> {
        val parsed = request.candidates.mapNotNull { candidate ->
            PublicPaykitRepo.parseEndpoint(
                methodId = candidate.identifier,
                endpointData = candidate.payload.exportText(),
            )?.let { candidate.candidateId to it }
        }
        return PublicPaykitRepo.payablePreferenceOrder.flatMap { methodId ->
            parsed.mapNotNull { (id, endpoint) -> id.takeIf { endpoint.methodId == methodId } }
        }
    }

    override fun buildPublicPaymentTarget(endpoint: PublicPaymentEndpointCandidate): PaymentTarget =
        PaymentTarget(endpoint.payload)

    override fun selectPrivatePaymentEndpointIds(request: PrivatePaymentEndpointSelectionRequest): List<String> {
        val parsed = request.candidates.mapNotNull { candidate ->
            PublicPaykitRepo.parseEndpoint(
                methodId = candidate.identifier,
                endpointData = candidate.payload.exportText(),
            )?.let { candidate.candidateId to it }
        }
        return PublicPaykitRepo.payablePreferenceOrder.flatMap { methodId ->
            parsed.mapNotNull { (id, endpoint) -> id.takeIf { endpoint.methodId == methodId } }
        }
    }

    override fun buildPrivatePaymentTarget(endpoint: PrivatePaymentEndpointCandidate): PaymentTarget =
        PaymentTarget(endpoint.payload)
}

private fun Endpoint.toPublicReceivingDetail() = PublicReceivingDetail(
    identifier = methodId.rawValue,
    payload = PaymentPayload(rawPayload),
)

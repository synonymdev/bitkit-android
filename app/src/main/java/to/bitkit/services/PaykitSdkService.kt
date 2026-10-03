package to.bitkit.services

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.synonym.paykit.ContactRecord
import com.synonym.paykit.ContactUpdate
import com.synonym.paykit.EndpointSyncReport
import com.synonym.paykit.IdentityStatus
import com.synonym.paykit.LinkedPeerRecord
import com.synonym.paykit.LinkedPeerState
import com.synonym.paykit.OutboundPrivateCounterpartySendReport
import com.synonym.paykit.PaykitAndroid
import com.synonym.paykit.PaykitAppCapabilities
import com.synonym.paykit.PaykitException
import com.synonym.paykit.PaykitIdentitySecretKey
import com.synonym.paykit.PaykitProfile
import com.synonym.paykit.PaykitProfileRecord
import com.synonym.paykit.PaykitSdk
import com.synonym.paykit.PaykitSdkDefaults
import com.synonym.paykit.PaymentAmountContext
import com.synonym.paykit.PaymentPayload
import com.synonym.paykit.PaymentProofSubmission
import com.synonym.paykit.PaymentReference
import com.synonym.paykit.PaymentRequestAmount
import com.synonym.paykit.PaymentRequestFilter
import com.synonym.paykit.PaymentRequestLifecycleState
import com.synonym.paykit.PaymentRequestLocalRole
import com.synonym.paykit.PaymentRequestRecord
import com.synonym.paykit.PaymentRequestRecurrence
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
import com.synonym.paykit.ProfileResolution
import com.synonym.paykit.PubkyAuthCompanionClaim
import com.synonym.paykit.PubkyClientConfig
import com.synonym.paykit.PubkyIdentityCapability
import com.synonym.paykit.PubkyLocalSecretKey
import com.synonym.paykit.PubkyProfile
import com.synonym.paykit.PubkySessionAccess
import com.synonym.paykit.PubkySessionBootstrap
import com.synonym.paykit.PubkySessionBootstrapResult
import com.synonym.paykit.PublicContactPaymentResolution
import com.synonym.paykit.PublicPaymentEndpointCandidate
import com.synonym.paykit.PublicPaymentEndpointSelectionRequest
import com.synonym.paykit.PublicReceivingDetail
import com.synonym.paykit.SdkPaymentAdapter
import com.synonym.paykit.SdkPubkySessionProvider
import com.synonym.paykit.defaultConfig
import com.synonym.paykit.defaultPubkyClientConfig
import com.synonym.paykit.parsePubkyAuthUrl
import com.synonym.paykit.paykitAuthorizerSessionCapabilities
import com.synonym.paykit.pubkyPublicKeyFromSecret
import com.synonym.paykit.pubkySecretKeyFromBip39Mnemonic
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lightningdevkit.ldknode.Network
import to.bitkit.async.BaseCoroutineScope
import to.bitkit.data.PubkyStore
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.sharedpubky.SharedPubkyClient
import to.bitkit.data.sharedpubky.SharedPubkyContract
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.fromHex
import to.bitkit.ext.nowMillis
import to.bitkit.ext.runSuspendCatching
import to.bitkit.ext.toHex
import to.bitkit.models.PubkyAuthClaim
import to.bitkit.models.PubkyAuthClaimCodec
import to.bitkit.models.PubkyAuthRequest
import to.bitkit.models.PubkyAuthRequestError
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.repositories.Endpoint
import to.bitkit.repositories.PaykitBillingPeriod
import to.bitkit.repositories.PaykitIssuerInterop
import to.bitkit.repositories.PubkyContactError
import to.bitkit.repositories.PublicPaykitRepo
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

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
    val appId: String,
    val identifier: String,
    val payload: String,
)

data class PaykitPaymentRequestProposalTerms(
    val amountValue: String,
    val paymentReference: String,
    val proposalExpiresAt: String,
    val acceptedPaymentEndpointIdentifiers: List<String>,
    val metadataJson: String,
    val recurrence: PaykitPaymentRequestRecurrenceTerms? = null,
)

data class PaykitPaymentRequestRecurrenceTerms(
    val every: UInt,
    val unit: String,
    val startsAt: String,
    val anchor: String,
    val endsAt: String? = null,
)

class PubkyFileNotFoundError : AppError("Pubky file not found")

/** Which public read slots a public Pubky read may use. */
enum class PaykitReadLane {
    /**
     * A read for what the user is looking at or waiting on; it takes only a shared read slot, and a freed read slot
     * goes to it before any waiting bulk read.
     */
    Interactive,

    /** A background read over many keys; it also takes a bulk slot, so bulk reads never fill every read slot. */
    Bulk,
}

/** A public Pubky read that ran out of the timeout its caller gave it once it held a read slot. */
class PaykitReadTimeoutError(timeout: Duration) : AppError("Public Pubky read timed out after $timeout")

private class TimedRead<T>(val value: T)

private class PaykitPublicReadSlots(slots: Int) {
    private val lock = Any()
    private var free = slots
    private val interactiveWaiters = ArrayDeque<CompletableDeferred<Unit>>()
    private val bulkWaiters = ArrayDeque<CompletableDeferred<Unit>>()

    suspend fun <T> withSlot(lane: PaykitReadLane, block: suspend () -> T): T {
        acquire(lane)
        return try {
            block()
        } finally {
            release()
        }
    }

    private suspend fun acquire(lane: PaykitReadLane) {
        val waiters = when (lane) {
            PaykitReadLane.Interactive -> interactiveWaiters
            PaykitReadLane.Bulk -> bulkWaiters
        }
        val slot = synchronized(lock) {
            if (free > 0) {
                free--
                return
            }
            CompletableDeferred<Unit>().also(waiters::addLast)
        }
        try {
            slot.await()
        } catch (error: CancellationException) {
            if (synchronized(lock) { !waiters.remove(slot) }) release()
            throw error
        }
    }

    private fun release() {
        val next = synchronized(lock) {
            (interactiveWaiters.removeFirstOrNull() ?: bulkWaiters.removeFirstOrNull()).also { if (it == null) free++ }
        }
        next?.complete(Unit)
    }
}

@Singleton
@Suppress("TooManyFunctions", "LargeClass")
class PaykitSdkService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keychain: Keychain,
    private val pubkyStore: PubkyStore,
    sharedPubky: SharedPubkyClient,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
    private val settingsStore: SettingsStore,
) : BaseCoroutineScope(ioDispatcher, TAG) {
    private val sessionProvider = PaykitSdkSessionProvider(keychain, sharedPubky)
    private val paymentAdapter = PaykitSdkPaymentAdapter()
    private val pubkyClientConfig by lazy { paykitPubkyClientConfig() }
    private var bootstrapFactory = {
        PubkySessionBootstrap.withPubkyClientConfig(
            clientId = BitkitPaykitSdkConfig.clientId,
            pubkyClient = pubkyClientConfig,
        )
    }
    private val cachedBootstrap by lazy { bootstrapFactory() }
    private val identityRepublishMutex = Mutex()

    @Volatile
    private var identityRepublishJob: Job? = null
    private var republishPublicKey: String? = null
    private var nextIdentityRepublishAt = 0L
    private var lastIdentityRepublishAt = 0L
    private val handleMutex = Mutex()
    private val operationLock = PaykitSdkOperationLock()
    private val publicReadSlots = PaykitPublicReadSlots(PUBLIC_READ_PERMITS)
    private val bulkReadPermits = Semaphore(BULK_READ_PERMITS)
    private val setupMutex = Mutex()
    private var isSetup = CompletableDeferred<Unit>()
    private var setupFailed = false
    private var platformInitializer: () -> Unit = { PaykitAndroid.initializeOrThrow(context) }

    @Volatile
    private var sdk: PaykitSdk? = null

    private var cachedPaykitKey: PaykitKeyGeneration? = null
    private var cachedBackupState: PaykitBackupStateSnapshot? = null
    private val _backupStateVersion = MutableStateFlow(0L)
    val backupStateVersion: StateFlow<Long> = _backupStateVersion.asStateFlow()
    private var sdkFactory: () -> PaykitSdk = {
        PaykitSdk.withPaymentAdapterAndPubkySharedStateAndClientConfig(
            sessionProvider = sessionProvider,
            paymentAdapter = paymentAdapter,
            config = paykitSdkConfig(),
            pubkyClient = pubkyClientConfig,
        )
    }

    @Suppress("LongParameterList")
    internal constructor(
        context: Context,
        keychain: Keychain,
        pubkyStore: PubkyStore,
        bootstrapFactory: (() -> PubkySessionBootstrap)? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        sharedPubky: SharedPubkyClient = SharedPubkyClient(context, ioDispatcher),
        platformInitializer: (() -> Unit)? = null,
        settingsStore: SettingsStore,
        sdkFactory: () -> PaykitSdk,
    ) : this(context, keychain, pubkyStore, sharedPubky, ioDispatcher, settingsStore) {
        this.sdkFactory = sdkFactory
        if (bootstrapFactory != null) this.bootstrapFactory = bootstrapFactory
        if (platformInitializer == null) {
            isSetup.complete(Unit)
        } else {
            this.platformInitializer = platformInitializer
        }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun initialize() {
        setupMutex.withLock {
            if (isSetup.isCompleted && !setupFailed) return
            if (setupFailed) {
                isSetup = CompletableDeferred()
                setupFailed = false
            }

            try {
                platformInitializer()
                launch { republishIdentityIfNeeded() }
                operationLock.withLock {
                    refreshPaykitKey(force = true)
                    var handle = handle()
                    try {
                        handle.initialize()
                    } catch (e: PaykitException.Identity) {
                        invalidatePaykitKeyIfNeeded(e)
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
                    publishAppIfLiveSessionAvailable(handle)
                }
                isSetup.complete(Unit)
            } catch (t: Throwable) {
                setupFailed = true
                isSetup.completeExceptionally(t)
                throw t
            }
        }
    }

    suspend fun republishIdentityIfNeeded(publicKey: String? = null, now: Long = nowMillis()) {
        val publication = launchIdentityRepublish(publicKey, now)
        withTimeoutOrNull(IDENTITY_REPUBLISH_WAIT_TIMEOUT) {
            publication.join()
            identityRepublishJob?.join()
            true
        } ?: Logger.debug("Continuing while Pubky identity publication is pending", context = TAG)
    }

    private suspend fun launchIdentityRepublish(publicKey: String?, now: Long = nowMillis()): Job {
        currentCoroutineContext().ensureActive()
        return launch {
            if (!identityRepublishMutex.tryLock()) return@launch
            identityRepublishJob = currentCoroutineContext().job
            try {
                withTimeoutOrNull(IDENTITY_REPUBLISH_TIMEOUT) {
                    runSuspendCatching {
                        if (!isSetup.isCompleted) PaykitAndroid.initializeOrThrow(context)
                        val key = publicKey ?: sessionProvider.loadLocalSecretKey()?.let(::pubkyPublicKeyFromSecret)
                        val identity = key?.let(PubkyPublicKeyFormat::normalized) ?: return@runSuspendCatching
                        if (
                            identity == republishPublicKey &&
                            now >= lastIdentityRepublishAt &&
                            now < nextIdentityRepublishAt
                        ) {
                            return@runSuspendCatching
                        }

                        republishPublicKey = identity
                        lastIdentityRepublishAt = now
                        nextIdentityRepublishAt = now + IDENTITY_REPUBLISH_RETRY_INTERVAL.inWholeMilliseconds
                        if (bootstrap().republishIdentity(identity)) {
                            nextIdentityRepublishAt = now + IDENTITY_REPUBLISH_INTERVAL.inWholeMilliseconds
                            Logger.debug("Republished Pubky identity", context = TAG)
                        } else {
                            Logger.debug("Found no Pubky identity record to republish", context = TAG)
                        }
                    }.onFailure { Logger.warn("Failed to republish Pubky identity", it, context = TAG) }
                } ?: Logger.warn("Timed out republishing Pubky identity", context = TAG)
            } finally {
                identityRepublishMutex.unlock()
            }
        }
    }

    /** Rebroadcasts the identity record when one exists. Returns false only when the network reports none. */
    suspend fun hasIdentityRecord(publicKey: String): Boolean {
        isSetup.await()
        return bootstrap().republishIdentity(publicKey)
    }

    suspend fun currentPublicKey(): String? {
        isSetup.await()
        return operationLock.withLock {
            withPaykitKey { handle ->
                handle.identityStatus()?.publicKey ?: handle.initialize().publicKey
            }
        }
    }

    suspend fun hasPrivatePaymentAccess(): Boolean {
        isSetup.await()
        return operationLock.withLock {
            withPaykitKey { it.identityStatus()?.capability == PubkyIdentityCapability.PRIVATE_LINK_CAPABLE }
        }
    }

    suspend fun importSession(secret: String): PubkySessionBootstrapResult {
        isSetup.await()
        return operationLock.withLock {
            val result = bootstrap().importSession(
                sessionSecret = secret,
                localSecretKey = sessionProvider.loadLocalSecretKey(),
                requiredCapabilities = paykitAuthorizerSessionCapabilities(),
            )

            activateBootstrapResult(
                result = result,
            )

            notifyBackupStateChanged()
            result
        }
    }

    suspend fun signUp(
        secretKeyHex: String,
        homeserverPublicKey: String,
        signupCode: String?,
    ): PubkySessionBootstrapResult {
        isSetup.await()
        return operationLock.withLock {
            val result = bootstrap().signUp(
                localSecretKey = localSecretKey(secretKeyHex),
                homeserverPublicKey = homeserverPublicKey,
                signupCode = signupCode,
                requiredCapabilities = requiredCapabilities(),
            )

            activateBootstrapResult(
                result = result,
            )

            notifyBackupStateChanged()
            result
        }
    }

    suspend fun registerIdentity(
        secretKeyHex: String,
        homeserverPublicKey: String,
        signupCode: String?,
    ): PubkySessionBootstrapResult {
        isSetup.await()
        return operationLock.withLock {
            bootstrap().signUp(
                localSecretKey = localSecretKey(secretKeyHex),
                homeserverPublicKey = homeserverPublicKey,
                signupCode = signupCode,
                requiredCapabilities = requiredCapabilities(),
            )
        }
    }

    suspend fun activateRegisteredIdentity(result: PubkySessionBootstrapResult) {
        isSetup.await()
        return operationLock.withLock {
            var activated = false
            try {
                activateBootstrapResult(
                    result = result,
                )
                activated = true
            } finally {
                if (!activated) clearRegisteredIdentityActivationLocked()
            }

            notifyBackupStateChanged()
        }
    }

    suspend fun signIn(secretKeyHex: String): PubkySessionBootstrapResult {
        isSetup.await()
        return operationLock.withLock {
            val result = bootstrap().signIn(
                localSecretKey = localSecretKey(secretKeyHex),
                requiredCapabilities = requiredCapabilities(),
            )

            activateBootstrapResult(
                result = result,
            )

            notifyBackupStateChanged()
            result
        }
    }

    suspend fun approveAuth(
        authUrl: String,
        expectedCapabilities: String,
        approvedClientId: String,
        secretKeyHex: String,
    ) {
        isSetup.await()
        return operationLock.withLock {
            approvalBootstrap(authUrl, approvedClientId).approveAuth(
                authUrl = authUrl,
                expectedCapabilities = expectedCapabilities,
                localSecretKey = localSecretKey(secretKeyHex),
            )
        }
    }

    suspend fun approveAuthWithCompanionClaim(
        authUrl: String,
        expectedCapabilities: String,
        approvedClientId: String,
        secretKeyHex: String,
        claim: PubkyAuthCompanionClaim,
    ) {
        isSetup.await()
        return operationLock.withLock {
            val requestedClaim = PubkyAuthRequest.parseBitkitClaim(authUrl, expectedCapabilities).getOrThrow()
                ?: throw PubkyAuthRequestError.MissingBitkitClaim
            require(
                claim.queryParameter == PubkyAuthClaim.QUERY_PARAMETER && claim.claimType == requestedClaim.wireValue
            ) {
                "Companion claim does not match the authorization request"
            }
            PubkyAuthClaimCodec.validateAccountPayload(requestedClaim, claim.unsignedPayload)
            val paykitKey = if (requestedClaim.includesPaykitAccess) paykitKey(localSecretKey(secretKeyHex)) else null
            approvalBootstrap(authUrl, approvedClientId).approveAuthWithCompanionClaim(
                authUrl = authUrl,
                expectedCapabilities = expectedCapabilities,
                localSecretKey = localSecretKey(secretKeyHex),
                claim = claim.copy(
                    unsignedPayload = PubkyAuthClaimCodec.encode(
                        claim = requestedClaim,
                        accountPayload = claim.unsignedPayload,
                        generation = paykitKey?.keyGeneration(),
                        secret = paykitKey?.exportBytes(),
                    ),
                ),
            )
        }
    }

    suspend fun fetchFile(uri: String, maxBytes: ULong): ByteArray =
        publicRead { it.fetchPubkyFileBounded(uri, maxBytes) } ?: throw PubkyFileNotFoundError()

    suspend fun publishPaykitProfile(profile: PaykitProfile): PaykitProfileRecord {
        isSetup.await()
        return operationLock.withLock {
            withPaykitKey { handle ->
                val publicKey = requireNotNull(handle.identityStatus()?.publicKey)
                val current = handle.fetchPaykitProfile(publicKey)
                handle.publishPaykitProfile(profile, current?.revision).also {
                    notifyBackupStateChanged()
                }
            }
        }
    }

    suspend fun uploadProfileAvatar(bytes: ByteArray, contentType: String, expectedIdentity: String? = null): String {
        isSetup.await()
        return operationLock.withLock {
            if (expectedIdentity != null) {
                val identityStatus = handle().identityStatus()
                check(
                    identityStatus?.capability == PubkyIdentityCapability.PRIVATE_LINK_CAPABLE &&
                        PubkyPublicKeyFormat.matches(identityStatus.publicKey, expectedIdentity)
                ) { "Paykit identity changed before uploading the subscription icon" }
            }
            handle().uploadProfileAvatar(bytes, contentType).uri.also {
                notifyBackupStateChanged()
            }
        }
    }

    suspend fun deletePaykitProfile() {
        isSetup.await()
        operationLock.withLock {
            withPaykitKey { handle ->
                val publicKey = requireNotNull(handle.identityStatus()?.publicKey)
                handle.fetchPaykitProfile(publicKey)?.let { handle.deletePaykitProfile(it.revision) }
                notifyBackupStateChanged()
            }
        }
    }

    suspend fun fetchPubkyProfile(publicKey: String): PubkyProfile? =
        publicRead { it.fetchPubkyProfile(publicKey) }?.profile

    suspend fun fetchPubkyFollows(publicKey: String): List<String> =
        publicRead { it.fetchPubkyFollows(publicKey, maxEntries = 10_000u) }

    suspend fun contactRecords(): List<ContactRecord> {
        isSetup.await()
        return operationLock.withLock {
            withPaykitKey { it.contactRecords() }
        }
    }

    suspend fun contactRecord(publicKey: String): ContactRecord? {
        isSetup.await()
        return operationLock.withLock {
            withPaykitKey { it.contactRecord(publicKey) }
        }
    }

    suspend fun saveContact(
        publicKey: String,
        label: String?,
        restorePrivateConnection: Boolean = false,
        expectedIdentity: String? = null,
    ): ContactRecord {
        isSetup.await()
        return operationLock.withLock {
            withStateRevisionTracking { handle ->
                if (expectedIdentity != null) {
                    check(PubkyPublicKeyFormat.matches(handle.identityStatus()?.publicKey, expectedIdentity)) {
                        "Paykit identity changed before saving the contact"
                    }
                }
                val existing = handle.contactRecord(publicKey)
                check(restorePrivateConnection || existing != null) { "Contact no longer exists" }
                val update = ContactUpdate(publicKey, label)
                if (!restorePrivateConnection) return@withStateRevisionTracking handle.saveContact(update)
                val blockedPeers = handle.linkedPeers().filter {
                    it.state == LinkedPeerState.BLOCKED && PubkyPublicKeyFormat.matches(it.counterparty, publicKey)
                }
                restorePrivateContact(handle, blockedPeers, update)
            }
        }
    }

    suspend fun removeContact(publicKey: String): ContactRecord? {
        isSetup.await()
        return operationLock.withLock {
            withStateRevisionTracking { handle ->
                val peers = handle.linkedPeers().filter {
                    PubkyPublicKeyFormat.matches(it.counterparty, publicKey)
                }
                val now = nowMillis()
                val hasActiveSubscription = handle.paymentRequests().any {
                    val endsAt = it.terms?.recurrence?.endsAt?.let { timestamp ->
                        runSuspendCatching { Instant.parse(timestamp).toEpochMilliseconds() }.getOrNull()
                    }
                    PubkyPublicKeyFormat.matches(it.counterparty, publicKey) &&
                        it.state == PaymentRequestLifecycleState.ACTIVE_RECURRING &&
                        (endsAt == null || endsAt > now)
                }
                if (hasActiveSubscription) throw PubkyContactError.ActiveSubscription
                peers.filter { it.state == LinkedPeerState.LINKED }.forEach {
                    runSuspendCatching {
                        val report = handle.clearPrivatePaymentListAndProcessOutbound(
                            publicKey,
                        )
                        if (report.failedToQueue.isNotEmpty() || report.failedToDeliver.isNotEmpty()) {
                            Logger.warn("Failed to withdraw private endpoints before contact deletion", context = TAG)
                        }
                    }.onFailure {
                        invalidatePaykitKeyIfNeeded(it)
                        Logger.warn("Failed to withdraw private endpoints before contact deletion", it, context = TAG)
                    }
                }
                peers.forEach { handle.blockPeer(publicKey) }
                handle.removeContact(publicKey)
            }
        }
    }

    suspend fun resolveContactProfile(
        publicKey: String,
        allowPubkyProfileFallback: Boolean,
        lane: PaykitReadLane = PaykitReadLane.Interactive,
        timeout: Duration? = null,
    ): ProfileResolution? = publicRead(lane, timeout) {
        it.resolveProfile(publicKey, allowPubkyProfileFallback)
    }

    suspend fun syncPaykitApp(privatePaymentsEnabled: Boolean) {
        isSetup.await()
        operationLock.withLock {
            withStateRevisionTracking { handle ->
                val capabilities = appCapabilities(handle)
                handle.publishPaykitApp(
                    displayName = "Bitkit",
                    capabilities = capabilities.copy(
                        privatePayments = capabilities.privatePayments && privatePaymentsEnabled
                    ),
                )
            }
        }
    }

    suspend fun syncPublicEndpoints(endpoints: List<Endpoint>): EndpointSyncReport {
        isSetup.await()
        return operationLock.withLock {
            withStateRevisionTracking { handle ->
                handle.syncPublicEndpointsWithReceivingDetails(endpoints.map { it.toPublicReceivingDetail() })
            }
        }
    }

    fun requiredCapabilities(): String = paykitAuthorizerSessionCapabilities()

    suspend fun syncPrivatePaymentListsWithReservations(
        updates: List<PrivatePaymentListReservationUpdateInput>,
        clearUnlistedLinkedPeers: Boolean,
    ): PrivatePaymentListDeliveryReport {
        isSetup.await()
        return operationLock.withLock {
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
        maxAdvanceSteps: UInt = 1u,
    ) = run {
        isSetup.await()
        operationLock.withLock {
            withStateRevisionTracking { handle ->
                handle.ensureLinkWithPeer(counterparty, maxAdvanceSteps)
            }
        }
    }

    suspend fun clearPrivatePaymentList(
        counterparty: String,
    ): PrivatePaymentListDeliveryReport? {
        isSetup.await()
        return operationLock.withLock {
            withStateRevisionTracking { handle ->
                if (
                    handle.linkedPeers().any {
                        it.state == LinkedPeerState.BLOCKED &&
                            PubkyPublicKeyFormat.matches(it.counterparty, counterparty)
                    }
                ) {
                    return@withStateRevisionTracking null
                }
                val publicKey = handle.identityStatus()?.publicKey
                if (publicKey != null) {
                    val app = handle.paykitAppRegistry(publicKey)?.apps?.find { it.appId == "bitkit" }
                    if (app?.capabilities?.privatePayments == false) return@withStateRevisionTracking null
                }
                handle.clearPrivatePaymentListAndProcessOutbound(counterparty)
            }
        }
    }

    suspend fun receivePrivateMessagesFromLinkedPeers(): List<PrivateStreamCounterpartyIntakeReport> {
        isSetup.await()
        return operationLock.withLock {
            withStateRevisionTracking { handle ->
                handle.receivePrivateMessagesFromLinkedPeers()
            }
        }
    }

    suspend fun receivePrivateMessages(counterparty: String) = run {
        isSetup.await()
        operationLock.withLock {
            withStateRevisionTracking { handle ->
                handle.receivePrivateMessages(counterparty)
            }
        }
    }

    suspend fun processOutboundPrivateMessages(counterparty: String) = run {
        isSetup.await()
        operationLock.withLock {
            withStateRevisionTracking { handle ->
                handle.processOutboundPrivateMessages(counterparty)
            }
        }
    }

    suspend fun processPendingPrivateMessages(): List<OutboundPrivateCounterpartySendReport> {
        isSetup.await()
        return operationLock.withLock {
            withStateRevisionTracking { handle ->
                handle.processPendingPrivateMessages()
            }
        }
    }

    suspend fun paymentRequests(): List<PaymentRequestRecord> = allPaymentRequests().filter(::isBitkitPaymentRequest)

    suspend fun allPaymentRequests(expectedIdentity: String? = null): List<PaymentRequestRecord> {
        isSetup.await()
        return operationLock.withLock {
            withPaykitKey { handle ->
                if (expectedIdentity != null) {
                    check(PubkyPublicKeyFormat.matches(handle.identityStatus()?.publicKey, expectedIdentity)) {
                        "Payment Request identity changed"
                    }
                }
                handle.listPaymentRequests(
                    PaymentRequestFilter(
                        counterparty = null,
                        localRole = null,
                        states = emptyList(),
                        recurring = null,
                        receivedOnly = false,
                    ),
                )
            }
        }
    }

    suspend fun identityStatus(): IdentityStatus? {
        isSetup.await()
        return operationLock.withLock {
            withPaykitKey { it.identityStatus() }
        }
    }

    /** Returns null on timeout or runtime replacement; public reads do not hold the mutation lock. */
    suspend fun canReceivePaymentRequests(
        publicKey: String,
        lane: PaykitReadLane = PaykitReadLane.Interactive,
    ): Boolean? = publicRead(lane) { handle ->
        val result = withTimeoutOrNull(PAYMENT_REQUEST_DISCOVERY_TIMEOUT) {
            handle.paykitAppRegistry(publicKey)?.apps?.any {
                it.capabilities.paymentRequests && it.capabilities.outgoingPayments
            } == true
        }
        result.takeIf { sdk === handle }
    }

    suspend fun proposePaymentRequest(
        counterparty: String,
        proposal: PaykitPaymentRequestProposalTerms,
        expectedIdentity: String,
    ): PaymentRequestRecord {
        isSetup.await()
        return operationLock.withLock {
            withStateRevisionTracking { handle ->
                val identityStatus = handle.identityStatus()
                check(
                    identityStatus?.capability == PubkyIdentityCapability.PRIVATE_LINK_CAPABLE &&
                        PubkyPublicKeyFormat.matches(identityStatus.publicKey, expectedIdentity)
                ) { "Paykit identity changed before proposing the payment request" }
                val terms = PaymentRequestTerms(
                    amount = PaymentRequestAmount(proposal.amountValue, PaykitIssuerInterop.BITCOIN_ASSET),
                    paymentReference = PaymentReference(proposal.paymentReference),
                    proposalExpiresAt = proposal.proposalExpiresAt,
                    recurrence = proposal.recurrence?.let {
                        PaymentRequestRecurrence(
                            every = it.every,
                            unit = it.unit,
                            startsAt = it.startsAt,
                            anchor = it.anchor,
                            endsAt = it.endsAt,
                        )
                    },
                    acceptedPaymentEndpointIdentifiers = proposal.acceptedPaymentEndpointIdentifiers,
                    paymentEndpoints = null,
                    requiredAppId = "bitkit",
                    conversion = null,
                    paymentDeadline = null,
                    metadata = PrivateJsonObject(proposal.metadataJson),
                )
                handle.proposePaymentRequest(counterparty, terms)
            }
        }
    }

    suspend fun acceptPaymentRequest(
        counterparty: String,
        paymentRequestId: String,
    ): PaymentRequestRecord {
        isSetup.await()
        return operationLock.withLock {
            withStateRevisionTracking { handle ->
                handle.claimPaymentRequestForExecution(counterparty, paymentRequestId)
                handle.acceptPaymentRequest(counterparty, paymentRequestId)
            }
        }
    }

    suspend fun claimPaymentRequestForExecution(counterparty: String, paymentRequestId: String): PaymentRequestRecord {
        isSetup.await()
        return operationLock.withLock {
            withStateRevisionTracking { it.claimPaymentRequestForExecution(counterparty, paymentRequestId) }
        }
    }

    @Suppress("LongParameterList")
    suspend fun submitPaymentProof(
        counterparty: String,
        paymentRequestId: String,
        paymentAppId: String,
        paymentEndpointIdentifier: String,
        proofJson: String,
        billingPeriod: PaykitBillingPeriod? = null,
    ): PaymentRequestRecord {
        isSetup.await()
        return operationLock.withLock {
            withStateRevisionTracking { handle ->
                handle.submitPaymentProof(
                    counterparty,
                    paymentRequestId,
                    PaymentProofSubmission(
                        billingPeriod = billingPeriod?.sdkValue,
                        paymentAppId = paymentAppId,
                        paymentEndpointIdentifier = paymentEndpointIdentifier,
                        allowanceId = null,
                        conversionQuoteId = null,
                        proof = PrivateJsonObject(proofJson),
                    ),
                )
            }
        }
    }

    suspend fun rejectPaymentRequest(
        counterparty: String,
        paymentRequestId: String,
        reason: String? = null,
    ): PaymentRequestRecord {
        isSetup.await()
        return operationLock.withLock {
            withStateRevisionTracking { handle ->
                handle.rejectPaymentRequest(counterparty, paymentRequestId, reason)
            }
        }
    }

    suspend fun cancelPaymentRequest(
        counterparty: String,
        paymentRequestId: String,
        reason: String? = null,
    ): PaymentRequestRecord {
        isSetup.await()
        return operationLock.withLock {
            withStateRevisionTracking { handle ->
                handle.cancelPaymentRequest(counterparty, paymentRequestId, reason)
            }
        }
    }

    suspend fun linkedPeers(): List<LinkedPeerRecord> {
        isSetup.await()
        return operationLock.withLock {
            withPaykitKey { it.linkedPeers() }
        }
    }

    suspend fun pendingOutboundPrivateCounterparties(): List<String> {
        isSetup.await()
        return operationLock.withLock {
            withPaykitKey { it.pendingOutboundPrivateCounterparties() }
        }
    }

    suspend fun prepareAndResolvePrivateContactPayment(
        counterparty: String,
        afterPrivatePaymentListVersion: ULong?,
        amount: PaymentAmountContext? = null,
    ): PaykitPreparedPrivateContactPayment {
        isSetup.await()
        val prepared = operationLock.withLock {
            withStateRevisionTracking { handle ->
                handle.prepareAndResolvePrivateContactPayment(
                    counterparty = counterparty,
                    amount = amount,
                    afterPrivatePaymentListVersion = afterPrivatePaymentListVersion,
                    maxAdvanceSteps = 1u,
                )
            }
        }
        return PaykitPreparedPrivateContactPayment(
            resolution = prepared.resolution.toPaykitPrivateContactPaymentResolution(),
            linkState = prepared.linkReport?.state,
        )
    }

    suspend fun prepareAndResolvePrivatePaymentRequest(
        counterparty: String,
        paymentRequestId: String,
        afterPrivatePaymentListVersion: ULong?,
    ): PaykitPreparedPrivateContactPayment {
        isSetup.await()
        val prepared = operationLock.withLock {
            withStateRevisionTracking {
                it.prepareAndResolvePrivatePaymentRequest(
                    counterparty,
                    paymentRequestId,
                    afterPrivatePaymentListVersion,
                    1u,
                )
            }
        }
        return PaykitPreparedPrivateContactPayment(
            prepared.resolution.toPaykitPrivateContactPaymentResolution(),
            prepared.linkReport?.state,
        )
    }

    suspend fun resolvePublicContactPayment(
        counterparty: String,
    ): PaykitPublicContactPaymentResolution {
        isSetup.await()
        val resolution = operationLock.withLock {
            handle().resolvePublicContactPayment(counterparty, amount = null)
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
                    appId = it.appId,
                    identifier = it.identifier,
                    payload = it.target.payload.exportText(),
                )
            },
        )

    private fun PublicContactPaymentResolution.toPaykitPublicContactPaymentResolution() =
        PaykitPublicContactPaymentResolution(
            payableEndpoints = payableEndpoints.map {
                PaykitResolvedPaymentEndpoint(
                    appId = it.appId,
                    identifier = it.identifier,
                    payload = it.target.payload.exportText(),
                )
            },
        )

    suspend fun exportBackupState(): String {
        isSetup.await()
        return operationLock.withLock {
            withPaykitKey { it.exportBackupString() }
        }
    }

    suspend fun retainRecoveryBackup(backup: String) {
        keychain.upsertString(Keychain.Key.PAYKIT_RECOVERY_BACKUP.name, backup)
    }

    suspend fun signOut() {
        isSetup.await()
        operationLock.withLock {
            withStateRevisionTracking { handle ->
                handle.signOut()
            }
            resetRuntime()
        }
    }

    suspend fun clearSessionAccess() = operationLock.withLock { clearRegisteredIdentityActivationLocked() }

    suspend fun forgetSessionAccess() {
        isSetup.await()
        operationLock.withLock {
            try {
                handle().forgetSessionAccess()
                notifyBackupStateChanged()
            } finally {
                resetRuntime()
            }
        }
    }

    suspend fun <T> withWalletWipe(operation: suspend () -> T): T {
        // Drain setup before closing admission, so cleanup cannot await setup queued behind its own barrier.
        runSuspendCatching { initialize() }
            .onFailure { Logger.warn("Failed to initialize Paykit before wallet wipe", it, context = TAG) }
        return operationLock.withWalletWipe {
            resetRuntime()
            try {
                operation()
            } finally {
                sessionProvider.clearLiveSessionAccess()
                resetRuntime()
            }
        }
    }

    suspend fun clearState() {
        operationLock.withLock {
            resetRuntime()
            notifyBackupStateChanged()
        }
    }

    private suspend fun refreshPaykitKey(force: Boolean = false) {
        if (force) cachedPaykitKey = null
        val root = sessionProvider.loadLocalSecretKey() ?: run {
            cachedPaykitKey = null
            return
        }
        val publicKey = pubkyPublicKeyFromSecret(root)
        val savedGeneration = keychain.loadString("${Keychain.Key.PAYKIT_KEY_GENERATION.name}:$publicKey")?.toULong()
        val cached = cachedPaykitKey
        if (cached != null && cached.publicKey == publicKey && cached.generation == savedGeneration) return
        cachedPaykitKey = null
        val key = paykitKey(root)
        sessionProvider.setPaykitIdentitySecretKey(key)
        cachedPaykitKey = PaykitKeyGeneration(publicKey, key.keyGeneration())
    }

    @VisibleForTesting
    internal suspend fun paykitKeyForAuthorization(secretKeyHex: String): PaykitIdentitySecretKey {
        isSetup.await()
        return operationLock.withLock { paykitKey(localSecretKey(secretKeyHex)) }
    }

    private suspend fun paykitKey(root: PubkyLocalSecretKey): PaykitIdentitySecretKey {
        val publicKey = pubkyPublicKeyFromSecret(root)
        val generation = handle().paykitAppRegistry(publicKey)?.keyGeneration ?: 1uL
        val storageKey = "${Keychain.Key.PAYKIT_KEY_GENERATION.name}:$publicKey"
        val saved = keychain.loadString(storageKey)?.toULong()
        check(generation >= (saved ?: 1uL)) { "The Paykit App Registry has an older key generation" }
        if (saved != generation) keychain.upsertString(storageKey, generation.toString())
        return root.derivePaykitIdentitySecretKey(generation)
    }

    private suspend fun persistSessionAccess(access: PubkySessionAccess) {
        keychain.upsertString(Keychain.Key.PAYKIT_SESSION.name, access.exportSessionSecret())
        val localSecret = access.exportLocalSecretKey()
        if (localSecret != null && sessionProvider.adoptedPubky() == null) {
            keychain.upsertString(Keychain.Key.PUBKY_SECRET_KEY.name, secretKeyHex(localSecret))
        } else {
            keychain.delete(Keychain.Key.PUBKY_SECRET_KEY.name)
        }
    }

    private suspend fun activateBootstrapResult(
        result: PubkySessionBootstrapResult,
    ) {
        persistSessionAccess(result.sessionAccess)
        sessionProvider.setLiveSessionAccess(result.sessionAccess)
        val previousOwner = pubkyStore.data.first().ownerPublicKey
        if (previousOwner != null && !PubkyPublicKeyFormat.matches(previousOwner, result.publicKey)) {
            pubkyStore.reset()
        }
        resetRuntime()
        refreshPaykitKey()
        val handle = handle()
        handle.initialize()
        if (result.sessionAccess.exportLocalSecretKey() != null) {
            handle.publishPaykitNoiseKeyAuthorization()
        }
        publishAppIfLiveSessionAvailable(handle)
        launchIdentityRepublish(publicKey = result.publicKey)
    }

    private suspend fun clearRegisteredIdentityActivationLocked() = withContext(NonCancellable) {
        runSuspendCatching { sessionProvider.clearSessionAccess() }
            .onFailure { Logger.warn("Failed to clear incomplete Pubky signup session", it, context = TAG) }
        resetRuntime()
        notifyBackupStateChanged()
    }

    private suspend fun publishAppIfLiveSessionAvailable(handle: PaykitSdk) {
        runSuspendCatching {
            val capabilities = appCapabilities(handle)
            if (capabilities.privatePayments) {
                handle.publishPaykitApp(
                    "Bitkit",
                    capabilities.copy(privatePayments = settingsStore.data.first().sharesPrivatePaykitEndpoints),
                )
            }
        }.onFailure {
            invalidatePaykitKeyIfNeeded(it)
            Logger.warn("Failed to publish Paykit app", it, context = TAG)
        }
    }

    private suspend fun appCapabilities(handle: PaykitSdk): PaykitAppCapabilities {
        val hasPrivatePaymentAccess =
            handle.identityStatus()?.capability == PubkyIdentityCapability.PRIVATE_LINK_CAPABLE
        return PaykitAppCapabilities(
            privatePayments = hasPrivatePaymentAccess,
            paymentRequests = hasPrivatePaymentAccess,
            receipts = false,
            outgoingPayments = true,
        )
    }

    private fun notifyBackupStateChanged() {
        _backupStateVersion.update { it + 1 }
    }

    private suspend fun restorePrivateContact(
        handle: PaykitSdk,
        blockedPeers: List<LinkedPeerRecord>,
        update: ContactUpdate,
    ): ContactRecord {
        var failure: Throwable? = null
        return try {
            runSuspendCatching {
                blockedPeers.forEach { handle.unblockPeer(it.counterparty) }
                handle.saveContact(update)
            }.onFailure { failure = it }.getOrThrow()
        } catch (error: CancellationException) {
            failure = error
            throw error
        } finally {
            failure?.let { restorationError ->
                withContext(NonCancellable) {
                    blockedPeers.forEach { peer ->
                        runSuspendCatching {
                            handle.blockPeer(peer.counterparty)
                        }.onFailure {
                            invalidatePaykitKeyIfNeeded(it)
                            restorationError.addSuppressed(it)
                        }
                    }
                }
            }
        }
    }

    private suspend fun <T> withPaykitKey(block: suspend (PaykitSdk) -> T): T {
        refreshPaykitKey()
        return runSuspendCatching { block(handle()) }
            .onFailure(::invalidatePaykitKeyIfNeeded)
            .getOrThrow()
    }

    private fun invalidatePaykitKeyIfNeeded(error: Throwable) {
        if (error !is PaykitException.Identity) return
        cachedPaykitKey = null
        cachedBackupState = null
    }

    private suspend fun <T> withStateRevisionTracking(block: suspend (PaykitSdk) -> T): T = withPaykitKey { handle ->
        withPaykitBackupStateTracking(
            readRevision = {
                runSuspendCatching { handle.backupStateRevision() }
                    .onFailure(::invalidatePaykitKeyIfNeeded)
                    .getOrThrow()
            },
            readStateRevision = { handle.stateRevision() },
            cachedSnapshot = cachedBackupState,
            onSnapshot = { cachedBackupState = it },
            onChange = ::notifyBackupStateChanged,
        ) {
            block(handle)
        }
    }

    private suspend fun handle(): PaykitSdk = handleMutex.withLock {
        sdk?.let { return@withLock it }
        sdkFactory().also { sdk = it }
    }

    /**
     * Runs [block] on the SDK instance without [operationLock]. [block] may only call unauthenticated public
     * Pubky reads, never session, secret, state-blob or publishing APIs. Without an instance it builds one under
     * [operationLock], because building one outside it would race [resetRuntime] and a wallet wipe, but reads
     * under its read slot. Like a locked call, a read that starts during a wallet wipe is rejected, and one that the
     * wipe overtakes fails instead of returning its result across the wipe. A [PaykitReadLane.Bulk] read takes a
     * bulk permit before its read slot, always in that order, so bulk reads hold at most [BULK_READ_PERMITS] read
     * slots and the rest stay free for interactive reads. A freed read slot goes to the oldest waiting interactive
     * read before any waiting bulk read, also one already queued for a slot, and reads of one lane start in the order
     * they asked for a slot. A [timeout] limits only the time [block] runs once the read holds its read slot, so
     * waiting for a slot never counts; a read that runs out is cancelled, gives back its slots and fails with
     * [PaykitReadTimeoutError].
     */
    private suspend fun <T> publicRead(
        lane: PaykitReadLane = PaykitReadLane.Interactive,
        timeout: Duration? = null,
        block: suspend (PaykitSdk) -> T,
    ): T {
        isSetup.await()
        return operationLock.withoutLock {
            val existing = sdk ?: operationLock.withLock { handle() }
            val read: suspend () -> T = { withReadTimeout(timeout) { block(existing) } }
            when (lane) {
                PaykitReadLane.Interactive -> publicReadSlots.withSlot(lane, read)
                PaykitReadLane.Bulk -> bulkReadPermits.withPermit { publicReadSlots.withSlot(lane, read) }
            }
        }
    }

    private suspend fun <T> withReadTimeout(timeout: Duration?, block: suspend () -> T): T {
        if (timeout == null) return block()
        val read = withTimeoutOrNull(timeout) { TimedRead(block()) } ?: throw PaykitReadTimeoutError(timeout)
        return read.value
    }

    private fun bootstrap() = cachedBootstrap

    private fun approvalBootstrap(authUrl: String, approvedClientId: String): PubkySessionBootstrap {
        val requestClientId = parsePubkyAuthUrl(authUrl).clientId.orEmpty()
        return PubkySessionBootstrap.withPubkyClientConfig(
            clientId = validatedApprovalClientId(requestClientId, approvedClientId),
            pubkyClient = pubkyClientConfig,
        )
    }

    private fun resetRuntime() {
        sdk = null
        cachedPaykitKey = null
        cachedBackupState = null
    }

    companion object {
        private const val TAG = "PaykitSdkService"

        /** Minimum delay between successful identity republications. */
        private val IDENTITY_REPUBLISH_INTERVAL = 30.minutes

        /** Minimum delay before retrying missing records or failed publication. */
        private val IDENTITY_REPUBLISH_RETRY_INTERVAL = 1.minutes

        /** Maximum duration of an identity publication attempt. */
        private val IDENTITY_REPUBLISH_TIMEOUT = 30.seconds

        /** Maximum time identity maintenance may delay its caller. */
        private val IDENTITY_REPUBLISH_WAIT_TIMEOUT = 5.seconds

        /** Maximum duration of a public payment-request capability lookup. */
        private val PAYMENT_REQUEST_DISCOVERY_TIMEOUT = 5.seconds

        /** Maximum concurrent public Pubky reads that run outside the operation lock. */
        private const val PUBLIC_READ_PERMITS = 6

        /** Maximum concurrent bulk public reads, which leaves the other read permits to interactive reads. */
        private const val BULK_READ_PERMITS = 4

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

internal fun isBitkitPaymentRequest(record: PaymentRequestRecord): Boolean = when (record.localRole) {
    PaymentRequestLocalRole.PAYEE -> record.proposalAppId == "bitkit"
    PaymentRequestLocalRole.PAYER -> record.executionClaimAppId?.let { it == "bitkit" } ?: when (record.state) {
        PaymentRequestLifecycleState.ACTIVE_RECURRING -> true
        PaymentRequestLifecycleState.ACCEPTED if record.paymentProofs.isEmpty() -> true
        else -> record.payerAppId == null || record.payerAppId == "bitkit"
    }
    else -> false
}

private data class PaykitKeyGeneration(val publicKey: String, val generation: ULong)

internal data class PaykitBackupStateSnapshot(val stateRevision: String, val backupRevision: String)

@Suppress("LongParameterList")
internal suspend fun <T> withPaykitBackupStateTracking(
    readRevision: suspend () -> String,
    readStateRevision: () -> String? = { null },
    cachedSnapshot: PaykitBackupStateSnapshot? = null,
    onSnapshot: (PaykitBackupStateSnapshot?) -> Unit = {},
    onChange: () -> Unit,
    operation: suspend () -> T,
): T {
    val observedStateRevision = runSuspendCatching { readStateRevision() }.getOrNull()
    val previousRevision = if (cachedSnapshot != null && observedStateRevision == cachedSnapshot.stateRevision) {
        cachedSnapshot.backupRevision
    } else {
        runSuspendCatching { readRevision() }.getOrNull()
    }
    val previousStateRevision = runSuspendCatching { readStateRevision() }.getOrNull()
    var succeeded = false
    return try {
        operation().also { succeeded = true }
    } finally {
        withContext(NonCancellable) {
            val nextStateRevision = runSuspendCatching { readStateRevision() }.getOrNull()
            val unchangedRevision = previousStateRevision?.takeIf { it == nextStateRevision }
            if (succeeded && unchangedRevision != null && previousRevision != null) {
                onSnapshot(PaykitBackupStateSnapshot(unchangedRevision, previousRevision))
                return@withContext
            }
            val nextRevision = runSuspendCatching { readRevision() }.getOrNull()
            val stateRevision = runSuspendCatching { readStateRevision() }.getOrNull()
            onSnapshot(
                if (succeeded && stateRevision != null && nextRevision != null) {
                    PaykitBackupStateSnapshot(stateRevision, nextRevision)
                } else {
                    null
                },
            )
            if (previousRevision == null || nextRevision == null || previousRevision != nextRevision) {
                onChange()
            }
        }
    }
}

internal object BitkitPaykitSdkConfig {
    val clientId: String
        get() = if (Env.network == Network.BITCOIN) "bitkit.to" else "staging.bitkit.to"
    val publicContactSharing = PaykitSdkDefaults.DEFAULT_PUBLIC_CONTACT_SHARING_POLICY
}

internal fun paykitSdkConfig() = defaultConfig("bitkit").copy(
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

internal class PaykitSdkSessionProvider(
    private val keychain: Keychain,
    private val sharedPubky: SharedPubkyClient,
) : SdkPubkySessionProvider {
    private val lock = Any()
    private var paykitIdentitySecretKey: PaykitIdentitySecretKey? = null
    private var liveSessionAccess: PubkySessionAccess? = null
    private var isStoredSessionAccessSuspended = false

    fun setLiveSessionAccess(access: PubkySessionAccess) = synchronized(lock) {
        liveSessionAccess = access
        paykitIdentitySecretKey = access.exportPaykitIdentitySecretKey()
    }

    fun clearLiveSessionAccess() = synchronized(lock) {
        liveSessionAccess = null
        paykitIdentitySecretKey = null
    }

    override fun loadSessionAccess(): PubkySessionAccess? = paykitStorageCallback("session_load_failed") {
        synchronized(lock) {
            if (isStoredSessionAccessSuspended) return@synchronized null

            val sessionSecret = keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)
                ?.takeIf { it.isNotBlank() }
                ?: return@synchronized null
            liveSessionAccess
                ?.takeIf { it.exportSessionSecret() == sessionSecret }
                ?.let { return@synchronized it }

            PubkySessionAccess(
                clientId = BitkitPaykitSdkConfig.clientId,
                sessionSecret = sessionSecret,
                localSecretKey = loadLocalSecretKey(),
                paykitIdentitySecretKey = paykitIdentitySecretKey,
            ).also { liveSessionAccess = it }
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

    override fun clearSessionAccess() = paykitStorageCallback("session_clear_failed") {
        clearLiveSessionAccess()
        keychain.accessBlocking {
            delete(Keychain.Key.SHARED_PUBKY_SOURCE.name)
            clearPubkySessionCredentials(::delete)
        }
    }

    private companion object {
        const val STALE_SESSION_RESTORE_CONTEXT = "restore Pubky grant session from platform provider"
    }

    fun adoptedPubky(): String? = keychain.loadString(Keychain.Key.SHARED_PUBKY_SOURCE.name)
        ?.substringAfter(SharedPubkyContract.RING_SOURCE_PREFIX, "")
        ?.takeIf { it.isNotBlank() }

    fun loadLocalSecretKey(): PubkyLocalSecretKey? {
        val secretKeyHex = keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)
            ?.takeIf { it.isNotBlank() }
            ?: adoptedPubky()?.let { sharedPubky.readCredential(it) }
            ?: return null
        return PaykitSdkService.localSecretKey(secretKeyHex)
    }

    fun setPaykitIdentitySecretKey(key: PaykitIdentitySecretKey) = synchronized(lock) {
        if (paykitIdentitySecretKey?.keyGeneration() != key.keyGeneration()) {
            liveSessionAccess = null
        }
        paykitIdentitySecretKey = key
    }
}

internal fun clearPubkySessionCredentials(deleteKeychainValue: (String) -> Unit) {
    val sessionResult = runCatching { deleteKeychainValue(Keychain.Key.PAYKIT_SESSION.name) }
    val localSecretResult = runCatching { deleteKeychainValue(Keychain.Key.PUBKY_SECRET_KEY.name) }
    sessionResult.getOrThrow()
    localSecretResult.getOrThrow()
}

class PaykitSdkPaymentAdapter : SdkPaymentAdapter {
    override fun currentPublicReceivingDetails(): List<PublicReceivingDetail> = emptyList()

    override fun currentPrivateReceivingDetails(
        counterparty: String,
    ): List<PrivateReceivingDetail> = emptyList()

    override fun reservePrivateReceivingDetails(
        counterparty: String,
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

internal fun <T> paykitStorageCallback(code: String, operation: () -> T): T =
    runCatching(operation).getOrElse {
        if (it is PaykitException) throw it
        throw PaykitException.Storage(code = code, context = "Platform Paykit storage operation failed")
    }

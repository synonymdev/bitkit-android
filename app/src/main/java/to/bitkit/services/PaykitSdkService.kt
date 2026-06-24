package to.bitkit.services

import android.content.Context
import com.synonym.paykit.ContactPaymentResolution
import com.synonym.paykit.ContactProfileResolution
import com.synonym.paykit.ContactRecord
import com.synonym.paykit.ContactUpdate
import com.synonym.paykit.EndpointSyncReport
import com.synonym.paykit.IdentityStatus
import com.synonym.paykit.PaykitAndroid
import com.synonym.paykit.PaykitException
import com.synonym.paykit.PaykitProfile
import com.synonym.paykit.PaykitProfileRecord
import com.synonym.paykit.PaykitSdk
import com.synonym.paykit.PaykitSdkDefaults
import com.synonym.paykit.PaymentEndpointCandidate
import com.synonym.paykit.PaymentEndpointReservationCancellation
import com.synonym.paykit.PaymentEndpointSelectionRequest
import com.synonym.paykit.PaymentEndpointSource
import com.synonym.paykit.PaymentPayload
import com.synonym.paykit.PaymentTarget
import com.synonym.paykit.PrivatePaymentListDeliveryReport
import com.synonym.paykit.PrivatePaymentListReservationUpdateInput
import com.synonym.paykit.PubkyAuthRequest
import com.synonym.paykit.PubkyLocalSecretKey
import com.synonym.paykit.PubkyProfile
import com.synonym.paykit.PubkySessionAccess
import com.synonym.paykit.PubkySessionBootstrap
import com.synonym.paykit.PubkySessionBootstrapResult
import com.synonym.paykit.ReceivingDetail
import com.synonym.paykit.ReceivingDetailReservationResponse
import com.synonym.paykit.ReceivingDetailReservationResponseKind
import com.synonym.paykit.ReceivingDetailScope
import com.synonym.paykit.SdkPaymentAdapter
import com.synonym.paykit.SdkPubkySessionProvider
import com.synonym.paykit.SdkStateBlob
import com.synonym.paykit.SdkStateBlobSnapshot
import com.synonym.paykit.SdkStateBlobStore
import com.synonym.paykit.decodeSdkStateBlobSnapshot
import com.synonym.paykit.defaultConfig
import com.synonym.paykit.derivePubkySecretKey
import com.synonym.paykit.encodeSdkStateBlobSnapshot
import com.synonym.paykit.parsePubkyAuthUrl
import com.synonym.paykit.pubkyPublicKeyFromSecret
import com.synonym.paykit.requiredSessionCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.lightningdevkit.ldknode.Network
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.ext.fromHex
import to.bitkit.ext.toHex
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.repositories.Endpoint
import to.bitkit.repositories.PublicPaykitRepo
import to.bitkit.utils.AppError
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class PaykitContactPaymentResolution(
    val payableEndpoints: List<PaykitResolvedPaymentEndpoint>,
)

data class PaykitResolvedPaymentEndpoint(
    val counterparty: String,
    val source: PaymentEndpointSource,
    val identifier: String,
    val payload: String,
)

@Singleton
@Suppress("TooManyFunctions")
class PaykitSdkService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keychain: Keychain,
) {
    private val stateStore = PaykitSdkStateBlobStore(keychain)
    private val sessionProvider = PaykitSdkSessionProvider(keychain)
    private val paymentAdapter = PaykitSdkPaymentAdapter()
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
                    handle().initialize()
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

    suspend fun identityStatus(): IdentityStatus? {
        isSetup.await()
        return operationMutex.withLock {
            handle().identityStatus()
        }
    }

    suspend fun importSession(
        secret: String,
        includeLocalSecret: Boolean = true,
    ): PubkySessionBootstrapResult {
        val previousPublicKey = operationMutex.withLock { currentSdkStatePublicKeyLocked() }
        val result = PubkySessionBootstrap().importSession(
            sessionSecret = secret,
            localSecretKey = if (includeLocalSecret) sessionProvider.loadLocalSecretKey() else null,
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
        val previousPublicKey = operationMutex.withLock { currentSdkStatePublicKeyLocked() }
        val result = PubkySessionBootstrap().signUp(
            localSecretKey = localSecretKey(secretKeyHex),
            homeserverPublicKey = homeserverPublicKey,
            signupCode = signupCode,
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

    suspend fun signIn(secretKeyHex: String): PubkySessionBootstrapResult {
        val previousPublicKey = operationMutex.withLock { currentSdkStatePublicKeyLocked() }
        val result = PubkySessionBootstrap().signIn(localSecretKey(secretKeyHex))
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
            val request = PubkySessionBootstrap().startSignInAuth(requiredCapabilities())
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

    suspend fun approveAuth(authUrl: String, secretKeyHex: String) {
        isSetup.await()
        PubkySessionBootstrap().approveAuth(
            authUrl = authUrl,
            expectedCapabilities = requiredCapabilities(),
            localSecretKey = localSecretKey(secretKeyHex),
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

    suspend fun saveContact(publicKey: String, label: String?): ContactRecord {
        isSetup.await()
        return operationMutex.withLock {
            handle().saveContact(ContactUpdate(publicKey, label)).also {
                notifyBackupStateChanged()
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
            handle().resolveContactProfile(publicKey, allowPubkyProfileFallback)
        }
    }

    suspend fun syncPublicEndpoints(endpoints: List<Endpoint>): EndpointSyncReport {
        isSetup.await()
        return operationMutex.withLock {
            withStateRevisionTracking { handle ->
                handle.syncPublicEndpointsWithReceivingDetails(endpoints.map { it.toReceivingDetail() })
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

    suspend fun ensureLinkWithPeer(counterparty: String, maxAdvanceSteps: UInt = 8u) = run {
        isSetup.await()
        operationMutex.withLock {
            withStateRevisionTracking { handle ->
                handle.ensureLinkWithPeer(counterparty, maxAdvanceSteps)
            }
        }
    }

    suspend fun clearPrivatePaymentList(counterparty: String): PrivatePaymentListDeliveryReport {
        isSetup.await()
        return operationMutex.withLock {
            withStateRevisionTracking { handle ->
                handle.clearPrivatePaymentListAndProcessOutbound(counterparty)
            }
        }
    }

    suspend fun receivePrivateMessagesFromLinkedPeers() {
        isSetup.await()
        operationMutex.withLock {
            withStateRevisionTracking { handle ->
                handle.receivePrivateMessagesFromLinkedPeers()
            }
        }
    }

    suspend fun processPendingPrivateMessages() {
        isSetup.await()
        operationMutex.withLock {
            withStateRevisionTracking { handle ->
                handle.processPendingPrivateMessages()
            }
        }
    }

    suspend fun prepareAndResolveContactPayment(
        counterparty: String,
        includePublicEndpoints: Boolean,
    ): PaykitContactPaymentResolution {
        isSetup.await()
        val prepared = operationMutex.withLock {
            withStateRevisionTracking { handle ->
                handle.prepareAndResolveContactPayment(
                    counterparty = counterparty,
                    amount = null,
                    includePublicEndpoints = includePublicEndpoints,
                    maxAdvanceSteps = 8u,
                )
            }
        }
        return prepared.resolution.toPaykitContactPaymentResolution()
    }

    suspend fun resolvePublicContactPayment(counterparty: String): PaykitContactPaymentResolution {
        isSetup.await()
        val resolution = operationMutex.withLock {
            handle().resolvePublicContactPayment(counterparty, amount = null)
        }
        return resolution.toPaykitContactPaymentResolution()
    }

    private fun ContactPaymentResolution.toPaykitContactPaymentResolution(): PaykitContactPaymentResolution {
        return PaykitContactPaymentResolution(
            payableEndpoints = payableEndpoints.map {
                PaykitResolvedPaymentEndpoint(
                    counterparty = it.counterparty,
                    source = it.source,
                    identifier = it.identifier,
                    payload = it.target.payload.exportText(),
                )
            },
        )
    }

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

    suspend fun forceSignOut() {
        operationMutex.withLock {
            clearSessionAccessLocked()
            clearStateLocked()
        }
    }

    suspend fun clearSessionAccess() {
        operationMutex.withLock {
            clearSessionAccessLocked()
            notifyBackupStateChanged()
        }
    }

    private suspend fun clearSessionAccessLocked() {
        sessionProvider.clearLiveSessionAccess()
        keychain.delete(Keychain.Key.PAYKIT_SESSION.name)
        keychain.delete(Keychain.Key.PUBKY_SECRET_KEY.name)
        activeAuthRequest = null
        resetRuntime()
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
        return runCatching { handle().identityStatus()?.publicKey }
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
        handle().initialize()
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
        PaykitSdk.withPaymentAdapter(
            stateStore = stateStore,
            sessionProvider = sessionProvider,
            paymentAdapter = paymentAdapter,
            config = paykitSdkConfig(),
        ).also { sdk = it }
    }

    private fun resetRuntime() {
        sdk = null
    }

    companion object {
        fun localSecretKey(secretKeyHex: String): PubkyLocalSecretKey =
            PubkyLocalSecretKey(secretKeyHex.fromHex())

        fun secretKeyHex(secretKey: PubkyLocalSecretKey): String =
            secretKey.exportBytes().toHex()

        private const val PUBKY_DERIVATION_RUNTIME_LABEL = "bitkit"

        fun deriveSecretKey(seed: ByteArray): String =
            secretKeyHex(derivePubkySecretKey(seed = seed, runtimeLabel = PUBKY_DERIVATION_RUNTIME_LABEL))

        fun publicKeyFromSecret(secretKeyHex: String): String =
            pubkyPublicKeyFromSecret(localSecretKey(secretKeyHex))

        fun parseAuthUrl(authUrl: String) =
            parsePubkyAuthUrl(authUrl)
    }
}

internal object BitkitPaykitSdkConfig {
    val profileNamespace: String
        get() = if (Env.network == Network.BITCOIN) "bitkit.to" else "staging.bitkit.to"
    val endpointManagementScope = PaykitSdkDefaults.DEFAULT_ENDPOINT_MANAGEMENT_SCOPE
    val encryptedLinkRecoveryMarkers = PaykitSdkDefaults.DEFAULT_ENCRYPTED_LINK_RECOVERY_MARKER_POLICY
    val publicContactSharing = PaykitSdkDefaults.DEFAULT_PUBLIC_CONTACT_SHARING_POLICY
}

internal fun paykitSdkConfig() = defaultConfig().copy(
    profileNamespace = BitkitPaykitSdkConfig.profileNamespace,
    endpointManagementScope = BitkitPaykitSdkConfig.endpointManagementScope,
    encryptedLinkRecoveryMarkers = BitkitPaykitSdkConfig.encryptedLinkRecoveryMarkers,
    publicContactSharing = BitkitPaykitSdkConfig.publicContactSharing,
)

private class PaykitSdkStateBlobStore(
    private val keychain: Keychain,
) : SdkStateBlobStore {
    private val lock = Any()

    override fun loadStateBlob(): SdkStateBlobSnapshot? = synchronized(lock) {
        val data = keychain.load(Keychain.Key.PAYKIT_SDK_STATE.name) ?: return@synchronized null
        decodeSdkStateBlobSnapshot(data)
    }

    override fun saveStateBlobAtomically(
        blob: SdkStateBlob,
        expectedRevision: String?,
    ): String = synchronized(lock) {
        val currentRevision = keychain.load(Keychain.Key.PAYKIT_SDK_STATE.name)
            ?.let { decodeSdkStateBlobSnapshot(it).revision }
        if (currentRevision != expectedRevision) {
            throw PaykitException.Storage(
                code = "revision_conflict",
                context = "SDK state revision changed",
            )
        }

        val nextRevision = UUID.randomUUID().toString()
        val snapshot = SdkStateBlobSnapshot(blob = blob, revision = nextRevision)
        keychain.upsert(Keychain.Key.PAYKIT_SDK_STATE.name, encodeSdkStateBlobSnapshot(snapshot))
        nextRevision
    }
}

private class PaykitSdkSessionProvider(
    private val keychain: Keychain,
) : SdkPubkySessionProvider {
    private val lock = Any()
    private var liveSessionAccess: PubkySessionAccess? = null

    fun setLiveSessionAccess(access: PubkySessionAccess) = synchronized(lock) {
        liveSessionAccess = access
    }

    fun clearLiveSessionAccess() = synchronized(lock) {
        liveSessionAccess = null
    }

    override fun loadSessionAccess(): PubkySessionAccess? {
        val sessionSecret = keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        synchronized(lock) {
            liveSessionAccess
                ?.takeIf { it.exportSessionSecret() == sessionSecret }
                ?.let { return it }
        }

        return PubkySessionAccess(
            sessionSecret = sessionSecret,
            localSecretKey = loadLocalSecretKey(),
        )
    }

    override fun publicStorageAvailable(): Boolean = true

    override fun clearSessionAccess() {
        runBlocking {
            clearLiveSessionAccess()
            keychain.delete(Keychain.Key.PAYKIT_SESSION.name)
            keychain.delete(Keychain.Key.PUBKY_SECRET_KEY.name)
        }
    }

    fun loadLocalSecretKey(): PubkyLocalSecretKey? {
        val secretKeyHex = keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return PaykitSdkService.localSecretKey(secretKeyHex)
    }
}

class PaykitSdkPaymentAdapter : SdkPaymentAdapter {
    override fun currentReceivingDetails(scope: ReceivingDetailScope): List<ReceivingDetail> = emptyList()

    override fun reserveReceivingDetails(counterparty: String): ReceivingDetailReservationResponse =
        ReceivingDetailReservationResponse(
            kind = ReceivingDetailReservationResponseKind.USE_CURRENT_RECEIVING_DETAILS,
            reservations = emptyList(),
        )

    override fun cancelReceivingDetailReservation(cancellation: PaymentEndpointReservationCancellation) = Unit

    override fun selectPaymentEndpointIds(request: PaymentEndpointSelectionRequest): List<String> {
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

    override fun buildPaymentTarget(endpoint: PaymentEndpointCandidate): PaymentTarget =
        PaymentTarget(endpoint.payload)
}

private fun Endpoint.toReceivingDetail() = ReceivingDetail(
    identifier = methodId.rawValue,
    payload = PaymentPayload(rawPayload),
)

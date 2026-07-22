package to.bitkit.repositories

import androidx.annotation.VisibleForTesting
import com.synonym.bitkitcore.Scanner
import com.synonym.bitkitcore.validateBitcoinAddress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.Network
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.runSuspendCatching
import to.bitkit.ext.toHex
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.models.toLdkNetwork
import to.bitkit.services.CoreService
import to.bitkit.services.PaykitReceiverPaths
import to.bitkit.services.PaykitSdkService
import to.bitkit.utils.AppError
import to.bitkit.utils.NetworkValidationHelper
import to.bitkit.utils.encodeToUrl
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import to.bitkit.di.json as appJson

sealed class PublicPaykitError(message: String) : AppError(message) {
    data object InvalidPayload : PublicPaykitError("Invalid Paykit payment endpoint payload")
    data object NoSupportedEndpoint : PublicPaykitError("No supported public payment endpoint is available")
    data object RouteHintsUnavailable : PublicPaykitError("Reachable Lightning payment endpoint is not available yet")
    data object SessionNotActive : PublicPaykitError("No active Paykit session")
    data object WalletNotReady : PublicPaykitError("Wallet is not ready to publish Paykit endpoints")
    data object PublicationFailed : PublicPaykitError("Failed to publish Paykit payment endpoints")
}

sealed interface PublicPaykitPaymentResult {
    data class Opened(
        val paymentRequest: String,
        val privatePaymentContext: PrivatePaykitPaymentContext? = null,
    ) : PublicPaykitPaymentResult

    data object NoEndpoint : PublicPaykitPaymentResult
    data object NotOpened : PublicPaykitPaymentResult
    data object WaitingForUpdatedPaymentList : PublicPaykitPaymentResult
}

data class PrivatePaykitPaymentContext(
    val receiverPath: String,
    val paymentListVersion: ULong,
)

@OptIn(ExperimentalTime::class)
@Suppress("LongParameterList")
@Singleton
class PublicPaykitRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val pubkyRepo: PubkyRepo,
    private val walletRepo: WalletRepo,
    private val lightningRepo: LightningRepo,
    private val coreService: CoreService,
    private val paykitSdkService: PaykitSdkService,
    private val settingsStore: SettingsStore,
    private val clock: Clock,
) {
    companion object {
        private val methodIdPattern = Regex("^[a-z0-9]+-[a-z0-9]+-[a-z0-9]+$")

        private val payloadJson = Json(appJson) {
            prettyPrint = false
            isLenient = false
            encodeDefaults = false
        }

        internal val payablePreferenceOrder = listOf(
            MethodId.Bolt11,
            MethodId.Lnurl,
            MethodId.P2tr,
            MethodId.P2wpkh,
            MethodId.P2sh,
            MethodId.P2pkh,
        )

        private val publicBolt11Expiry = 24.hours
        private val publicBolt11RefreshWindow = 30.minutes

        @VisibleForTesting
        internal var lightningRouteHintsValidator: ((String) -> Boolean)? = null

        fun isLightningPaymentOptionEnabled(settings: SettingsData): Boolean =
            settings.publicPaykitLightningEnabled

        fun isOnchainPaymentOptionEnabled(settings: SettingsData): Boolean =
            settings.publicPaykitOnchainEnabled

        fun parseEndpoint(methodId: String, endpointData: String): Endpoint? {
            if (!methodIdPattern.matches(methodId)) return null

            val knownMethodId = MethodId.fromRawValue(methodId) ?: return null
            val payload = runCatching {
                payloadJson.decodeFromString<PaymentEndpointPayload>(endpointData)
            }.getOrNull() ?: return null
            val value = payload.value.trim()
            if (value.isEmpty()) return null

            return Endpoint(
                methodId = knownMethodId,
                value = value,
                min = payload.min,
                max = payload.max,
                rawPayload = endpointData,
            )
        }

        fun serializePayload(value: String): String {
            val trimmedValue = value.trim()
            if (trimmedValue.isEmpty()) throw PublicPaykitError.InvalidPayload
            return payloadJson.encodeToString(PaymentEndpointPayload(value = trimmedValue))
        }

        fun hasLightningRouteHints(bolt11: String): Boolean =
            lightningRouteHintsValidator?.invoke(bolt11)
                ?: runCatching {
                    Bolt11Invoice.fromStr(bolt11).routeHints().any { it.isNotEmpty() }
                }.getOrDefault(false)

        fun paymentRequest(endpoints: List<Endpoint>): String {
            val sortedEndpoints = endpoints.sortedBy { payablePreferenceOrder.indexOf(it.methodId) }
            val lightning = sortedEndpoints.firstOrNull { it.methodId == MethodId.Bolt11 }
            val onchain = sortedEndpoints.firstOrNull { it.methodId.isOnchain }

            if (lightning != null && onchain != null) {
                return "bitcoin:${onchain.value}?lightning=${lightning.value.encodeToUrl()}"
            }

            return sortedEndpoints.firstOrNull()?.paymentRequest.orEmpty()
        }

        fun onchainMethodId(address: String): MethodId {
            val normalizedAddress = address.lowercase(Locale.US)
            return when {
                normalizedAddress.startsWith("bc1p") ||
                    normalizedAddress.startsWith("tb1p") ||
                    normalizedAddress.startsWith("bcrt1p") ->
                    MethodId.P2tr
                normalizedAddress.startsWith("bc1q") ||
                    normalizedAddress.startsWith("tb1q") ||
                    normalizedAddress.startsWith("bcrt1q") ->
                    MethodId.P2wpkh
                normalizedAddress.startsWith("3") || normalizedAddress.startsWith("2") -> MethodId.P2sh
                else -> MethodId.P2pkh
            }
        }
    }

    private val publishMutex = Mutex()

    suspend fun beginPayment(publicKey: String): Result<PublicPaykitPaymentResult> = withContext(ioDispatcher) {
        runSuspendCatching {
            val endpoints = fetchPublicEndpoints(publicKey).getOrThrow()
            if (endpoints.isEmpty()) return@runSuspendCatching PublicPaykitPaymentResult.NoEndpoint

            val payable = endpoints.filter { isPayable(it) }
            if (payable.isEmpty()) return@runSuspendCatching PublicPaykitPaymentResult.NotOpened

            PublicPaykitPaymentResult.Opened(paymentRequest(payable))
        }
    }

    suspend fun hasPayablePublicEndpoint(publicKey: String): Result<Boolean> = withContext(ioDispatcher) {
        runSuspendCatching {
            fetchPublicEndpoints(publicKey).getOrThrow().any { isPayable(it) }
        }
    }

    suspend fun payableEndpoints(endpoints: List<Endpoint>): List<Endpoint> = withContext(ioDispatcher) {
        endpoints.filter { isPayable(it) }
    }

    suspend fun syncPublishedEndpoints(publish: Boolean): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            if (!publish) {
                val endpointError = runSuspendCatching { removePublishedEndpoints() }.exceptionOrNull()
                val markerError = syncLocalReceiverMarker(publicSharingEnabled = false).exceptionOrNull()
                if (endpointError != null) {
                    markerError?.let(endpointError::addSuppressed)
                    throw endpointError
                }
                markerError?.let { throw it }
                settingsStore.update { it.copy(publicPaykitCleanupPending = false) }
                return@runSuspendCatching
            }

            val desired = buildWalletEndpoints(refresh = true)
            syncLocalReceiverMarker(publicSharingEnabled = true).getOrThrow()
            applyPublishedEndpoints(desired)
            settingsStore.update { it.copy(publicPaykitCleanupPending = false) }
        }
    }

    suspend fun syncCurrentPublishedEndpoints(
        forceRefreshLightning: Boolean = false,
        requireEndpoint: Boolean = false,
    ): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            val desired = buildWalletEndpoints(
                refresh = false,
                forceRefreshLightning = forceRefreshLightning,
                requireEndpoint = requireEndpoint,
            )
            syncLocalReceiverMarker(publicSharingEnabled = true).getOrThrow()
            applyPublishedEndpoints(desired)
            settingsStore.update { it.copy(publicPaykitCleanupPending = false) }
        }
    }

    suspend fun refreshPublishedBolt11ForPayment(paymentHash: String): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            val settings = settingsStore.data.first()
            if (!settings.sharesPublicPaykitEndpoints) return@runSuspendCatching
            if (settings.publicPaykitBolt11PaymentHash != paymentHash) return@runSuspendCatching

            clearPublicBolt11Metadata()
            val desired = buildWalletEndpoints(refresh = true)
            applyPublishedEndpoints(desired)
        }
    }

    private suspend fun fetchPublicEndpoints(publicKey: String): Result<List<Endpoint>> = withContext(ioDispatcher) {
        runSuspendCatching {
            val normalizedKey = PubkyPublicKeyFormat.normalized(publicKey) ?: publicKey
            paykitSdkService.resolvePublicContactPayment(
                counterparty = normalizedKey,
                receiverPath = PaykitReceiverPaths.WALLET,
            ).payableEndpoints
                .mapNotNull { parseEndpoint(it.identifier, it.payload) }
                .associateBy { it.methodId }
                .values
                .sortedBy { endpoint -> payablePreferenceOrder.indexOf(endpoint.methodId) }
        }
    }

    suspend fun syncLocalReceiverMarker(
        publicSharingEnabled: Boolean? = null,
        privateSharingEnabled: Boolean? = null,
    ): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            val settings = settingsStore.data.first()
            val publicSharing = publicSharingEnabled ?: settings.sharesPublicPaykitEndpoints
            val privateSharing = privateSharingEnabled ?: settings.sharesPrivatePaykitEndpoints
            paykitSdkService.syncLocalReceiverMarker(
                isDiscoverable = publicSharing || privateSharing,
            )
        }
    }

    private suspend fun removePublishedEndpoints() {
        applyPublishedEndpoints(emptyList())
        clearPublicBolt11Metadata()
    }

    private suspend fun applyPublishedEndpoints(desiredEndpoints: List<Endpoint>) {
        publishMutex.withLock {
            requireCurrentPublicKey()
            val report = paykitSdkService.syncPublicEndpoints(desiredEndpoints)
            if (report.failed.isNotEmpty()) throw PublicPaykitError.PublicationFailed
        }
    }

    private suspend fun requireCurrentPublicKey(): String {
        val currentPublicKey = pubkyRepo.publicKey.value
            ?: pubkyRepo.currentPublicKey().getOrThrow()
        if (currentPublicKey == null) throw PublicPaykitError.SessionNotActive

        return currentPublicKey
    }

    private suspend fun buildWalletEndpoints(
        refresh: Boolean,
        forceRefreshLightning: Boolean = false,
        requireEndpoint: Boolean = true,
    ): List<Endpoint> {
        val settings = settingsStore.data.first()
        val includeLightning = isLightningPaymentOptionEnabled(settings)
        val includeOnchain = isOnchainPaymentOptionEnabled(settings)

        if (refresh) {
            lightningRepo.executeWhenNodeRunning(
                operationName = "sync public Paykit endpoints",
            ) {
                Result.success(Unit)
            }.getOrThrow()
        }
        if (includeOnchain) {
            walletRepo.refreshReusableReceiveAddress().getOrThrow()
        }

        val state = walletRepo.walletState.value
        val endpoints = mutableListOf<Endpoint>()
        if (includeLightning) {
            buildPublicBolt11Endpoint(forceRefreshLightning)?.let { endpoints += it }
        } else {
            clearPublicBolt11Metadata()
        }

        val onchainAddress = state.onchainAddress
        if (includeOnchain && onchainAddress.isNotBlank()) {
            val methodId = onchainMethodId(onchainAddress)
            endpoints += Endpoint(
                methodId = methodId,
                value = onchainAddress,
                rawPayload = serializePayload(onchainAddress),
            )
        }

        if (endpoints.isEmpty() && requireEndpoint) throw PublicPaykitError.NoSupportedEndpoint

        return endpoints
    }

    private suspend fun buildPublicBolt11Endpoint(forceRefreshLightning: Boolean = false): Endpoint? {
        if (!lightningRepo.canReceive()) {
            clearPublicBolt11Metadata()
            return null
        }

        val settings = settingsStore.data.first()
        val cachedBolt11 = settings.publicPaykitBolt11
        val shouldReuseCachedBolt11 = !forceRefreshLightning &&
            cachedBolt11.isNotBlank() &&
            !settings.shouldRefreshPublicBolt11(clock.now().toEpochMilliseconds())
        if (shouldReuseCachedBolt11) {
            if (!hasLightningRouteHints(cachedBolt11)) {
                clearPublicBolt11Metadata()
            } else {
                return Endpoint(
                    methodId = MethodId.Bolt11,
                    value = cachedBolt11,
                    rawPayload = serializePayload(cachedBolt11),
                )
            }
        }

        val bolt11 = lightningRepo.createInvoice(
            amountSats = null,
            description = "",
            expirySeconds = publicBolt11Expiry.inWholeSeconds.toUInt(),
        ).getOrThrow()
        val invoice = (coreService.decode(bolt11) as? Scanner.Lightning)?.invoice
            ?: throw PublicPaykitError.InvalidPayload
        if (!hasLightningRouteHints(bolt11)) {
            clearPublicBolt11Metadata()
            return null
        }
        val expiresAtMillis = clock.now().plus(publicBolt11Expiry).toEpochMilliseconds()

        settingsStore.update {
            it.copy(
                publicPaykitBolt11 = bolt11,
                publicPaykitBolt11PaymentHash = invoice.paymentHash.toHex(),
                publicPaykitBolt11ExpiresAtMillis = expiresAtMillis,
            )
        }

        return Endpoint(
            methodId = MethodId.Bolt11,
            value = bolt11,
            rawPayload = serializePayload(bolt11),
        )
    }

    private suspend fun clearPublicBolt11Metadata() {
        settingsStore.update {
            it.copy(
                publicPaykitBolt11 = "",
                publicPaykitBolt11PaymentHash = "",
                publicPaykitBolt11ExpiresAtMillis = 0,
            )
        }
    }

    private fun SettingsData.shouldRefreshPublicBolt11(nowMillis: Long): Boolean {
        if (publicPaykitBolt11PaymentHash.isBlank()) return true
        if (publicPaykitBolt11ExpiresAtMillis <= 0) return true

        val refreshAtMillis = publicPaykitBolt11ExpiresAtMillis - publicBolt11RefreshWindow.inWholeMilliseconds
        return nowMillis >= refreshAtMillis
    }

    private suspend fun isPayable(endpoint: Endpoint): Boolean = runSuspendCatching {
        when (endpoint.methodId) {
            MethodId.Bolt11 -> {
                val scan = coreService.decode(endpoint.paymentRequest) as? Scanner.Lightning
                    ?: return@runSuspendCatching false
                !scan.invoice.isExpired &&
                    !NetworkValidationHelper.isNetworkMismatch(scan.invoice.networkType.toLdkNetwork(), Env.network)
            }
            MethodId.Lnurl -> coreService.decode(endpoint.paymentRequest) is Scanner.LnurlPay
            MethodId.P2tr,
            MethodId.P2wpkh,
            MethodId.P2sh,
            MethodId.P2pkh,
            -> {
                val scan = coreService.decode(endpoint.paymentRequest) as? Scanner.OnChain
                    ?: return@runSuspendCatching false
                val address = validateBitcoinAddress(scan.invoice.address)
                !NetworkValidationHelper.isNetworkMismatch(address.network.toLdkNetwork(), Env.network)
            }
        }
    }.getOrDefault(false)
}

data class Endpoint(
    val methodId: MethodId,
    val value: String,
    val min: String? = null,
    val max: String? = null,
    val rawPayload: String,
) {
    val paymentRequest: String
        get() = value
}

enum class MethodId(
    private val fixedRawValue: String? = null,
    private val onchainEndpoint: String? = null,
    val isOnchain: Boolean = false,
    val isBitkitManaged: Boolean = false,
) {
    Bolt11(fixedRawValue = "btc-lightning-bolt11", isBitkitManaged = true),
    Lnurl(fixedRawValue = "btc-lightning-lnurl"),
    P2tr(onchainEndpoint = "p2tr", isOnchain = true, isBitkitManaged = true),
    P2wpkh(onchainEndpoint = "p2wpkh", isOnchain = true, isBitkitManaged = true),
    P2sh(onchainEndpoint = "p2sh", isOnchain = true, isBitkitManaged = true),
    P2pkh(onchainEndpoint = "p2pkh", isOnchain = true, isBitkitManaged = true),
    ;

    val rawValue: String
        get() = rawValueForNetwork(Env.network)

    fun rawValueForNetwork(network: Network): String {
        fixedRawValue?.let { return it }
        val endpoint = checkNotNull(onchainEndpoint)
        val rail = when (network) {
            Network.BITCOIN -> "bitcoin"
            Network.REGTEST -> "regtest"
            Network.TESTNET -> "testnet"
            Network.SIGNET -> "signet"
        }
        return "btc-$rail-$endpoint"
    }

    companion object {
        fun fromRawValue(value: String): MethodId? = entries.firstOrNull { it.rawValue == value }
    }
}

@Serializable
private data class PaymentEndpointPayload(
    val value: String,
    val min: String? = null,
    val max: String? = null,
)

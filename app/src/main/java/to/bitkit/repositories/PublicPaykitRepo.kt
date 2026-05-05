package to.bitkit.repositories

import com.synonym.bitkitcore.Scanner
import com.synonym.bitkitcore.validateBitcoinAddress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.di.IoDispatcher
import to.bitkit.di.json
import to.bitkit.env.Env
import to.bitkit.ext.toHex
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.models.toLdkNetwork
import to.bitkit.services.CoreService
import to.bitkit.utils.AppError
import to.bitkit.utils.NetworkValidationHelper
import to.bitkit.utils.encodeToUrl
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

sealed class PublicPaykitError(message: String) : AppError(message) {
    data object InvalidPayload : PublicPaykitError("Invalid Paykit payment endpoint payload")
    data object NoSupportedEndpoint : PublicPaykitError("No supported public payment endpoint is available")
    data object SessionNotActive : PublicPaykitError("No active Paykit session")
    data object WalletNotReady : PublicPaykitError("Wallet is not ready to publish Paykit endpoints")
}

sealed interface PublicPaykitPaymentResult {
    data class Opened(val paymentRequest: String) : PublicPaykitPaymentResult
    data object NoEndpoint : PublicPaykitPaymentResult
    data object NotOpened : PublicPaykitPaymentResult
}

@OptIn(ExperimentalTime::class)
@Suppress("LongParameterList")
@Singleton
class PublicPaykitRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val pubkyRepo: PubkyRepo,
    private val walletRepo: WalletRepo,
    private val lightningRepo: LightningRepo,
    private val coreService: CoreService,
    private val settingsStore: SettingsStore,
    private val clock: Clock,
) {
    companion object {
        private val methodIdPattern = Regex("^[a-z0-9]+-[a-z0-9]+-[a-z0-9]+$")

        private val payablePreferenceOrder = listOf(
            MethodId.Bolt11,
            MethodId.Lnurl,
            MethodId.P2tr,
            MethodId.P2wpkh,
            MethodId.P2sh,
            MethodId.P2pkh,
        )

        private val managedMethodIds = MethodId.entries.filter { it.isBitkitManaged }
        private val publicBolt11Expiry = 60.minutes
        private val publicBolt11RefreshWindow = 30.minutes

        fun parseEndpoint(methodId: String, endpointData: String): Endpoint? {
            if (!methodIdPattern.matches(methodId)) return null

            val knownMethodId = MethodId.fromRawValue(methodId) ?: return null
            val payload = runCatching {
                json.decodeFromString<PaymentEndpointPayload>(endpointData)
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
            return buildJsonObject { put("value", trimmedValue) }.toString()
        }

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
        runCatching {
            val endpoints = fetchPublicEndpoints(publicKey).getOrThrow()
            if (endpoints.isEmpty()) return@runCatching PublicPaykitPaymentResult.NoEndpoint

            val payable = endpoints.filter { isPayable(it) }
            if (payable.isEmpty()) return@runCatching PublicPaykitPaymentResult.NotOpened

            PublicPaykitPaymentResult.Opened(paymentRequest(payable))
        }
    }

    suspend fun hasPayablePublicEndpoint(publicKey: String): Result<Boolean> = withContext(ioDispatcher) {
        runCatching {
            fetchPublicEndpoints(publicKey).getOrThrow().any { isPayable(it) }
        }
    }

    suspend fun syncPublishedEndpoints(publish: Boolean): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            if (!publish) {
                removePublishedEndpoints()
                return@runCatching
            }

            val desired = buildWalletEndpoints(refresh = true)
            applyPublishedEndpoints(desired)
        }
    }

    suspend fun syncCurrentPublishedEndpoints(): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val desired = buildWalletEndpoints(refresh = false)
            applyPublishedEndpoints(desired)
        }
    }

    suspend fun refreshPublishedBolt11ForPayment(paymentHash: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val settings = settingsStore.data.first()
            if (!settings.sharesPublicPaykitEndpoints) return@runCatching
            if (settings.publicPaykitBolt11PaymentHash != paymentHash) return@runCatching

            clearPublicBolt11Metadata()
            val desired = buildWalletEndpoints(refresh = true)
            applyPublishedEndpoints(desired)
        }
    }

    private suspend fun fetchPublicEndpoints(publicKey: String): Result<List<Endpoint>> = withContext(ioDispatcher) {
        runCatching {
            val normalizedKey = PubkyPublicKeyFormat.normalized(publicKey) ?: publicKey
            pubkyRepo.getPaymentList(normalizedKey).getOrThrow()
                .mapNotNull { parseEndpoint(it.methodId, it.endpointData) }
                .associateBy { it.methodId }
                .values
                .sortedBy { endpoint -> payablePreferenceOrder.indexOf(endpoint.methodId) }
        }
    }

    private suspend fun removePublishedEndpoints() {
        publishMutex.withLock {
            val currentMethodIds = currentPublishedMethodIds()
            managedMethodIds
                .filter { it.rawValue in currentMethodIds }
                .forEach { pubkyRepo.removePaymentEndpoint(it.rawValue).getOrThrow() }
            clearPublicBolt11Metadata()
        }
    }

    private suspend fun applyPublishedEndpoints(desiredEndpoints: List<Endpoint>) {
        publishMutex.withLock {
            requireCurrentPublicKey()
            val desiredMethodIds = desiredEndpoints.map { it.methodId.rawValue }.toSet()

            desiredEndpoints.forEach {
                pubkyRepo.setPaymentEndpoint(it.methodId.rawValue, it.rawPayload).getOrThrow()
            }

            val publishedMethodIds = currentPublishedMethodIds()
            managedMethodIds
                .filter { it.rawValue in publishedMethodIds && it.rawValue !in desiredMethodIds }
                .forEach { pubkyRepo.removePaymentEndpoint(it.rawValue).getOrThrow() }
        }
    }

    private suspend fun currentPublishedMethodIds(): Set<String> {
        return pubkyRepo.getPaymentList(requireCurrentPublicKey()).getOrThrow()
            .map { it.methodId }
            .toSet()
    }

    private suspend fun requireCurrentPublicKey(): String {
        val currentPublicKey = pubkyRepo.publicKey.value
            ?: pubkyRepo.currentPublicKey().getOrThrow()
        if (currentPublicKey == null) throw PublicPaykitError.SessionNotActive

        return currentPublicKey
    }

    private suspend fun buildWalletEndpoints(refresh: Boolean): List<Endpoint> {
        if (refresh) {
            lightningRepo.executeWhenNodeRunning(
                operationName = "sync public Paykit endpoints",
            ) {
                Result.success(Unit)
            }.getOrThrow()
        }

        val state = walletRepo.walletState.value
        val endpoints = mutableListOf<Endpoint>()
        buildPublicBolt11Endpoint()?.let { endpoints += it }

        val onchainAddress = state.onchainAddress
        if (onchainAddress.isNotBlank()) {
            val methodId = onchainMethodId(onchainAddress)
            endpoints += Endpoint(
                methodId = methodId,
                value = onchainAddress,
                rawPayload = serializePayload(onchainAddress),
            )
        }

        if (endpoints.isEmpty()) throw PublicPaykitError.NoSupportedEndpoint

        return endpoints
    }

    private suspend fun buildPublicBolt11Endpoint(): Endpoint? {
        if (!lightningRepo.canReceive()) {
            clearPublicBolt11Metadata()
            return null
        }

        val settings = settingsStore.data.first()
        val cachedBolt11 = settings.publicPaykitBolt11
        if (cachedBolt11.isNotBlank() && !settings.shouldRefreshPublicBolt11(clock.now().toEpochMilliseconds())) {
            return Endpoint(
                methodId = MethodId.Bolt11,
                value = cachedBolt11,
                rawPayload = serializePayload(cachedBolt11),
            )
        }

        val bolt11 = lightningRepo.createInvoice(
            amountSats = null,
            description = "",
            expirySeconds = publicBolt11Expiry.inWholeSeconds.toUInt(),
        ).getOrThrow()
        val invoice = (coreService.decode(bolt11) as Scanner.Lightning).invoice
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

    private suspend fun isPayable(endpoint: Endpoint): Boolean = runCatching {
        when (endpoint.methodId) {
            MethodId.Bolt11 -> {
                val scan = coreService.decode(endpoint.paymentRequest) as? Scanner.Lightning ?: return@runCatching false
                !scan.invoice.isExpired &&
                    !NetworkValidationHelper.isNetworkMismatch(scan.invoice.networkType.toLdkNetwork(), Env.network)
            }
            MethodId.Lnurl -> coreService.decode(endpoint.paymentRequest) is Scanner.LnurlPay
            MethodId.P2tr,
            MethodId.P2wpkh,
            MethodId.P2sh,
            MethodId.P2pkh,
            -> {
                val scan = coreService.decode(endpoint.paymentRequest) as? Scanner.OnChain ?: return@runCatching false
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
    val rawValue: String,
    val isOnchain: Boolean = false,
    val isBitkitManaged: Boolean = false,
) {
    Bolt11("btc-lightning-bolt11", isBitkitManaged = true),
    Lnurl("btc-lightning-lnurl"),
    P2tr("btc-bitcoin-p2tr", isOnchain = true, isBitkitManaged = true),
    P2wpkh("btc-bitcoin-p2wpkh", isOnchain = true, isBitkitManaged = true),
    P2sh("btc-bitcoin-p2sh", isOnchain = true, isBitkitManaged = true),
    P2pkh("btc-bitcoin-p2pkh", isOnchain = true, isBitkitManaged = true),
    ;

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

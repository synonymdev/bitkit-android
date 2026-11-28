package to.bitkit.wear.services

import android.content.Context
import breez_sdk_spark.BreezSdkInterface
import breez_sdk_spark.ChainApiType
import breez_sdk_spark.Config
import breez_sdk_spark.EventListener
import breez_sdk_spark.GetInfoRequest
import breez_sdk_spark.GetInfoResponse
import breez_sdk_spark.InputType
import breez_sdk_spark.ListPaymentsRequest
import breez_sdk_spark.MaxFee
import breez_sdk_spark.MaxOnchainFee
import breez_sdk_spark.Payment
import breez_sdk_spark.PaymentRequest
import breez_sdk_spark.PrepareSendPaymentRequest
import breez_sdk_spark.PrepareSendPaymentResponse
import breez_sdk_spark.ReceivePaymentMethod
import breez_sdk_spark.ReceivePaymentRequest
import breez_sdk_spark.SdkBuilder
import breez_sdk_spark.SdkEvent
import breez_sdk_spark.Seed
import breez_sdk_spark.SendPaymentMethod
import breez_sdk_spark.SendPaymentOptions
import breez_sdk_spark.SendPaymentRequest
import breez_sdk_spark.SyncWalletRequest
import breez_sdk_spark.defaultConfig
import breez_sdk_spark.parseSparkConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import to.bitkit.wear.env.Env
import to.bitkit.wear.env.LocalSparkSettings
import to.bitkit.wear.env.WalletNetwork
import to.bitkit.wear.ext.runSuspendCatching
import to.bitkit.wear.security.Bip39
import to.bitkit.wear.security.WalletSeed
import to.bitkit.wear.utils.AppError
import to.bitkit.wear.utils.Logger
import java.io.File
import java.math.BigInteger
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import breez_sdk_spark.Network as SparkNetwork

/**
 * The watch's Spark wallet: a slim port of the phone's `SparkService` with the same SDK settings. It holds no
 * lifecycle of its own: `WalletRepo` connects it only while the app is on screen and disconnects it a short while
 * after the app leaves the screen, so closing the watch app stops its Spark instance.
 */
@Singleton
class WatchSparkService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bip39: Bip39,
) {
    companion object {
        private const val TAG = "WatchSparkService"

        /** The watch holds one wallet, at the phone's first wallet index. */
        private const val WALLET_INDEX = 0

        /** Leeway in sat/vB over the recommended fee that an automatic deposit claim may pay. */
        private const val DEPOSIT_CLAIM_FEE_LEEWAY_SAT_PER_VB = 2uL

        /** How long a [disconnect] waits for a send that Spark is taking, so it is not cut off halfway. */
        val SEND_GRACE = 10.seconds

        /**
         * Bitkit's settings over the SDK's [defaults], as on the phone. Breez's real-time data sync server, its
         * Lightning address (LNURL) server and its default external input parsers stay off on every network.
         */
        internal fun configure(defaults: Config, apiKey: String?): Config = defaults.apply {
            this.apiKey = apiKey
            realTimeSyncServerUrl = null
            lnurlDomain = null
            useDefaultExternalInputParsers = false
            maxDepositClaimFee = MaxFee(
                onchain = MaxOnchainFee.NetworkRecommended(DEPOSIT_CLAIM_FEE_LEEWAY_SAT_PER_VB),
                proportionalPpm = null,
            )
            leafOptimizationConfig = leafOptimizationConfig.copy(autoEnabled = true)
        }

        /** Applies the pinned operator settings and selects the fixture's mempool.space REST API. */
        internal fun configureLocalSpark(config: Config, settings: LocalSparkSettings?): ChainApiType {
            if (settings == null) return ChainApiType.ESPLORA
            require(settings.isComplete) { "Local Spark requires SPARK_LOCAL_CONFIG_B64 and SPARK_LOCAL_CHAIN_URL" }
            config.sparkConfig = parseSparkConfig(Base64.getDecoder().decode(settings.configBase64).decodeToString())
            // The pinned regtest operators require anchored refunds.
            config.watchtowerFeeLadderEnabled = false
            return ChainApiType.MEMPOOL_SPACE
        }

        /**
         * Spark runs on Lightspark's hosted regtest, which needs no API key, and on mainnet with a Breez API key
         * once [isMainnetEnabled].
         */
        internal fun sparkNetwork(
            network: WalletNetwork,
            breezApiKey: String?,
            isMainnetEnabled: Boolean = Env.IS_MAINNET_ENABLED,
        ): SparkNetwork? = when (network) {
            WalletNetwork.BITCOIN if isMainnetEnabled && breezApiKey != null -> SparkNetwork.MAINNET
            WalletNetwork.REGTEST -> SparkNetwork.REGTEST
            else -> null
        }

        /**
         * Returns as soon as the payment starts, as on the phone, so the call is short and the payment's completion
         * arrives as an event.
         */
        internal fun bolt11SendOptions(prefersSpark: Boolean): SendPaymentOptions =
            SendPaymentOptions.Bolt11Invoice(preferSpark = prefersSpark, completionTimeoutSecs = null)
    }

    private val mutex = Mutex()

    @Volatile
    private var sdk: BreezSdkInterface? = null
    private var listenerId: String? = null

    /** The connected wallet's [SparkSendKey.secret], derived from its seed at [connect]. */
    @Volatile
    private var sendKeySecret: ByteArray? = null

    /** How many sends hold the connection: those between [holdSend] and [releaseSend], and running [send] calls. */
    private val runningSends = MutableStateFlow(0)

    private val _events = MutableSharedFlow<SdkEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SdkEvent> = _events.asSharedFlow()

    private val listener = object : EventListener {
        override suspend fun onEvent(event: SdkEvent) {
            _events.emit(event)
        }
    }

    val isSupported: Boolean get() = sparkNetwork(Env.network, Env.breezApiKey) != null

    /**
     * On regtest, an invoice that carries a Spark address is paid as a Spark transfer, as on the phone. Bitkit's own
     * invoices carry none, so they are paid over Lightning through Spark's provider, as on mainnet.
     */
    val prefersSpark: Boolean get() = Env.network == WalletNetwork.REGTEST

    /** Whether a wallet wipe is running: nothing connects during it. */
    @Volatile
    var isWiping: Boolean = false
        private set

    private var wipeCount = 0

    private val storageDir: File
        get() = File(context.filesDir, "${Env.network.name.lowercase()}/wallet$WALLET_INDEX/spark")

    /** Marks the start of a wallet wipe; [endWipe] must follow. */
    fun beginWipe() = synchronized(this) {
        wipeCount++
        isWiping = true
    }

    fun endWipe() = synchronized(this) {
        wipeCount++
        isWiping = false
    }

    /**
     * The token a [connect] has to show, read before the seed is. It is null while a wipe runs, and it stops being
     * current when a wipe begins or ends, so a seed read before or during a wipe never reconnects the wiped wallet.
     */
    fun wipeToken(): Int? = synchronized(this) { if (isWiping) null else wipeCount }

    /** Whether no wipe has begun or ended since [token] was read. */
    fun isCurrent(token: Int): Boolean = synchronized(this) { !isWiping && token == wipeCount }

    /**
     * Connects the Spark wallet of [seed]. Returns true, or false when it was already connected. It fails when
     * [wipeToken] is no longer current, also over an SDK already connected. An SDK kept by a [disconnect] that failed
     * after it removed the listener gets the listener back. An SDK that was built and then cannot be used, and
     * cannot be disconnected either, stays referenced without a listener, so the next [disconnect] reaches it and a
     * wipe never deletes the storage under it.
     */
    suspend fun connect(seed: WalletSeed, wipeToken: Int): Boolean {
        val secret = bip39.toSeed(seed.mnemonic, seed.passphrase).let { bytes ->
            SparkSendKey.secret(bytes).also { bytes.fill(0) }
        }
        return connectWith(wipeToken, secret) { build(seed) }
    }

    internal suspend fun connectWith(
        wipeToken: Int,
        sendKeySecret: ByteArray? = null,
        build: suspend () -> BreezSdkInterface,
    ): Boolean = mutex.withLock {
        if (!isCurrent(wipeToken)) throw SparkError.WipeInProgress()
        // The secret comes before the SDK, so a send that finds the wallet connected can derive its key.
        sendKeySecret?.let { this.sendKeySecret = it }
        sdk?.let {
            if (listenerId == null) listenerId = it.addEventListener(listener)
            return@withLock false
        }
        val connected = build()
        // A caller that gives up from here on, as on a timeout, must not leave a built SDK that nothing disconnects.
        withContext(NonCancellable) {
            if (!isCurrent(wipeToken)) {
                runSuspendCatching { connected.disconnect() }.onFailure {
                    Logger.warn("Failed to disconnect Spark built during a wipe", it, context = TAG)
                    sdk = connected
                }
                throw SparkError.WipeInProgress()
            }
            attach(connected)
        }
        true
    }

    private suspend fun build(seed: WalletSeed): BreezSdkInterface {
        val network = sparkNetwork(Env.network, Env.breezApiKey) ?: throw SparkError.Unsupported()
        val config = configure(defaultConfig(network), Env.breezApiKey)
        val storagePath = storageDir.apply { mkdirs() }.path
        val apiType = configureLocalSpark(config, Env.localSparkSettings)
        val chainServiceUrl = Env.sparkChainServiceUrl(Env.network)
        val connected = SdkBuilder(config, Seed.Mnemonic(seed.mnemonic, seed.passphrase)).use { builder ->
            builder.withDefaultStorage(storagePath)
            chainServiceUrl?.let { builder.withRestChainService(it, apiType, null) }
            builder.build()
        }
        Logger.info("Connected Spark wallet on '$network' with chain '${chainServiceUrl ?: "default"}'", context = TAG)
        return connected
    }

    private suspend fun attach(connected: BreezSdkInterface) {
        runSuspendCatching {
            listenerId = connected.addEventListener(listener)
        }.onFailure {
            runSuspendCatching { connected.disconnect() }.onFailure { sdk = connected }
            throw it
        }
        sdk = connected
    }

    /**
     * Disconnects the SDK as one step that its caller cannot cancel, after waiting up to [SEND_GRACE] for a send that
     * holds the connection ([holdSend]) or runs in the SDK ([send]). A listener that cannot be removed does not stop
     * the disconnect. When the disconnect itself fails, it throws and the SDK stays referenced, so the next
     * [disconnect] reaches it again; a listener already removed is not removed twice.
     */
    suspend fun disconnect() = withContext(NonCancellable) {
        withTimeoutOrNull(SEND_GRACE) { runningSends.first { it == 0 } }
        mutex.withLock {
            val current = sdk ?: return@withLock
            listenerId?.let { id ->
                runSuspendCatching { current.removeEventListener(id) }
                    .onSuccess { listenerId = null }
                    .onFailure { Logger.warn("Failed to remove Spark event listener", it, context = TAG) }
            }
            current.disconnect()
            sdk = null
            listenerId = null
            sendKeySecret = null
            Logger.info("Disconnected Spark wallet", context = TAG)
        }
    }

    /**
     * Deletes Spark's storage, after a [disconnect] that is tried twice. When the SDK still cannot be disconnected,
     * it throws, the SDK stays referenced and nothing is deleted, so storage is never deleted under a live SDK; once
     * the storage is gone, the wipe has succeeded.
     */
    suspend fun wipeStorage() {
        runSuspendCatching { disconnect() }.onFailure {
            Logger.warn("Failed to disconnect Spark wallet before its wipe, trying again", it, context = TAG)
            disconnect()
        }
        val dir = storageDir
        if (!dir.deleteRecursively() && dir.exists()) throw SparkError.WipeFailed(dir.path)
    }

    /**
     * Syncs with Spark now instead of waiting for the SDK's next background sync. Without a network the SDK still
     * returns at once, so the result says whether this sync reached Spark.
     */
    suspend fun syncWallet(): SparkSync {
        val response = requireSdk().syncWallet(SyncWalletRequest)
        return SparkSync(error = response.lastSyncError?.toString(), syncedAtSecs = response.lastSyncedAt)
    }

    suspend fun getInfo(): GetInfoResponse = requireSdk().getInfo(GetInfoRequest(ensureSynced = false))

    /** The wallet's Spark identity public key, in hex: what its Spark address encodes. Read from local state. */
    suspend fun identityPubkey(): String = getInfo().identityPubkey

    /** Creates a BOLT11 invoice paid into the Spark balance; amountless without [amountSats]. */
    suspend fun receiveBolt11(amountSats: ULong?, description: String, expirySecs: UInt): String {
        val method = ReceivePaymentMethod.Bolt11Invoice(
            description = description,
            amountSats = amountSats,
            expirySecs = expirySecs,
            paymentHash = null,
            receiverIdentityPublicKey = null,
            preimage = null,
        )
        return requireSdk().receivePayment(ReceivePaymentRequest(method)).paymentRequest
    }

    /**
     * Reads [input] with the SDK's own parser. Only for a BOLT11 invoice or a Spark address, which it reads without
     * a network call: anything else could be sent to a name server or an LNURL host.
     */
    suspend fun parse(input: String): InputType = requireSdk().parse(input)

    /** Prices a send of [paymentRequest], a BOLT11 invoice or a Spark address; [amountSats] when it names none. */
    suspend fun prepareSend(paymentRequest: String, amountSats: ULong?): PrepareSendPaymentResponse =
        requireSdk().prepareSendPayment(
            PrepareSendPaymentRequest(
                paymentRequest = PaymentRequest.Input(input = paymentRequest),
                amount = amountSats?.let { BigInteger.valueOf(it.toLong()) },
                tokenIdentifier = null,
                conversionOptions = null,
                feePolicy = null,
            ),
        )

    /** The idempotency key of attempt [attempt] at paying the invoice with [paymentHash], see [SparkSendKey]. */
    fun sendKey(paymentHash: String, attempt: Int): String {
        val secret = sendKeySecret ?: throw SparkError.NotConnected()
        return SparkSendKey.key(secret, paymentHash, attempt)
    }

    /**
     * Holds the connection for a send that is about to go out, so a [disconnect] waits up to [SEND_GRACE] for it
     * also before the SDK has the call. [releaseSend] must follow.
     */
    fun holdSend() = runningSends.update { it + 1 }

    fun releaseSend() = runningSends.update { it - 1 }

    /**
     * Sends [prepared] as one step that its caller cannot cancel, so a send is never dropped halfway. Spark makes
     * one payment per [idempotencyKey], a UUID that becomes the payment's id.
     */
    suspend fun send(prepared: PrepareSendPaymentResponse, idempotencyKey: String): Payment {
        val options = when (prepared.paymentMethod) {
            is SendPaymentMethod.Bolt11Invoice -> bolt11SendOptions(prefersSpark)
            else -> null
        }
        val current = requireSdk()
        return withContext(NonCancellable) {
            runningSends.update { it + 1 }
            try {
                current.sendPayment(SendPaymentRequest(prepared, options, idempotencyKey)).payment
            } finally {
                runningSends.update { it - 1 }
            }
        }
    }

    /** The wallet's payments from Spark's local list, newest first; at most [limit] when given. */
    suspend fun listPayments(limit: UInt? = null): List<Payment> =
        requireSdk().listPayments(ListPaymentsRequest(limit = limit, sortAscending = false)).payments

    private fun requireSdk(): BreezSdkInterface = sdk ?: throw SparkError.NotConnected()
}

/**
 * The outcome of a wallet sync: the SDK's last sync [error], and [syncedAtSecs], the Unix time of the last sync that
 * reached Spark, which the SDK sets only when a wallet sync succeeds.
 */
data class SparkSync(
    val error: String?,
    val syncedAtSecs: ULong?,
) {
    /** Whether the sync reached Spark: the SDK reports a network failure as [error] and still returns. */
    val hasReachedSpark: Boolean get() = error == null && syncedAtSecs != null
}

sealed class SparkError(message: String) : AppError(message) {
    class Unsupported : SparkError("Spark is not available on this network or build")
    class NotConnected : SparkError("Spark wallet is not connected")
    class WipeInProgress : SparkError("Spark wallet cannot connect while the wallet is wiped")
    class WipeFailed(path: String) : SparkError("Failed to delete Spark storage at '$path'")
}

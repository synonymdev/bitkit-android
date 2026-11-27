package to.bitkit.wear.services

import android.content.Context
import breez_sdk_spark.BreezSdk
import breez_sdk_spark.ChainApiType
import breez_sdk_spark.Config
import breez_sdk_spark.EventListener
import breez_sdk_spark.FeePolicy
import breez_sdk_spark.GetInfoRequest
import breez_sdk_spark.GetInfoResponse
import breez_sdk_spark.ListPaymentsRequest
import breez_sdk_spark.MaxFee
import breez_sdk_spark.MaxOnchainFee
import breez_sdk_spark.OnchainConfirmationSpeed
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import breez_sdk_spark.Network as SparkNetwork

/**
 * The watch's Spark wallet: a slim port of the phone's `SparkService` with the same SDK settings. It runs only while
 * the app is in use; nothing keeps it connected in the background.
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

        /** Returns as soon as the payment starts, so its completion reaches callers as an event, as on the phone. */
        internal fun bolt11SendOptions(prefersSpark: Boolean): SendPaymentOptions =
            SendPaymentOptions.Bolt11Invoice(preferSpark = prefersSpark, completionTimeoutSecs = null)

        /** Spark runs on mainnet with a Breez API key, and on Lightspark's hosted regtest, which needs none. */
        internal fun sparkNetwork(network: WalletNetwork, breezApiKey: String?): SparkNetwork? = when (network) {
            WalletNetwork.BITCOIN if breezApiKey != null -> SparkNetwork.MAINNET
            WalletNetwork.REGTEST -> SparkNetwork.REGTEST
            else -> null
        }
    }

    private val mutex = Mutex()

    @Volatile
    private var sdk: BreezSdk? = null
    private var listenerId: String? = null

    /** The connected wallet's [SparkSendKey.secret], derived once at connect. */
    @Volatile
    private var sendKeySecret: ByteArray? = null

    private val _events = MutableSharedFlow<SdkEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SdkEvent> = _events.asSharedFlow()

    val isSupported: Boolean get() = sparkNetwork(Env.network, Env.breezApiKey) != null

    /** Set for the whole wallet wipe, so a start that races it cannot connect and recreate the deleted storage. */
    @Volatile
    var isWiping: Boolean = false

    /** On regtest, an invoice that carries a Spark address is paid as a Spark transfer, as on the phone. */
    private val prefersSpark: Boolean get() = Env.network == WalletNetwork.REGTEST

    private val storageDir: File
        get() = File(context.filesDir, "${Env.network.name.lowercase()}/wallet$WALLET_INDEX/spark")

    /** Connects the Spark wallet of [seed]. Returns true, or false when it was already connected. */
    suspend fun connect(seed: WalletSeed): Boolean = mutex.withLock {
        if (sdk != null) return@withLock false
        if (isWiping) throw SparkError.WipeInProgress()
        val network = sparkNetwork(Env.network, Env.breezApiKey) ?: throw SparkError.Unsupported()
        val seedBytes = bip39.seed(seed.mnemonic, seed.passphrase)
        val secret = try {
            SparkSendKey.secret(seedBytes)
        } finally {
            seedBytes.fill(0)
        }
        val config = configure(defaultConfig(network), Env.breezApiKey)
        val storagePath = storageDir.apply { mkdirs() }.path
        val apiType = configureLocalSpark(config, Env.localSparkSettings)
        val chainServiceUrl = Env.sparkChainServiceUrl(Env.network)
        val connected = SdkBuilder(config, Seed.Mnemonic(seed.mnemonic, seed.passphrase)).use { builder ->
            builder.withDefaultStorage(storagePath)
            chainServiceUrl?.let { builder.withRestChainService(it, apiType, null) }
            builder.build()
        }
        listenerId = connected.addEventListener(
            object : EventListener {
                override suspend fun onEvent(event: SdkEvent) {
                    _events.emit(event)
                }
            },
        )
        // The secret comes first, so a send that sees the wallet connected can derive its key.
        sendKeySecret = secret
        sdk = connected
        Logger.info("Connected Spark wallet on '$network' with chain '${chainServiceUrl ?: "default"}'", context = TAG)
        true
    }

    suspend fun disconnect() = mutex.withLock {
        val current = sdk ?: return@withLock
        try {
            listenerId?.let { current.removeEventListener(it) }
            current.disconnect()
            Logger.info("Disconnected Spark wallet", context = TAG)
        } finally {
            sdk = null
            listenerId = null
            sendKeySecret = null
        }
    }

    /** Deletes Spark's storage, even when disconnecting failed. */
    suspend fun wipeStorage() {
        val disconnected = runSuspendCatching { disconnect() }
        val dir = storageDir
        if (!dir.deleteRecursively() && dir.exists()) throw SparkError.WipeFailed(dir.path)
        disconnected.getOrThrow()
    }

    /** Syncs with Spark now instead of waiting for the SDK's next background sync. */
    suspend fun syncWallet() {
        requireSdk().syncWallet(SyncWalletRequest)
    }

    suspend fun getInfo(): GetInfoResponse = requireSdk().getInfo(GetInfoRequest(ensureSynced = false))

    suspend fun listPayments(): List<Payment> = requireSdk().listPayments(ListPaymentsRequest()).payments

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

    suspend fun prepareSend(
        paymentRequest: String,
        amountSats: ULong?,
        feePolicy: FeePolicy? = null,
    ): PrepareSendPaymentResponse =
        requireSdk().prepareSendPayment(
            PrepareSendPaymentRequest(
                paymentRequest = PaymentRequest.Input(input = paymentRequest),
                amount = amountSats?.let { BigInteger.valueOf(it.toLong()) },
                tokenIdentifier = null,
                conversionOptions = null,
                feePolicy = feePolicy,
            ),
        )

    /** The idempotency key of attempt [attempt] at paying the invoice with [paymentHash], see [SparkSendKey]. */
    fun sendKey(paymentHash: String, attempt: Int): String {
        val secret = checkNotNull(sendKeySecret) { "Spark is not connected" }
        return SparkSendKey.key(secret, paymentHash, attempt)
    }

    /** Sends [prepared]; Spark makes one payment per [idempotencyKey], a UUID that becomes the payment's id. */
    suspend fun send(prepared: PrepareSendPaymentResponse, idempotencyKey: String? = null): Payment {
        val options = when (prepared.paymentMethod) {
            is SendPaymentMethod.Bolt11Invoice -> bolt11SendOptions(prefersSpark)
            is SendPaymentMethod.BitcoinAddress -> SendPaymentOptions.BitcoinAddress(OnchainConfirmationSpeed.MEDIUM)
            else -> null
        }
        return requireSdk().sendPayment(SendPaymentRequest(prepared, options, idempotencyKey)).payment
    }

    private fun requireSdk(): BreezSdk = sdk ?: throw SparkError.NotConnected()
}

sealed class SparkError(message: String) : AppError(message) {
    class Unsupported : SparkError("Spark is not available on this network or build")
    class NotConnected : SparkError("Spark wallet is not connected")
    class WipeInProgress : SparkError("Spark wallet cannot connect while the wallet is wiped")
    class WipeFailed(path: String) : SparkError("Failed to delete Spark storage at '$path'")
}

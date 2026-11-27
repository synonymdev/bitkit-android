package to.bitkit.wear.services

import android.content.Context
import breez_sdk_spark.BreezSdk
import breez_sdk_spark.ChainApiType
import breez_sdk_spark.Config
import breez_sdk_spark.EventListener
import breez_sdk_spark.GetInfoRequest
import breez_sdk_spark.GetInfoResponse
import breez_sdk_spark.MaxFee
import breez_sdk_spark.MaxOnchainFee
import breez_sdk_spark.SdkBuilder
import breez_sdk_spark.SdkEvent
import breez_sdk_spark.Seed
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
import to.bitkit.wear.security.WalletSeed
import to.bitkit.wear.utils.AppError
import to.bitkit.wear.utils.Logger
import java.io.File
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
    }

    private val mutex = Mutex()

    @Volatile
    private var sdk: BreezSdk? = null
    private var listenerId: String? = null

    private val _events = MutableSharedFlow<SdkEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SdkEvent> = _events.asSharedFlow()

    val isSupported: Boolean get() = sparkNetwork(Env.network, Env.breezApiKey) != null

    /** Whether a wallet wipe is running: nothing connects during it. */
    @Volatile
    var isWiping: Boolean = false
        private set

    /** How many wallet wipes have begun. A [connect] names the count it saw before it read the seed. */
    @Volatile
    var wipeCount: Int = 0
        private set

    private val storageDir: File
        get() = File(context.filesDir, "${Env.network.name.lowercase()}/wallet$WALLET_INDEX/spark")

    /** Marks the start of a wallet wipe; [endWipe] must follow. */
    fun beginWipe() = synchronized(this) {
        wipeCount++
        isWiping = true
    }

    fun endWipe() = run { isWiping = false }

    /**
     * Connects the Spark wallet of [seed]. Returns true, or false when it was already connected. It fails when a
     * wipe is running or has begun since [wipeCount] was read, so a seed read before a wipe never reconnects the
     * wiped wallet.
     */
    suspend fun connect(seed: WalletSeed, wipeCount: Int): Boolean = mutex.withLock {
        if (sdk != null) return@withLock false
        if (isWiping || wipeCount != this.wipeCount) throw SparkError.WipeInProgress()
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
        listenerId = connected.addEventListener(
            object : EventListener {
                override suspend fun onEvent(event: SdkEvent) {
                    _events.emit(event)
                }
            },
        )
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
        }
    }

    /** Deletes Spark's storage, even when disconnecting failed. */
    suspend fun wipeStorage() {
        val disconnected = runSuspendCatching { disconnect() }
        val dir = storageDir
        if (!dir.deleteRecursively() && dir.exists()) throw SparkError.WipeFailed(dir.path)
        disconnected.getOrThrow()
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

    private fun requireSdk(): BreezSdk = sdk ?: throw SparkError.NotConnected()
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

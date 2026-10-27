package to.bitkit.env

import android.os.Build
import com.synonym.bitkitcore.UsdtDestination
import org.lightningdevkit.ldknode.LogLevel
import org.lightningdevkit.ldknode.Network
import to.bitkit.BuildConfig
import to.bitkit.BuildConfig.VERSION_NAME
import to.bitkit.ext.ensureDir
import to.bitkit.utils.Logger
import java.io.File
import kotlin.io.path.Path
import com.synonym.bitkitcore.Network as BitkitCoreNetwork

@Suppress("ConstPropertyName", "KotlinConstantConditions", "SimplifyBooleanWithConstants")
internal object Env {
    val isUsdtEnabled: Boolean get() = (network == Network.BITCOIN || isDebug) &&
        usdtRpcUrl != null && usdtBundlerUrl.isNotBlank()
    val usdtBundlerUrl: String get() = BuildConfig.USDT_BUNDLER_URL
    val usdtDepositsUrl: String? get() = BuildConfig.USDT_DEPOSITS_URL.takeIf { it.isNotBlank() }
    val usdtBridgesUrl: String? get() = BuildConfig.USDT_BRIDGES_URL.takeIf { it.isNotBlank() }
    val usdtRpcUrl: String? get() = BuildConfig.USDT_RPC_URL.takeIf { it.isNotBlank() }

    val usdtDestinations: List<UsdtDestination> get() {
        val enabled = BuildConfig.USDT_BRIDGE_NETWORKS.split(',').map { it.trim() }.toSet()
        return listOf(UsdtDestination.ARBITRUM) + listOf(
            "ethereum" to UsdtDestination.ETHEREUM,
            "polygon" to UsdtDestination.POLYGON,
            "plasma" to UsdtDestination.PLASMA,
            "stable" to UsdtDestination.STABLE,
        ).filter { it.first in enabled }.map { it.second }
    }

    val breezApiKey: String? get() = BuildConfig.BREEZ_API_KEY.takeIf { it.isNotBlank() }

    /**
     * The Spark token held as the dollar balance on Spark, matched by identifier since anyone can issue a token
     * with any ticker. No Tether-issued USDT exists on Spark yet, so both networks use a 6-decimal test token:
     * "Test Tether USD" on Lightspark's regtest and "Bitkit Test Dollar" (tUSD) on mainnet.
     */
    val sparkUsdTokenId: String?
        get() = when (network) {
            Network.REGTEST -> "btknrt1e2h2l3jwna0dzwfjsf3dcgck8zjuug3qprnuvl7hs23a68xvz38q7966fl"
            Network.BITCOIN -> "btkn1nhdmkalm7wwh5pylhwacwwxeghv2q9mf0f3km44rruv0jf7vhm7stwkdzs"
            else -> null
        }

    /**
     * Esplora endpoint for the node's chain source on regtest builds, set to put the wallet on Spark's regtest chain
     * through `scripts/spark-regtest-esplora.py`; Electrum is used when unset.
     */
    val regtestEsploraUrl: String?
        get() = BuildConfig.ESPLORA_URL.takeIf { it.isNotBlank() && network == Network.REGTEST }
    val isDebug = BuildConfig.DEBUG
    const val isE2eTest = BuildConfig.E2E
    const val isGeoblockingEnabled = BuildConfig.GEO
    val e2eBackend = BuildConfig.E2E_BACKEND.lowercase()
    val isLocalE2eBackend = isE2eTest && e2eBackend == "local"
    const val e2eLocalHost = BuildConfig.E2E_LOCAL_HOST
    const val e2eHomegateUrl = BuildConfig.E2E_HOMEGATE_URL
    val e2eHomeserverPubky = BuildConfig.E2E_HOMESERVER_PUBKY.takeIf { isLocalE2eBackend && it.isNotBlank() }
    val network = Network.valueOf(BuildConfig.NETWORK)
    val locales = BuildConfig.LOCALES.split(",")
    const val walletSyncIntervalSecs = 10_uL
    const val walletSyncTimeoutSecs = 10_uL
    val platform = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    const val version = "$VERSION_NAME (${BuildConfig.VERSION_CODE})"

    val ldkLogLevel = LogLevel.TRACE

    /**
     * Whether LN -> onchain swaps can reach a Boltz backend. Boltz only serves a public API on
     * mainnet: its testnet deployment is deprecated and regtest resolves to a local backend that
     * no build of ours can reach. Elsewhere the transfer to savings closes a channel instead.
     */
    val isSwapSupported get() = network == Network.BITCOIN

    const val fxRateRefreshInterval = 2 * 60 * 1000L // 2 minutes in millis
    const val fxRateStaleThreshold = 10 * 60 * 1000L // 10 minutes in millis
    const val lspOrdersRefreshInterval = 2 * 60 * 1000L // 2 minutes in millis

    const val FILE_PROVIDER_AUTHORITY = "${BuildConfig.APPLICATION_ID}.fileprovider"
    const val SUPPORT_EMAIL = "support@synonym.to"
    const val PIN_LENGTH = 4
    const val PIN_ATTEMPTS = 8

    // region urls

    val electrumServerUrl: String
        get() {
            return when (network) {
                Network.BITCOIN -> ElectrumServers.MAINNET.ESPLORA
                Network.REGTEST -> {
                    if (isLocalE2eBackend) ElectrumServers.REGTEST.LOCAL else ElectrumServers.REGTEST.STAG
                }
                Network.TESTNET -> ElectrumServers.TESTNET
                else -> TODO("${network.name} network not implemented")
            }
        }

    val ldkRgsServerUrl
        get() = when (network) {
            Network.BITCOIN -> "https://rgs.blocktank.to/snapshot"
            Network.TESTNET -> "https://rapidsync.lightningdevkit.org/testnet/snapshot"
            Network.REGTEST -> "https://bitkit.stag0.blocktank.to/rgs/snapshot"
            else -> null
        }

    val ldkScorerUrl
        get() = when (network) {
            Network.BITCOIN -> "https://api.blocktank.to/scorer-prod"
            Network.REGTEST -> "https://api.stag0.blocktank.to/scorer"
            else -> null
        }

    val vssStoreIdPrefix get() = "bitkit_v1_${network.name.lowercase()}"

    val vssServerUrl
        get() = when (network) {
            Network.BITCOIN -> "https://bitkit.to/vss_rs_auth"
            else -> "https://bitkit.stag0.blocktank.to/vss_rs_auth"
        }

    val lnurlAuthServerUrl
        get() = when (network) {
            Network.BITCOIN -> "https://bitkit.to/lnurl_auth/auth"
            else -> "https://bitkit.stag0.blocktank.to/lnurl_auth/auth"
        }

    val blockExplorerUrl
        get() = when (network) {
            Network.BITCOIN -> "https://mempool.space"
            Network.SIGNET -> "https://mutinynet.com"
            Network.TESTNET -> "https://mempool.space/testnet"
            Network.REGTEST -> "https://mempool.bitkit.stag0.blocktank.to/"
        }

    val blocktankBaseUrl
        get() = when (network) {
            Network.BITCOIN -> "https://api1.blocktank.to"
            else -> "https://api.stag0.blocktank.to"
        }
    val blocktankApiUrl
        get() = when (network) {
            Network.BITCOIN -> "$blocktankBaseUrl/api"
            else -> "$blocktankBaseUrl/blocktank/api/v2"
        }

    val btcRatesServer
        get() = when (network) {
            Network.BITCOIN -> "https://api1.blocktank.to/api/fx/rates/btc"
            else -> "https://bitkit.stag0.blocktank.to/fx/rates/btc"
        }

    const val geoCheckUrl = "https://api1.blocktank.to/api/geocheck"
    const val chatwootUrl = "https://synonym.to/api/chatwoot"
    const val newsBaseUrl = "https://feeds.synonym.to/news-feed/api"
    const val mempoolBaseUrl = "https://mempool.space/api"
    const val pricesWidgetBaseUrl = "https://feeds.synonym.to/price-feed/api"

    const val APP_STORE_URL = "https://apps.apple.com/app/bitkit-wallet/id6502440655"
    const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=to.bitkit"

    const val REPO_URL = "https://github.com/synonymdev/bitkit-android"
    val RELEASE_URL = if (isE2eTest) {
        "https://github.com/synonymdev/bitkit-e2e-tests/releases/download/updater/release.json"
    } else {
        "$REPO_URL/releases/download/updater/release.json"
    }
    const val EXCHANGES_URL = "https://bitcoin.org/en/exchanges#international"
    const val BTC_MAP_URL = "https://btcmap.org/map"
    const val BITKIT_WEBSITE = "https://bitkit.to/"
    const val SYNONYM_CONTACT = "https://synonym.to/contact"
    const val SYNONYM_MEDIUM = "https://medium.com/synonym-to"
    const val SYNONYM_X = "https://twitter.com/bitkitwallet/"
    const val BITKIT_DISCORD = "https://discord.gg/DxTBJXvJxn"
    const val BITKIT_TELEGRAM = "https://t.me/bitkitchat"
    const val BITKIT_GITHUB = "https://github.com/synonymdev"
    const val BITKIT_HELP_CENTER = "https://help.bitkit.to"
    const val BLOCKTANK_SUNSET_URL = "https://blog.bitkit.to/"
    const val TERMS_OF_USE_URL = "https://bitkit.to/terms-of-use"
    const val PRIVACY_POLICY_URL = "https://bitkit.to/privacy-policy"
    const val STORING_BITCOINS_URL = "https://en.bitcoin.it/wiki/Storing_bitcoins"

    const val BITREFILL_URL = "https://embed.bitrefill.com"
    const val BITREFILL_APP = "Bitkit"
    const val BITREFILL_REF = "AL6dyZYt"

    val homegateUrl: String
        get() {
            // An explicit E2E_HOMEGATE_URL wins on every backend, as on iOS: demos run their own homegate.
            if (isE2eTest && BuildConfig.E2E_HOMEGATE_OVERRIDE.isNotBlank()) {
                return BuildConfig.E2E_HOMEGATE_OVERRIDE
            }
            if (isLocalE2eBackend) {
                return e2eHomegateUrl
            }

            return when (network) {
                Network.BITCOIN -> "https://homegate.pubky.app"
                else -> "https://homegate.staging.pubky.app"
            }
        }

    val rnBackupServerHost: String
        get() = when (network) {
            Network.BITCOIN -> "https://blocktank.synonym.to/backups-ldk"
            else -> "https://bitkit.stag0.blocktank.to/backups-ldk"
        }

    fun electrumUrlForNetwork(network: BitkitCoreNetwork): String {
        val isE2eLocal = isE2eTest && e2eBackend == "local"
        return when (network) {
            BitkitCoreNetwork.BITCOIN -> ElectrumServers.MAINNET.ESPLORA
            BitkitCoreNetwork.TESTNET, BitkitCoreNetwork.TESTNET4, BitkitCoreNetwork.SIGNET ->
                ElectrumServers.TESTNET
            BitkitCoreNetwork.REGTEST ->
                if (isE2eLocal) ElectrumServers.REGTEST.LOCAL else ElectrumServers.REGTEST.STAG
        }
    }

    // endregion

    // region paths

    private lateinit var appStoragePath: String

    fun initAppStoragePath(path: String) {
        require(path.isNotBlank()) { "App storage path cannot be empty." }
        appStoragePath = path
        Logger.info("App storage path: $path")
    }

    val logDir: File
        get() {
            require(::appStoragePath.isInitialized)
            return File(appStoragePath).resolve("logs").ensureDir()
        }

    fun ldkStoragePath(walletIndex: Int) = storagePathOf(walletIndex, network.name.lowercase(), "ldk")

    fun sparkStoragePath(walletIndex: Int) = storagePathOf(walletIndex, network.name.lowercase(), "spark")

    /** A restored Spark exit data backup waiting to be imported once Spark connects; outside Spark's storage. */
    fun sparkExitRestorePath(walletIndex: Int) =
        Path(storagePathOf(walletIndex, network.name.lowercase(), "spark-restore"), "exit-state.json").toString()

    fun bitkitCoreStoragePath(walletIndex: Int): String {
        return storagePathOf(walletIndex, network.name.lowercase(), "core")
    }

    /**
     * Generates the storage path for a specified wallet index, network, and directory.
     *
     * Output format:
     *
     * `appStoragePath/network/walletN/dir`
     */
    private fun storagePathOf(walletIndex: Int, network: String, dir: String): String {
        require(::appStoragePath.isInitialized) { "App storage path should be 'context.filesDir.absolutePath'." }
        val path = Path(appStoragePath, network, "wallet$walletIndex", dir)
            .toFile()
            .ensureDir()
            .path
        Logger.debug("Using ${dir.uppercase()} storage path: $path")
        return path
    }

    // endregion
}

@Suppress("ConstPropertyName")
object Defaults {
    /** Default Bolt11 invoice expiry in seconds. */
    const val bolt11ExpirySec = 86_400u

    /** Recommended transaction base fee in sats */
    const val recommendedBaseFee = 256u

    /** Fallback fee percentage used when fee estimates are temporarily unavailable. */
    const val fallbackFeePercent = 0.1

    /**
     * Minimum value in sats for an output. Outputs below the dust limit may not be processed because the fees
     * required to include them in a block would be greater than the value of the transaction itself.
     * */
    const val dustLimit = 546u

    /** Max characters in a user-entered tag. */
    const val TAG_MAX_LENGTH = 20
}

private object ElectrumServers {
    object MAINNET {
        const val ESPLORA = "ssl://bitkit.to:9999"
    }

    object REGTEST {
        const val STAG = "ssl://electrs.bitkit.stag0.blocktank.to:9999"
        val LOCAL get() = "tcp://${Env.e2eLocalHost}:60001"
    }

    const val TESTNET = "ssl://electrum.blockstream.info:60002"
}

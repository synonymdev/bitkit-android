package to.bitkit.env

import android.os.Build
import org.lightningdevkit.ldknode.LogLevel
import org.lightningdevkit.ldknode.Network
import org.lightningdevkit.ldknode.PeerDetails
import to.bitkit.BuildConfig
import to.bitkit.ext.ensureDir
import to.bitkit.ext.of
import to.bitkit.models.BlocktankNotificationType
import to.bitkit.utils.Logger
import java.io.File
import kotlin.io.path.Path

@Suppress("ConstPropertyName", "KotlinConstantConditions", "SimplifyBooleanWithConstants")
internal object Env {
    val isDebug = BuildConfig.DEBUG
    const val isE2eTest = BuildConfig.E2E
    const val isGeoblockingEnabled = BuildConfig.GEO
    val e2eBackend = BuildConfig.E2E_BACKEND.lowercase()
    val network = Network.valueOf(BuildConfig.NETWORK)
    val locales = BuildConfig.LOCALES.split(",")
    const val walletSyncIntervalSecs = 10_uL
    val platform = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    const val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    val ldkLogLevel = LogLevel.TRACE

    val trustedLnPeers
        get() = when (network) {
            Network.BITCOIN -> listOf(Peers.lnd1, Peers.lnd3, Peers.lnd4)
            Network.REGTEST -> listOf(Peers.stag)
            Network.TESTNET -> listOf(Peers.stag)
            else -> listOf()
        }

    const val fxRateRefreshInterval = 2 * 60 * 1000L // 2 minutes in millis
    const val fxRateStaleThreshold = 10 * 60 * 1000L // 10 minutes in millis
    const val lspOrdersRefreshInterval = 2 * 60 * 1000L // 2 minutes in millis

    val pushNotificationFeatures = BlocktankNotificationType.entries
    const val derivationName = "bitkit-notifications"

    const val FILE_PROVIDER_AUTHORITY = "${BuildConfig.APPLICATION_ID}.fileprovider"
    const val SUPPORT_EMAIL = "support@synonym.to"
    const val PIN_LENGTH = 4
    const val PIN_ATTEMPTS = 8

    // region urls

    val electrumServerUrl: String
        get() {
            val isE2eLocal = isE2eTest && e2eBackend == "local"
            return when (network) {
                Network.BITCOIN -> ElectrumServers.MAINNET.ESPLORA
                Network.REGTEST -> if (isE2eLocal) ElectrumServers.REGTEST.LOCAL else ElectrumServers.REGTEST.STAG
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
    val blocktankNotificationApiUrl
        get() = when (network) {
            Network.BITCOIN -> "$blocktankBaseUrl/api/notifications"
            else -> "$blocktankBaseUrl/notifications/api"
        }

    val btcRatesServer
        get() = when (network) {
            Network.BITCOIN -> "https://blocktank.synonym.to/fx/rates/btc"
            else -> "https://bitkit.stag0.blocktank.to/fx/rates/btc"
        }

    const val geoCheckUrl = "https://api1.blocktank.to/api/geocheck"
    const val chatwootUrl = "https://synonym.to/api/chatwoot"
    const val newsBaseUrl = "https://feeds.synonym.to/news-feed/api"
    const val mempoolBaseUrl = "https://mempool.space/api"
    const val pricesWidgetBaseUrl = "https://feeds.synonym.to/price-feed/api"

    const val APP_STORE_URL = "https://apps.apple.com/app/bitkit-wallet/id6502440655"
    const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=to.bitkit"

    const val RELEASE_URL = "https://github.com/synonymdev/bitkit-android/releases/download/updater/release.json"
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
    const val TERMS_OF_USE_URL = "https://bitkit.to/terms-of-use"
    const val PRIVACY_POLICY_URL = "https://bitkit.to/privacy-policy"
    const val STORING_BITCOINS_URL = "https://en.bitcoin.it/wiki/Storing_bitcoins"

    const val BITREFILL_URL = "https://embed.bitrefill.com"
    const val BITREFILL_APP = "Bitkit"
    const val BITREFILL_REF = "AL6dyZYt"

    val rnBackupServerHost: String
        get() = when (network) {
            Network.BITCOIN -> "https://blocktank.synonym.to/backups-ldk"
            else -> "https://bitkit.stag0.blocktank.to/backups-ldk"
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
    /** Recommended transaction base fee in sats */
    const val recommendedBaseFee = 256u

    /**
     * Minimum value in sats for an output. Outputs below the dust limit may not be processed because the fees
     * required to include them in a block would be greater than the value of the transaction itself.
     * */
    const val dustLimit = 546u
}

object Peers {
    val stag = PeerDetails.of("028a8910b0048630d4eb17af25668cdd7ea6f2d8ae20956e7a06e2ae46ebcb69fc@34.65.86.104:9400")
    val lnd1 = PeerDetails.of("039b8b4dd1d88c2c5db374290cda397a8f5d79f312d6ea5d5bfdfc7c6ff363eae3@34.65.111.104:9735")
    val lnd3 = PeerDetails.of("03816141f1dce7782ec32b66a300783b1d436b19777e7c686ed00115bd4b88ff4b@34.65.191.64:9735")
    val lnd4 = PeerDetails.of("02a371038863605300d0b3fc9de0cf5ccb57728b7f8906535709a831b16e311187@34.65.186.40:9735")
}

private object ElectrumServers {
    object MAINNET {
        const val ESPLORA = "ssl://bitkit.to:9999"
    }

    object REGTEST {
        const val STAG = "ssl://electrs.bitkit.stag0.blocktank.to:9999"
        const val LOCAL = "tcp://127.0.0.1:60001"
    }

    const val TESTNET = "ssl://electrum.blockstream.info:60002"
}

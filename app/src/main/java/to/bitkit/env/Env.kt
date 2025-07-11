package to.bitkit.env

import android.os.Build
import org.lightningdevkit.ldknode.LogLevel
import org.lightningdevkit.ldknode.Network
import to.bitkit.BuildConfig
import to.bitkit.ext.ensureDir
import to.bitkit.models.BlocktankNotificationType
import to.bitkit.models.ElectrumProtocol
import to.bitkit.models.ElectrumServer
import to.bitkit.models.LnPeer
import to.bitkit.utils.Logger
import java.io.File
import kotlin.io.path.Path

@Suppress("ConstPropertyName")
internal object Env {
    val isDebug = BuildConfig.DEBUG
    val network = Network.valueOf(BuildConfig.NETWORK)
    val walletSyncIntervalSecs = 10_uL // TODO review
    val platform = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    const val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    // TODO: remove this to load from BT API instead
    val trustedLnPeers
        get() = when (network) {
            Network.REGTEST -> listOf(Peers.btStaging)
            Network.TESTNET -> listOf(Peers.btStaging)
            else -> TODO("Not yet implemented")
        }

    val ldkRgsServerUrl
        get() = when (network) {
            Network.BITCOIN -> "https://rgs.blocktank.to/snapshot/"
            Network.TESTNET -> "https://rapidsync.lightningdevkit.org/testnet/snapshot"
            else -> null
        }

    val vssServerUrl
        get() = when (network) {
            Network.REGTEST -> "https://bitkit.stag0.blocktank.to/vss"
            Network.TESTNET -> "https://bitkit.stag0.blocktank.to/vss"
            else -> TODO("${network.name} network not implemented")
        }

    val vssStoreId
        get() = when (network) {
            Network.REGTEST -> "bitkit_regtest"
            Network.TESTNET -> "bitkit_testnet"
            else -> TODO("${network.name} network not implemented")
        }

    val esploraServerUrl
        get() = when (network) {
            Network.REGTEST -> "https://bitkit.stag0.blocktank.to/electrs"
            Network.TESTNET -> "https://blockstream.info/testnet/api"
            else -> TODO("${network.name} network not implemented")
        }

    val blocktankBaseUrl
        get() = when (network) {
            Network.REGTEST -> "https://api.stag0.blocktank.to"
            Network.TESTNET -> "https://api.stag0.blocktank.to"
            else -> TODO("${network.name} network not implemented")
        }

    val blocktankClientServer get() = "$blocktankBaseUrl/blocktank/api/v2"
    val blocktankPushNotificationServer get() = "$blocktankBaseUrl/notifications/api"

    // const val btcRatesServer = "https://blocktank.synonym.to/fx/rates/btc/"
    const val btcRatesServer = "https://api1.blocktank.to/api/fx/rates/btc"
    const val geoCheckUrl = "https://api1.blocktank.to/api/geocheck"
    const val chatwootUrl = "https://synonym.to/api/chatwoot"
    const val newsBaseUrl = "https://feeds.synonym.to/news-feed/api"
    const val mempoolBaseUrl = "https://mempool.space/api"
    const val pricesWidgetBaseUrl = "https://feeds.synonym.to/price-feed/api"

    const val fxRateRefreshInterval: Long = 2 * 60 * 1000 // 2 minutes in milliseconds
    const val fxRateStaleThreshold: Long = 10 * 60 * 1000 // 10 minutes in milliseconds

    const val blocktankOrderRefreshInterval: Long = 2 * 60 * 1000 // 2 minutes in milliseconds

    val pushNotificationFeatures = listOf(
        BlocktankNotificationType.incomingHtlc,
        BlocktankNotificationType.mutualClose,
        BlocktankNotificationType.orderPaymentConfirmed,
        BlocktankNotificationType.cjitPaymentArrived,
        BlocktankNotificationType.wakeToTimeout,
    )
    const val DERIVATION_NAME = "bitkit-notifications"

    object TransactionDefaults {
        /** Total recommended tx base fee in sats */
        val recommendedBaseFee = 256u

        /**
         * Minimum value in sats for an output. Outputs below the dust limit may not be processed because the fees
         * required to include them in a block would be greater than the value of the transaction itself.
         * */
        val dustLimit = 546u
    }

    private lateinit var appStoragePath: String

    fun initAppStoragePath(path: String) {
        require(path.isNotBlank()) { "App storage path cannot be empty." }
        Logger.info("App storage path: $path")
        appStoragePath = path
    }

    val logDir: String
        get() {
            require(::appStoragePath.isInitialized)
            return File(appStoragePath).resolve("logs").ensureDir().path
        }

    val ldkLogLevel = LogLevel.TRACE

    fun ldkStoragePath(walletIndex: Int) = storagePathOf(walletIndex, network.name.lowercase(), "ldk")

    fun bitkitCoreStoragePath(walletIndex: Int): String {
        return storagePathOf(walletIndex, network.name.lowercase(), "core")
    }

    private fun storagePathOf(walletIndex: Int, network: String, dir: String): String {
        require(::appStoragePath.isInitialized) { "App storage path should be 'context.filesDir.absolutePath'." }
        val path = Path(appStoragePath, network, "wallet$walletIndex", dir)
            .toFile()
            .ensureDir()
            .path
        Logger.debug("Using ${dir.uppercase()} storage path: $path")
        return path
    }

    object Peers {
        val btStaging = LnPeer(
            nodeId = "028a8910b0048630d4eb17af25668cdd7ea6f2d8ae20956e7a06e2ae46ebcb69fc",
            address = "34.65.86.104:9400",
        )
    }

    object ElectrumServers {
        val BITCOIN = ElectrumServer(
            host = "35.187.18.233",
            tcp = 8911,
            ssl = 8900,
            protocol = ElectrumProtocol.SSL,
        )
        val TESTNET = ElectrumServer(
            host = "electrum.blockstream.info",
            tcp = 60001, // or 50001
            ssl = 60002, // or 50002
            protocol = ElectrumProtocol.TCP,
        )
        val REGTEST = ElectrumServer(
            host = "34.65.252.32",
            tcp = 18483,
            ssl = 18484,
            protocol = ElectrumProtocol.TCP,
        )
    }

    val defaultElectrumServer: ElectrumServer
        get() = when (network) {
            Network.REGTEST -> ElectrumServers.REGTEST
            Network.TESTNET -> ElectrumServers.TESTNET
            Network.BITCOIN -> ElectrumServers.BITCOIN
            else -> TODO("${network.name} network not implemented")
        }

    const val PIN_LENGTH = 4
    const val PIN_ATTEMPTS = 8
    const val DEFAULT_INVOICE_MESSAGE = "Bitkit"
    const val FILE_PROVIDER_AUTHORITY = "${BuildConfig.APPLICATION_ID}.fileprovider"
    const val APP_STORE_URL = "https://apps.apple.com/app/bitkit-wallet/id6502440655"
    const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=to.bitkit"
    const val EXCHANGES_URL = "https://bitcoin.org/en/exchanges#international"
    const val BIT_REFILL_URL = "https://www.bitrefill.com/br/en/gift-cards/"
    const val BITKIT_WEBSITE = "https://bitkit.to/"
    const val SYNONYM_CONTACT = "https://synonym.to/contact"
    const val SYNONYM_MEDIUM = "https://medium.com/synonym-to"
    const val SYNONYM_X = "https://twitter.com/bitkitwallet/"
    const val BITKIT_DISCORD = "https://discord.gg/DxTBJXvJxn"
    const val BITKIT_TELEGRAM = "https://t.me/bitkitchat"
    const val BITKIT_GITHUB = "https://github.com/synonymdev"
    const val BITKIT_HELP_CENTER = "https://help.bitkit.to"
    const val TERMS_OF_USE_URL = "https://bitkit.to/terms-of-use"
    const val STORING_BITCOINS_URL = "https://en.bitcoin.it/wiki/Storing_bitcoins"
    const val SUPPORT_EMAIL = "support@synonym.to"
}

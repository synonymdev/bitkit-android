package to.bitkit.wear.env

import to.bitkit.wear.BuildConfig

enum class WalletNetwork { BITCOIN, TESTNET, REGTEST }

internal data class LocalSparkSettings(val configBase64: String, val chainUrl: String) {
    companion object {
        fun fromInputs(configBase64: String, chainUrl: String): LocalSparkSettings? {
            if (configBase64.isBlank() && chainUrl.isBlank()) return null
            return LocalSparkSettings(configBase64, chainUrl.trimEnd('/'))
        }
    }

    val isComplete: Boolean get() = configBase64.isNotBlank() && chainUrl.isNotBlank()
}

@Suppress("KotlinConstantConditions", "SimplifyBooleanWithConstants")
internal object Env {
    val network = WalletNetwork.valueOf(BuildConfig.NETWORK)
    val isDebug = BuildConfig.DEBUG
    val breezApiKey: String? get() = BuildConfig.BREEZ_API_KEY.takeIf { it.isNotBlank() }
    private val isLocalE2eBackend = BuildConfig.E2E && BuildConfig.E2E_BACKEND.lowercase() == "local"

    /** The local regtest Spark fixture, for debug E2E builds only, as on the phone. */
    val localSparkSettings = if (isDebug && isLocalE2eBackend && network == WalletNetwork.REGTEST) {
        LocalSparkSettings.fromInputs(BuildConfig.SPARK_LOCAL_CONFIG_B64, BuildConfig.SPARK_LOCAL_CHAIN_URL)
    } else {
        null
    }

    /** Mainnet stays off on the watch until two live Spark instances on one seed are safe (#2152). */
    const val IS_MAINNET_ENABLED = false

    const val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    /** Bitkit's Esplora, the Spark SDK's chain source on mainnet. */
    const val BITKIT_ESPLORA_URL = "https://bitkit.to/electrs"

    /** REST endpoint for the Spark SDK. An unconfigured regtest uses the SDK's hosted default. */
    fun sparkChainServiceUrl(
        network: WalletNetwork,
        localSpark: LocalSparkSettings? = localSparkSettings,
    ): String? = when (network) {
        WalletNetwork.BITCOIN -> BITKIT_ESPLORA_URL
        WalletNetwork.REGTEST -> localSpark?.takeIf { it.isComplete }?.chainUrl
        WalletNetwork.TESTNET -> null
    }

    /**
     * The namespace of [network]'s backups on VSS, the phone's `Env.vssStoreIdPrefix`. This and the two servers
     * below must equal the phone's: `phone-backup-contract.json` in the tests holds them, and a test on each side
     * compares its own values with that file.
     */
    fun vssStoreIdPrefix(network: WalletNetwork = this.network): String = "bitkit_v1_${network.name.lowercase()}"

    /** The phone's backup server. The watch only ever reads from it. */
    fun vssServerUrl(network: WalletNetwork = this.network): String = when (network) {
        WalletNetwork.BITCOIN -> "https://api.bitkit.to/vss_rs_auth"
        else -> "https://bitkit.stag0.blocktank.to/vss_rs_auth"
    }

    /** The LNURL-auth server that signs the watch in to [vssServerUrl], as it does the phone. */
    fun lnurlAuthServerUrl(network: WalletNetwork = this.network): String = when (network) {
        WalletNetwork.BITCOIN -> "https://bitkit.to/lnurl_auth/auth"
        else -> "https://bitkit.stag0.blocktank.to/lnurl_auth/auth"
    }

    val btcRatesServer: String
        get() = when (network) {
            WalletNetwork.BITCOIN -> "https://api1.blocktank.to/api/fx/rates/btc"
            else -> "https://bitkit.stag0.blocktank.to/fx/rates/btc"
        }
}

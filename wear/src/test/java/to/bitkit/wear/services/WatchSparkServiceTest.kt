package to.bitkit.wear.services

import breez_sdk_spark.ChainApiType
import breez_sdk_spark.Config
import breez_sdk_spark.LeafOptimizationConfig
import breez_sdk_spark.Network
import breez_sdk_spark.SendPaymentOptions
import breez_sdk_spark.TokenOptimizationConfig
import org.junit.Test
import to.bitkit.wear.env.LocalSparkSettings
import to.bitkit.wear.env.WalletNetwork
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchSparkServiceTest {
    @Test
    fun `a sync reached spark only without an error and with a sync time`() {
        assertTrue(SparkSync(error = null, syncedAtSecs = 1uL).hasReachedSpark)
        assertFalse(SparkSync(error = "Network(message=dns error)", syncedAtSecs = 1uL).hasReachedSpark)
        assertFalse(SparkSync(error = null, syncedAtSecs = null).hasReachedSpark)
    }

    @Test
    fun `config turns off breez data sync, the lightning address server and default parsers on every network`() {
        Network.entries.forEach { network ->
            val config = WatchSparkService.configure(defaults(network), apiKey = "key")

            assertNull(config.realTimeSyncServerUrl, "$network")
            assertNull(config.lnurlDomain, "$network")
            assertFalse(config.useDefaultExternalInputParsers, "$network")
            assertNotNull(config.maxDepositClaimFee, "$network")
            assertTrue(config.leafOptimizationConfig.autoEnabled, "$network")
            assertEquals("key", config.apiKey)
        }
    }

    @Test
    fun `local spark requires both fixture values and leaves defaults untouched without a fixture`() {
        val config = defaults(Network.REGTEST)
        assertEquals(ChainApiType.ESPLORA, WatchSparkService.configureLocalSpark(config, null))
        for (settings in listOf(LocalSparkSettings("encoded", ""), LocalSparkSettings("", "http://fixture/api"))) {
            assertFailsWith<IllegalArgumentException> { WatchSparkService.configureLocalSpark(config, settings) }
        }
        assertNull(config.sparkConfig)
        assertNull(config.watchtowerFeeLadderEnabled)
    }

    @Test
    fun `spark runs on regtest and on mainnet with an api key only`() {
        assertEquals(Network.REGTEST, WatchSparkService.sparkNetwork(WalletNetwork.REGTEST, null))
        assertEquals(Network.MAINNET, WatchSparkService.sparkNetwork(WalletNetwork.BITCOIN, "key"))
        assertNull(WatchSparkService.sparkNetwork(WalletNetwork.BITCOIN, null))
        assertNull(WatchSparkService.sparkNetwork(WalletNetwork.TESTNET, "key"))
    }

    @Test
    fun `lightning sends return before completion`() {
        val options = WatchSparkService.bolt11SendOptions(prefersSpark = true)

        assertIs<SendPaymentOptions.Bolt11Invoice>(options)
        assertNull(options.completionTimeoutSecs)
        assertTrue(options.preferSpark)
    }

    private fun defaults(network: Network) = Config(
        apiKey = null,
        network = network,
        syncIntervalSecs = 60u,
        maxDepositClaimFee = null,
        lnurlDomain = "breez.tips",
        preferSparkOverLightning = false,
        exitChainAutoFetchEnabled = true,
        watchtowerFeeLadderEnabled = null,
        externalInputParsers = null,
        useDefaultExternalInputParsers = true,
        realTimeSyncServerUrl = "https://datasync.breez.technology",
        privateEnabledDefault = true,
        leafOptimizationConfig = LeafOptimizationConfig(autoEnabled = false, multiplicity = 1u),
        tokenOptimizationConfig = TokenOptimizationConfig(
            autoEnabled = true,
            targetOutputCount = 1u,
            minOutputsThreshold = 1u,
        ),
        stableBalanceConfig = null,
        maxConcurrentClaims = 4u,
        sparkConfig = null,
        backgroundTasksEnabled = true,
        proxy = null,
        crossChainConfig = null,
    )
}

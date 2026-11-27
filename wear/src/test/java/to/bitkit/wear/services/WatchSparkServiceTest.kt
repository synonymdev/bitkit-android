package to.bitkit.wear.services

import android.content.Context
import breez_sdk_spark.BreezSdkInterface
import breez_sdk_spark.ChainApiType
import breez_sdk_spark.Config
import breez_sdk_spark.LeafOptimizationConfig
import breez_sdk_spark.Network
import breez_sdk_spark.TokenOptimizationConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.wear.env.Env
import to.bitkit.wear.env.LocalSparkSettings
import to.bitkit.wear.env.WalletNetwork
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun `spark runs on regtest, and on mainnet only when enabled with an api key`() {
        assertEquals(Network.REGTEST, WatchSparkService.sparkNetwork(WalletNetwork.REGTEST, null))
        assertEquals(Network.MAINNET, mainnet("key", isMainnetEnabled = true))
        assertNull(mainnet(null, isMainnetEnabled = true))
        assertNull(mainnet("key", isMainnetEnabled = false))
        assertNull(WatchSparkService.sparkNetwork(WalletNetwork.TESTNET, "key", isMainnetEnabled = true))
    }

    @Test
    fun `mainnet is off on the watch`() {
        assertFalse(Env.IS_MAINNET_ENABLED)
        assertNull(WatchSparkService.sparkNetwork(WalletNetwork.BITCOIN, "key"))
    }

    @Test
    fun `no wipe token is handed out while a wipe runs, and one read before a wipe is refused after it`() = runTest {
        val sut = WatchSparkService(mock<Context>())
        val before = assertNotNull(sut.wipeToken())
        assertTrue(sut.isCurrent(before))

        sut.beginWipe()
        assertNull(sut.wipeToken())
        assertFalse(sut.isCurrent(before))
        assertFailsWith<SparkError.WipeInProgress> { sut.connectWith(before) { error("built during a wipe") } }

        sut.endWipe()
        assertFalse(sut.isCurrent(before))
        assertFailsWith<SparkError.WipeInProgress> { sut.connectWith(before) { error("built after a wipe") } }
        assertTrue(sut.isCurrent(assertNotNull(sut.wipeToken())))
    }

    @Test
    fun `a current token connects once`() = runTest {
        val sut = WatchSparkService(mock<Context>())
        val sdk = mock<BreezSdkInterface>()
        whenever(sdk.addEventListener(any())).thenReturn("listener")
        whenever(sdk.removeEventListener(any())).thenReturn(true)
        val token = assertNotNull(sut.wipeToken())

        assertTrue(sut.connectWith(token) { sdk })
        assertFalse(sut.connectWith(token) { error("built twice") })

        sut.disconnect()
        verify(sdk).removeEventListener("listener")
        verify(sdk).disconnect()
    }

    @Test
    fun `a connect whose caller gives up after the sdk is built keeps it, so it is disconnected later`() = runTest {
        val sut = WatchSparkService(mock<Context>())
        val sdk = mock<BreezSdkInterface>()
        lateinit var caller: Job
        whenever(sdk.addEventListener(any())).doSuspendableAnswer {
            caller.cancel()
            yield()
            "listener"
        }
        whenever(sdk.removeEventListener(any())).thenReturn(true)
        val token = assertNotNull(sut.wipeToken())

        caller = launch { sut.connectWith(token) { sdk } }
        caller.join()

        assertTrue(caller.isCancelled)
        sut.disconnect()
        verify(sdk).disconnect()
    }

    @Test
    fun `an sdk whose listener cannot be added is disconnected at once`() = runTest {
        val sut = WatchSparkService(mock<Context>())
        val sdk = mock<BreezSdkInterface>()
        whenever(sdk.addEventListener(any())).thenAnswer { throw SparkError.NotConnected() }
        val token = assertNotNull(sut.wipeToken())

        assertFailsWith<SparkError.NotConnected> { sut.connectWith(token) { sdk } }

        verify(sdk).disconnect()
        assertFailsWith<SparkError.NotConnected> { sut.getInfo() }
    }

    private fun mainnet(apiKey: String?, isMainnetEnabled: Boolean) =
        WatchSparkService.sparkNetwork(WalletNetwork.BITCOIN, apiKey, isMainnetEnabled)

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

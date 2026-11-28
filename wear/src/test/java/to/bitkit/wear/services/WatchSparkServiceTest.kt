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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.wear.env.Env
import to.bitkit.wear.env.LocalSparkSettings
import to.bitkit.wear.env.WalletNetwork
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchSparkServiceTest {
    @get:Rule
    val folder = TemporaryFolder()

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

    @Test
    fun `an sdk built while a wipe began is disconnected and never used`() = runTest {
        val sut = WatchSparkService(mock<Context>())
        val sdk = mock<BreezSdkInterface>()
        val token = assertNotNull(sut.wipeToken())

        assertFailsWith<SparkError.WipeInProgress> {
            sut.connectWith(token) {
                sut.beginWipe()
                sdk
            }
        }

        verify(sdk).disconnect()
        verify(sdk, never()).addEventListener(any())
        assertFailsWith<SparkError.NotConnected> { sut.getInfo() }
    }

    @Test
    fun `an sdk that gets no listener and cannot be disconnected is kept for the next disconnect`() = runTest {
        val sut = WatchSparkService(mock<Context>())
        val sdk = mock<BreezSdkInterface>()
        var failures = 1
        whenever(sdk.addEventListener(any())).thenAnswer { throw SparkError.NotConnected() }
        whenever(sdk.disconnect()).thenAnswer { if (failures-- > 0) throw SparkError.WipeFailed("disconnect") }
        val token = assertNotNull(sut.wipeToken())

        assertFailsWith<SparkError.NotConnected> { sut.connectWith(token) { sdk } }
        sut.disconnect()

        verify(sdk, times(2)).disconnect()
        verify(sdk, never()).removeEventListener(any())
        assertFailsWith<SparkError.NotConnected> { sut.getInfo() }
    }

    @Test
    fun `an sdk built during a wipe that cannot be disconnected fails the wipe, which keeps the storage`() = runTest {
        val sut = WatchSparkService(context())
        val sdk = mock<BreezSdkInterface>()
        val storage = storage()
        whenever(sdk.disconnect()).thenAnswer { throw SparkError.NotConnected() }
        val token = assertNotNull(sut.wipeToken())

        assertFailsWith<SparkError.WipeInProgress> {
            sut.connectWith(token) {
                sut.beginWipe()
                sdk
            }
        }
        assertFailsWith<SparkError.NotConnected> { sut.wipeStorage() }

        verify(sdk, times(3)).disconnect()
        verify(sdk, never()).addEventListener(any())
        assertTrue(storage.exists())
    }

    @Test
    fun `a stale token is refused over an sdk kept by a disconnect that failed, which gets no listener`() = runTest {
        val sut = WatchSparkService(mock<Context>())
        val sdk = connected(sut)
        whenever(sdk.disconnect()).thenAnswer { throw SparkError.NotConnected() }
        val token = assertNotNull(sut.wipeToken())
        sut.beginWipe()
        assertFailsWith<SparkError.NotConnected> { sut.disconnect() }

        assertFailsWith<SparkError.WipeInProgress> { sut.connectWith(token) { error("built during a wipe") } }

        verify(sdk, times(1)).addEventListener(any())
    }

    @Test
    fun `a listener that cannot be removed does not stop the disconnect`() = runTest {
        val sut = WatchSparkService(mock<Context>())
        val sdk = connected(sut)
        whenever(sdk.removeEventListener(any())).thenAnswer { throw SparkError.NotConnected() }

        sut.disconnect()

        verify(sdk).disconnect()
        assertFailsWith<SparkError.NotConnected> { sut.getInfo() }
    }

    @Test
    fun `a disconnect that fails keeps the sdk, so the next one reaches it`() = runTest {
        val sut = WatchSparkService(mock<Context>())
        val sdk = connected(sut)
        var failures = 1
        whenever(sdk.disconnect()).thenAnswer { if (failures-- > 0) throw SparkError.NotConnected() }

        assertFailsWith<SparkError.NotConnected> { sut.disconnect() }
        assertFalse(sut.connectWith(assertNotNull(sut.wipeToken())) { error("built over a live sdk") })

        sut.disconnect()

        verify(sdk, times(2)).disconnect()
        assertFailsWith<SparkError.NotConnected> { sut.getInfo() }
    }

    @Test
    fun `a listener removed by a disconnect that failed is not removed twice`() = runTest {
        val sut = WatchSparkService(mock<Context>())
        val sdk = connected(sut)
        var failures = 1
        whenever(sdk.disconnect()).thenAnswer { if (failures-- > 0) throw SparkError.NotConnected() }

        assertFailsWith<SparkError.NotConnected> { sut.disconnect() }
        sut.disconnect()

        verify(sdk, times(1)).removeEventListener("listener")
    }

    @Test
    fun `an sdk kept by a disconnect that failed gets its listener back when it connects again`() = runTest {
        val sut = WatchSparkService(mock<Context>())
        val sdk = connected(sut)
        whenever(sdk.disconnect()).thenAnswer { throw SparkError.NotConnected() }
        assertFailsWith<SparkError.NotConnected> { sut.disconnect() }

        assertFalse(sut.connectWith(assertNotNull(sut.wipeToken())) { error("built over a live sdk") })
        assertFalse(sut.connectWith(assertNotNull(sut.wipeToken())) { error("built over a live sdk") })

        verify(sdk, times(2)).addEventListener(any())
    }

    @Test
    fun `a wipe tries a disconnect that failed again, then deletes the storage`() = runTest {
        val sut = WatchSparkService(context())
        val sdk = connected(sut)
        val storage = storage()
        var failures = 1
        whenever(sdk.disconnect()).thenAnswer { if (failures-- > 0) throw SparkError.NotConnected() }

        sut.wipeStorage()

        verify(sdk, times(2)).disconnect()
        assertFalse(storage.exists())
        assertFailsWith<SparkError.NotConnected> { sut.getInfo() }
    }

    @Test
    fun `a wipe that cannot disconnect spark fails, and keeps the sdk and its storage`() = runTest {
        val sut = WatchSparkService(context())
        val sdk = connected(sut)
        val storage = storage()
        whenever(sdk.disconnect()).thenAnswer { throw SparkError.NotConnected() }

        assertFailsWith<SparkError.NotConnected> { sut.wipeStorage() }

        verify(sdk, times(2)).disconnect()
        assertTrue(storage.exists())
        assertFalse(sut.connectWith(assertNotNull(sut.wipeToken())) { error("built over a live sdk") })
    }

    @Test
    fun `a disconnect whose caller is cancelled still disconnects`() = runTest {
        val sut = WatchSparkService(mock<Context>())
        val sdk = connected(sut)
        lateinit var caller: Job
        whenever(sdk.removeEventListener(any())).doSuspendableAnswer {
            caller.cancel()
            yield()
            true
        }

        caller = launch { sut.disconnect() }
        caller.join()

        verify(sdk).disconnect()
        assertFailsWith<SparkError.NotConnected> { sut.getInfo() }
    }

    private fun context() = mock<Context>().also { whenever(it.filesDir).thenReturn(folder.root) }

    private fun storage() = File(folder.root, "${Env.network.name.lowercase()}/wallet0/spark").apply {
        mkdirs()
        File(this, "storage.sql").writeText("spark")
    }

    private suspend fun connected(sut: WatchSparkService): BreezSdkInterface {
        val sdk = mock<BreezSdkInterface>()
        whenever(sdk.addEventListener(any())).thenReturn("listener")
        whenever(sdk.removeEventListener(any())).thenReturn(true)
        assertTrue(sut.connectWith(assertNotNull(sut.wipeToken())) { sdk })
        return sdk
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

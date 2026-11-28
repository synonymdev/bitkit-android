package to.bitkit.services

import breez_sdk_spark.BitcoinNetwork
import breez_sdk_spark.Bolt11Invoice
import breez_sdk_spark.Bolt11InvoiceDetails
import breez_sdk_spark.Bolt11RouteHint
import breez_sdk_spark.Bolt11RouteHintHop
import breez_sdk_spark.ChainApiType
import breez_sdk_spark.Config
import breez_sdk_spark.LeafOptimizationConfig
import breez_sdk_spark.Network
import breez_sdk_spark.PaymentRequestSource
import breez_sdk_spark.SendPaymentOptions
import breez_sdk_spark.SparkConfig
import breez_sdk_spark.TokenOptimizationConfig
import breez_sdk_spark.parseSparkConfig
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.env.LocalSparkSettings
import java.io.File
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SparkServiceTest {
    private companion object {
        const val SPARK_IDENTITY = "02698b27ac308b275671b3ca25436346469d04a5bba578ae39feba1d65897a6abc"
        const val SPARK_PROVIDER_NODE = "0313ee54b3e35eed377248c83e77004972a3807af648a22ea1e8a9d22ef8b9a11c"
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `connect is refused during a wallet wipe`() = runTest {
        val keychain = mock<Keychain>()
        val sut = SparkService(keychain).apply { isWiping = true }

        assertIs<SparkError.WipeInProgress>(runCatching { sut.connect(0) }.exceptionOrNull())

        assertFalse(sut.isConnected)
        verifyNoInteractions(keychain)
    }

    @Test
    fun `wipe deletes spark storage and a staged exit data restore`() = runTest {
        Env.initAppStoragePath(tempFolder.root.absolutePath)
        val storage = File(Env.sparkStoragePath(0)).apply { mkdirs() }
        File(storage, "db").writeText("old wallet")
        val staged = File(Env.sparkExitRestorePath(0)).apply { parentFile?.mkdirs() }
        staged.writeText("state")

        SparkService(mock()).wipeStorage(0)

        assertFalse(storage.exists())
        assertFalse(staged.exists())
    }

    @Test
    fun `lightning sends return before completion so callers see the completion event`() {
        val options = assertIs<SendPaymentOptions.Bolt11Invoice>(SparkService.bolt11SendOptions(prefersSpark = true))

        assertNull(options.completionTimeoutSecs)
        assertEquals(true, options.preferSpark)
    }

    @Test
    fun `only spark's own route hint marks an invoice as carrying a spark address`() {
        assertTrue(SparkService.hasSparkAddressHint(listOf(1uL, 17_592_187_092_992_000_001uL)))
        assertFalse(SparkService.hasSparkAddressHint(listOf(1uL, 17_592_187_092_992_000_000uL)))
        assertFalse(SparkService.hasSparkAddressHint(emptyList()))
    }

    // What SDK 0.28.3 printed on the local regtest Spark stack (2026-11-27) for a wallet whose identity is
    // SPARK_IDENTITY: an invoice made with Bitkit's settings carries only the provider's channel hint, and one made
    // with `preferSparkOverLightning` on also carries the Spark address hint, whose node is the identity.
    @Test
    fun `an invoice names a spark identity only by its spark address hint`() {
        val providerHint = hint("026ccad2762df4865753f7adc464acdbb9ae4509d0f2109789b91941cfe0e67261", "1x1026x2")
        val sparkHint = hint(SPARK_IDENTITY, "16000000x16000000x1")

        assertEquals("16000000x16000000x1", SparkService.sparkAddressHintScid)
        assertTrue(SparkService.namesSparkIdentity(invoice(providerHint, sparkHint), SPARK_IDENTITY))
        assertTrue(SparkService.namesSparkIdentity(invoice(providerHint, sparkHint), SPARK_IDENTITY.uppercase()))
        assertFalse(SparkService.namesSparkIdentity(invoice(providerHint), SPARK_IDENTITY))
        assertFalse(SparkService.namesSparkIdentity(invoice(providerHint, sparkHint), SPARK_PROVIDER_NODE))
        assertFalse(SparkService.namesSparkIdentity(invoice(hint(SPARK_IDENTITY, "1x1026x2")), SPARK_IDENTITY))
    }

    @Test
    fun `an invoice expiry past the largest time stays at the largest time`() {
        val farOff = SparkService.invoiceExpiresAt(timestamp = ULong.MAX_VALUE - 1uL, expiry = 3_600uL)
        assertEquals(ULong.MAX_VALUE, farOff)
        assertEquals(1_800_003_600uL, SparkService.invoiceExpiresAt(timestamp = 1_800_000_000uL, expiry = 3_600uL))
    }

    @Test
    fun `claim fee estimate covers the claims seen on mainnet`() {
        // Mainnet claims so far took 198 and 297 sats; the cap at 1 sat/vB stays above both.
        assertEquals(302uL, SparkService.maxDepositClaimFeeSats(fastestFeeRate = 1uL, amountSats = 2_500uL))
        assertEquals(327uL, SparkService.maxDepositClaimFeeSats(fastestFeeRate = 1uL, amountSats = 15_000uL))
    }

    @Test
    fun `later claim fee prices the mature claim at the half-hour rate`() {
        // Run 2 quoted the mature claim at 198 sats at 2 sat/vB.
        assertEquals(198uL, SparkService.matureDepositClaimFeeSats(halfHourFeeRate = 2uL))
    }

    /** A config as `defaultConfig` returns it, with Breez's data sync and Lightning address servers set. */
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
        leafOptimizationConfig = LeafOptimizationConfig(autoEnabled = true, multiplicity = 1u),
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

    @Test
    fun `config turns off breez data sync, the lightning address server and default parsers on every network`() {
        Network.entries.forEach { network ->
            val config = SparkService.configure(defaults(network), apiKey = "key", isExiting = false)

            assertNull(config.realTimeSyncServerUrl, "$network")
            assertNull(config.lnurlDomain, "$network")
            assertFalse(config.useDefaultExternalInputParsers, "$network")
            assertEquals("key", config.apiKey)
        }
    }

    @Test
    fun `local spark selects the fixture operators and mempool API together`() {
        val config = defaults(Network.REGTEST)
        val json = """{"threshold":2}"""
        val settings = LocalSparkSettings(Base64.getEncoder().encodeToString(json.toByteArray()), "http://fixture/api")
        val operators = mock<SparkConfig>()

        mockStatic(Class.forName("breez_sdk_spark.Breez_sdk_spark_jvmKt")).use { native ->
            native.`when`<SparkConfig> { parseSparkConfig(json) }.thenReturn(operators)
            assertEquals(ChainApiType.MEMPOOL_SPACE, SparkService.configureLocalSpark(config, settings))
            assertEquals(operators, config.sparkConfig)
            assertEquals(false, config.watchtowerFeeLadderEnabled)
        }
    }

    @Test
    fun `local spark requires both fixture values and leaves defaults untouched without a fixture`() {
        val config = defaults(Network.REGTEST)
        assertEquals(ChainApiType.ESPLORA, SparkService.configureLocalSpark(config, null))
        for (settings in listOf(LocalSparkSettings("encoded", ""), LocalSparkSettings("", "http://fixture/api"))) {
            assertFailsWith<IllegalArgumentException> { SparkService.configureLocalSpark(config, settings) }
        }
        assertFailsWith<IllegalArgumentException> {
            SparkService.configureLocalSpark(config, LocalSparkSettings("invalid base64", "http://fixture/api"))
        }
        assertNull(config.sparkConfig)
        assertNull(config.watchtowerFeeLadderEnabled)
    }

    private fun hint(srcNodeId: String, shortChannelId: String) = Bolt11RouteHint(
        listOf(Bolt11RouteHintHop(srcNodeId, shortChannelId, 0u, 0u, 0u, null, null)),
    )

    private fun invoice(vararg hints: Bolt11RouteHint) = Bolt11InvoiceDetails(
        amountMsat = null,
        description = "",
        descriptionHash = null,
        expiry = 3_600uL,
        invoice = Bolt11Invoice("lnbcrt1invoice", PaymentRequestSource(null, null)),
        minFinalCltvExpiryDelta = 60uL,
        network = BitcoinNetwork.REGTEST,
        payeePubkey = SPARK_PROVIDER_NODE,
        paymentHash = "0a00a78ffbde5c36ad8220a26bb2609347bbf7e7c195622c42b81f088ccf1f6e",
        paymentSecret = "secret",
        routingHints = hints.toList(),
        timestamp = 1_795_827_219uL,
    )

    @Test
    fun `invoices carry no spark address on any network, so every payment is a lightning payment bitkit records`() {
        Network.entries.forEach { network ->
            val config = SparkService.configure(defaults(network), apiKey = null, isExiting = false)
            assertFalse(config.preferSparkOverLightning, "$network")
        }
    }
}

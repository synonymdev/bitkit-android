package to.bitkit.services

import breez_sdk_spark.Config
import breez_sdk_spark.LeafOptimizationConfig
import breez_sdk_spark.Network
import breez_sdk_spark.SendPaymentOptions
import breez_sdk_spark.TokenOptimizationConfig
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class SparkServiceTest {
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
    fun `invoices carry no spark address on any network, so every payment is a lightning payment bitkit records`() {
        Network.entries.forEach { network ->
            val config = SparkService.configure(defaults(network), apiKey = null, isExiting = false)
            assertFalse(config.preferSparkOverLightning, "$network")
        }
    }
}

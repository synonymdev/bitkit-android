package to.bitkit.services

import breez_sdk_spark.Config
import breez_sdk_spark.LeafOptimizationConfig
import breez_sdk_spark.Network
import breez_sdk_spark.SendPaymentOptions
import breez_sdk_spark.TokenOptimizationConfig
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SparkServiceTest {
    @Test
    fun `lightning sends return before completion so callers see the completion event`() {
        val options = assertIs<SendPaymentOptions.Bolt11Invoice>(SparkService.bolt11SendOptions(prefersSpark = true))

        assertNull(options.completionTimeoutSecs)
        assertEquals(true, options.preferSpark)
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
    fun `config turns off breez data sync and the lightning address server on every network`() {
        Network.entries.forEach { network ->
            val config = SparkService.configure(defaults(network), apiKey = "key", isExiting = false)

            assertNull(config.realTimeSyncServerUrl, "$network")
            assertNull(config.lnurlDomain, "$network")
            assertEquals("key", config.apiKey)
        }
    }
}

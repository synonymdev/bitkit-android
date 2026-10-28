package to.bitkit.services

import breez_sdk_spark.SendPaymentOptions
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
}

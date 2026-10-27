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
}

package to.bitkit.data

import org.junit.Test
import to.bitkit.data.serializers.AppCacheSerializer
import to.bitkit.models.BalanceState
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals

class AppCacheDataTest : BaseUnitTest() {
    companion object {
        private const val LEGACY_BOLT11 =
            "lnbc1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs" +
                "pp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypq" +
                "dpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq" +
                "9qrsgq357wnc5r2ueh7ck6q93dj32dlqnls087fxdwk8qakdyafkq3yap9us6v52" +
                "vjjsrvywa6rt52cm9r9zqt8r2t7mlcwspyetp5h2tztugp9lfyql"
    }

    @Test
    fun `AppCacheSerializer reads a legacy receive invoice without a stored payment hash`() = test {
        val cachedReceive = AppCacheSerializer.readFrom(
            """{"bolt11":"$LEGACY_BOLT11","bip21":"bitcoin:bc1qtest?lightning=$LEGACY_BOLT11"}"""
                .byteInputStream(),
        )

        assertEquals(LEGACY_BOLT11, cachedReceive.bolt11)
        assertEquals("", cachedReceive.bolt11PaymentHash)
    }

    @Test
    fun `invalidateReceiveLightningInvoice clears the paid request and preserves other cache data`() {
        val cache = AppCacheData(
            onchainAddress = "bc1qtest",
            bolt11 = "paidInvoice",
            bolt11PaymentHash = "paymentHash",
            bip21 = "bitcoin:bc1qtest?lightning=paidInvoice",
            balance = BalanceState(totalOnchainSats = 42uL),
            paidOrders = mapOf("order" to "txid"),
        )

        val invalidated = cache.invalidateReceiveLightningInvoice()

        assertEquals("", invalidated.bolt11)
        assertEquals("", invalidated.bolt11PaymentHash)
        assertEquals("", invalidated.bip21)
        assertEquals(cache.onchainAddress, invalidated.onchainAddress)
        assertEquals(cache.balance, invalidated.balance)
        assertEquals(cache.paidOrders, invalidated.paidOrders)
    }
}

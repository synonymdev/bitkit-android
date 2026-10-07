package to.bitkit.models

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PaykitAmountTest {
    private val now = 1_800_000_000_000L
    private val rate = PaykitExchangeRate("100000", now)

    @Test
    fun `conversion preserves value and rounds payment atomic units upward`() {
        data class Case(val source: PaykitAsset, val value: String, val target: PaykitAsset, val expected: String)
        val cases = listOf(
            Case(PaykitAsset.USD, "5", PaykitAsset.USDT, "5"),
            Case(PaykitAsset.USDT, "5.000001", PaykitAsset.USD, "5.01"),
            Case(PaykitAsset.BTC, "0.00000001", PaykitAsset.USDT, "0.001"),
            Case(PaykitAsset.USD, "5", PaykitAsset.BTC, "0.00005"),
            Case(PaykitAsset.USDT, "0.000001", PaykitAsset.BTC, "0.00000001"),
        )
        cases.forEach {
            assertEquals(it.expected, PaykitAmount.parse(it.source, it.value).convertedTo(it.target, rate, now).value)
        }
    }

    @Test
    fun `stale missing and future rates block only bitcoin conversions`() {
        val amount = PaykitAmount.parse(PaykitAsset.USD, "5")
        listOf(
            null,
            rate.copy(timestampMillis = now - 600001),
            rate.copy(timestampMillis = now + 1),
            rate.copy(price = "0"),
            rate.copy(price = "NaN")
        ).forEach { invalid ->
            assertFailsWith<PaykitAmountError.RateUnavailable> { amount.convertedTo(PaykitAsset.BTC, invalid, now) }
            assertEquals("5", amount.convertedTo(PaykitAsset.USDT, invalid, now).value)
        }
        assertEquals(
            "0.00005",
            amount.convertedTo(PaykitAsset.BTC, rate.copy(timestampMillis = now - 600000), now).value
        )
    }

    @Test
    fun `amount parsing enforces positive exact decimals and atomic bounds`() {
        listOf("", "0", "-1", "+1", "1e2", "1,2", ".5", "5.", "0.0000001", "18446744073710").forEach {
            assertFailsWith<PaykitAmountError.InvalidAmount> { PaykitAmount.parse(PaykitAsset.USDT, it) }
        }
        assertEquals("1.23", PaykitAmount.parse(PaykitAsset.USDT, "01.23000000").value)
        assertEquals(ULong.MAX_VALUE, PaykitAmount.parse(PaykitAsset.USDT, "18446744073709.551615").atomic)
    }
}

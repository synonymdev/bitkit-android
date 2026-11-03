package to.bitkit.models

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PaykitAmountTest {
    @Test
    fun `editor amounts keep cents and exact sub-cent precision without changing wire amounts`() {
        for ((asset, value, expected) in listOf(
            Triple(PaykitAsset.USD, "150.50", "150.50"),
            Triple(PaykitAsset.USDT, "150.50", "150.50"),
            Triple(PaykitAsset.USDT, "1", "1.00"),
            Triple(PaykitAsset.USDT, "0.000001", "0.000001"),
            Triple(PaykitAsset.USDT, "18446744073709.551615", "18446744073709.551615"),
            Triple(PaykitAsset.BTC, "0.00000001", "0.00000001"),
        )) {
            val amount = PaykitAmount.parse(asset, value)
            assertEquals(expected, amount.inputValue)
            assertEquals(amount, PaykitAmount.parseInput(asset, amount.inputValue))
        }
        assertEquals("150.5", PaykitAmount.parse(PaykitAsset.USDT, "150.50").value)
    }

    @Test
    fun `editing conversion preserves the original across repeated toggles and resets on edits`() {
        val converter = PaykitAmountConversion()
        val rate = PaykitExchangeRate("65432.10", now)
        val original = PaykitAmount.parse(PaykitAsset.USD, "10")
        var amount = original
        repeat(5) {
            amount = converter.convert(amount, PaykitAsset.BTC, rate, now)
            assertEquals(15_284uL, amount.atomic)
            amount = converter.convert(amount, PaykitAsset.USD, rate, now)
            assertEquals(original, amount)
        }
        val edited = PaykitAmount.parse(PaykitAsset.BTC, "0.001")
        amount = converter.convert(edited, PaykitAsset.USD, rate, now)
        assertEquals("65.44", amount.value)
        assertEquals(edited, converter.convert(amount, PaykitAsset.BTC, rate, now))
        assertEquals(0uL, converter.convert(null, PaykitAsset.USD, null, now).atomic)
    }

    @Test
    fun `amount entry accepts a trailing decimal separator without weakening protocol parsing`() {
        assertEquals("5", PaykitAmount.parseInput(PaykitAsset.USDT, "5.").value)
        for (input in listOf(".", "5..", "0.", "1e2")) {
            assertFailsWith<PaykitAmountError.InvalidAmount> { PaykitAmount.parseInput(PaykitAsset.USDT, input) }
        }
        assertFailsWith<PaykitAmountError.InvalidAmount> { PaykitAmount.parse(PaykitAsset.USDT, "5.") }
    }

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

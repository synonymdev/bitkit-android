package to.bitkit.models

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class USatTest {

    // region Subtraction
    @Test
    fun `minus returns difference when a greater than b`() {
        val result = USat(10uL) - USat(5uL)
        assertEquals(5uL, result)
    }

    @Test
    fun `minus returns zero when a equals b`() {
        val result = USat(5uL) - USat(5uL)
        assertEquals(0uL, result)
    }

    @Test
    fun `minus returns zero when would underflow`() {
        val result = USat(5uL) - USat(10uL)
        assertEquals(0uL, result)
    }

    @Test
    fun `minus handles max ULong values`() {
        val result = USat(0uL) - USat(ULong.MAX_VALUE)
        assertEquals(0uL, result)
    }

    @Test
    fun `chained minus operations work correctly`() {
        val intermediate = 100uL.safe() - 30uL.safe()
        val result = intermediate.safe() - 20uL.safe()
        assertEquals(50uL, result)
    }

    @Test
    fun `chained minus returns zero when intermediate would underflow`() {
        val intermediate = 10uL.safe() - 20uL.safe()
        val result = intermediate.safe() - 5uL.safe()
        assertEquals(0uL, result)
    }
    // endregion

    // region Addition
    @Test
    fun `plus returns sum`() {
        val result = USat(10uL) + USat(5uL)
        assertEquals(15uL, result)
    }

    @Test
    fun `plus saturates at max when would overflow`() {
        val result = USat(ULong.MAX_VALUE) + USat(1uL)
        assertEquals(ULong.MAX_VALUE, result)
    }

    @Test
    fun `plus saturates when both values are large`() {
        val result = USat(ULong.MAX_VALUE - 10uL) + USat(20uL)
        assertEquals(ULong.MAX_VALUE, result)
    }

    @Test
    fun `chained plus operations work correctly`() {
        val intermediate = 10uL.safe() + 20uL.safe()
        val result = intermediate.safe() + 30uL.safe()
        assertEquals(60uL, result)
    }
    // endregion

    // region Multiplication
    @Test
    fun `times returns product`() {
        val result = USat(42uL) * USat(1_000uL)
        assertEquals(42_000uL, result)
    }

    @Test
    fun `times returns zero when either value is zero`() {
        assertEquals(0uL, USat(0uL) * USat(1_000uL))
        assertEquals(0uL, USat(1_000uL) * USat(0uL))
    }

    @Test
    fun `times saturates at max when would overflow`() {
        val result = USat(ULong.MAX_VALUE) * USat(1_000uL)
        assertEquals(ULong.MAX_VALUE, result)
    }
    // endregion

    // region Comparisons
    @Test
    fun `compareTo returns negative when less than`() {
        assertTrue(USat(5uL) < USat(10uL))
    }

    @Test
    fun `compareTo returns positive when greater than`() {
        assertTrue(USat(10uL) > USat(5uL))
    }

    @Test
    fun `compareTo returns zero when equal`() {
        assertEquals(0, USat(10uL).compareTo(USat(10uL)))
    }

    @Test
    fun `comparison operators work correctly`() {
        assertTrue(USat(5uL) <= USat(10uL))
        assertTrue(USat(10uL) >= USat(5uL))
        assertTrue(USat(10uL) <= USat(10uL))
        assertTrue(USat(10uL) >= USat(10uL))
        assertFalse(USat(10uL) < USat(10uL))
        assertFalse(USat(10uL) > USat(10uL))
    }
    // endregion

    // region Realistic scenarios
    @Test
    fun `realistic bitcoin calculation`() {
        val channelSize = 10_000_000uL // 0.1 BTC in sats
        val balance = 1_000_000uL // 0.01 BTC in sats

        val maxSend = channelSize.safe() - balance.safe()

        assertEquals(9_000_000uL, maxSend)
    }

    @Test
    fun `minus prevents the coerceAtLeast bug`() {
        // The old pattern: (5u - 10u).coerceAtLeast(0u)
        // Would incorrectly return ULong.MAX_VALUE - 4
        val old = (5uL - 10uL).coerceAtLeast(0u)
        assertTrue(old > 1000000u) // WRONG! Shows the bug

        // The new pattern: 5u.safe() - 10u.safe()
        val new = 5uL.safe() - 10uL.safe()
        assertEquals(0uL, new) // CORRECT!
    }
    // endregion
}

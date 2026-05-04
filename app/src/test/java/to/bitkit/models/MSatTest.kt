package to.bitkit.models

import org.junit.Test
import kotlin.test.assertEquals

class MSatTest {

    // region ceil
    @Test
    fun `ceil returns exact sat for multiple of 1000`() {
        assertEquals(1uL, MSat(1000uL).ceil())
    }

    @Test
    fun `ceil rounds up for non-multiple`() {
        assertEquals(2uL, MSat(1001uL).ceil())
    }

    @Test
    fun `ceil rounds up for 1 msat`() {
        assertEquals(1uL, MSat(1uL).ceil())
    }

    @Test
    fun `ceil rounds up for 999 msat`() {
        assertEquals(1uL, MSat(999uL).ceil())
    }

    @Test
    fun `ceil returns zero for zero`() {
        assertEquals(0uL, MSat(0uL).ceil())
    }

    @Test
    fun `ceil handles realistic payment amount`() {
        assertEquals(223uL, MSat(222_222uL).ceil())
    }
    // endregion

    // region floor
    @Test
    fun `floor returns exact sat for multiple of 1000`() {
        assertEquals(2uL, MSat(2000uL).floor())
    }

    @Test
    fun `floor truncates for non-multiple`() {
        assertEquals(1uL, MSat(1999uL).floor())
    }

    @Test
    fun `floor returns zero for sub-sat value`() {
        assertEquals(0uL, MSat(999uL).floor())
    }

    @Test
    fun `floor returns zero for zero`() {
        assertEquals(0uL, MSat(0uL).floor())
    }

    @Test
    fun `floor handles realistic fee amount`() {
        assertEquals(222uL, MSat(222_222uL).floor())
    }
    // endregion

    // region sugar functions
    @Test
    fun `msatCeilOf matches MSat ceil`() {
        assertEquals(MSat(1500uL).ceil(), msatCeilOf(1500uL))
    }

    @Test
    fun `msatFloorOf matches MSat floor`() {
        assertEquals(MSat(1500uL).floor(), msatFloorOf(1500uL))
    }

    @Test
    fun `satsToMsat converts sats to msats`() {
        assertEquals(42_000uL, satsToMsat(42uL))
    }

    @Test
    fun `satsToMsat saturates when conversion would overflow`() {
        assertEquals(ULong.MAX_VALUE, satsToMsat(ULong.MAX_VALUE))
    }
    // endregion
}

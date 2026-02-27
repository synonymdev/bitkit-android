package to.bitkit.utils

import org.junit.Test
import kotlin.test.assertEquals

class BlockTimeHelpersTest {

    @Test
    fun `blocksRemaining returns positive difference`() {
        assertEquals(6, BlockTimeHelpers.blocksRemaining(106u, 100u))
    }

    @Test
    fun `blocksRemaining returns zero when target equals current`() {
        assertEquals(0, BlockTimeHelpers.blocksRemaining(100u, 100u))
    }

    @Test
    fun `blocksRemaining returns zero when target is below current`() {
        assertEquals(0, BlockTimeHelpers.blocksRemaining(95u, 100u))
    }

    @Test
    fun `getDurationForBlocks returns minutes for 6 or fewer blocks`() {
        assertEquals("60m", BlockTimeHelpers.getDurationForBlocks(6))
        assertEquals("10m", BlockTimeHelpers.getDurationForBlocks(1))
        assertEquals("30m", BlockTimeHelpers.getDurationForBlocks(3))
    }

    @Test
    fun `getDurationForBlocks returns hours for 7 to 143 blocks`() {
        assertEquals("1h", BlockTimeHelpers.getDurationForBlocks(7))
        assertEquals("12h", BlockTimeHelpers.getDurationForBlocks(72))
        assertEquals("24h", BlockTimeHelpers.getDurationForBlocks(143))
    }

    @Test
    fun `getDurationForBlocks returns days for more than 143 blocks`() {
        assertEquals("1d", BlockTimeHelpers.getDurationForBlocks(144))
        assertEquals("7d", BlockTimeHelpers.getDurationForBlocks(1008))
    }
}

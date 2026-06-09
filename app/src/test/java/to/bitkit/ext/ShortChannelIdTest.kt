package to.bitkit.ext

import org.junit.Test
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShortChannelIdTest : BaseUnitTest() {

    @Test
    fun `formattedAsShortChannelId formats lnd scid into cln form`() {
        // The two formats the issue calls out (LND uint64 and CLN block x tx x output)
        // are two encodings of the same channel.
        assertEquals("777477x916x0", 854_845_001_888_432_128uL.formattedAsShortChannelId())
    }

    @Test
    fun `formattedAsShortChannelId formats from components`() {
        val scid = (700_000uL shl 40) or (1uL shl 16) or 2uL
        assertEquals("700000x1x2", scid.formattedAsShortChannelId())
    }

    @Test
    fun `formattedAsShortChannelId formats zero as all zeroes`() {
        assertEquals("0x0x0", 0uL.formattedAsShortChannelId())
    }

    @Test
    fun `formattedAsShortChannelId keeps max components in their fields`() {
        val scid = (0xFFFFFFuL shl 40) or (0xFFFFFFuL shl 16) or 0xFFFFuL
        assertEquals("16777215x16777215x65535", scid.formattedAsShortChannelId())
    }

    @Test
    fun `resolveDisplayShortChannelId prefers channel scid for open channel`() {
        val result = resolveDisplayShortChannelId(
            channelScid = 854_845_001_888_432_128uL,
            linkedOrderScid = "0",
        )
        assertEquals("777477x916x0", result)
    }

    @Test
    fun `resolveDisplayShortChannelId falls back to linked order scid for closed channel`() {
        val result = resolveDisplayShortChannelId(
            channelScid = null,
            linkedOrderScid = "854845001888432128",
        )
        assertEquals("777477x916x0", result)
    }

    @Test
    fun `resolveDisplayShortChannelId returns null when both unavailable`() {
        assertNull(resolveDisplayShortChannelId(channelScid = null, linkedOrderScid = null))
    }

    @Test
    fun `resolveDisplayShortChannelId returns null for non-numeric order scid`() {
        assertNull(resolveDisplayShortChannelId(channelScid = null, linkedOrderScid = "not-a-number"))
    }
}

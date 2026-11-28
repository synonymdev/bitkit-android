package to.bitkit.wear.ui

import android.content.Intent
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LaunchRequestTest {
    @Test
    fun `a payment link asks for a send of the whole link`() {
        val links = listOf(
            "bitcoin:bcrt1q?lightning=lnbcrt1abc",
            "BITCOIN:BCRT1Q?LIGHTNING=LNBCRT1ABC",
            "lightning:lnbcrt1abc",
            "LIGHTNING:LNBCRT1ABC",
            "lnurl:lnurl1abc",
            "lnurlp://pay.example/lnurlp/satoshi",
            "spark:sprt1abc",
        )
        for (link in links) {
            assertEquals(LaunchRequest.Send(link), LaunchRequest.from(Intent.ACTION_VIEW, link, open = null))
        }
    }

    @Test
    fun `the tile's extra asks for the receive QR`() {
        assertEquals(
            LaunchRequest.Receive,
            LaunchRequest.from(Intent.ACTION_MAIN, data = null, open = LaunchRequest.OPEN_RECEIVE),
        )
    }

    @Test
    fun `a plain launch or another link asks for nothing`() {
        assertNull(LaunchRequest.from(Intent.ACTION_MAIN, data = null, open = null))
        assertNull(LaunchRequest.from(Intent.ACTION_VIEW, data = "https://bitkit.to", open = null))
        assertNull(LaunchRequest.from(Intent.ACTION_VIEW, data = "lnbcrt1abc", open = null))
        assertNull(LaunchRequest.from(Intent.ACTION_SEND, data = "lightning:lnbcrt1abc", open = null))
        assertNull(LaunchRequest.from(Intent.ACTION_MAIN, data = null, open = "send"))
    }

    @Test
    fun `a withdraw or channel lnurl link asks for nothing, since the watch only pays`() {
        val links = listOf("lnurlw://pay.example/withdraw", "LNURLW://PAY.EXAMPLE/W", "lnurlc://pay.example/channel")
        for (link in links) {
            assertNull(LaunchRequest.from(Intent.ACTION_VIEW, link, open = null), link)
        }
    }
}

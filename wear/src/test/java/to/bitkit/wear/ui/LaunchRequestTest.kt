package to.bitkit.wear.ui

import android.content.Intent
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LaunchRequestTest {
    @Test
    fun `a payment link asks for a send of the whole link`() {
        for (link in listOf("lightning:lnbcrt1abc", "BITCOIN:bcrt1q?lightning=lnbcrt1abc", "spark:sprt1abc")) {
            assertEquals(LaunchRequest.Send(link), LaunchRequest.from(Intent.ACTION_VIEW, link))
        }
    }

    @Test
    fun `a plain launch or another link asks for nothing`() {
        assertNull(LaunchRequest.from(Intent.ACTION_MAIN, data = null))
        assertNull(LaunchRequest.from(Intent.ACTION_VIEW, data = "https://bitkit.to"))
        assertNull(LaunchRequest.from(Intent.ACTION_VIEW, data = "lnbcrt1abc"))
        assertNull(LaunchRequest.from(Intent.ACTION_SEND, data = "lightning:lnbcrt1abc"))
    }
}

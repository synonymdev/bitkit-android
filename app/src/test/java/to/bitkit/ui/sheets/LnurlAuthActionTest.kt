package to.bitkit.ui.sheets

import org.junit.Test
import kotlin.test.assertEquals

class LnurlAuthActionTest {
    private val base = "https://site.com/auth?tag=login&k1=abc"

    @Test
    fun `action picks the matching wording`() {
        assertEquals(LnurlAuthAction.LOGIN, LnurlAuthAction.fromUri("$base&action=login"))
        assertEquals(LnurlAuthAction.REGISTER, LnurlAuthAction.fromUri("$base&action=register"))
        assertEquals(LnurlAuthAction.LINK, LnurlAuthAction.fromUri("$base&action=link"))
        assertEquals(LnurlAuthAction.AUTH, LnurlAuthAction.fromUri("$base&action=auth"))
    }

    @Test
    fun `missing or unknown action falls back to authenticate`() {
        assertEquals(LnurlAuthAction.AUTH, LnurlAuthAction.fromUri(base))
        assertEquals(LnurlAuthAction.AUTH, LnurlAuthAction.fromUri("$base&action=other"))
        assertEquals(LnurlAuthAction.AUTH, LnurlAuthAction.fromUri("not a uri"))
    }
}

package to.bitkit.ui.sheets

import org.junit.Test
import java.util.Locale
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

    @Test
    fun `encoded action is decoded`() {
        assertEquals(LnurlAuthAction.REGISTER, LnurlAuthAction.fromUri("$base&action=%72egister"))
        assertEquals(LnurlAuthAction.LOGIN, LnurlAuthAction.fromUri("$base&%61ction=login"))
    }

    @Test
    fun `uppercase action matches under a turkish locale`() {
        val default = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(LnurlAuthAction.LOGIN, LnurlAuthAction.fromUri("$base&action=LOGIN"))
            assertEquals(LnurlAuthAction.REGISTER, LnurlAuthAction.fromUri("$base&action=REGISTER"))
            assertEquals(LnurlAuthAction.LINK, LnurlAuthAction.fromUri("$base&action=LINK"))
        } finally {
            Locale.setDefault(default)
        }
    }
}

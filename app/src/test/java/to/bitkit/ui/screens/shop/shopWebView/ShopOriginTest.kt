package to.bitkit.ui.screens.shop.shopWebView

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShopOriginTest {

    @Test
    fun `bridge script accepts only Bitrefill https message origins`() {
        val script = shopMessageBridgeScript()

        assertTrue("addEventListener('message'" in script)
        assertFalse("window.postMessage =" in script)
        assertTrue("originUrl.protocol !== 'https:'" in script)
        assertTrue("host !== 'bitrefill.com' && !host.endsWith('.bitrefill.com')" in script)
        assertTrue("catch (e)" in script)
    }

    @Test
    fun `https Bitrefill hosts are allowed`() {
        assertTrue(isAllowedShopOrigin("https://embed.bitrefill.com"))
        assertTrue(isAllowedShopOrigin("https://embed.bitrefill.com/gift-cards"))
        assertTrue(isAllowedShopOrigin("https://bitrefill.com"))
        assertTrue(isAllowedShopOrigin("https://www.bitrefill.com/esims"))
        assertTrue(isAllowedShopHost("embed.bitrefill.com"))
        assertTrue(isAllowedShopHost("BITREFILL.COM"))
        assertEquals(
            setOf("https://bitrefill.com", "https://*.bitrefill.com"),
            shopAllowedOriginRules(),
        )
    }

    @Test
    fun `non-Bitrefill and non-https origins are rejected`() {
        assertFalse(isAllowedShopOrigin(null))
        assertFalse(isAllowedShopOrigin(""))
        assertFalse(isAllowedShopOrigin("embed.bitrefill.com"))
        assertFalse(isAllowedShopOrigin("https://evil.example"))
        assertFalse(isAllowedShopOrigin("https://bitrefill.com.evil.example"))
        assertFalse(isAllowedShopOrigin("https://notbitrefill.com"))
        assertFalse(isAllowedShopOrigin("http://embed.bitrefill.com"))
        assertFalse(isAllowedShopOrigin("javascript:alert(1)"))
        assertFalse(isAllowedShopOrigin("https://127.0.0.1"))
        assertFalse(isAllowedShopHost("evil.example"))
        assertFalse(isAllowedShopHost(null))
    }
}

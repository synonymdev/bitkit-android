package to.bitkit.ui.screens.shop.shopWebView

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShopOriginTest {

    @Test
    fun `bridge script accepts only the Bitrefill embed origin`() {
        val script = shopMessageBridgeScript()

        assertTrue("addEventListener('message'" in script)
        assertFalse("window.postMessage =" in script)
        assertTrue("event.origin !== 'https://embed.bitrefill.com'" in script)
        assertFalse("endsWith('.bitrefill.com')" in script)
    }

    @Test
    fun `https Bitrefill hosts are allowed`() {
        assertTrue(isAllowedShopOrigin("https://embed.bitrefill.com"))
        assertTrue(isAllowedShopOrigin("https://embed.bitrefill.com/gift-cards"))
        assertTrue(isAllowedShopOrigin("https://bitrefill.com"))
        assertTrue(isAllowedShopOrigin("https://www.bitrefill.com/esims"))
        assertTrue(isAllowedShopHost("embed.bitrefill.com"))
        assertTrue(isAllowedShopHost("BITREFILL.COM"))
    }

    @Test
    fun `payment messages accept only the Bitrefill embed origin`() {
        assertTrue(isAllowedShopPaymentOrigin("https://embed.bitrefill.com"))
        assertTrue(isAllowedShopPaymentOrigin("HTTPS://EMBED.BITREFILL.COM"))
        assertFalse(isAllowedShopPaymentOrigin("https://bitrefill.com"))
        assertFalse(isAllowedShopPaymentOrigin("https://checkout.bitrefill.com"))
        assertFalse(isAllowedShopPaymentOrigin("https://embed.bitrefill.com/gift-cards"))
        assertFalse(isAllowedShopPaymentOrigin("https://embed.bitrefill.com.evil.example"))
        assertFalse(isAllowedShopPaymentOrigin("http://embed.bitrefill.com"))
        assertFalse(isAllowedShopPaymentOrigin("https://embed.bitrefill.com:444"))
        assertEquals(setOf("https://embed.bitrefill.com"), shopPaymentOriginRules())
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

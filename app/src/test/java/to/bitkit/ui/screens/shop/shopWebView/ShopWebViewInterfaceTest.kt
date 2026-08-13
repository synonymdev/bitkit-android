package to.bitkit.ui.screens.shop.shopWebView

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShopWebViewInterfaceTest {

    @Test
    fun `payment_intent from an allowed origin is forwarded`() {
        var received: String? = null
        val sut = interfaceOf(
            pageUrl = "https://embed.bitrefill.com/gift-cards",
            onPaymentIntent = { received = it },
        )

        sut.postMessage("""{"event":"payment_intent","paymentUri":"lightning:lnbcrt1shop"}""")

        assertEquals("lightning:lnbcrt1shop", received)
    }

    @Test
    fun `payment_intent from a disallowed origin is ignored`() {
        var received: String? = null
        val sut = interfaceOf(
            pageUrl = "https://evil.example",
            onPaymentIntent = { received = it },
        )

        sut.postMessage("""{"event":"payment_intent","paymentUri":"lightning:lnbcrt1shop"}""")

        assertNull(received)
    }

    @Test
    fun `payment_intent with a blank URI is ignored`() {
        var received: String? = null
        val sut = interfaceOf(
            pageUrl = "https://embed.bitrefill.com",
            onPaymentIntent = { received = it },
        )

        sut.postMessage("""{"event":"payment_intent","paymentUri":"  "}""")

        assertNull(received)
    }

    @Test
    fun `unknown events are ignored`() {
        var received: String? = null
        val sut = interfaceOf(
            pageUrl = "https://embed.bitrefill.com",
            onPaymentIntent = { received = it },
        )

        sut.postMessage("""{"event":"invoice","paymentUri":"lightning:lnbcrt1shop"}""")

        assertNull(received)
    }

    private fun interfaceOf(
        pageUrl: String?,
        onPaymentIntent: (String) -> Unit,
    ) = ShopWebViewInterface(
        onPaymentIntent = onPaymentIntent,
        currentUrl = { pageUrl },
        runOnMain = { it() },
    )
}

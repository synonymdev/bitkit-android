package to.bitkit.ui.screens.shop.shopWebView

import android.webkit.WebView
import androidx.core.net.toUri
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class ShopWebViewInterfaceTest : BaseUnitTest() {

    @Test
    fun `payment_intent from an allowed origin is forwarded`() {
        var received: String? = null
        val sut = interfaceOf(
            onPaymentIntent = { received = it },
        )

        sut.onWebMessage(
            WebMessageCompat("""{"event":"payment_intent","paymentUri":"lightning:lnbcrt1shop"}"""),
            "https://embed.bitrefill.com/gift-cards",
        )

        assertEquals("lightning:lnbcrt1shop", received)
    }

    @Test
    fun `payment_intent from a disallowed origin is ignored`() {
        var received: String? = null
        val sut = interfaceOf(
            onPaymentIntent = { received = it },
        )

        sut.onWebMessage(
            WebMessageCompat("""{"event":"payment_intent","paymentUri":"lightning:lnbcrt1shop"}"""),
            "https://evil.example",
        )

        assertNull(received)
    }

    @Test
    fun `payment_intent with a blank URI is ignored`() {
        var received: String? = null
        val sut = interfaceOf(
            onPaymentIntent = { received = it },
        )

        sut.onWebMessage(
            WebMessageCompat("""{"event":"payment_intent","paymentUri":"  "}"""),
            "https://embed.bitrefill.com",
        )

        assertNull(received)
    }

    @Test
    fun `unknown events are ignored`() {
        var received: String? = null
        val sut = interfaceOf(
            onPaymentIntent = { received = it },
        )

        sut.onWebMessage(
            WebMessageCompat("""{"event":"invoice","paymentUri":"lightning:lnbcrt1shop"}"""),
            "https://embed.bitrefill.com",
        )

        assertNull(received)
    }

    @Test
    fun `payment_intent from a bitrefill iframe origin is forwarded`() {
        var received: String? = null
        val sut = interfaceOf(
            onPaymentIntent = { received = it },
        )

        sut.onWebMessage(
            WebMessageCompat("""{"event":"payment_intent","paymentUri":"lightning:lnbcrt1shop"}"""),
            "https://checkout.bitrefill.com",
        )

        assertEquals("lightning:lnbcrt1shop", received)
    }

    @Test
    fun `array buffer messages are ignored`() {
        var received: String? = null
        val sut = interfaceOf(onPaymentIntent = { received = it })

        sut.onWebMessage(
            WebMessageCompat(byteArrayOf(1, 2, 3)),
            "https://embed.bitrefill.com",
        )

        assertNull(received)
    }

    @Test
    fun `supported WebMessageListener is registered and forwards messages`() {
        val webView = mock<WebView>()
        var received: String? = null
        var registeredObjectName: String? = null
        var registeredOriginRules: Set<String>? = null
        var registeredListener: WebViewCompat.WebMessageListener? = null
        val sut = interfaceOf(
            onPaymentIntent = { received = it },
            addWebMessageListener = { registeredWebView, jsObjectName, allowedOriginRules, listener ->
                assertSame(webView, registeredWebView)
                registeredObjectName = jsObjectName
                registeredOriginRules = allowedOriginRules
                registeredListener = listener
            },
        )

        sut.attachTo(webView)
        requireNotNull(registeredListener).onPostMessage(
            webView,
            WebMessageCompat("""{"event":"payment_intent","paymentUri":"lightning:lnbcrt1shop"}"""),
            "https://embed.bitrefill.com".toUri(),
            true,
            mock(),
        )

        assertEquals("Android", registeredObjectName)
        assertEquals(shopAllowedOriginRules(), registeredOriginRules)
        assertEquals("lightning:lnbcrt1shop", received)
    }

    @Test
    fun `unsupported WebMessageListener does not register a JavaScript interface`() {
        val webView = mock<WebView>()
        var webMessageListenerRegistered = false
        val sut = interfaceOf(
            onPaymentIntent = {},
            isWebMessageListenerSupported = { false },
            addWebMessageListener = { _, _, _, _ -> webMessageListenerRegistered = true },
        )

        sut.attachTo(webView)

        verify(webView, never()).addJavascriptInterface(any(), any())
        assertFalse(webMessageListenerRegistered)
    }

    private fun interfaceOf(
        onPaymentIntent: (String) -> Unit,
        isWebMessageListenerSupported: () -> Boolean = { true },
        addWebMessageListener: (
            WebView,
            String,
            Set<String>,
            WebViewCompat.WebMessageListener,
        ) -> Unit = { _, _, _, _ -> },
    ) = ShopWebViewInterface(
        onPaymentIntent = onPaymentIntent,
        isWebMessageListenerSupported = isWebMessageListenerSupported,
        addWebMessageListener = addWebMessageListener,
    )
}

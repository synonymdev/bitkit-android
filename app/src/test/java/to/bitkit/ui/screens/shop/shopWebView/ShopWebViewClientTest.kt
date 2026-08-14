package to.bitkit.ui.screens.shop.shopWebView

import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.core.net.toUri
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class ShopWebViewClientTest : BaseUnitTest() {

    private val sut = ShopWebViewClient(
        onLoadingStateChanged = {},
        onError = {},
        isPaymentBridgeSupported = { true },
    )

    @Test
    fun `main-frame Bitrefill https navigation is allowed`() {
        val request = request(url = "https://embed.bitrefill.com/gift-cards", isForMainFrame = true)

        assertFalse(sut.shouldOverrideUrlLoading(null, request))
    }

    @Test
    fun `main-frame Bitrefill sibling navigation remains allowed`() {
        val request = request(url = "https://www.bitrefill.com/esims", isForMainFrame = true)

        assertFalse(sut.shouldOverrideUrlLoading(null, request))
    }

    @Test
    fun `main-frame navigation off Bitrefill is blocked`() {
        val request = request(url = "https://evil.example/pay", isForMainFrame = true)

        assertTrue(sut.shouldOverrideUrlLoading(null, request))
    }

    @Test
    fun `subframe requests are not blocked`() {
        val request = request(url = "https://cdn.example/script.js", isForMainFrame = false)

        assertFalse(sut.shouldOverrideUrlLoading(null, request))
    }

    @Test
    fun `bridge script is not injected when the payment bridge is unsupported`() {
        val webView = mock<WebView>()
        val sut = ShopWebViewClient(
            onLoadingStateChanged = {},
            onError = {},
            isPaymentBridgeSupported = { false },
        )

        sut.onPageFinished(webView, "https://embed.bitrefill.com")

        verify(webView, never()).evaluateJavascript(any(), any())
    }

    @Test
    fun `bridge script is injected when the payment bridge is supported`() {
        val webView = mock<WebView>()

        sut.onPageFinished(webView, "https://embed.bitrefill.com")

        verify(webView).evaluateJavascript(shopMessageBridgeScript(), null)
    }

    @Test
    fun `bridge script is not injected on a Bitrefill sibling origin`() {
        val webView = mock<WebView>()

        sut.onPageFinished(webView, "https://www.bitrefill.com/esims")

        verify(webView, never()).evaluateJavascript(any(), any())
    }

    private fun request(url: String, isForMainFrame: Boolean): WebResourceRequest {
        val request = mock<WebResourceRequest>()
        whenever(request.isForMainFrame).thenReturn(isForMainFrame)
        whenever(request.url).thenReturn(url.toUri())
        return request
    }
}

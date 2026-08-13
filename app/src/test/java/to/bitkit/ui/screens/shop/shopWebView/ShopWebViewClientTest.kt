package to.bitkit.ui.screens.shop.shopWebView

import android.webkit.WebResourceRequest
import androidx.core.net.toUri
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
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
    )

    @Test
    fun `main-frame Bitrefill https navigation is allowed`() {
        val request = request(url = "https://embed.bitrefill.com/gift-cards", isForMainFrame = true)

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

    private fun request(url: String, isForMainFrame: Boolean): WebResourceRequest {
        val request = mock<WebResourceRequest>()
        whenever(request.isForMainFrame).thenReturn(isForMainFrame)
        whenever(request.url).thenReturn(url.toUri())
        return request
    }
}

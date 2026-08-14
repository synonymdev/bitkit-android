package to.bitkit.ui.screens.shop.shopWebView

import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import to.bitkit.utils.Logger

/**
 * Custom WebViewClient for handling page loading and error states
 */
class ShopWebViewClient(
    private val onLoadingStateChanged: (Boolean) -> Unit,
    private val onError: () -> Unit,
    private val isPaymentBridgeSupported: () -> Boolean,
) : WebViewClient() {
    private companion object {
        const val TAG = "ShopWebViewClient"
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onLoadingStateChanged(true)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onLoadingStateChanged(false)

        if (isPaymentBridgeSupported() && isAllowedShopPaymentPage(url)) {
            view?.evaluateJavascript(shopMessageBridgeScript(), null)
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        if (request?.isForMainFrame != true) return false
        val url = request.url?.toString()
        if (isAllowedShopOrigin(url)) return false
        Logger.warn("Blocked shop navigation to untrusted origin '$url'", context = TAG)
        return true
    }

    @Suppress("ComplexCondition")
    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        super.onReceivedError(view, request, error)
        Logger.warn(
            "Error: ${error?.description}, Code: ${error?.errorCode}, URL: ${request?.url}",
            context = TAG,
        )
        onLoadingStateChanged(false)

        if (request?.isForMainFrame == true) {
            error?.let {
                if (it.errorCode == ERROR_HOST_LOOKUP ||
                    it.errorCode == ERROR_CONNECT ||
                    it.errorCode == ERROR_TIMEOUT ||
                    it.errorCode == ERROR_FILE_NOT_FOUND
                ) {
                    onError()
                }
            }
        }
    }
}

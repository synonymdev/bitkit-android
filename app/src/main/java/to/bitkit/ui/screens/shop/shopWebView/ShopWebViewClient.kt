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
    private val onError: () -> Unit
) : WebViewClient() {

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onLoadingStateChanged(true)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onLoadingStateChanged(false)

        // Inject JavaScript to bridge postMessage to Android
        view?.evaluateJavascript(
            """
            window.ReactNativeWebView = {
                postMessage: function(data) {
                    Android.postMessage(data);
                }
            };

            // Override the default postMessage if it exists
            if (window.postMessage) {
                window.originalPostMessage = window.postMessage;
                window.postMessage = function(data) {
                    if (typeof data === 'string') {
                        Android.postMessage(data);
                    } else {
                        Android.postMessage(JSON.stringify(data));
                    }
                };
            }
            """.trimIndent(), null
        )
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        super.onReceivedError(view, request, error)
        Logger.warn(
            "Error: ${error?.description}, Code: ${error?.errorCode}, URL: ${request?.url}",
            context = "ShopWebViewScreen"
        )
        onLoadingStateChanged(false)

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

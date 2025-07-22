package to.bitkit.ui.screens.shop.shopDiscover

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import to.bitkit.utils.Logger

/**
 * Simple WebViewClient for the map tab that handles loading states and errors
 */
class MapWebViewClient(
    private val onLoadingStateChanged: (Boolean) -> Unit,
    private val onError: (() -> Unit)? = null
) : WebViewClient() {

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onLoadingStateChanged(true)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onLoadingStateChanged(false)
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        super.onReceivedError(view, request, error)
        Logger.warn(
            "Error: ${error?.description}, Code: ${error?.errorCode}, URL: ${request?.url}",
            context = "MapTabContent",
        )
        onLoadingStateChanged(false)

        error?.let {
            if (it.errorCode == ERROR_HOST_LOOKUP ||
                it.errorCode == ERROR_CONNECT ||
                it.errorCode == ERROR_TIMEOUT ||
                it.errorCode == ERROR_FILE_NOT_FOUND
            ) {
                onError?.invoke()
            }
        }
    }
}

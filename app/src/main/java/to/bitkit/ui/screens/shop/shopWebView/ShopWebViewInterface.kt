package to.bitkit.ui.screens.shop.shopWebView

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import kotlinx.serialization.json.Json
import to.bitkit.utils.Logger

/**
 * JavaScript interface for handling WebView messages.
 *
 * SECURITY NOTE: This interface is exposed to JavaScript running in the WebView.
 * Only methods annotated with @JavascriptInterface are accessible from JavaScript
 * on API 17+ (Android 4.2+). All methods should validate input and handle errors
 * gracefully since they run on a background thread.
 *
 * Thread Safety: JavaScript interacts with this object on a private background
 * thread. All callbacks should be thread-safe or use appropriate dispatching.
 */
class ShopWebViewInterface(
    private val onPaymentIntent: (String) -> Unit,
    private val currentUrl: () -> String?,
    private val runOnMain: (() -> Unit) -> Unit = { action ->
        Handler(Looper.getMainLooper()).post(action)
    },
) {
    private companion object {
        const val TAG = "ShopWebViewInterface"
        const val PAYMENT_INTENT_EVENT = "payment_intent"
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Handles messages posted from JavaScript.
     * This method is called on a background thread - ensure thread safety.
     *
     * @param message JSON string containing the message data
     */
    @JavascriptInterface
    fun postMessage(message: String) {
        if (message.isBlank()) {
            Logger.warn("Received empty shop WebView message", context = TAG)
            return
        }
        runOnMain { handlePaymentMessage(message) }
    }

    /**
     * Returns whether the interface is ready to receive messages.
     *
     * @return true if the interface is initialized and ready
     */
    @Suppress("FunctionOnlyReturningConstant")
    @JavascriptInterface
    fun isReady(): Boolean {
        return true
    }

    private fun handlePaymentMessage(message: String) {
        val pageUrl = currentUrl()
        if (!isAllowedShopOrigin(pageUrl)) {
            Logger.warn("Rejected shop payment_intent from untrusted origin '$pageUrl'", context = TAG)
            return
        }

        runCatching {
            val data = json.decodeFromString<WebViewMessage>(message)
            when (data.event) {
                PAYMENT_INTENT_EVENT -> {
                    val uri = data.paymentUri?.trim().orEmpty()
                    if (uri.isBlank()) {
                        Logger.warn("Received payment_intent with empty URI", context = TAG)
                        return
                    }
                    onPaymentIntent(uri)
                }
                else -> Logger.debug("Ignored shop WebView event '${data.event}'", context = TAG)
            }
        }.onFailure {
            Logger.error("Failed to parse shop WebView message", it, context = TAG)
        }
    }
}

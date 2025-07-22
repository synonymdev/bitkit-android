package to.bitkit.ui.screens.shop.shopWebView

import android.util.Log
import android.webkit.JavascriptInterface
import kotlinx.serialization.json.Json
import to.bitkit.ui.screens.shop.shopWebView.WebViewMessage
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
) {
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
            Logger.warn("Received empty message", context = "WebView")
            return
        }

        try {
            val data = json.decodeFromString<WebViewMessage>(message)
            when (data.event) {
                "payment_intent" -> {
                    data.paymentUri?.let { uri ->
                        // Validate URI before passing it along
                        if (uri.isNotBlank()) {
                            onPaymentIntent(uri)
                        } else {
                            Logger.warn("Received payment_intent with empty URI", context = "WebView")
                        }
                    } ?: Logger.warn("Received payment_intent without URI", context = "WebView")
                }

                else -> {
                    Logger.debug("Unknown event type: ${data.event}", context = "WebView")
                }
            }
        } catch (e: Exception) {
            Logger.error("Error parsing message: $message", e)
        }
    }

    /**
     * Returns whether the interface is ready to receive messages.
     *
     * @return true if the interface is initialized and ready
     */
    @JavascriptInterface
    fun isReady(): Boolean {
        return true
    }
}

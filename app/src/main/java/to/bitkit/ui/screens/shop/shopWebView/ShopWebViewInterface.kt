package to.bitkit.ui.screens.shop.shopWebView

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.Json
import to.bitkit.utils.Logger

/**
 * JavaScript interface for handling WebView messages.
 *
 * Prefer [attachTo], which uses an origin-scoped WebMessageListener when the
 * WebView supports it. [addJavascriptInterface] is only a fallback and cannot
 * tell which iframe called [postMessage].
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
        const val JS_OBJECT_NAME = "Android"
        const val PAYMENT_INTENT_EVENT = "payment_intent"
    }

    private val json = Json { ignoreUnknownKeys = true }

    @SuppressLint("JavascriptInterface")
    fun attachTo(webView: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                webView,
                JS_OBJECT_NAME,
                shopAllowedOriginRules(),
            ) { _, message, sourceOrigin, _, _ ->
                onBridgeMessage(message.data.orEmpty(), sourceOrigin.toString())
            }
            return
        }
        Logger.warn(
            "Using JavascriptInterface shop bridge because WebMessageListener is unavailable",
            context = TAG,
        )
        webView.addJavascriptInterface(this, JS_OBJECT_NAME)
    }

    /**
     * Handles messages posted from JavaScript.
     * This method is called on a background thread - ensure thread safety.
     *
     * @param message JSON string containing the message data
     */
    @JavascriptInterface
    fun postMessage(message: String) {
        onBridgeMessage(message, currentUrl())
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

    internal fun onBridgeMessage(message: String, sourceOrigin: String?) {
        if (message.isBlank()) {
            Logger.warn("Received empty shop WebView message", context = TAG)
            return
        }
        runOnMain { handlePaymentMessage(message, sourceOrigin) }
    }

    internal fun handlePaymentMessage(message: String, sourceOrigin: String?) {
        if (!isAllowedShopOrigin(sourceOrigin)) {
            Logger.warn("Rejected shop payment_intent from untrusted origin '$sourceOrigin'", context = TAG)
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

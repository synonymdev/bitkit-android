package to.bitkit.ui.screens.shop.shopWebView

import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.Json
import to.bitkit.utils.Logger

/**
 * JavaScript interface for handling WebView messages.
 *
 * [attachTo] uses an origin-scoped WebMessageListener. Payment handling is
 * disabled when that listener is unavailable because legacy JavaScript
 * interfaces cannot identify the calling frame.
 */
class ShopWebViewInterface(
    private val onPaymentIntent: (String) -> Unit,
    private val isWebMessageListenerSupported: () -> Boolean = {
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    },
    private val addWebMessageListener: (
        WebView,
        String,
        Set<String>,
        WebViewCompat.WebMessageListener,
    ) -> Unit = { webView, jsObjectName, allowedOriginRules, listener ->
        WebViewCompat.addWebMessageListener(webView, jsObjectName, allowedOriginRules, listener)
    },
) {
    private companion object {
        const val TAG = "ShopWebViewInterface"
        const val JS_OBJECT_NAME = "Android"
        const val PAYMENT_INTENT_EVENT = "payment_intent"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val webMessageListenerSupported by lazy(isWebMessageListenerSupported)

    internal fun supportsPaymentBridge() = webMessageListenerSupported

    fun attachTo(webView: WebView) {
        if (!supportsPaymentBridge()) {
            Logger.warn("Disabled shop payment bridge because WebMessageListener is unavailable", context = TAG)
            return
        }

        addWebMessageListener(
            webView,
            JS_OBJECT_NAME,
            shopPaymentOriginRules(),
        ) { _, message, sourceOrigin, _, _ ->
            onWebMessage(message, sourceOrigin.toString())
        }
    }

    internal fun onWebMessage(message: WebMessageCompat, sourceOrigin: String?) {
        if (message.type != WebMessageCompat.TYPE_STRING) {
            Logger.warn("Rejected non-string shop WebView message", context = TAG)
            return
        }
        val data = message.data.orEmpty()
        if (data.isBlank()) {
            Logger.warn("Received empty shop WebView message", context = TAG)
            return
        }
        handlePaymentMessage(data, sourceOrigin)
    }

    internal fun handlePaymentMessage(message: String, sourceOrigin: String?) {
        if (!isAllowedShopPaymentOrigin(sourceOrigin)) {
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

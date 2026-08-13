package to.bitkit.ui.screens.shop.shopWebView

import java.net.URI

/** Root host for Bitrefill shop pages and payment_intent messages. */
const val BITREFILL_ROOT_HOST = "bitrefill.com"

fun isAllowedShopHost(host: String?): Boolean {
    val normalized = host?.lowercase()?.trim('.') ?: return false
    return normalized == BITREFILL_ROOT_HOST || normalized.endsWith(".$BITREFILL_ROOT_HOST")
}

fun isAllowedShopOrigin(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val parsed = runCatching { URI(url.trim()) }.getOrNull() ?: return false
    if (parsed.scheme?.equals("https", ignoreCase = true) != true) return false
    return isAllowedShopHost(parsed.host)
}

fun shopAllowedOriginRules(): Set<String> = setOf(
    "https://$BITREFILL_ROOT_HOST",
    "https://*.$BITREFILL_ROOT_HOST",
)

internal fun shopMessageBridgeScript(): String = """
    window.ReactNativeWebView = {
        postMessage: function(data) {
            Android.postMessage(typeof data === 'string' ? data : JSON.stringify(data));
        }
    };
    window.addEventListener('message', function(event) {
        try {
            var originUrl = new URL(event.origin);
            if (originUrl.protocol !== 'https:') return;
            var host = originUrl.hostname.toLowerCase();
            if (host !== '$BITREFILL_ROOT_HOST' && !host.endsWith('.$BITREFILL_ROOT_HOST')) return;
        } catch (e) {
            return;
        }
        var data = event.data;
        if (data == null) return;
        Android.postMessage(typeof data === 'string' ? data : JSON.stringify(data));
    });
""".trimIndent()

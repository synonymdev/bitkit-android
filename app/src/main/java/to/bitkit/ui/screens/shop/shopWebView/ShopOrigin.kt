package to.bitkit.ui.screens.shop.shopWebView

import to.bitkit.env.Env
import java.net.URI

/** Root host for Bitrefill shop pages and payment_intent messages. */
internal const val BITREFILL_ROOT_HOST = "bitrefill.com"

/** Default HTTPS port accepted for the trusted shop payment origin. */
private const val HTTPS_DEFAULT_PORT = 443

internal fun isAllowedShopHost(host: String?): Boolean {
    val normalized = host?.lowercase()?.trim('.') ?: return false
    return normalized == BITREFILL_ROOT_HOST || normalized.endsWith(".$BITREFILL_ROOT_HOST")
}

internal fun isAllowedShopOrigin(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val parsed = runCatching { URI(url.trim()) }.getOrNull() ?: return false
    if (parsed.scheme?.equals("https", ignoreCase = true) != true) return false
    return isAllowedShopHost(parsed.host)
}

private val bitrefillEmbedOrigin = URI(Env.BITREFILL_URL)

private fun hasTrustedPaymentOrigin(parsed: URI): Boolean {
    val hasTrustedScheme = parsed.scheme.equals(bitrefillEmbedOrigin.scheme, ignoreCase = true)
    val hasTrustedHost = parsed.host.equals(bitrefillEmbedOrigin.host, ignoreCase = true)
    val hasTrustedPort = parsed.port == -1 || parsed.port == HTTPS_DEFAULT_PORT
    return hasTrustedScheme && hasTrustedHost && hasTrustedPort && parsed.rawUserInfo == null
}

internal fun isAllowedShopPaymentPage(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val parsed = runCatching { URI(url.trim()) }.getOrNull() ?: return false
    return hasTrustedPaymentOrigin(parsed)
}

internal fun isAllowedShopPaymentOrigin(origin: String?): Boolean {
    if (origin.isNullOrBlank()) return false
    val parsed = runCatching { URI(origin.trim()) }.getOrNull() ?: return false
    return hasTrustedPaymentOrigin(parsed) &&
        parsed.rawPath.isNullOrEmpty() &&
        parsed.rawQuery == null &&
        parsed.rawFragment == null
}

internal fun shopPaymentOriginRules(): Set<String> = setOf(Env.BITREFILL_URL)

internal fun shopMessageBridgeScript(): String = """
    window.ReactNativeWebView = {
        postMessage: function(data) {
            Android.postMessage(typeof data === 'string' ? data : JSON.stringify(data));
        }
    };
    window.addEventListener('message', function(event) {
        if (event.origin !== '${Env.BITREFILL_URL}') return;
        var data = event.data;
        if (data == null) return;
        Android.postMessage(typeof data === 'string' ? data : JSON.stringify(data));
    });
""".trimIndent()

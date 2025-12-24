package to.bitkit.ui.nav

import android.net.Uri
import androidx.core.net.toUri
import kotlin.reflect.KClass

/**
 * Supported URI schemes for deep linking.
 */
enum class UriScheme(val value: String) {
    BITCOIN("bitcoin"),
    LIGHTNING("lightning"),
    LNURL("lnurl"),
    LNURL_PAY("lnurlp"),
    LNURL_WITHDRAW("lnurlw"),
    LNURL_CHANNEL("lnurlc"),
    BITKIT("bitkit"),
    HTTPS("https");

    val withColon: String get() = "$value:"
    val withSlashes: String get() = "$value://"
}

/**
 * Defines a pattern for matching deep link URIs to Routes.
 */
data class DeepLinkPattern<T : Routes>(
    val routeClass: KClass<T>,
    val uriPattern: Uri,
)

/**
 * Request object for parsing incoming deep link URIs.
 */
data class DeepLinkRequest(
    val uri: Uri,
) {
    val scheme: String? = uri.scheme?.lowercase()
    val host: String? = uri.host?.lowercase()
    val pathSegments: List<String> = uri.pathSegments
    val queryParams: Map<String, String> = uri.queryParameterNames
        .associateWith { uri.getQueryParameter(it) ?: "" }
}

/**
 * Result of a successful pattern match.
 */
data class DeepLinkMatchResult<T : Routes>(
    val routeClass: KClass<T>,
    val args: Map<String, String>,
)

/**
 * Matches a DeepLinkRequest against a DeepLinkPattern.
 */
class DeepLinkMatcher<T : Routes>(
    private val request: DeepLinkRequest,
    private val pattern: DeepLinkPattern<T>,
) {
    fun match(): DeepLinkMatchResult<T>? {
        val patternUri = pattern.uriPattern

        // Check scheme matches (case-insensitive)
        val patternScheme = patternUri.scheme?.lowercase()
        if (request.scheme != patternScheme) return null

        // Check host matches (if specified in pattern)
        val patternHost = patternUri.host?.lowercase()
        if (patternHost != null && request.host != patternHost) return null

        // Extract path arguments
        val args = mutableMapOf<String, String>()
        val patternSegments = patternUri.pathSegments
        val requestSegments = request.pathSegments

        // For schemes without host (like bitcoin:address), the "host" becomes path
        val effectiveRequestSegments = if (request.host != null && patternHost == null) {
            listOf(request.host) + requestSegments
        } else {
            requestSegments
        }

        if (patternSegments.size != effectiveRequestSegments.size) return null

        patternSegments.forEachIndexed { index, segment ->
            if (segment.startsWith("{") && segment.endsWith("}")) {
                val argName = segment.removeSurrounding("{", "}")
                args[argName] = effectiveRequestSegments[index]
            } else if (segment.lowercase() != effectiveRequestSegments[index].lowercase()) {
                return null
            }
        }

        // Add query params to args
        args.putAll(request.queryParams)

        return DeepLinkMatchResult(pattern.routeClass, args)
    }
}

/**
 * Registry of all supported deep link patterns.
 *
 * Note: The Rust-based bitkitcore.decode() remains the primary parser for complex
 * Bitcoin/Lightning URIs. These patterns provide type-safe documentation and
 * early pattern matching for known routes.
 */
object DeepLinkPatterns {
    // bitkit:// scheme patterns
    val RECOVERY_MODE = DeepLinkPattern(
        routeClass = Routes.RecoveryMode::class,
        uriPattern = "${UriScheme.BITKIT.withSlashes}recovery-mode".toUri()
    )

    // bitcoin:// scheme patterns (BIP21)
    // Note: bitcoin: URIs use address as "host" not path
    val SEND_BITCOIN = DeepLinkPattern(
        routeClass = Routes.SendAddress::class,
        uriPattern = "${UriScheme.BITCOIN.withSlashes}{address}".toUri()
    )

    // lightning:// scheme patterns
    val SEND_LIGHTNING = DeepLinkPattern(
        routeClass = Routes.SendAddress::class,
        uriPattern = "${UriScheme.LIGHTNING.withSlashes}{invoice}".toUri()
    )

    // lnurl:// scheme patterns
    val LNURL_PAY = DeepLinkPattern(
        routeClass = Routes.SendAddress::class,
        uriPattern = "${UriScheme.LNURL_PAY.withSlashes}{data}".toUri()
    )

    val LNURL_WITHDRAW = DeepLinkPattern(
        routeClass = Routes.ReceiveQr::class,
        uriPattern = "${UriScheme.LNURL_WITHDRAW.withSlashes}{data}".toUri()
    )

    val LNURL_CHANNEL = DeepLinkPattern(
        routeClass = Routes.LnurlChannel::class,
        uriPattern = "${UriScheme.LNURL_CHANNEL.withSlashes}{data}".toUri()
    )

    // https:// scheme patterns (App Links)
    val TREASURE_HUNT = DeepLinkPattern(
        routeClass = Routes.GiftLoading::class,
        uriPattern = "${UriScheme.HTTPS.withSlashes}www.bitkit.to/treasure-hunt".toUri()
    )

    /**
     * All registered patterns for matching.
     */
    val all: List<DeepLinkPattern<out Routes>> = listOf(
        RECOVERY_MODE,
        SEND_BITCOIN,
        SEND_LIGHTNING,
        LNURL_PAY,
        LNURL_WITHDRAW,
        LNURL_CHANNEL,
        TREASURE_HUNT,
    )

    /**
     * Find the first matching pattern for the given URI.
     */
    fun findMatch(uri: Uri): DeepLinkMatchResult<out Routes>? {
        val request = DeepLinkRequest(uri)
        return all.firstNotNullOfOrNull { pattern ->
            @Suppress("UNCHECKED_CAST")
            DeepLinkMatcher(request, pattern as DeepLinkPattern<Routes>).match()
        }
    }
}

// TODO Temporary fix while these schemes can't be decoded
fun String.removeLightningSchemes(): String = listOf(
    UriScheme.LNURL,
    UriScheme.LNURL_PAY,
    UriScheme.LNURL_WITHDRAW,
    UriScheme.LNURL_CHANNEL,
).fold(this) { acc, scheme -> acc.replace(scheme.withColon, "") }

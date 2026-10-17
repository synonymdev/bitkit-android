package to.bitkit.ui.utils

import android.content.Intent
import android.net.Uri
import androidx.navigation.NavDeepLink
import to.bitkit.ui.Routes
import kotlin.reflect.KClass

object ScreenDeepLinks {
    const val SCHEME = "bitkit"
    const val HOST = "screen"

    private const val BASE_URI = "$SCHEME://$HOST"

    private val CAMEL_HUMP = Regex("(?<=[a-z0-9])(?=[A-Z])")

    val isEnabled: Boolean get() = ScreenDeepLinkRuntime.isEnabled

    fun shouldQueue(isDevModeEnabled: Boolean): Boolean = isEnabled && isDevModeEnabled

    fun screenId(route: KClass<out Routes.DeepLinkable>): String? = kebabId(route)

    fun kebabId(route: KClass<*>): String? {
        val name = route.simpleName ?: return null
        return CAMEL_HUMP.split(name).joinToString("-") { it.lowercase() }
    }

    fun basePath(route: KClass<out Routes.DeepLinkable>): String? = screenId(route)?.let { "$BASE_URI/$it" }

    fun <T : Routes.DeepLinkable> linksFor(route: KClass<T>): List<NavDeepLink> =
        ScreenDeepLinkRuntime.linksFor(route)

    fun <T : Any> matchStart(path: String, default: T, starts: List<T>): T? = when {
        path.isEmpty() -> default
        else -> starts.firstOrNull { kebabId(it::class).equals(path, ignoreCase = true) }
    }

    fun isScreenDeepLink(uri: Uri): Boolean =
        uri.scheme?.lowercase() == SCHEME && uri.host?.lowercase() == HOST

    /**
     * Parses `bitkit://screen/spending-hw-sign/{walletId}/{amountSats}`.
     *
     * Returns null when the URI is not for that screen at all, so the caller can fall through to the
     * ordinary nav handling, and [SpendingHwSignLink.Malformed] when it is but carries unusable
     * arguments - those must be refused rather than navigated to, or the sign screen opens without a
     * quote and immediately bounces the user home.
     *
     * Gated on [isEnabled] so a release build cannot reach live transfer state through a dev-only URI
     * even if a caller forgets to check [shouldQueue] first.
     */
    fun spendingHwSignLink(uri: Uri): SpendingHwSignLink? {
        if (!isEnabled || !isScreenDeepLink(uri)) return null
        val segments = uri.pathSegments.orEmpty()
        val screenId = kebabId(Routes.SpendingHwSign::class)
        if (segments.isEmpty() || screenId == null || !segments[0].equals(screenId, ignoreCase = true)) {
            return null
        }
        if (segments.size != 3) return SpendingHwSignLink.Malformed

        val walletId = segments[1]
        val amountSats = segments[2].toLongOrNull()
        if (walletId.isBlank() || amountSats == null || amountSats <= 0) return SpendingHwSignLink.Malformed

        return SpendingHwSignLink.Valid(walletId = walletId, amountSats = amountSats)
    }

    fun detachScreenUri(intent: Intent): Boolean {
        val uri = intent.data ?: return false
        if (!isScreenDeepLink(uri)) return false

        intent.data = null
        return true
    }
}

sealed interface SpendingHwSignLink {
    data class Valid(val walletId: String, val amountSats: Long) : SpendingHwSignLink

    data object Malformed : SpendingHwSignLink
}

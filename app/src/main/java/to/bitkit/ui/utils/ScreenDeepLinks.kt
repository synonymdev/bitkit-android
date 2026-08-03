package to.bitkit.ui.utils

import android.content.Intent
import android.net.Uri
import androidx.navigation.NavDeepLink
import androidx.navigation.navDeepLink
import to.bitkit.ui.Routes
import kotlin.reflect.KClass

object ScreenDeepLinks {
    const val SCHEME = "bitkit"
    const val HOST = "screen"

    private const val BASE_URI = "$SCHEME://$HOST"

    private val CAMEL_HUMP = Regex("(?<=[a-z0-9])(?=[A-Z])")

    fun screenId(route: KClass<out Routes.DeepLinkable>): String? = kebabId(route)

    fun kebabId(route: KClass<*>): String? {
        val name = route.simpleName ?: return null
        return CAMEL_HUMP.split(name).joinToString("-") { it.lowercase() }
    }

    fun basePath(route: KClass<out Routes.DeepLinkable>): String? = screenId(route)?.let { "$BASE_URI/$it" }

    fun <T : Routes.DeepLinkable> linksFor(route: KClass<T>): List<NavDeepLink> {
        val basePath = basePath(route) ?: return emptyList()
        return listOf(navDeepLink(route = route, basePath = basePath) {})
    }

    fun <T : Any> matchStart(path: String, default: T, starts: List<T>): T? = when {
        path.isEmpty() -> default
        else -> starts.firstOrNull { kebabId(it::class).equals(path, ignoreCase = true) }
    }

    fun isScreenDeepLink(uri: Uri): Boolean =
        uri.scheme?.lowercase() == SCHEME && uri.host?.lowercase() == HOST

    fun detachScreenUri(intent: Intent): Boolean {
        val uri = intent.data ?: return false
        if (!isScreenDeepLink(uri)) return false

        intent.data = null
        return true
    }
}

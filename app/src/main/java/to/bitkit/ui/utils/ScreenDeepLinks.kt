package to.bitkit.ui.utils

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

    private val DENIED: Set<KClass<out Routes>> = setOf(
        Routes.AuthCheck::class,
        Routes.CriticalUpdate::class,
        Routes.LegacyRnRecovery::class,
        Routes.LnurlChannel::class,
        Routes.RecoveryMnemonic::class,
        Routes.RecoveryMode::class,
    )

    fun isDenied(route: KClass<*>): Boolean = route in DENIED

    fun screenId(route: KClass<*>): String? {
        if (!isScreenRoute(route)) return null
        if (isDenied(route)) return null

        val name = route.simpleName ?: return null
        return CAMEL_HUMP.split(name).joinToString("-") { it.lowercase() }
    }

    fun basePath(route: KClass<*>): String? = screenId(route)?.let { "$BASE_URI/$it" }

    fun <T : Any> linksFor(route: KClass<T>): List<NavDeepLink> {
        val basePath = basePath(route) ?: return emptyList()
        return listOf(navDeepLink(route = route, basePath = basePath) {})
    }

    fun isScreenDeepLink(uri: Uri): Boolean =
        uri.scheme?.lowercase() == SCHEME && uri.host?.lowercase() == HOST

    private fun isScreenRoute(route: KClass<*>): Boolean = Routes::class.java.isAssignableFrom(route.java)
}

package to.bitkit.ui.utils

import android.net.Uri
import androidx.navigation.NavDeepLink
import to.bitkit.ui.Routes
import to.bitkit.ui.components.Sheet
import kotlin.reflect.KClass

internal object ScreenDeepLinkRuntime {
    val isEnabled = false
    val sheetIds: Set<String> = emptySet()

    @Suppress("UNUSED_PARAMETER")
    fun <T : Routes.DeepLinkable> linksFor(route: KClass<T>): List<NavDeepLink> = emptyList()

    @Suppress("UNUSED_PARAMETER")
    fun sheetFor(uri: Uri): Sheet? = null
}

package to.bitkit.ui.utils

import android.net.Uri
import androidx.navigation.NavDeepLink
import androidx.navigation.navDeepLink
import to.bitkit.ui.Routes
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.screens.wallets.receive.ReceiveRoute
import to.bitkit.ui.sheets.BackupRoute
import to.bitkit.ui.sheets.SendRoute
import to.bitkit.ui.sheets.WidgetsRoute
import to.bitkit.ui.sheets.hardware.HardwareRoute
import kotlin.reflect.KClass

internal object ScreenDeepLinkRuntime {
    val isEnabled = true

    val sheetIds: Set<String>
        get() {
            val families = FAMILIES.mapNotNull { ScreenDeepLinks.kebabId(it.type) }
            val standalone = STANDALONE.mapNotNull { ScreenDeepLinks.kebabId(it::class) }
            return (families + standalone).toSet()
        }

    fun <T : Routes.DeepLinkable> linksFor(route: KClass<T>): List<NavDeepLink> {
        val basePath = ScreenDeepLinks.basePath(route) ?: return emptyList()
        return listOf(navDeepLink(route = route, basePath = basePath) {})
    }

    fun sheetFor(uri: Uri): Sheet? {
        if (!ScreenDeepLinks.isScreenDeepLink(uri)) return null

        val segments = uri.pathSegments.orEmpty()
        if (segments.isEmpty() || segments.size > 2) return null

        val id = segments.first().lowercase()
        val child = segments.getOrElse(1) { "" }

        FAMILIES.firstOrNull { ScreenDeepLinks.kebabId(it.type) == id }
            ?.let { return it.fromDeepLink(child) }

        if (child.isNotEmpty()) return null

        return STANDALONE.firstOrNull { ScreenDeepLinks.kebabId(it::class) == id }
    }
}

private class SheetDeepLinkFamily(
    val type: KClass<out Sheet>,
    val fromDeepLink: (String) -> Sheet?,
)

private val FAMILIES: List<SheetDeepLinkFamily> = listOf(
    SheetDeepLinkFamily(Sheet.Send::class) { path -> SendRoute.fromDeepLink(path)?.let { Sheet.Send(it) } },
    SheetDeepLinkFamily(Sheet.Receive::class) { path -> ReceiveRoute.fromDeepLink(path)?.let { Sheet.Receive(it) } },
    SheetDeepLinkFamily(Sheet.Backup::class) { path -> BackupRoute.fromDeepLink(path)?.let { Sheet.Backup(it) } },
    SheetDeepLinkFamily(Sheet.Widgets::class) { path -> WidgetsRoute.fromDeepLink(path)?.let { Sheet.Widgets(it) } },
    SheetDeepLinkFamily(Sheet.Hardware::class) { path -> HardwareRoute.fromDeepLink(path)?.let { Sheet.Hardware(it) } },
)

private val STANDALONE: List<Sheet> = listOf(
    Sheet.ActivityDateRangeSelector,
    Sheet.ActivityTagSelector,
    Sheet.QrScanner(),
)

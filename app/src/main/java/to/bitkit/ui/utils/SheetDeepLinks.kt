package to.bitkit.ui.utils

import android.net.Uri
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.screens.wallets.receive.ReceiveRoute
import to.bitkit.ui.sheets.BackupRoute
import to.bitkit.ui.sheets.SendRoute
import to.bitkit.ui.sheets.WidgetsRoute
import to.bitkit.ui.sheets.hardware.HardwareRoute

object SheetDeepLinks {
    private val FAMILIES: Map<String, (String) -> Sheet?> = mapOf(
        "send" to { path -> SendRoute.fromDeepLink(path)?.let { Sheet.Send(it) } },
        "receive" to { path -> ReceiveRoute.fromDeepLink(path)?.let { Sheet.Receive(it) } },
        "backup" to { path -> BackupRoute.fromDeepLink(path)?.let { Sheet.Backup(it) } },
        "widgets" to { path -> WidgetsRoute.fromDeepLink(path)?.let { Sheet.Widgets(it) } },
        "hardware" to { path -> HardwareRoute.fromDeepLink(path)?.let { Sheet.Hardware(it) } },
    )

    private val STANDALONE: List<Sheet> = listOf(
        Sheet.ActivityDateRangeSelector,
        Sheet.ActivityTagSelector,
        Sheet.QrScanner,
    )

    fun sheetFor(uri: Uri): Sheet? {
        if (!ScreenDeepLinks.isScreenDeepLink(uri)) return null

        val segments = uri.pathSegments.orEmpty()
        if (segments.isEmpty() || segments.size > 2) return null

        val id = segments.first().lowercase()
        val child = segments.getOrElse(1) { "" }

        FAMILIES[id]?.let { return it(child) }
        if (child.isNotEmpty()) return null

        return STANDALONE.firstOrNull { ScreenDeepLinks.kebabId(it::class) == id }
    }
}

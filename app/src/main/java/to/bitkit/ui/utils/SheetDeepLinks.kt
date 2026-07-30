package to.bitkit.ui.utils

import android.net.Uri
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.screens.wallets.receive.ReceiveRoute
import to.bitkit.ui.sheets.BackupRoute
import to.bitkit.ui.sheets.SendRoute
import to.bitkit.ui.sheets.WidgetsRoute
import to.bitkit.ui.sheets.hardware.HardwareRoute

object SheetDeepLinks {
    private val SHEETS: List<Sheet> = listOf(
        Sheet.Send(SendRoute.Recipient),
        Sheet.Send(SendRoute.Address),
        Sheet.Send(SendRoute.ContactSelect),
        Sheet.Send(SendRoute.Amount),
        Sheet.Send(SendRoute.QrScanner),
        Sheet.Send(SendRoute.CoinSelection),
        Sheet.Send(SendRoute.AddTag),
        Sheet.Send(SendRoute.ComingSoon),
        Sheet.Send(SendRoute.Support),

        Sheet.Receive(ReceiveRoute.QR),
        Sheet.Receive(ReceiveRoute.Amount),
        Sheet.Receive(ReceiveRoute.EditInvoice),
        Sheet.Receive(ReceiveRoute.AddTag),
        Sheet.Receive(ReceiveRoute.GeoBlock),

        Sheet.Backup(BackupRoute.Intro),
        Sheet.Backup(BackupRoute.Warning),
        Sheet.Backup(BackupRoute.Success),
        Sheet.Backup(BackupRoute.MultipleDevices),
        Sheet.Backup(BackupRoute.Metadata),

        Sheet.Widgets(WidgetsRoute.Gallery),
        Sheet.Widgets(WidgetsRoute.PricePreview),
        Sheet.Widgets(WidgetsRoute.PriceEdit),
        Sheet.Widgets(WidgetsRoute.WeatherPreview),
        Sheet.Widgets(WidgetsRoute.WeatherEdit),
        Sheet.Widgets(WidgetsRoute.BlocksPreview),
        Sheet.Widgets(WidgetsRoute.BlocksEdit),
        Sheet.Widgets(WidgetsRoute.HeadlinesPreview),
        Sheet.Widgets(WidgetsRoute.HeadlinesEdit),
        Sheet.Widgets(WidgetsRoute.FactsPreview),
        Sheet.Widgets(WidgetsRoute.CalculatorPreview),
        Sheet.Widgets(WidgetsRoute.SuggestionsPreview),

        Sheet.Hardware(HardwareRoute.Intro),
        Sheet.Hardware(HardwareRoute.Searching),
        Sheet.Hardware(HardwareRoute.Paired),

        Sheet.ActivityDateRangeSelector,
        Sheet.ActivityTagSelector,
        Sheet.QrScanner,
    )

    private val BY_PATH: Map<String, Sheet> = buildMap {
        SHEETS.forEach { sheet ->
            val sheetId = ScreenDeepLinks.kebabId(sheet::class) ?: return@forEach
            putIfAbsent(sheetId, sheet)

            val route = routeOf(sheet) ?: return@forEach
            val routeId = ScreenDeepLinks.kebabId(route::class) ?: return@forEach
            put("$sheetId/$routeId", sheet)
        }
    }

    val paths: Set<String> get() = BY_PATH.keys

    fun sheetFor(uri: Uri): Sheet? {
        if (!ScreenDeepLinks.isScreenDeepLink(uri)) return null

        val path = uri.pathSegments.orEmpty().joinToString("/").lowercase()
        return BY_PATH[path]
    }

    private fun routeOf(sheet: Sheet): Any? = when (sheet) {
        is Sheet.Send -> sheet.route
        is Sheet.Receive -> sheet.route
        is Sheet.Backup -> sheet.route
        is Sheet.Widgets -> sheet.route
        is Sheet.Hardware -> sheet.route
        else -> null
    }
}

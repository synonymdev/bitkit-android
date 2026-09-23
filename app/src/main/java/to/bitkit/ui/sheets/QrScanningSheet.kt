package to.bitkit.ui.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.screens.scanner.QrScanningScreen
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.viewmodels.AppViewModel

@Composable
fun QrScanningSheet(
    sheet: Sheet.QrScanner,
    appViewModel: AppViewModel,
) {
    Content(
        isPubkyScan = sheet.isPubkyScan,
        onBack = if (sheet.showBackButton) {
            { appViewModel.hideScannerSheet() }
        } else {
            null
        },
        onScanSuccess = { appViewModel.onScannerSheetResult(it) },
    )
}

@Composable
private fun Content(
    isPubkyScan: Boolean,
    onBack: (() -> Unit)?,
    onScanSuccess: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sheetHeight()
    ) {
        QrScanningScreen(
            isPubkyScan = isPubkyScan,
            onScanSuccess = { payload -> payload.text?.let(onScanSuccess) },
            onBack = onBack,
        )
    }
}

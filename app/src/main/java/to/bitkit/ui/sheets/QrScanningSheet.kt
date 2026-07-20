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
        onBack = { appViewModel.hideScannerSheet() },
        onScanSuccess = { appViewModel.onScannerSheetResult(it) },
    )
}

@Composable
private fun Content(
    isPubkyScan: Boolean,
    onBack: () -> Unit,
    onScanSuccess: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sheetHeight()
    ) {
        QrScanningScreen(
            isPubkyScan = isPubkyScan,
            onScanSuccess = onScanSuccess,
            onBack = onBack,
        )
    }
}

package to.bitkit.ui.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import to.bitkit.ui.screens.scanner.QrScanningScreen
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.viewmodels.AppViewModel

@Composable
fun QrScanningSheet(appViewModel: AppViewModel) {
    Content(
        onScanSuccess = { appViewModel.onScannerSheetResult(it) },
    )
}

@Composable
private fun Content(
    onScanSuccess: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sheetHeight()
    ) {
        QrScanningScreen(
            onScanSuccess = onScanSuccess,
        )
    }
}

package to.bitkit.ui.screens.scanner

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

@Composable
fun qrCodeScanner(): GmsBarcodeScanner? {
    val context = LocalContext.current

    if (LocalInspectionMode.current) {
        // Return a mock or null for Preview
        return null
    }
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .enableAutoZoom()
        .build()
    return GmsBarcodeScanning.getClient(context, options)
}

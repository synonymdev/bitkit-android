package to.bitkit.ui.screens.scanner

import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import to.bitkit.env.Tag.APP

@OptIn(ExperimentalGetImage::class)
class QrCodeAnalyzer(
    private val onScanResult: (Result<String>) -> Unit,
) : ImageAnalysis.Analyzer {
    private var isScanning = true

    private val scannerOptions = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()
    private val scanner: BarcodeScanner = BarcodeScanning.getClient(scannerOptions)

    override fun analyze(image: ImageProxy) {
        if (!isScanning) {
            image.close()
            return
        }

        if (image.image != null) {
            val inputImage = InputImage.fromMediaImage(image.image!!, image.imageInfo.rotationDegrees)
            scanner.process(inputImage)
                .addOnCompleteListener {
                    if (it.isSuccessful) {
                        it.result.let { barcodes ->
                            barcodes.forEach { barcode ->
                                barcode.rawValue?.let { qrCode ->
                                    isScanning = false
                                    onScanResult(Result.success(qrCode))
                                    image.close()
                                    return@addOnCompleteListener
                                }
                            }
                        }
                    } else {
                        val error = it.exception ?: Exception("Scan failed")
                        Log.e(APP, error.message.orEmpty(), error)
                        onScanResult(Result.failure(error))
                    }
                    image.close()
                }
        } else {
            image.close()
        }
    }
}

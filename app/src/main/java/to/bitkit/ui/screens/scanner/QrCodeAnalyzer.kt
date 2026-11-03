package to.bitkit.ui.screens.scanner

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import to.bitkit.ext.nowMillis
import to.bitkit.models.QrCodePayload
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger

@OptIn(ExperimentalGetImage::class)
class QrCodeAnalyzer(
    private val acceptsBinaryPayload: Boolean = false,
    private val onScanResult: (Result<QrCodePayload>) -> Unit,
) : ImageAnalysis.Analyzer {
    private var lastScannedCode: String? = null
    private var lastScanTime: Long = 0
    private val scanCooldownMs = 2000L // 2 seconds cooldown between scans

    private val scannerOptions = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()
    private val scanner: BarcodeScanner = BarcodeScanning.getClient(scannerOptions)

    fun reset() {
        lastScannedCode = null
        lastScanTime = 0
    }

    override fun analyze(image: ImageProxy) {
        if (image.image != null) {
            val inputImage = InputImage.fromMediaImage(image.image!!, image.imageInfo.rotationDegrees)
            scanner.process(inputImage)
                .addOnCompleteListener {
                    if (it.isSuccessful) {
                        selectQrCodePayload(
                            payloads = it.result.map { barcode ->
                                QrCodePayload(
                                    text = barcode.rawValue,
                                    rawBytes = barcode.rawBytes,
                                )
                            },
                            acceptsBinaryPayload = acceptsBinaryPayload,
                        )?.let { payload ->
                            val scanKey = payload.text ?: payload.rawBytes?.contentHashCode()?.toString()
                            val currentTime = nowMillis()
                            val isDifferentCode = scanKey != lastScannedCode
                            val isCooldownExpired = currentTime - lastScanTime > scanCooldownMs

                            if (isDifferentCode || isCooldownExpired) {
                                lastScannedCode = scanKey
                                lastScanTime = currentTime
                                onScanResult(
                                    Result.success(payload)
                                )
                            }
                        }
                    } else {
                        val error = it.exception ?: AppError("Scan failed")
                        Logger.error("Failed to analyze QR code", error, context = "QrCodeAnalyzer")
                        onScanResult(Result.failure(error))
                    }
                    image.close()
                }
        } else {
            image.close()
        }
    }
}

internal fun selectQrCodePayload(
    payloads: List<QrCodePayload>,
    acceptsBinaryPayload: Boolean,
): QrCodePayload? = payloads.firstOrNull { it.text != null }
    ?: payloads.firstOrNull { acceptsBinaryPayload && it.rawBytes != null }

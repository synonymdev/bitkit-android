package to.bitkit.wear.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.bitkit.wear.ext.runSuspendCatching
import to.bitkit.wear.ui.theme.Colors
import to.bitkit.wear.utils.Logger

private const val TAG = "QrCode"

/** The quiet zone around the code, in modules: the four the QR standard asks for. */
private const val QUIET_ZONE_MODULES = 4

/**
 * [content] as a black-on-white QR code with its quiet zone, filling its square. Error correction is level L, which
 * keeps the modules of a long invoice as large as the small screen allows. Each module is one pixel of the bitmap,
 * scaled up without smoothing, so the edges stay sharp at any size. The code is drawn off the main thread; the white
 * square shows until it is ready.
 */
@Composable
fun QrCode(
    content: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, content) {
        value = withContext(Dispatchers.Default) {
            runSuspendCatching { qrBitmap(content) }
                .onFailure { Logger.error("Failed to draw a QR code", it, context = TAG) }
                .getOrNull()
        }
    }
    Box(modifier = modifier.background(Colors.White)) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = contentDescription,
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private fun qrBitmap(content: String): ImageBitmap {
    val hints = mapOf(
        EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix[index % matrix.width, index / matrix.width]) BLACK else WHITE
    }
    val bitmap = createBitmap(matrix.width, matrix.height)
    bitmap.setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
    return bitmap.asImageBitmap()
}

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()

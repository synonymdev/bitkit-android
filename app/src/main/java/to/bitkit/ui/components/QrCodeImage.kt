package to.bitkit.ui.components

import android.content.ClipData
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import androidx.core.graphics.createBitmap
import to.bitkit.ui.shared.util.clickableAlpha

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrCodeImage(
    content: String,
    modifier: Modifier = Modifier,
    logoPainter: Painter? = null,
    tipMessage: String = "",
    size: Dp = LocalConfiguration.current.screenWidthDp.dp,
) {
    val clipboard = LocalClipboardManager.current

    val tooltipState = rememberTooltipState()
    val coroutineScope = rememberCoroutineScope()

    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier
            .background(Color.White, RoundedCornerShape(8.dp))
            .aspectRatio(1f)
            .padding(8.dp)
    ) {
        val bitmap = rememberQrBitmap(content, size)

        if (bitmap != null) {
            Tooltip(
                text = tipMessage,
                tooltipState = tooltipState
            ) {
                Image(
                    painter = remember(bitmap) { BitmapPainter(bitmap.asImageBitmap()) },
                    contentDescription = null,
                    contentScale = ContentScale.Inside,
                    modifier = if (tipMessage.isNotBlank()) {
                        Modifier.clickableAlpha {
                            coroutineScope.launch {
                                clipboard.setText(AnnotatedString(content))
                                tooltipState.show()
                            }
                        }
                    } else Modifier
                )
            }
            logoPainter?.let {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(68.dp)
                        .background(Color.White, shape = CircleShape)
                        .align(Alignment.Center)
                ) {
                    Image(
                        painter = it,
                        contentDescription = null,
                        modifier = Modifier.size(50.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        } else {
            CircularProgressIndicator(
                color = Colors.Black,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun rememberQrBitmap(content: String, size: Dp): Bitmap? {
    if (content.isEmpty()) return null

    var bitmap by remember(content) { mutableStateOf<Bitmap?>(null) }
    val sizePx = with(LocalDensity.current) { size.roundToPx() }

    LaunchedEffect(content, size) {
        if (bitmap != null) return@LaunchedEffect

        launch(Dispatchers.Default) {
            val qrCodeWriter = QRCodeWriter()

            val encodeHints = mutableMapOf<EncodeHintType, Any?>().apply {
                this[EncodeHintType.MARGIN] = 0
            }

            val bitmapMatrix = try {
                qrCodeWriter.encode(
                    content,
                    BarcodeFormat.QR_CODE,
                    sizePx,
                    sizePx,
                    encodeHints,
                )
            } catch (_: WriterException) {
                null
            }

            val matrixWidth = bitmapMatrix?.width ?: sizePx
            val matrixHeight = bitmapMatrix?.height ?: sizePx

            val newBitmap = createBitmap(
                width = bitmapMatrix?.width ?: sizePx,
                height = bitmapMatrix?.height ?: sizePx
            )

            val pixels = IntArray(matrixWidth * matrixHeight)

            for (x in 0 until matrixWidth) {
                for (y in 0 until matrixHeight) {
                    val shouldColorPixel = bitmapMatrix?.get(x, y) ?: false
                    val pixelColor =
                        if (shouldColorPixel) android.graphics.Color.BLACK
                        else android.graphics.Color.WHITE

                    pixels[y * matrixWidth + x] = pixelColor
                }
            }

            newBitmap.setPixels(pixels, 0, matrixWidth, 0, 0, matrixWidth, matrixHeight)

            bitmap = newBitmap
        }
    }
    return bitmap
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        QrCodeImage(
            content = "https://bitkit.to",
            logoPainter = painterResource(R.drawable.ic_btc_circle),
            modifier = Modifier.padding(16.dp)
        )
    }
}

package to.bitkit.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ext.setClipboardText
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

private const val QUIET_ZONE_MIN = 2
private const val QUIET_ZONE_MAX = 4
private const val QUIET_ZONE_RATIO = 150

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrCodeImage(
    content: String,
    modifier: Modifier = Modifier,
    logoPainter: Painter? = null,
    tipMessage: String = "",
    size: Dp = LocalConfiguration.current.screenWidthDp.dp,
    onBitmapGenerated: (Bitmap?) -> Unit = {},
    testTag: String? = null,
    copyContent: String? = null,
) {
    val context = LocalContext.current
    val tooltipState = rememberTooltipState()
    val coroutineScope = rememberCoroutineScope()

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .aspectRatio(1f)
            .clip(AppShapes.small)
            .background(Color.White)
    ) {
        val bitmap = rememberQrBitmap(content, size)

        LaunchedEffect(bitmap) {
            onBitmapGenerated(bitmap)
        }

        Crossfade(
            targetState = bitmap,
            animationSpec = tween(durationMillis = 200),
            label = "QR Code Crossfade"
        ) { currentBitmap ->
            if (currentBitmap != null) {
                val imageComposable = @Composable {
                    Image(
                        painter = remember(currentBitmap) { BitmapPainter(currentBitmap.asImageBitmap()) },
                        contentDescription = content,
                        contentScale = ContentScale.Inside,
                        modifier = Modifier
                            .clickableAlpha(
                                onClick = if (tipMessage.isNotBlank()) {
                                    {
                                        coroutineScope.launch {
                                            context.setClipboardText(copyContent ?: content)
                                            tooltipState.show()
                                        }
                                    }
                                } else {
                                    null
                                }
                            )
                            .then(testTag?.let { Modifier.testTag(it) } ?: Modifier)
                    )
                }

                if (tipMessage.isNotBlank()) {
                    Tooltip(
                        text = tipMessage,
                        tooltipState = tooltipState,
                        content = imageComposable,
                    )
                } else {
                    imageComposable()
                }
            } else {
                Image(
                    painter = painterResource(R.drawable.qr_placeholder),
                    contentDescription = content,
                    contentScale = ContentScale.Inside,
                    modifier = Modifier.fillMaxSize()
                )
            }
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
                    modifier = Modifier.size(50.dp)
                )
            }
        }

        if (bitmap == null) {
            CircularProgressIndicator(
                color = Colors.Black,
                strokeWidth = 4.dp,
                modifier = Modifier.size(68.dp)
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
        bitmap = null // Always reset to show loading indicator

        launch(Dispatchers.Default) {
            val qrCodeWriter = QRCodeWriter()

            val quietZoneModules = (content.length / QUIET_ZONE_RATIO + 1).coerceIn(QUIET_ZONE_MIN, QUIET_ZONE_MAX)

            val encodeHints = mapOf(EncodeHintType.MARGIN to quietZoneModules)

            val bitmapMatrix = runCatching {
                qrCodeWriter.encode(
                    content,
                    BarcodeFormat.QR_CODE,
                    sizePx,
                    sizePx,
                    encodeHints,
                )
            }.getOrElse { return@launch }

            val matrixWidth = bitmapMatrix.width
            val matrixHeight = bitmapMatrix.height

            val newBitmap = createBitmap(width = matrixWidth, height = matrixHeight)
            val pixels = IntArray(matrixWidth * matrixHeight)

            for (x in 0 until matrixWidth) {
                for (y in 0 until matrixHeight) {
                    val shouldColorPixel = bitmapMatrix[x, y]
                    val pixelColor = if (shouldColorPixel) {
                        android.graphics.Color.BLACK
                    } else {
                        android.graphics.Color.WHITE
                    }

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

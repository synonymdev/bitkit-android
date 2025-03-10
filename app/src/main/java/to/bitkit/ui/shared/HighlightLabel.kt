package to.bitkit.ui.shared


import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.R
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors.Brand

@Composable
fun HighlightLabel(
    text: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle(
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )
) {
    val density = LocalDensity.current
    val triangleWidth = 13.dp
    val triangleWidthPx = with(density) { triangleWidth.toPx() }
    val logoHeight = 42.dp
    val mainRectHeight = 26.dp
    val mainRectHeightPx = with(density) { mainRectHeight.toPx() }
    val logoHeightPx = with(density) { logoHeight.toPx() }

    Row(
        modifier = modifier.height(logoHeight),
        verticalAlignment = Alignment.Top
    ) {
        // Left triangles
        Canvas(
            modifier = Modifier
                .width(triangleWidth)
                .fillMaxHeight()
        ) {
            // Top left triangle
            drawPath(
                path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(triangleWidthPx, triangleWidthPx)
                    lineTo(triangleWidthPx, 0f)
                    close()
                },
                color = Brand
            )

            // Bottom left triangle
            drawPath(
                path = Path().apply {
                    moveTo(0f, mainRectHeightPx)
                    lineTo(triangleWidthPx, triangleWidthPx)
                    lineTo(triangleWidthPx, mainRectHeightPx)
                    close()
                },
                color = Brand
            )
        }

        // Text with background
        Box(
            modifier = Modifier
                .height(mainRectHeight)
                .wrapContentWidth()
                .drawBehind {
                    // Main rectangle (flexible width based on text)
                    drawRect(
                        color = Brand,
                        size = Size(this.size.width, this.size.height)
                    )

                    // Top right triangle (semi-transparent)
                    if (this.size.width > 0) {
                        drawPath(
                            path = Path().apply {
                                val startX = this@drawBehind.size.width - triangleWidthPx
                                moveTo(startX, this@drawBehind.size.height)
                                lineTo(this@drawBehind.size.width, logoHeightPx)
                                lineTo(this@drawBehind.size.width, this@drawBehind.size.height)
                                close()
                            },
                            color = Brand.copy(alpha = 0.5f)
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Caption13Up(
                text = text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }
}

// Usage example
@Preview(showBackground = true)
@Composable
fun FlexibleLogoPreview() {
    AppThemeSurface {
        HighlightLabel(text = stringResource(R.string.onboarding__advanced))
    }
}

@Preview(showBackground = true)
@Composable
fun ShortTextLogoPreview() {
    AppThemeSurface {
        HighlightLabel(text = "GAME")
    }
}

@Preview(showBackground = true)
@Composable
fun LongTextLogoPreview() {
    AppThemeSurface {
        HighlightLabel(text = "NOT YOUR KEYS, NOT YOUR COINS")
    }
}

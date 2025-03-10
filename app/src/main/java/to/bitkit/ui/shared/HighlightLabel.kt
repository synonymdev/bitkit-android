package to.bitkit.ui.shared


import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun HighlightLabel(label: String, modifier: Modifier = Modifier) {
    val orangeColor = Color(0xFFFF4400)

    Box(modifier = modifier.size(106.dp, 42.dp)) {
        // Logo shapes
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Calculate scaling factor to maintain proportions
            val scaleX = size.width / 106f
            val scaleY = size.height / 42f

            // Top right triangle (opacity 0.5)
            drawPath(
                path = Path().apply {
                    moveTo(91.7534f * scaleX, 26f * scaleY)
                    lineTo(106f * scaleX, 42f * scaleY)
                    lineTo(106f * scaleX, 26f * scaleY)
                    close()
                },
                color = orangeColor.copy(alpha = 0.5f)
            )

            // Top left triangle
            drawPath(
                path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(13f * scaleX, 13f * scaleY)
                    lineTo(13f * scaleX, 0f)
                    close()
                },
                color = orangeColor
            )

            // Bottom left triangle
            drawPath(
                path = Path().apply {
                    moveTo(0f, 26f * scaleY)
                    lineTo(13f * scaleX, 13f * scaleY)
                    lineTo(13f * scaleX, 26f * scaleY)
                    close()
                },
                color = orangeColor
            )

            // Main rectangle
            drawRect(
                color = orangeColor,
                topLeft = Offset(13f * scaleX, 0f),
                size = androidx.compose.ui.geometry.Size(93f * scaleX, 26f * scaleY)
            )
        }

        Box(
            modifier = Modifier
                .size((93).dp, 26.dp)
                .align(Alignment.TopStart)
                .offset(x = 13.dp, y = 0.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = Color.White,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Preview()
@Composable
private fun Preview() {
    AppThemeSurface {
        HighlightLabel(stringResource(R.string.onboarding__advanced))
    }
}

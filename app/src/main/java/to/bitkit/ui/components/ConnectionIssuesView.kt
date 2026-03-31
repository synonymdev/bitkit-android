package to.bitkit.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun ConnectionIssuesView(
    titleText: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .testTag("ConnectionIssueView"),
    ) {
        SheetTopBar(titleText = titleText)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            DashedRingsLayer(outerOnly = true)

            Image(
                painter = painterResource(R.drawable.phone),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(311.dp)
                    .align(Alignment.CenterStart),
            )

            DashedRingsLayer(outerOnly = false)
        }

        Display(
            text = stringResource(R.string.other__connection_issues_title)
                .withAccent(accentColor = Colors.Yellow),
            modifier = Modifier.fillMaxWidth(),
        )

        VerticalSpacer(8.dp)

        BodyM(
            text = stringResource(R.string.other__connection_issues_explain),
            color = Colors.White64,
            modifier = Modifier.fillMaxWidth(),
        )

        VerticalSpacer(24.dp)

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            GradientCircularProgressIndicator(
                strokeWidth = 1.dp,
                modifier = Modifier.size(32.dp),
            )
        }

        VerticalSpacer(16.dp)
    }
}

private val outerRing = DashedRingSpec(
    radiusFraction = 0.60f,
    color = Colors.Yellow.copy(alpha = 0.08f),
)

private val innerRings = listOf(
    DashedRingSpec(radiusFraction = 0.15f, color = Colors.Yellow.copy(alpha = 0.4f)),
    DashedRingSpec(radiusFraction = 0.30f, color = Colors.Yellow.copy(alpha = 0.25f)),
    DashedRingSpec(radiusFraction = 0.45f, color = Colors.Yellow.copy(alpha = 0.15f)),
)

@Composable
private fun DashedRingsLayer(outerOnly: Boolean, modifier: Modifier = Modifier) {
    val rings = if (outerOnly) listOf(outerRing) else innerRings
    Canvas(modifier = modifier.fillMaxSize()) {
        val center = Offset(size.width * 0.25f, size.height * 0.40f)

        if (outerOnly) {
            val fadeRadius = size.minDimension * 0.45f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Colors.White.copy(alpha = 0.06f), Color.Transparent),
                    center = center,
                    radius = fadeRadius,
                ),
                radius = fadeRadius,
                center = center,
            )
        }

        rings.forEach { ring -> drawDashedRing(ring, center) }
    }
}

private fun DrawScope.drawDashedRing(ring: DashedRingSpec, center: Offset) {
    drawCircle(
        color = ring.color,
        radius = size.minDimension * ring.radiusFraction,
        center = center,
        style = Stroke(
            width = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(8.dp.toPx(), 6.dp.toPx()),
            ),
        ),
    )
}

private data class DashedRingSpec(
    val radiusFraction: Float,
    val color: Color,
)

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            ConnectionIssuesView(titleText = "Send Bitcoin")
        }
    }
}

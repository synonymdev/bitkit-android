package to.bitkit.ui.onboarding

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateValue
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun InitializingWalletView() {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val infiniteTransition = rememberInfiniteTransition("rocketTransition")

        val rocketOffset by infiniteTransition.animateValue(
            initialValue = 0f,
            targetValue = 1f,
            typeConverter = Float.VectorConverter,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "rocketAnimation"
        )

        val xOffset = lerp(
            start = -maxWidth / 2 + 50.dp,
            end = maxWidth / 2 - 50.dp,
            fraction = rocketOffset,
        )
        val yOffset = lerp(
            start = maxHeight / 2 - 50.dp,
            end = -maxHeight / 2 + 50.dp,
            fraction = rocketOffset,
        )

        // Rocket
        Text(
            text = "🚀",
            fontSize = 40.sp,
            modifier = Modifier.offset(
                x = xOffset,
                y = yOffset,
            )
        )

        // Content
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                text = "Setting up\nyour wallet",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            CircularProgressIndicator(
                modifier = Modifier.size(48.dp)
            )
        }
    }
}

// Helper function to interpolate between Dp values
private fun lerp(start: Dp, end: Dp, fraction: Float): Dp {
    return (start.value + fraction * (end.value - start.value)).dp
}

@Preview
@Composable
fun InitializingWalletViewPreview() {
    AppThemeSurface {
        InitializingWalletView()
    }
}

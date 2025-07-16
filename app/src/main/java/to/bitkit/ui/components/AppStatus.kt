package to.bitkit.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.HealthState
import to.bitkit.ui.appViewModel
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun AppStatus(
    healthStatus: HealthState = rememberHealthState(),
    modifier: Modifier = Modifier,
    showText: Boolean = false,
    showReady: Boolean = false,
    color: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val statusColor = color ?: when (healthStatus) {
        HealthState.READY -> Colors.Green
        HealthState.PENDING -> Colors.Yellow
        HealthState.ERROR -> Colors.Red
    }

    // Don't show anything if ready and showReady is false
    if (healthStatus == HealthState.READY && !showReady) {
        return
    }

    val infiniteTransition = rememberInfiniteTransition(label = "AppStatus")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2000, easing = rememberRotationEasing())),
        label = "rotation",
    )
    val opacity by infiniteTransition.animateFloat(
        initialValue = if (healthStatus == HealthState.ERROR) 0.3f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "opacity",
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clickableAlpha(onClick = onClick)
    ) {
        when (healthStatus) {
            HealthState.READY -> {
                Icon(
                    painter = painterResource(R.drawable.ic_power),
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            HealthState.PENDING -> {
                Icon(
                    painter = painterResource(R.drawable.ic_arrows_clockwise),
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(rotation)
                )
            }

            HealthState.ERROR -> {
                Icon(
                    painter = painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier
                        .size(24.dp)
                        .alpha(opacity)
                )
            }
        }

        if (showText) {
            BodyMSB(stringResource(R.string.wallet__drawer__status), color = statusColor)
        }
    }
}

@Composable
fun rememberHealthState(): HealthState {
    val isPreview = LocalInspectionMode.current
    if (isPreview) return HealthState.READY

    val app = requireNotNull(appViewModel)
    val healthState by app.healthState.collectAsStateWithLifecycle()

    return healthState.overallHealth
}

@Composable
private fun rememberRotationEasing(): Easing {
    val bezierEasing = remember { CubicBezierEasing(0.4f, 0f, 0.2f, 1f) }

    return remember {
        Easing { fraction ->
            when {
                fraction <= 0.4f -> {
                    val normalizedFraction = fraction / 0.4f
                    bezierEasing.transform(normalizedFraction) * 0.5f
                }

                else -> {
                    val normalizedFraction = (fraction - 0.4f) / 0.6f
                    0.5f + bezierEasing.transform(normalizedFraction) * 0.5f
                }
            }
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
        ) {
            AppStatus(
                healthStatus = HealthState.READY,
                showText = true,
                showReady = true,
            )
            AppStatus(
                healthStatus = HealthState.PENDING,
                showText = true,
            )
            AppStatus(
                healthStatus = HealthState.ERROR,
                showText = true,
            )
        }
    }
}

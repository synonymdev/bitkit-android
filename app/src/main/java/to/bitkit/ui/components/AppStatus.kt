package to.bitkit.ui.components

import androidx.compose.animation.core.LinearEasing
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
import to.bitkit.models.NodeLifecycleState
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.appViewModel
import to.bitkit.ui.walletViewModel

@Composable
fun AppStatus(
    status: HealthState = rememberAppStatus(),
    modifier: Modifier = Modifier,
    showText: Boolean = false,
    showReady: Boolean = false,
    color: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val statusColor = color ?: when (status) {
        HealthState.READY -> Colors.Green
        HealthState.PENDING -> Colors.Yellow
        HealthState.ERROR -> Colors.Red
    }

    // Don't show anything if ready and showReady is false
    if (status == HealthState.READY && !showReady) {
        return
    }

    val infiniteTransition = rememberInfiniteTransition(label = "AppStatus")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation",
    )

    val opacity by infiniteTransition.animateFloat(
        initialValue = if (status == HealthState.ERROR) 0.3f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(600), repeatMode = RepeatMode.Reverse),
        label = "opacity",
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clickableAlpha(onClick = onClick)
    ) {
        when (status) {
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
fun rememberAppStatus(
    initialState: HealthState = HealthState.READY,
): HealthState {
    val isPreview = LocalInspectionMode.current
    if (isPreview) return initialState

    val wallet = requireNotNull(walletViewModel)
    val app = requireNotNull(appViewModel)

    val walletUiState by wallet.uiState.collectAsStateWithLifecycle()
    val healthState by app.healthState.collectAsStateWithLifecycle()

    return remember(walletUiState.nodeLifecycleState, healthState) {
        // Check node state first, then other states
        when (walletUiState.nodeLifecycleState) {
            is NodeLifecycleState.ErrorStarting -> HealthState.ERROR

            NodeLifecycleState.Stopped -> HealthState.ERROR

            NodeLifecycleState.Starting,
            NodeLifecycleState.Stopping,
            NodeLifecycleState.Initializing,
                -> HealthState.PENDING

            // If node is running, check other states
            NodeLifecycleState.Running -> {
                val states = listOf(
                    healthState.internetState,
                    healthState.bitcoinNodeState,
                    healthState.lightningNodeState,
                    healthState.backupState,
                )

                when {
                    HealthState.ERROR in states -> HealthState.ERROR
                    HealthState.PENDING in states -> HealthState.PENDING
                    else -> HealthState.READY
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
                status = HealthState.READY,
                showText = true,
                showReady = true,
            )
            AppStatus(
                status = HealthState.PENDING,
                showText = true,
            )
            AppStatus(
                status = HealthState.ERROR,
                showText = true,
            )
        }
    }
}

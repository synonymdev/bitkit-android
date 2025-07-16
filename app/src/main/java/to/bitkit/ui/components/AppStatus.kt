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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import to.bitkit.R
import to.bitkit.models.NodeLifecycleState
import to.bitkit.ui.settings.appStatus.AppStatusViewModel
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.walletViewModel
import kotlin.time.Duration.Companion.seconds

enum class AppStatusState { READY, PENDING, ERROR, }

/**
 * Determines the overall app status based on node lifecycle state and other factors.
 */
@Composable
fun rememberAppStatus(
    initialState: AppStatusState = AppStatusState.READY,
): AppStatusState {
    val isPreview = LocalInspectionMode.current
    if (isPreview) return initialState

    val wallet = requireNotNull(walletViewModel)
    val appStatus = hiltViewModel<AppStatusViewModel>()

    val walletUiState by wallet.uiState.collectAsStateWithLifecycle()
    val appStatusUiState by appStatus.uiState.collectAsStateWithLifecycle()

    var showStatus by remember { mutableStateOf(false) }

    // Give the app some time to initialize before showing the status
    LaunchedEffect(Unit) {
        delay(5.seconds)
        showStatus = true
    }

    // During init, return READY instead of error
    if (!showStatus) {
        return initialState
    }

    // Priority order: Check node state first, then other states
    return when (walletUiState.nodeLifecycleState) {
        is NodeLifecycleState.ErrorStarting -> AppStatusState.ERROR
        NodeLifecycleState.Stopped -> AppStatusState.ERROR
        NodeLifecycleState.Starting,
        NodeLifecycleState.Stopping,
        NodeLifecycleState.Initializing,
            -> AppStatusState.PENDING

        NodeLifecycleState.Running -> {
            // If node is running, check other states
            val states = listOf(
                appStatusUiState.internetState,
                appStatusUiState.bitcoinNodeState,
                appStatusUiState.lightningNodeState,
                appStatusUiState.backupState,
            )

            when {
                states.any { it == to.bitkit.ui.settings.appStatus.StatusUi.State.ERROR } -> AppStatusState.ERROR
                states.any { it == to.bitkit.ui.settings.appStatus.StatusUi.State.PENDING } -> AppStatusState.PENDING
                else -> AppStatusState.READY
            }
        }
    }
}

@Composable
fun AppStatus(
    status: AppStatusState = rememberAppStatus(),
    modifier: Modifier = Modifier,
    showText: Boolean = false,
    showReady: Boolean = false,
    color: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val statusColor = color ?: when (status) {
        AppStatusState.READY -> Colors.Green
        AppStatusState.PENDING -> Colors.Yellow
        AppStatusState.ERROR -> Colors.Red
    }

    // Don't show anything if ready and showReady is false
    if (status == AppStatusState.READY && !showReady) {
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
        initialValue = if (status == AppStatusState.ERROR) 0.3f else 1f,
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
            AppStatusState.READY -> {
                Icon(
                    painter = painterResource(R.drawable.ic_power),
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            AppStatusState.PENDING -> {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_clockwise),
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(rotation)
                )
            }

            AppStatusState.ERROR -> {
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

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
        ) {
            AppStatus(
                status = AppStatusState.READY,
                showText = true,
                showReady = true,
            )
            AppStatus(
                status = AppStatusState.PENDING,
                showText = true,
            )
            AppStatus(
                status = AppStatusState.ERROR,
                showText = true,
            )
        }
    }
}

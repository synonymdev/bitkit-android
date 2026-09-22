package to.bitkit.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.CupertinoMaterials
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.Toast
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.modifiers.rememberDebouncedClick
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import kotlin.math.roundToInt

private const val DISMISS_THRESHOLD_DP = 40
private const val SNAP_BACK_DAMPING_RATIO = 0.7f
private const val DRAG_RESISTANCE_FACTOR = 0.12f
private const val TINT_ALPHA = 0.32f
private const val SHADOW_ALPHA = 0.4f
private const val ELEVATION_DP = 10

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun ToastView(
    toast: Toast,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState = rememberHazeState(blurEnabled = true),
    hazeStyle: HazeStyle = CupertinoMaterials.thin(containerColor = toast.tintColor()),
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
) {
    val tintColor = toast.tintColor()
    val coroutineScope = rememberCoroutineScope()
    val dragOffsetY = remember { Animatable(0f) }
    var dragDistanceY by remember { mutableFloatStateOf(0f) }
    var hasPausedAutoHide by remember { mutableStateOf(false) }
    val dismissThreshold = DISMISS_THRESHOLD_DP.dp

    Box(
        contentAlignment = Alignment.TopStart,
        modifier = modifier
            .fillMaxWidth()
            .systemBarsPadding()
            .padding(horizontal = 16.dp)
            .then(toast.testTag?.let { Modifier.testTag(it) } ?: Modifier),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, dragOffsetY.value.roundToInt()) }
                .shadow(
                    elevation = ELEVATION_DP.dp,
                    shape = MaterialTheme.shapes.medium,
                    ambientColor = Color.Black.copy(alpha = SHADOW_ALPHA),
                    spotColor = Color.Black.copy(alpha = SHADOW_ALPHA)
                )
                .hazeEffect(
                    state = hazeState,
                    style = hazeStyle
                )
                .background(
                    color = tintColor.copy(alpha = TINT_ALPHA),
                    shape = MaterialTheme.shapes.medium
                )
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            // Resume auto-hide when drag ends (if we paused it)
                            dragDistanceY = 0f
                            if (hasPausedAutoHide) {
                                hasPausedAutoHide = false
                                onDragEnd()
                            }

                            coroutineScope.launch {
                                if (dragOffsetY.value < -dismissThreshold.toPx()) {
                                    onDismiss()
                                } else {
                                    // Snap back to original position
                                    dragOffsetY.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = SNAP_BACK_DAMPING_RATIO,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    )
                                }
                            }
                        },
                        onDragCancel = {
                            dragDistanceY = 0f
                            if (hasPausedAutoHide) {
                                hasPausedAutoHide = false
                                onDragEnd()
                            }

                            coroutineScope.launch {
                                dragOffsetY.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = SNAP_BACK_DAMPING_RATIO,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                            }
                        },
                        onVerticalDrag = { change, dragAmount ->
                            dragDistanceY += dragAmount
                            val translationY = if (dragDistanceY < 0f) {
                                dragDistanceY
                            } else {
                                dragDistanceY * DRAG_RESISTANCE_FACTOR
                            }

                            change.consume()
                            // Pause auto-hide when drag starts (only once)
                            if (!hasPausedAutoHide) {
                                hasPausedAutoHide = true
                                onDragStart()
                            }

                            coroutineScope.launch { dragOffsetY.snapTo(translationY) }
                        }
                    )
                }
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                BodyMSB(
                    text = toast.title,
                    color = tintColor,
                )
                toast.description?.let { description ->
                    Caption(
                        text = description,
                        color = Colors.White
                    )
                }
            }
        }

        // Close button overlay (top-trailing)
        if (!toast.autoHide) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, dragOffsetY.value.roundToInt()) },
                contentAlignment = Alignment.TopEnd
            ) {
                IconButton(
                    onClick = rememberDebouncedClick(onClick = onDismiss),
                    modifier = Modifier
                        .size(48.dp)
                        .padding(16.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_x),
                        contentDescription = stringResource(R.string.common__close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ToastHost(
    toast: Toast?,
    hazeState: HazeState,
    onDismiss: () -> Unit,
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
) {
    AnimatedContent(
        targetState = toast,
        transitionSpec = {
            (fadeIn() + slideInVertically { -it })
                .togetherWith(fadeOut() + slideOutVertically { -it })
                .using(SizeTransform(clip = false))
        },
        contentAlignment = Alignment.TopCenter,
        label = "toastAnimation",
    ) {
        if (it != null) {
            ToastView(
                toast = it,
                onDismiss = onDismiss,
                hazeState = hazeState,
                onDragStart = onDragStart,
                onDragEnd = onDragEnd
            )
        }
    }
}

@Composable
fun ToastOverlay(
    toast: Toast?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState = rememberHazeState(blurEnabled = true),
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
) {
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier.fillMaxSize(),
    ) {
        ToastHost(
            toast = toast,
            hazeState = hazeState,
            onDismiss = onDismiss,
            onDragStart = onDragStart,
            onDragEnd = onDragEnd
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun ToastViewPreview() {
    AppThemeSurface {
        ScreenColumn(
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ToastView(
                toast = Toast(
                    type = Toast.ToastType.WARNING,
                    title = "You're still offline",
                    description = "Check your connection to keep using Bitkit.",
                    autoHide = true,
                ),
                onDismiss = {},
            )
            ToastView(
                toast = Toast(
                    type = Toast.ToastType.LIGHTNING,
                    title = "Instant Payments Ready",
                    description = "You can now pay anyone, anywhere, instantly.",
                    autoHide = true,
                ),
                onDismiss = {},
            )
            ToastView(
                toast = Toast(
                    type = Toast.ToastType.SUCCESS,
                    title = "You're Back Online!",
                    description = "Successfully reconnected to the Internet.",
                    autoHide = true,
                ),
                onDismiss = {},
            )
            ToastView(
                toast = Toast(
                    type = Toast.ToastType.INFO,
                    title = "General Message",
                    description = "Used for neutral content to inform the user.",
                    autoHide = false,
                ),
                onDismiss = {},
            )
            ToastView(
                toast = Toast(
                    type = Toast.ToastType.ERROR,
                    title = "Error Toast",
                    description = "This is a toast message.",
                    autoHide = true,
                ),
                onDismiss = {},
            )
        }
    }
}

@ReadOnlyComposable
@Composable
private fun Toast.tintColor(): Color = when (type) {
    Toast.ToastType.SUCCESS -> Colors.Green
    Toast.ToastType.INFO -> Colors.Blue
    Toast.ToastType.LIGHTNING -> Colors.Purple
    Toast.ToastType.WARNING -> Colors.Brand
    Toast.ToastType.ERROR -> Colors.Brand
}

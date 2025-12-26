package to.bitkit.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.Colors
import kotlin.math.roundToInt

private const val MS_DURATION_ANIM = 300
private const val THRESHOLD_DISMISS = 0.33f
private const val VELOCITY_DISMISS = 1000f
private const val OFFSET_MIN_DRAG = -0.1f
private val sheetContainerColor = Color.Black

enum class SheetSize { LARGE, MEDIUM, SMALL, CALENDAR; }

/** @param priority Priority levels for timed sheets (higher number = higher priority)*/
enum class TimedSheetType(val priority: Int) {
    APP_UPDATE(priority = 5),
    BACKUP(priority = 4),
    NOTIFICATIONS(priority = 3),
    QUICK_PAY(priority = 2),
    HIGH_BALANCE(priority = 1)
}

@Composable
fun SheetHost(
    sheetSize: SheetSize,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var sheetHeightPx by remember { mutableFloatStateOf(0f) }
    val offsetY = remember { Animatable(1f) }
    var sheetVisible by remember { mutableStateOf(false) }
    val velocityTracker = remember { VelocityTracker() }

    LaunchedEffect(Unit) {
        sheetVisible = true
        offsetY.animateTo(0f, animationSpec = tween(MS_DURATION_ANIM))
    }

    fun dismiss() {
        scope.launch {
            offsetY.animateTo(1f, animationSpec = tween(MS_DURATION_ANIM))
            sheetVisible = false
            onDismiss()
        }
    }

    BackHandler(enabled = offsetY.value < 1f) {
        dismiss()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scrim(
            isVisible = offsetY.value < 1f,
            progress = 1f - offsetY.value,
            onClick = { dismiss() },
        )

        AnimatedVisibility(
            visible = sheetVisible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .offset { IntOffset(0, (offsetY.value * sheetHeightPx).roundToInt()) }
                    .onSizeChanged { sheetHeightPx = it.height.toFloat() }
                    .clip(AppShapes.sheet)
                    .background(sheetContainerColor)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        var dragPosition = 0f
                        detectVerticalDragGestures(
                            onDragStart = {
                                velocityTracker.resetTracking()
                                dragPosition = 0f
                            },
                            onDragEnd = {
                                val velocity = velocityTracker.calculateVelocity().y
                                val shouldDismiss = velocity > VELOCITY_DISMISS ||
                                    offsetY.value > THRESHOLD_DISMISS
                                scope.launch {
                                    if (shouldDismiss) {
                                        offsetY.animateTo(
                                            1f,
                                            animationSpec = tween(MS_DURATION_ANIM)
                                        )
                                        sheetVisible = false
                                        onDismiss()
                                    } else {
                                        offsetY.animateTo(
                                            0f,
                                            animationSpec = tween(MS_DURATION_ANIM)
                                        )
                                    }
                                }
                            },
                            onVerticalDrag = { change, dragAmount ->
                                dragPosition += dragAmount
                                velocityTracker.addPosition(
                                    change.uptimeMillis,
                                    Offset(0f, dragPosition)
                                )
                                val newOffset = offsetY.value + (dragAmount / sheetHeightPx)
                                scope.launch {
                                    offsetY.snapTo(newOffset.coerceIn(OFFSET_MIN_DRAG, 1f))
                                }
                            }
                        )
                    }
            ) {
                Box(
                    contentAlignment = Alignment.TopCenter,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Colors.White08)
                ) {
                    SheetDragHandle()
                }
                Box(
                    modifier = Modifier
                        .sheetHeight(sheetSize)
                        .navigationBarsPadding()
                ) {
                    content()
                }
                // Extended background tail to cover gap when dragging sheet up
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                )
            }
        }
    }
}

@Composable
private fun Scrim(
    isVisible: Boolean,
    progress: Float,
    onClick: () -> Unit,
) {
    val scrimAlpha by animateFloatAsState(
        targetValue = if (isVisible) progress * 0.5f else 0f,
        animationSpec = tween(durationMillis = MS_DURATION_ANIM),
        label = "sheetScrimAlpha"
    )
    if (scrimAlpha > 0f || isVisible) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Colors.Black.copy(alpha = scrimAlpha))
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = onClick,
                )
        )
    }
}

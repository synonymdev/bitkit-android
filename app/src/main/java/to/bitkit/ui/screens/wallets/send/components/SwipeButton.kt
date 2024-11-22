package to.bitkit.ui.screens.wallets.send.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.max

@Composable
fun SwipeButton(
    modifier: Modifier = Modifier,
    onComplete: suspend () -> Unit
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    val buttonHeight = 70.dp
    val innerPadding = 10.dp
    val coroutineScope = rememberCoroutineScope()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(buttonHeight)
    ) {
        val maxWidth = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        val buttonHeightPx = with(density) { buttonHeight.toPx() }
        val innerPaddingPx = with(density) { innerPadding.toPx() }

        // Gray background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(buttonHeight / 2))
                .background(Color.Gray.copy(alpha = 0.2f))
        )

        // Contents (Trail and Handle)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding / 2)
        ) {
            // Green trail
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(
                        with(density) {
                            max(0f, offsetX + buttonHeightPx - innerPaddingPx).toDp()
                        }
                    )
                    .height(buttonHeight - innerPadding)
                    .clip(CircleShape)
                    .background(Color.Green.copy(alpha = 0.2f))
            )

            // Text in the middle
            Text(
                text = "Swipe To Pay",
                color = Color.Black,
                modifier = Modifier
                    .align(Alignment.Center)
                    .alpha(1.0f - (offsetX / (maxWidth - buttonHeightPx)))
            )

            // Circular drag handle
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(offsetX.toInt(), 0) }
                    .size(buttonHeight - innerPadding)
                    .clip(CircleShape)
                    .background(Color.Green)
                    .pointerInput(isLoading) {
                        detectDragGestures(
                            onDragEnd = {
                                if (!isLoading) {
                                    val threshold = maxWidth * 0.7f
                                    if (offsetX > threshold) {
                                        isLoading = true
                                        offsetX = maxWidth - buttonHeightPx
                                        coroutineScope.launch {
                                            try {
                                                onComplete()
                                            } catch (e: Exception) {
                                                offsetX = 0f
                                            } finally {
                                                isLoading = false
                                            }
                                        }
                                    } else {
                                        offsetX = 0f
                                    }
                                }
                            },
                            onDrag = { _, dragAmount ->
                                if (!isLoading) {
                                    offsetX = (offsetX + dragAmount.x).coerceIn(0f, maxWidth - buttonHeightPx)
                                    isDragging = true
                                }
                            }
                        )
                    }
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    // Arrow Icon: It should transition from 1.0 to 0.25 alpha between 0% to 50% drag.
                    val arrowAlpha = if (offsetX <= maxWidth / 2) {
                        1f - (offsetX / (maxWidth / 2)) * 0.75f
                    } else {
                        0f
                    }

                    // Checkmark Icon: It stays invisible until 50% drag, then fades in from 25% to 100% opacity.
                    val checkAlpha = if (offsetX < maxWidth / 2) 0f else {
                        val alphaProgress = (offsetX - maxWidth / 2) / (maxWidth / 2)
                        0.25f + (alphaProgress * 0.75f)
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Default.ArrowForward,
                        contentDescription = "Arrow",
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .alpha(arrowAlpha)
                    )
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Check",
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .alpha(checkAlpha)
                    )
                }
            }
        }
    }
}

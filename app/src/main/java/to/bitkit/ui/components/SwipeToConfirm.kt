package to.bitkit.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import kotlin.math.roundToInt

private val CircleSize = 60.dp
private val GrabSize = 120.dp
private val InvisibleBorder = (GrabSize - CircleSize) / 2
private val Padding = 8.dp

@Composable
fun SwipeToConfirm(
    text: String = stringResource(R.string.other__swipe),
    color: Color = Colors.Green,
    icon: ImageVector = Icons.AutoMirrored.Default.ArrowForward,
    @DrawableRes endIcon: Int = R.drawable.ic_check,
    endIconTint: Color = Colors.Black,
    loading: Boolean = false,
    confirmed: Boolean = false,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val trailColor = remember(color) { color.copy(alpha = 0.24f) }

    var swiperWidth by remember { mutableFloatStateOf(0f) }
    val maxPanX = if (swiperWidth == 0f) 1f else swiperWidth - with(LocalDensity.current) { CircleSize.toPx() }

    val panX = remember { Animatable(0f) }
    val loadingOpacity = remember { Animatable(0f) }

    LaunchedEffect(loading) {
        loadingOpacity.animateTo(
            targetValue = if (loading) 1f else 0f,
            animationSpec = tween(500)
        )
    }

    LaunchedEffect(confirmed, maxPanX) {
        panX.animateTo(
            targetValue = if (confirmed) maxPanX else 0f,
            animationSpec = spring()
        )
    }

    Box(
        modifier = modifier
            .requiredHeight(CircleSize + Padding * 2)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.16f))
            .padding(Padding)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    swiperWidth = size.width.toFloat()
                }
        ) {
            // Trail
            Box(
                modifier = Modifier
                    .width(with(LocalDensity.current) { panX.value.toDp() + CircleSize })
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(trailColor)
            )

            // Text
            Text(
                text = text,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .alpha(1f - (panX.value / maxPanX))
            )

            // Draggable Circle
            Box(
                modifier = Modifier
                    .offset { IntOffset(x = (panX.value.toDp() - InvisibleBorder).toPx().roundToInt(), y = 0) }
                    .size(GrabSize)
                    .pointerInput(loading, confirmed) {
                        if (!loading && !confirmed) {
                            detectHorizontalDragGestures(
                                onDragStart = { },
                                onHorizontalDrag = { _, dragAmount ->
                                    scope.launch {
                                        val newValue = (panX.value + dragAmount).coerceIn(0f, maxPanX)
                                        panX.snapTo(newValue)
                                    }
                                },
                                onDragEnd = {
                                    scope.launch {
                                        val swiped = panX.value > maxPanX * 0.8f
                                        if (swiped) {
                                            panX.animateTo(maxPanX, spring())
                                            onConfirm()
                                        } else {
                                            panX.animateTo(0f, spring())
                                        }
                                    }
                                }
                            )
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .size(CircleSize)
                        .align(Alignment.Center)
                        .background(color, CircleShape)
                ) {
                    // Start Icon (Arrow)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(1f - (panX.value / (maxPanX / 2)) - loadingOpacity.value)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = Color.Black,
                        )
                    }

                    // End Icon
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha((panX.value - maxPanX / 2) / (maxPanX / 2) - loadingOpacity.value)
                    ) {
                        Icon(
                            painter = painterResource(endIcon),
                            contentDescription = null,
                            tint = endIconTint,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    // Loading Spinner
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(loadingOpacity.value)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(34.dp)
                                .align(Alignment.Center),
                            color = Color.Black,
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun SwipeToConfirmPreview() {
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AppThemeSurface {
        Column(
            verticalArrangement = Arrangement.Bottom,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .systemBarsPadding()
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
            )
            SwipeToConfirm(
                text = stringResource(R.string.wallet__send_swipe),
                color = Colors.Green,
                loading = isLoading,
                onConfirm = {
                    scope.launch {
                        isLoading = true
                        delay(1500)
                        isLoading = false
                    }
                }
            )
        }
    }
}

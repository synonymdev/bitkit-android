package to.bitkit.ui.onboarding

import android.annotation.SuppressLint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateValue
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import kotlinx.coroutines.delay
import to.bitkit.R
import to.bitkit.ui.components.Display
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import kotlin.math.roundToInt

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun InitializingWalletView(
    shouldFinish: Boolean,
    onComplete: () -> Unit,
    isRestoringBackups: Boolean = false,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val percentage = remember { Animatable(0f) }

        val animationDuration = if (isRestoringBackups) 8000 else 2000

        // Progress to 100%
        LaunchedEffect(animationDuration) {
            percentage.animateTo(
                targetValue = 100f,
                animationSpec = tween(durationMillis = animationDuration, easing = LinearEasing)
            )
        }

        // Completion callback
        LaunchedEffect(shouldFinish, percentage.value) {
            if (shouldFinish && percentage.value >= 99.9) {
                delay(500)
                onComplete()
            }
        }

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
            start = -maxWidth / 2 - 100.dp,
            end = maxWidth / 2 + 100.dp,
            fraction = rocketOffset,
        )

        val yOffset = lerp(
            start = maxHeight / 2 + 150.dp,
            end = 0.dp - 100.dp,
            fraction = rocketOffset,
        )

        // Rocket
        Image(
            painter = painterResource(id = R.drawable.rocket),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(400.dp)
                .offset(
                    x = xOffset,
                    y = yOffset,
                )
        )

        // Central layout with constraints for the circle and column
        ConstraintLayout(
            modifier = Modifier.fillMaxSize()
        ) {
            val (circle, texts) = createRefs()

            // Animated Circle
            val rotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 4000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "circleAnimation"
            )

            // Loading Circle
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .constrainAs(circle) {
                        top.linkTo(parent.top)
                        bottom.linkTo(parent.bottom)
                        start.linkTo(parent.start)
                        end.linkTo(parent.end)
                    }
            ) {
                Image(
                    painter = painterResource(id = R.drawable.loading_circle),
                    contentDescription = null,
                    modifier = Modifier
                        .size(192.dp)
                        .rotate(rotation)
                )
                Display(
                    text = "${percentage.value.roundToInt()}%",
                    fontWeight = FontWeight.Bold,
                    fontSize = 48.sp,
                    color = Colors.Brand,
                )
            }

            // Content Column
            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .constrainAs(texts) {
                        top.linkTo(circle.bottom)
                        start.linkTo(circle.start)
                        end.linkTo(circle.end)
                    }
                    .padding(top = 32.dp)
            ) {
                Display(text = stringResource(R.string.onboarding__loading_header).withAccent())
            }
        }
    }
}

// Helper function to interpolate between Dp values
private fun lerp(start: Dp, end: Dp, fraction: Float): Dp {
    return (start.value + fraction * (end.value - start.value)).dp
}

@Preview(showSystemUi = true)
@Composable
private fun InitializingWalletViewPreview() {
    AppThemeSurface {
        InitializingWalletView(
            shouldFinish = false,
            onComplete = {},
        )
    }
}

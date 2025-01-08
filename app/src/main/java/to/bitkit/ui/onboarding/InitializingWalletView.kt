package to.bitkit.ui.onboarding

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import kotlinx.coroutines.delay
import to.bitkit.R
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.Display
import kotlin.math.roundToInt

@Composable
fun InitializingWalletView(
    shouldFinish: Boolean,
    onComplete: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val percentage = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            percentage.animateTo(
                targetValue = 100f,
                animationSpec = tween(durationMillis = 2000, easing = LinearEasing)
            )
        }

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
                Display(text = "SETTING UP")
                Display(
                    text = "YOUR WALLET",
                    color = Colors.Brand,
                    modifier = Modifier.offset(y = (-8).dp)
                )
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

package to.bitkit.ui.screens.transfer.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun TransferAnimationView(
    @DrawableRes largeCircleRes: Int,
    @DrawableRes smallCircleRes: Int,
    @DrawableRes contentRes: Int = R.drawable.transfer,
    rotateContent: Boolean = true,
) {
    val animationSpec: InfiniteRepeatableSpec<Float> = infiniteRepeatable(
        animation = tween(durationMillis = 3000, easing = EaseInOut),
        repeatMode = RepeatMode.Reverse,
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        val infiniteTransition = rememberInfiniteTransition("transition")

        val rotationLarge by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = -90f,
            animationSpec = animationSpec,
            label = "rotationLarge",
        )
        val rotationSmall by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 60f,
            animationSpec = animationSpec,
            label = "rotationSmall",
        )
        val rotationArrows by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 70f,
            animationSpec = animationSpec,
            label = "rotationArrows",
        )
        Image(
            painter = painterResource(largeCircleRes),
            contentDescription = null,
            modifier = Modifier.rotate(rotationLarge),
        )
        Image(
            painter = painterResource(smallCircleRes),
            contentDescription = null,
            modifier = Modifier.rotate(rotationSmall),
        )
        Image(
            painter = painterResource(contentRes),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (rotateContent) {
                        Modifier.rotate(rotationArrows)
                    } else {
                        Modifier
                    },
                ),
        )
    }
}

@Preview
@Composable
private fun PreviewSyncing() {
    AppThemeSurface {
        TransferAnimationView(
            largeCircleRes = R.drawable.ln_sync_large,
            smallCircleRes = R.drawable.ln_sync_small,
            contentRes = R.drawable.transfer,
            rotateContent = true,
        )
    }
}

@Preview
@Composable
private fun PreviewNoContentRotation() {
    AppThemeSurface {
        TransferAnimationView(
            largeCircleRes = R.drawable.ln_sync_large,
            smallCircleRes = R.drawable.ln_sync_small,
            contentRes = R.drawable.transfer,
            rotateContent = false,
        )
    }
}

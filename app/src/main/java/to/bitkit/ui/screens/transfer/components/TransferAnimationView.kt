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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun TransferAnimationView(
    @DrawableRes largeCircleRes: Int,
    @DrawableRes smallCircleRes: Int,
    modifier: Modifier = Modifier,
    @DrawableRes contentRes: Int = R.drawable.transfer,
    rotateContent: Boolean = true,
    contentWidthFraction: Float = 1f,
    keepAssetRatios: Boolean = false,
) {
    val animationSpec: InfiniteRepeatableSpec<Float> = infiniteRepeatable(
        animation = tween(durationMillis = 3000, easing = EaseInOut),
        repeatMode = RepeatMode.Reverse,
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.padding(horizontal = 16.dp),
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
        if (keepAssetRatios) {
            ScaledLayers(
                largeCircleRes = largeCircleRes,
                smallCircleRes = smallCircleRes,
                contentRes = contentRes,
                rotationLarge = rotationLarge,
                rotationSmall = rotationSmall,
                rotationContent = if (rotateContent) rotationArrows else 0f,
            )
            return@Box
        }
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
                .then(
                    if (contentWidthFraction < 1f) {
                        Modifier
                            .matchParentSize()
                            .wrapContentSize()
                            .fillMaxSize(contentWidthFraction)
                    } else {
                        Modifier.fillMaxWidth()
                    },
                )
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

/**
 * Draws the rings and figure at their sizes relative to the large ring, as the design sets them, at any density:
 * the assets are `nodpi`, so drawn at their own pixel size the small ring grows against the clamped large one.
 */
@Composable
private fun ScaledLayers(
    @DrawableRes largeCircleRes: Int,
    @DrawableRes smallCircleRes: Int,
    @DrawableRes contentRes: Int,
    rotationLarge: Float,
    rotationSmall: Float,
    rotationContent: Float,
) {
    val large = painterResource(largeCircleRes)
    val small = painterResource(smallCircleRes)
    val content = painterResource(contentRes)
    val largeWidth = large.intrinsicSize.width
    val maxSize = with(LocalDensity.current) { largeWidth.toDp() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .widthIn(max = maxSize)
            .aspectRatio(1f),
    ) {
        Image(
            painter = large,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .rotate(rotationLarge),
        )
        Image(
            painter = small,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize(small.intrinsicSize.width / largeWidth)
                .rotate(rotationSmall),
        )
        Image(
            painter = content,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize(content.intrinsicSize.width / largeWidth)
                .rotate(rotationContent),
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

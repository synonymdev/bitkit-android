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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import to.bitkit.R
import to.bitkit.ui.theme.AppThemeSurface

/** Inner ring size against the outer ring, as iOS's `EllipseLoader` draws it. */
const val ELLIPSE_INNER_SHARE = 0.70f

/** Side of the square the figure fits in, against the outer ring, as iOS's `EllipseLoader` `centerScale`. */
const val ELLIPSE_FIGURE_SHARE = 0.85f

@Composable
fun TransferAnimationView(
    @DrawableRes largeCircleRes: Int,
    @DrawableRes smallCircleRes: Int,
    modifier: Modifier = Modifier,
    @DrawableRes contentRes: Int = R.drawable.transfer,
    rotateContent: Boolean = true,
    contentWidthFraction: Float = 1f,
    keepAssetRatios: Boolean = false,
    innerRingShare: Float = ELLIPSE_INNER_SHARE,
    figureShare: Float = ELLIPSE_FIGURE_SHARE,
    figureOffsetShare: Float = 0f,
    maxSize: Dp = Dp.Infinity,
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
                innerRingShare = innerRingShare,
                figureShare = figureShare,
                figureOffsetShare = figureOffsetShare,
                maxSize = maxSize,
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
 * Draws the rings and figure as iOS's `EllipseLoader` does: the outer ring fills the largest square that fits, up to
 * [maxSize], the inner ring takes [innerRingShare] of it and the figure fits a square of [figureShare] of it, moved
 * down by [figureOffsetShare] of it.
 */
@Composable
private fun ScaledLayers(
    @DrawableRes largeCircleRes: Int,
    @DrawableRes smallCircleRes: Int,
    @DrawableRes contentRes: Int,
    rotationLarge: Float,
    rotationSmall: Float,
    rotationContent: Float,
    innerRingShare: Float,
    figureShare: Float,
    figureOffsetShare: Float,
    maxSize: Dp,
) {
    BoxWithConstraints(contentAlignment = Alignment.Center) {
        val container = min(min(maxWidth, maxHeight), maxSize)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(container)
                .clipToBounds()
                .testTag("TransferAnimation"),
        ) {
            Image(
                painter = painterResource(largeCircleRes),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .rotate(rotationLarge),
            )
            Image(
                painter = painterResource(smallCircleRes),
                contentDescription = null,
                modifier = Modifier
                    .size(container * innerRingShare)
                    .rotate(rotationSmall),
            )
            Image(
                painter = painterResource(contentRes),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .offset(y = container * figureOffsetShare)
                    // Required, as a figure can be drawn larger than the outer ring, clipped to it.
                    .requiredSize(container * figureShare)
                    .rotate(rotationContent),
            )
        }
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

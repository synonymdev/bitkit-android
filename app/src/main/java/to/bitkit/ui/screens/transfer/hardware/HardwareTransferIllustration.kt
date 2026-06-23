package to.bitkit.ui.screens.transfer.hardware

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

/** Figma illustration width ratio from a 256px asset in a 375px frame. */
private const val HARDWARE_VISUAL_WIDTH_RATIO = 256f / 375f

/** Figma top ratio for the signed check visual within the content area below navigation. */
internal const val SIGNED_VISUAL_TOP_RATIO = (481f - 92f) / (812f - 92f - 34f)

/** Figma top ratio for the Trezor visual within the content area below navigation. */
internal const val SIGN_VISUAL_TOP_RATIO = (488f - 92f) / (812f - 92f - 34f)

@Composable
internal fun BoxScope.HardwareTransferIllustration(
    modifier: Modifier = Modifier,
    @DrawableRes drawableRes: Int,
    topRatio: Float,
) {
    BoxWithConstraints(modifier = Modifier.matchParentSize()) {
        val visualSize = maxWidth * HARDWARE_VISUAL_WIDTH_RATIO
        val topOffset = maxHeight * topRatio

        Image(
            painter = painterResource(id = drawableRes),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = topOffset)
                .size(visualSize)
                .then(modifier)
        )
    }
}

package to.bitkit.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.bitkit.R

// Device illustration proportions, taken from the Figma hardware wallet frames.
private const val HW_DEVICE_IMAGE_SIZE_RATIO = 256f / 375f
private const val HW_DEVICE_TREZOR_BLEED_RATIO = 84f / 375f
private const val HW_DEVICE_LEDGER_BLEED_RATIO = 53f / 375f
private const val HW_DEVICE_STAGGER_RATIO = 12f / 375f

@Composable
fun HwDeviceIllustrations(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val imageSize = maxWidth * HW_DEVICE_IMAGE_SIZE_RATIO
        val staggerY = maxWidth * HW_DEVICE_STAGGER_RATIO
        TrezorImage(imageSize = imageSize, staggerY = staggerY)
        LedgerImage(
            imageSize = imageSize,
            staggerY = staggerY,
            modifier = Modifier.blur(16.dp, BlurredEdgeTreatment.Unbounded)
        )
    }
}

@Composable
private fun BoxWithConstraintsScope.TrezorImage(
    imageSize: Dp,
    staggerY: Dp,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(R.drawable.trezor),
        contentDescription = null,
        modifier = modifier
            .size(imageSize)
            .align(Alignment.CenterStart)
            .offset(x = -maxWidth * HW_DEVICE_TREZOR_BLEED_RATIO, y = staggerY)
    )
}

@Composable
private fun BoxWithConstraintsScope.LedgerImage(
    imageSize: Dp,
    staggerY: Dp,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(R.drawable.ledger),
        contentDescription = null,
        modifier = modifier
            .size(imageSize)
            .align(Alignment.CenterEnd)
            .offset(x = maxWidth * HW_DEVICE_LEDGER_BLEED_RATIO, y = -staggerY)
    )
}

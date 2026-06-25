package to.bitkit.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.models.TransportType
import to.bitkit.ui.theme.Colors

/**
 * HwWalletComponents - Reusable components and utilities for the Hardware Wallet UI.
 * */

/** Illustration width as a fraction of the sheet width — the 256-wide Visual in the 375-wide Figma frame. */
internal const val HW_ILLUSTRATION_SIZE_RATIO = 256f / 375f

/** Figma top ratio for the signed check visual within the content area below navigation. */
internal const val SIGNED_VISUAL_TOP_RATIO = (481f - 92f) / (812f - 92f - 34f)

/** Figma top ratio for the Trezor visual within the content area below navigation. */
internal const val SIGN_VISUAL_TOP_RATIO = (488f - 92f) / (812f - 92f - 34f)

/** Trezor illustration left bleed past the frame, as a fraction of the sheet width (Figma device frames). */
private const val HW_DEVICE_TREZOR_BLEED_RATIO = 84f / 375f

/** Ledger illustration right bleed past the frame, as a fraction of the sheet width (Figma device frames). */
private const val HW_DEVICE_LEDGER_BLEED_RATIO = 53f / 375f

/** Vertical stagger between the two device illustrations, as a fraction of the sheet width. */
private const val HW_DEVICE_STAGGER_RATIO = 12f / 375f

@Composable
fun HwDeviceIllustrations(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val imageSize = maxWidth * HW_ILLUSTRATION_SIZE_RATIO
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
fun HwWalletConnectionIcon(
    transportType: TransportType,
    isConnected: Boolean,
    modifier: Modifier = Modifier,
) {
    val contentDescription = stringResource(
        id = when (transportType) {
            TransportType.BLUETOOTH -> if (isConnected) {
                R.string.hardware__connection_badge_connected_bluetooth
            } else {
                R.string.hardware__connection_badge_disconnected_bluetooth
            }
            TransportType.USB -> if (isConnected) {
                R.string.hardware__connection_badge_connected_usb
            } else {
                R.string.hardware__connection_badge_disconnected_usb
            }
        }
    )

    Icon(
        painter = painterResource(
            id = when (transportType) {
                TransportType.BLUETOOTH -> R.drawable.ic_bluetooth_connected
                TransportType.USB -> R.drawable.ic_usb_connected
            }
        ),
        contentDescription = contentDescription,
        tint = if (isConnected) Colors.Green else Colors.Gray1,
        modifier = modifier
    )
}

@Composable
internal fun BoxScope.HardwareTransferIllustration(
    modifier: Modifier = Modifier,
    @DrawableRes drawableRes: Int,
    topRatio: Float,
) {
    BoxWithConstraints(modifier = Modifier.matchParentSize()) {
        val visualSize = maxWidth * HW_ILLUSTRATION_SIZE_RATIO
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

package to.bitkit.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import to.bitkit.R

private const val IMAGE_FILL_PERCENTAGE = 0.8f
private const val USDT_IMAGE_SIZE_RATIO = 9f / 7f
private const val SWIPE_ROTATION_DEGREES = 14f

@Composable
fun PaymentReviewIllustration(
    swipeProgress: () -> Float,
    modifier: Modifier = Modifier,
    isUsdt: Boolean = false,
    isPaymentRequest: Boolean = false,
) {
    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxWidth().then(if (isUsdt || isPaymentRequest) Modifier.clipToBounds() else Modifier)
    ) {
        val imageModifier = if (isUsdt || isPaymentRequest) {
            Modifier.requiredSize(minOf(maxHeight, 256.dp) * USDT_IMAGE_SIZE_RATIO)
        } else {
            Modifier.fillMaxWidth(IMAGE_FILL_PERCENTAGE).padding(bottom = 16.dp)
        }
        Image(
            painter = painterResource(
                if (isPaymentRequest) R.drawable.payment_request_coins else R.drawable.coin_stack_4
            ),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = imageModifier.graphicsLayer { rotationZ = swipeProgress() * SWIPE_ROTATION_DEGREES }
        )
    }
}

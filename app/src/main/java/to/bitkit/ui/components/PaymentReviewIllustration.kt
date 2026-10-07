package to.bitkit.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import to.bitkit.R

private const val IMAGE_FILL_PERCENTAGE = 0.8f
private const val SWIPE_ROTATION_DEGREES = 14f

@Composable
fun PaymentReviewIllustration(swipeProgress: () -> Float, modifier: Modifier = Modifier) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxWidth()) {
        Image(
            painter = painterResource(R.drawable.coin_stack_4),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(IMAGE_FILL_PERCENTAGE).padding(bottom = 16.dp)
                .graphicsLayer { rotationZ = swipeProgress() * SWIPE_ROTATION_DEGREES }
        )
    }
}

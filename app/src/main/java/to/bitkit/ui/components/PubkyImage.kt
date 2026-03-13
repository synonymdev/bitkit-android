package to.bitkit.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import to.bitkit.R
import to.bitkit.ui.theme.Colors

@Composable
fun PubkyImage(
    uri: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    SubcomposeAsyncImage(
        model = uri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        loading = {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = Colors.White32,
                    modifier = Modifier.size(size / 3),
                )
            }
        },
        success = {
            var loaded by remember { mutableStateOf(false) }
            val scale by animateFloatAsState(
                targetValue = if (loaded) 1f else 0.8f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
                label = "pubky_image_scale",
            )
            LaunchedEffect(Unit) { loaded = true }
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
            )
        },
        error = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .matchParentSize()
                    .background(Colors.Gray5, CircleShape),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_user_square),
                    contentDescription = null,
                    tint = Colors.White32,
                    modifier = Modifier.size(size / 2),
                )
            }
        },
        modifier = modifier
            .size(size)
            .clip(CircleShape),
    )
}

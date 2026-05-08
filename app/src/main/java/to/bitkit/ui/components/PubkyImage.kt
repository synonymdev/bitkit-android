package to.bitkit.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import to.bitkit.R
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun PubkyImage(
    uri: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    var imageState by remember { mutableStateOf(ImageState.Loading) }

    val scale by animateFloatAsState(
        targetValue = if (imageState == ImageState.Success) 1f else 0.8f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "pubky_image_scale",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
    ) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onSuccess = { imageState = ImageState.Success },
            onError = { imageState = ImageState.Error },
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
        )

        ImageOverlay(state = imageState, size = size)
    }
}

private enum class ImageState { Loading, Success, Error }

@Composable
private fun ImageOverlay(state: ImageState, size: Dp) {
    val loadingAlpha by animateFloatAsState(
        targetValue = if (state == ImageState.Loading) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "loading_alpha",
    )
    val errorAlpha by animateFloatAsState(
        targetValue = if (state == ImageState.Error) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "error_alpha",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        if (loadingAlpha > 0f) {
            GradientCircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier
                    .size(size / 3)
                    .graphicsLayer { alpha = loadingAlpha }
            )
        }

        if (errorAlpha > 0f) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = errorAlpha }
                    .background(Colors.Gray5, CircleShape)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_user_square),
                    contentDescription = null,
                    tint = Colors.White32,
                    modifier = Modifier.size(size / 2)
                )
            }
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .background(Colors.Gray7)
                .padding(16.dp)
        ) {
            ImageState.entries.forEach { state ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BodyMSB(state.name)
                    VerticalSpacer(16.dp)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Colors.Black)
                    ) {
                        if (state == ImageState.Success) {
                            Icon(
                                painter = painterResource(R.drawable.ic_user_square),
                                contentDescription = null,
                                tint = Colors.Brand,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        ImageOverlay(state = state, size = 64.dp)
                    }
                }
            }
        }
    }
}

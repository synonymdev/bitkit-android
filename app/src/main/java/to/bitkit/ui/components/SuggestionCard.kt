package to.bitkit.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ShapeDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.models.Suggestion
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.Colors

private const val GLOW_ANIMATION_MILLIS = 1100
private const val MIN_ALPHA_GRADIENT = 0.24f
private const val MAX_ALPHA_GRADIENT = 0.9f

@Composable
fun SuggestionCard(
    modifier: Modifier = Modifier,
    gradientColor: Color,
    title: String,
    description: String,
    @DrawableRes icon: Int,
    onClose: (() -> Unit)? = null,
    size: Int = 152,
    disableGlow: Boolean = false,
    captionColor: Color = Colors.White64,
    onClick: () -> Unit,
) {
    val isDismissible = onClose != null

    // Glow animation for non-dismissible cards
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = MIN_ALPHA_GRADIENT,
        targetValue = MAX_ALPHA_GRADIENT,
        animationSpec = infiniteRepeatable(
            animation = tween(GLOW_ANIMATION_MILLIS),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    Box(
        modifier = modifier
            .size(size.dp)
            .clip(ShapeDefaults.Large)
            .then(
                if (isDismissible || disableGlow) {
                    Modifier.gradientBackground(gradientColor)
                } else {
                    val (borderColor, centerColor) =
                        @Suppress("MagicNumber")
                        when (gradientColor) {
                            Colors.Purple24 -> Pair(
                                Color(185, 92, 232),
                                Color(65, 32, 80)
                            )

                            Colors.Red24 -> Pair(
                                Color(255, 68, 0),
                                Color(100, 24, 0)
                            )

                            else -> Pair(
                                gradientColor,
                                gradientColor.copy(alpha = MIN_ALPHA_GRADIENT)
                            )
                        }

                    Modifier
                        .gradientRadialBackground(centerColor, glowAlpha)
                        .border(width = 1.dp, color = borderColor, shape = ShapeDefaults.Large)
                }
            )
            .clickableAlpha { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Image(
                    painter = painterResource(icon),
                    contentDescription = null,
                    contentScale = ContentScale.FillHeight,
                    modifier = Modifier.weight(1f)
                )

                if (onClose != null) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(16.dp)
                            .testTag("SuggestionDismiss")
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_x),
                            contentDescription = null,
                            tint = Colors.White,
                        )
                    }
                }
            }

            Headline20(
                text = AnnotatedString(title),
                color = Colors.White,
            )

            CaptionB(
                text = description,
                color = captionColor,
            )
        }
    }
}

private fun Modifier.gradientRadialBackground(
    centerColor: Color,
    glowAlpha: Float = 1f,
): Modifier {
    return this
        .background(
            brush = Brush.radialGradient(
                colors = listOf(
                    centerColor.copy(alpha = glowAlpha * 0.10f),
                    centerColor.copy(alpha = glowAlpha),
                ),
            )
        )
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    LazyVerticalGrid(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(Suggestion.entries) { item ->
            SuggestionCard(
                gradientColor = item.color,
                title = stringResource(item.title),
                description = stringResource(item.description),
                icon = item.icon,
                onClose = {},
                onClick = {}, // All cards are clickable
            )
        }
    }
}

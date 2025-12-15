package to.bitkit.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.ShapeDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.models.ActivityBannerType
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.util.outerGlow
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

private const val GLOW_ANIMATION_MILLIS = 1200

@Composable
fun ActivityBanner(
    gradientColor: Color,
    title: String,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "glow")

    val innerShadowOpacity by infiniteTransition.animateFloat(
        initialValue = 0.32f,
        targetValue = 0.64f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = GLOW_ANIMATION_MILLIS),
            repeatMode = RepeatMode.Reverse
        ),
        label = "inner_shadow_opacity"
    )

    val dropShadowOpacity by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = GLOW_ANIMATION_MILLIS),
            repeatMode = RepeatMode.Reverse
        ),
        label = "drop_shadow_opacity"
    )

    val radialGradientOpacity by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = GLOW_ANIMATION_MILLIS),
            repeatMode = RepeatMode.Reverse
        ),
        label = "radial_gradient_opacity"
    )

    val borderOpacity by infiniteTransition.animateFloat(
        initialValue = 0.32f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = GLOW_ANIMATION_MILLIS),
            repeatMode = RepeatMode.Reverse
        ),
        label = "border_opacity"
    )

    val density = LocalDensity.current.density

    Box(
        modifier = modifier
            .requiredHeight(72.dp)
            .outerGlow(
                glowColor = gradientColor,
                glowOpacity = dropShadowOpacity,
                glowRadius = 12.dp,
                cornerRadius = 16.dp
            )
            .clickableAlpha(onClick = onClick)
    ) {
        // Main card content with clipped backgrounds
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .requiredHeight(72.dp)
                .clip(ShapeDefaults.Large)
                // Layer 1: Base color (black)
                .background(Color.Black)
                // Layer 2: Inner shadow approximation (radial gradient from edges)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            gradientColor.copy(alpha = innerShadowOpacity)
                        ),
                        radius = 400f
                    )
                )
                // Layer 3: Linear gradient (top to bottom)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            gradientColor.copy(alpha = 0.32f),
                            gradientColor.copy(alpha = 0f)
                        )
                    )
                )
                // Layer 4: Radial gradient (top-left corner)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            gradientColor.copy(alpha = radialGradientOpacity),
                            gradientColor.copy(alpha = 0f)
                        ),
                        center = Offset(0f, 0f),
                        radius = 160f * density
                    )
                )
                // Border with animated opacity
                .border(
                    width = 1.dp,
                    color = gradientColor.copy(alpha = borderOpacity),
                    shape = ShapeDefaults.Large
                )
        )

        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = gradientColor
            )

            Headline20(
                text = AnnotatedString(title),
                color = Colors.White,
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items = ActivityBannerType.entries) { item ->
                ActivityBanner(
                    gradientColor = item.color,
                    title = stringResource(R.string.activity_banner__transfer_in_progress),
                    icon = item.icon,
                    onClick = {},
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

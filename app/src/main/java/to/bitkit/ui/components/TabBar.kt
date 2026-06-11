package to.bitkit.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.rememberHazeState
import to.bitkit.R
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.shared.util.primaryButtonStyle
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

private val iconToTextGap = 4.dp
private val iconSize = 20.dp

const val TAB_BAR_HEIGHT = 56
const val TAB_BAR_PADDING_BOTTOM = 8
private const val GRADIENT_HEIGHT = 134
private const val TAB_BAR_EXIT_DURATION_MS = 180
private const val TAB_BAR_SETTLE_DAMPING_RATIO = 0.8f

private val buttonLeftShape = RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
private val buttonRightShape = RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
private val tabBarEnterOffsetSpring = spring<IntOffset>(
    dampingRatio = TAB_BAR_SETTLE_DAMPING_RATIO,
    stiffness = Spring.StiffnessMediumLow,
)
private val tabBarExitOffsetTween = tween<IntOffset>(
    durationMillis = TAB_BAR_EXIT_DURATION_MS,
    easing = FastOutSlowInEasing,
)

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun BoxScope.TabBar(
    modifier: Modifier = Modifier,
    isVisible: Boolean = true,
    onSendClick: () -> Unit = {},
    onReceiveClick: () -> Unit = {},
    onScanClick: () -> Unit = {},
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = tabBarEnterOffsetSpring,
        ),
        exit = slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tabBarExitOffsetTween,
        ),
        modifier = modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(GRADIENT_HEIGHT.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Colors.Black),
                        )
                    )
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = TAB_BAR_PADDING_BOTTOM.dp)
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier.primaryButtonStyle(
                        isEnabled = true,
                        shape = MaterialTheme.shapes.large,
                    )
                ) {
                    // Send Button
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(TAB_BAR_HEIGHT.dp)
                            .clip(buttonLeftShape)
                            .clickableAlpha(ripple = true, enabled = isVisible) { onSendClick() }
                            .testTag("Send")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = stringResource(R.string.wallet__send),
                                modifier = Modifier.size(iconSize)
                            )
                            Spacer(Modifier.width(iconToTextGap))
                            BodySSB(text = stringResource(R.string.wallet__send))
                        }
                    }

                    // Receive Button
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(TAB_BAR_HEIGHT.dp)
                            .clip(buttonRightShape)
                            .clickableAlpha(ripple = true, enabled = isVisible) { onReceiveClick() }
                            .testTag("Receive")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = stringResource(R.string.wallet__receive),
                                modifier = Modifier.size(iconSize)
                            )
                            Spacer(Modifier.width(iconToTextGap))
                            BodySSB(text = stringResource(R.string.wallet__receive))
                        }
                    }
                }

                // Scan button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(64.dp)
                        .shadow(
                            elevation = 25.dp,
                            shape = CircleShape,
                            ambientColor = Colors.Black25,
                            spotColor = Colors.Black25
                        )
                        .clip(CircleShape)
                        .background(Colors.Gray7)
                        .border(
                            width = 2.dp,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Colors.Gray2.copy(alpha = 0.6f),
                                    Color.Transparent,
                                ),
                            ),
                            shape = CircleShape,
                        )
                        .clickableAlpha(ripple = true, enabled = isVisible) { onScanClick() }
                        .testTag("Scan")
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_scan),
                        contentDescription = stringResource(R.string.wallet__recipient_scan),
                        tint = Colors.Gray1,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        val hazeState = rememberHazeState()
        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier
                .fillMaxSize()
                .background(Colors.Black)
                .gradientBackground()
        ) {
            // Content Behind
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .hazeSource(hazeState, zIndex = 0f)
            ) {
                BodyMB("Some text behind the footer bar to simulate content.")
                BodyM("Additional random text for a second line of content.")
            }
            TabBar(
                onSendClick = {},
                onReceiveClick = {},
                onScanClick = {},
            )
        }
    }
}

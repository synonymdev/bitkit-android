package to.bitkit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
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

private val buttonLeftShape = RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
private val buttonRightShape = RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun BoxScope.TabBar(
    modifier: Modifier = Modifier,
    onSendClick: () -> Unit = {},
    onReceiveClick: () -> Unit = {},
    onScanClick: () -> Unit = {},
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
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
                    .height(60.dp)
                    .clip(buttonLeftShape)
                    .clickable { onSendClick() }
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
                    .height(60.dp)
                    .clip(buttonRightShape)
                    .clickable { onReceiveClick() }
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
                // Shadow 1: gray2 shadow with radius 0 at y=-1 (top highlight)
                .drawWithContent {
                    // Draw a prominent top highlight
                    drawCircle(
                        color = Colors.Gray2,
                        radius = size.width / 2,
                        center = Offset(size.width / 2, size.height / 2 - 1.5.dp.toPx()),
                        alpha = 0.6f
                    )
                    drawContent()
                }
                // Shadow 2: black 25% opacity, radius 25, y offset 20
                .shadow(
                    elevation = 25.dp,
                    shape = CircleShape,
                    ambientColor = Colors.Black25,
                    spotColor = Colors.Black25
                )
                .clip(CircleShape)
                .background(Colors.Gray7)
                // Overlay: Circle strokeBorder with linear gradient mask (iOS: .mask)
                .drawWithContent {
                    drawContent()

                    // The mask gradient goes from black (visible) at top to clear (invisible) at bottom
                    val borderWidth = 2.dp.toPx()

                    // Create vertical gradient mask (black to clear)
                    val maskGradient = Brush.verticalGradient(
                        colors = listOf(
                            Color.White, // Top: full opacity (shows border)
                            Color.Transparent // Bottom: transparent (hides border)
                        ),
                        startY = 0f,
                        endY = size.height
                    )

                    // Draw solid black circular border first, then apply gradient as alpha mask
                    drawCircle(
                        color = Color.Black,
                        radius = (size.width - borderWidth) / 2,
                        center = Offset(size.width / 2, size.height / 2),
                        style = Stroke(width = borderWidth),
                        alpha = 1f
                    )

                    // Apply gradient mask by drawing gradient as overlay with BlendMode
                    drawCircle(
                        brush = maskGradient,
                        radius = (size.width - borderWidth) / 2,
                        center = Offset(size.width / 2, size.height / 2),
                        style = Stroke(width = borderWidth),
                        blendMode = BlendMode.DstIn
                    )
                }
                .clickableAlpha { onScanClick() }
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

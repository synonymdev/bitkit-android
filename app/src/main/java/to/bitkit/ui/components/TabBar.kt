package to.bitkit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.CupertinoMaterials
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.rememberHazeState
import to.bitkit.R
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

private val buttonBg = Color(40, 40, 40).copy(alpha = 0.95f)
private val buttonBgOverlay = Brush.verticalGradient(colors = listOf(buttonBg, Color.Transparent))

private val iconToTextGap = 4.dp
private val iconSize = 20.dp

private val buttonLeftShape = RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
private val buttonRightShape = RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun TabBar(
    onSendClick: () -> Unit,
    onReceiveClick: () -> Unit,
    onScanClick: () -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color.Transparent,
                        0.5f to Colors.Black,
                    ),
                )
            )
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
            .navigationBarsPadding()
    ) {
        Row {
            val buttonMaterial = CupertinoMaterials.ultraThin(containerColor = buttonBg)
            // Send Button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(60.dp)
                    .clip(buttonLeftShape)
                    .background(buttonBg) // fallback if no haze
                    .hazeEffect(
                        state = hazeState,
                        style = buttonMaterial,
                    )
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(buttonBg, Color.Transparent),
                        )
                    )
                    .clickable { onSendClick() }
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
                    .background(buttonBg) // fallback if no haze
                    .hazeEffect(
                        state = hazeState,
                        style = buttonMaterial,
                    )
                    .background(buttonBgOverlay)
                    .clickable { onReceiveClick() }
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

        // Scan Button
        val scanBg = Colors.Gray6.copy(alpha = 0.75f)
        // Outer Border
        Box(
            content = {},
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(buttonBg) // fallback if no haze
                .hazeEffect(
                    state = hazeState,
                    style = CupertinoMaterials.regular(containerColor = buttonBg),
                )
                .background(
                    Brush.verticalGradient(colors = listOf(Colors.White10, Color.Transparent))
                )
                .clickable { onScanClick() }
                .padding(2.dp)
        )
        // Inner Content
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(buttonBg) // fallback if no haze
                .hazeEffect(
                    state = hazeState,
                    style = CupertinoMaterials.regular(containerColor = Color.Transparent),
                )
                .background(scanBg)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_scan),
                contentDescription = stringResource(R.string.wallet__recipient_scan),
                tint = Colors.Gray2,
                modifier = Modifier.size(32.dp)
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
                .background(Colors.Brand)
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
                hazeState = hazeState,
            )
        }
    }
}

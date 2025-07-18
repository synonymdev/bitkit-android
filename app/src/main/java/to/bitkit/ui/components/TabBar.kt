package to.bitkit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import to.bitkit.R
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

private val buttonBg = Color(40, 40, 40).copy(alpha = 0.95f)
private val buttonBgOverlay = buttonBg.copy(alpha = 0.5f)
private val iconToTextGap = 4.dp
private val iconSize = 20.dp
private val ButtonLeftShape = RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
private val ButtonRightShape = RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)

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
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
    ) {
        Row {
            val buttonMaterial = CupertinoMaterials.ultraThin(containerColor = buttonBg)
            // Send Button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(60.dp)
                    .clip(ButtonLeftShape)
                    .background(buttonBg) // fallback if no haze
                    .hazeSource(hazeState, zIndex = 1f)
                    .hazeEffect(
                        state = hazeState,
                        style = buttonMaterial,
                    )
                    .background(buttonBgOverlay)
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
                    .clip(ButtonRightShape)
                    .background(buttonBg) // fallback if no haze
                    .hazeSource(hazeState, zIndex = 1f)
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
        val scanBg = Colors.Gray6
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .hazeSource(hazeState, zIndex = 2f)
                .background(buttonBg) // fallback if no haze
                .hazeEffect(
                    state = hazeState,
                    style = CupertinoMaterials.regular(containerColor = buttonBg),
                )
                .background(buttonBgOverlay)
                .clickable { onScanClick() }
                .padding(2.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(scanBg) // fallback if no haze
                    .hazeSource(hazeState, zIndex = 2f)
                    .hazeEffect(
                        state = hazeState,
                        style = CupertinoMaterials.thick(containerColor = scanBg),
                    )
                    .background(Colors.Black.copy(alpha = 0.4f))
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
}

@Preview()
@Composable
private fun TabBarPreview() {
    AppThemeSurface {
        TabBar(
            onSendClick = {},
            onReceiveClick = {},
            onScanClick = {},
            hazeState = remember { HazeState() },
        )
    }
}

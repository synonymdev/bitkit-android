package to.bitkit.ui.sheets.hardware

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

private val ANIMATION_SIZE = 280.dp
private const val ARROWS_SIZE_RATIO = 0.82f

// Mirrors the Figma "Loading Animation" HW variants: four 90° keyframes, each a 1s linear
// Smart Animate step, so the arrows complete a counter-clockwise turn every 4 seconds.
private const val ARROWS_ROTATION_DURATION_MS = 4000

@Composable
fun HwSearchingSheet(
    modifier: Modifier = Modifier,
    onCancel: () -> Unit = {},
) {
    Content(
        onCancel = onCancel,
        modifier = modifier
    )
}

@Composable
private fun Content(
    modifier: Modifier = Modifier,
    onCancel: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("hw_searching_screen")
    ) {
        SheetTopBar(titleText = stringResource(R.string.hardware__connect_title))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        ) {
            Display(stringResource(R.string.hardware__connect_header).withAccent(accentColor = Colors.Blue))
            VerticalSpacer(8.dp)
            BodyM(stringResource(R.string.hardware__connect_text), color = Colors.White64)
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            SearchingAnimation()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        ) {
            SecondaryButton(
                text = stringResource(R.string.common__cancel),
                onClick = onCancel,
            )
            VerticalSpacer(16.dp)
        }
    }
}

@Composable
private fun SearchingAnimation(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "hw_searching")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = -360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = ARROWS_ROTATION_DURATION_MS, easing = LinearEasing)),
        label = "arrows_rotation",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(ANIMATION_SIZE)
    ) {
        Image(
            painter = painterResource(R.drawable.hw_searching_ring),
            contentDescription = null,
            modifier = Modifier.fillMaxSize()
        )
        Image(
            painter = painterResource(R.drawable.hw_searching_arrows),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize(ARROWS_SIZE_RATIO)
                .rotate(rotation)
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            Content()
        }
    }
}

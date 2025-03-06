package to.bitkit.ui.onboarding

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.Footnote
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingSlidesScreen(
    currentTab: Int = 0,
    onAdvancedSetupClick: () -> Unit,
    onCreateClick: () -> Unit,
    onRestoreClick: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = currentTab, pageCount = { 5 })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 32.dp,
            contentPadding = PaddingValues(horizontal = 32.dp),
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> OnboardingTab(
                    imageResId = R.drawable.keyring,
                    title = stringResource(R.string.onboarding__slide0_header),
                    titleAccentColor = Colors.Blue,
                    text = stringResource(R.string.onboarding__slide0_text),
                )

                1 -> OnboardingTab(
                    imageResId = R.drawable.lightning,
                    title = stringResource(R.string.onboarding__slide1_header),
                    titleAccentColor = Colors.Purple,
                    text = stringResource(R.string.onboarding__slide1_text),
                    disclaimerText = stringResource(R.string.onboarding__slide1_note), // TODO use GeoBlocking state
                )

                2 -> OnboardingTab(
                    imageResId = R.drawable.spark,
                    title = stringResource(R.string.onboarding__slide2_header),
                    titleAccentColor = Colors.Yellow,
                    text = stringResource(R.string.onboarding__slide2_text),
                )

                3 -> OnboardingTab(
                    imageResId = R.drawable.shield,
                    title = stringResource(R.string.onboarding__slide3_header),
                    titleAccentColor = Colors.Green,
                    text = stringResource(R.string.onboarding__slide3_text),
                )

                4 -> CreateWalletScreen(
                    onCreateClick = onCreateClick,
                    onRestoreClick = onRestoreClick,
                )
            }
        }

        // Dots indicator
        val isIndicatorVisible = pagerState.currentPage != 4
        val yOffset by animateDpAsState(
            targetValue = if (isIndicatorVisible) 0.dp else 20.dp,
            animationSpec = tween(durationMillis = 300),
            label = "yOffset",
        )
        val alpha by animateFloatAsState(
            targetValue = if (isIndicatorVisible) 1f else 0f,
            animationSpec = tween(durationMillis = 300),
            label = "alpha",
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
                .height(16.dp)
                .offset { IntOffset(0, yOffset.roundToPx()) }
                .alpha(alpha)
        ) {
            repeat(5) { index ->
                val size by animateDpAsState(
                    targetValue = if (index == pagerState.currentPage) 10.dp else 7.dp,
                    animationSpec = tween(durationMillis = 300),
                    label = "dotSize"
                )
                Box(
                    modifier = Modifier
                        .size(size)
                        .background(
                            color = if (pagerState.currentPage == index) Colors.White else Colors.White32,
                            shape = CircleShape,
                        )
                )
            }
        }
    }

    // Toolbar (Skip and Advanced Setup buttons)
    TopAppBar(
        title = { },
        actions = {
            if (pagerState.currentPage == 4) {
                TextButton(onClick = onAdvancedSetupClick) {
                    Text(
                        text = stringResource(R.string.onboarding__advanced_setup),
                        fontSize = 17.sp,
                        color = Colors.White64,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                TextButton(onClick = {
                    scope.launch { pagerState.animateScrollToPage(4) }
                }) {
                    Text(
                        text = stringResource(R.string.onboarding__skip),
                        fontSize = 17.sp,
                        color = Colors.White64,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    )
}

@Composable
fun OnboardingTab(
    imageResId: Int,
    title: String,
    titleAccentColor: Color,
    text: String,
    disclaimerText: String? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier.fillMaxSize()
    ) {
        Image(
            painter = painterResource(id = imageResId),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .padding(top = 170.dp)
                .fillMaxWidth()
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(.325f)
                .align(Alignment.BottomCenter),
        ) {
            Display(text = title.withAccent(accentColor = titleAccentColor))
            Spacer(modifier = Modifier.height(8.dp))
            BodyM(
                text = text,
                color = Colors.White64,
            )
            disclaimerText?.let {
                Spacer(modifier = Modifier.height(6.5.dp))
                Footnote(text = it)
            }
        }
    }
}

@Preview(showSystemUi = false)
@Composable
private fun OnboardingViewPreview() {
    AppThemeSurface {
        OnboardingSlidesScreen(
            currentTab = 0,
            onAdvancedSetupClick = {},
            onCreateClick = {},
            onRestoreClick = {},
        )
    }
}

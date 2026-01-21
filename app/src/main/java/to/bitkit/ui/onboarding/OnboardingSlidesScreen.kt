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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.Footnote
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.shared.util.screen
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

private const val LAST_PAGE_INDEX = 3
private const val PAGE_COUNT = LAST_PAGE_INDEX + 1

@Composable
fun OnboardingSlidesScreen(
    currentTab: Int = 0,
    isGeoBlocked: Boolean,
    onAdvancedSetupClick: () -> Unit,
    onCreateClick: () -> Unit,
    onRestoreClick: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = currentTab, pageCount = { PAGE_COUNT })

    Box(
        modifier = Modifier
            .screen()
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
                    modifier = Modifier.testTag("Slide0")
                )

                1 -> OnboardingTab(
                    imageResId = R.drawable.lightning,
                    title = stringResource(R.string.onboarding__slide1_header),
                    titleAccentColor = Colors.Purple,
                    text = stringResource(R.string.onboarding__slide1_text),
                    disclaimerText = stringResource(R.string.onboarding__slide1_note).takeIf { isGeoBlocked },
                    modifier = Modifier.testTag("Slide1")
                )

                2 -> OnboardingTab(
                    imageResId = R.drawable.shield,
                    title = stringResource(R.string.onboarding__slide3_header),
                    titleAccentColor = Colors.Green,
                    text = stringResource(R.string.onboarding__slide3_text),
                    modifier = Modifier.testTag("Slide2")
                )

                LAST_PAGE_INDEX -> CreateWalletScreen(
                    onCreateClick = onCreateClick,
                    onRestoreClick = onRestoreClick,
                    modifier = Modifier.testTag("Slide$LAST_PAGE_INDEX")
                )
            }
        }

        // Dots indicator
        val isIndicatorVisible = pagerState.currentPage != LAST_PAGE_INDEX
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
            repeat(PAGE_COUNT) { index ->
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

    AppTopBar(
        onBackClick = null,
        titleText = null,
        actions = {
            if (pagerState.currentPage == LAST_PAGE_INDEX) {
                SecondaryButton(
                    text = stringResource(R.string.onboarding__advanced_setup),
                    onClick = onAdvancedSetupClick,
                    size = ButtonSize.Small,
                    fullWidth = false,
                    modifier = Modifier
                        .testTag("Passphrase")
                        .padding(horizontal = 16.dp)
                )
            } else {
                SecondaryButton(
                    text = stringResource(R.string.onboarding__skip),
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.pageCount - 1) }
                    },
                    size = ButtonSize.Small,
                    fullWidth = false,
                    modifier = Modifier
                        .testTag("SkipButton")
                        .padding(horizontal = 16.dp)
                )
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
    modifier: Modifier = Modifier,
    disclaimerText: String? = null,
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
                .padding(top = 125.dp)
                .fillMaxWidth()
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
        ) {
            Display(text = title.withAccent(accentColor = titleAccentColor))
            Spacer(modifier = Modifier.height(8.dp))
            BodyM(
                text = text,
                color = Colors.White64,
                minLines = 3
            )
            disclaimerText?.let {
                Footnote(text = it)
            }
            VerticalSpacer(70.dp)
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun OnboardingViewPreview() {
    AppThemeSurface {
        OnboardingSlidesScreen(
            currentTab = 0,
            onAdvancedSetupClick = {},
            onCreateClick = {},
            onRestoreClick = {},
            isGeoBlocked = true
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun OnboardingViewPreview2() {
    AppThemeSurface {
        OnboardingSlidesScreen(
            currentTab = 1,
            onAdvancedSetupClick = {},
            onCreateClick = {},
            onRestoreClick = {},
            isGeoBlocked = true
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun OnboardingViewPreview3() {
    AppThemeSurface {
        OnboardingSlidesScreen(
            currentTab = 1,
            onAdvancedSetupClick = {},
            onCreateClick = {},
            onRestoreClick = {},
            isGeoBlocked = false
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun OnboardingViewPreview4() {
    AppThemeSurface {
        OnboardingSlidesScreen(
            currentTab = LAST_PAGE_INDEX,
            onAdvancedSetupClick = {},
            onCreateClick = {},
            onRestoreClick = {},
            isGeoBlocked = false
        )
    }
}

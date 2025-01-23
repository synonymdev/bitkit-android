package to.bitkit.ui.onboarding

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ui.components.Display
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

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
            .padding(horizontal = 32.dp)
            .systemBarsPadding()
    ) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 32.dp,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> OnboardingTab(
                    imageResId = R.drawable.keyring,
                    titleFirstLine = "FREEDOM IN",
                    titleSecondLine = "YOUR POCKET",
                    secondLineColor = Colors.Blue,
                    text = "Bitkit hands you the keys to manage your money. Spend now or save for later. The choice is yours."
                )

                1 -> OnboardingTab(
                    imageResId = R.drawable.lightning,
                    titleFirstLine = "INSTANT",
                    titleSecondLine = "PAYMENTS",
                    secondLineColor = Colors.Purple,
                    text = "Spend bitcoin faster than ever. Enjoy instant and cheap payments with friends, family, and merchants*.",
                    disclaimerText = "*Bitkit does not currently provide Lightning services in your country, but you can still connect to other nodes." // TODO use GeoBlocking state
                )

                2 -> OnboardingTab(
                    imageResId = R.drawable.spark,
                    titleFirstLine = "BITCOINERS,",
                    titleSecondLine = "BORDERLESS",
                    secondLineColor = Colors.Yellow,
                    text = "Take charge of your digital life with portable profiles and payable contacts."
                )

                3 -> OnboardingTab(
                    imageResId = R.drawable.shield,
                    titleFirstLine = "PRIVACY IS",
                    titleSecondLine = "NOT A CRIME",
                    secondLineColor = Colors.Green,
                    text = "Swipe to hide your balance, enjoy more private payments, and protect your wallet by enabling security features."
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
                        text = "Advanced Setup",
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
                        text = "Skip",
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
    titleFirstLine: String,
    titleSecondLine: String,
    secondLineColor: Color,
    text: String,
    disclaimerText: String? = null,
) {
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier
            .fillMaxSize()
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
            Display(text = titleFirstLine)
            Display(
                text = titleSecondLine,
                color = secondLineColor,
                modifier = Modifier.offset(y = (-8).dp)
            )
            Text(
                text = text,
                fontSize = 17.sp,
                lineHeight = 22.sp,
                letterSpacing = 0.4.sp,
                color = Colors.White64,
                modifier = Modifier.fillMaxWidth()
            )
            disclaimerText?.let {
                Text(
                    text = it,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = Colors.White32,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.5.dp)
                )
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

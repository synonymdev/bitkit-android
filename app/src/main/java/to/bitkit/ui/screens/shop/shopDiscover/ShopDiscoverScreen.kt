package to.bitkit.ui.screens.shop.shopDiscover

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.ext.configureForBasicWebContent
import to.bitkit.models.BitrefillCategory
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.PinnedTabsScaffold
import to.bitkit.ui.components.SuggestionCard
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.wallets.activity.components.CustomTabRowWithSpacing
import to.bitkit.ui.screens.wallets.activity.components.TabItem
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.Shapes

private enum class ShopDiscoverTab(@StringRes private val titleRes: Int) : TabItem {
    Shop(R.string.other__shop__discover__tabs__shop),
    Map(R.string.other__shop__discover__tabs__map);

    override val uiText @Composable get() = stringResource(titleRes)
}

@Composable
fun ShopDiscoverScreen(
    onBack: () -> Unit,
    navigateWebView: (String, String) -> Unit, // Page, Title
    modifier: Modifier = Modifier,
) {
    val tabs = remember { ShopDiscoverTab.entries.toImmutableList() }
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()

    ScreenColumn(modifier = modifier) {
        AppTopBar(
            titleText = stringResource(R.string.other__shop__discover__nav_title),
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )

        PinnedTabsScaffold(
            header = {
                CustomTabRowWithSpacing(
                    tabs = tabs,
                    currentTabIndex = pagerState.currentPage,
                    selectedColor = Colors.White,
                    onTabChange = { scope.launch { pagerState.animateScrollToPage(tabs.indexOf(it)) } },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        ) { topPadding ->
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = tabs[pagerState.settledPage] != ShopDiscoverTab.Map,
            ) { page ->
                when (tabs[page]) {
                    ShopDiscoverTab.Shop -> ShopTabContent(
                        navigateWebView = navigateWebView,
                        contentPadding = PaddingValues(top = topPadding, bottom = 42.dp),
                    )

                    ShopDiscoverTab.Map -> MapTabContent(modifier = Modifier.padding(top = topPadding))
                }
            }
        }
    }
}

@Composable
private fun ShopTabContent(
    navigateWebView: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    LazyColumn(
        contentPadding = contentPadding,
        modifier = modifier.padding(horizontal = 16.dp)
    ) {
        item {
            VerticalSpacer(16.dp)

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val title = stringResource(R.string.other__shop__discover__gift_cards__title)
                SuggestionCard(
                    gradientColor = Colors.Green24,
                    title = title,
                    description = stringResource(R.string.other__shop__discover__gift_cards__description),
                    icon = R.drawable.gift,
                    captionColor = Colors.Gray1,
                    disableGlow = true,
                    onClick = {
                        navigateWebView("gift-cards", title)
                    },
                    modifier = Modifier.weight(1f)
                )
                val title2 = stringResource(R.string.other__shop__discover__esims__title)
                SuggestionCard(
                    gradientColor = Colors.Yellow24,
                    title = title2,
                    description = stringResource(R.string.other__shop__discover__esims__description),
                    icon = R.drawable.globe,
                    captionColor = Colors.Gray1,
                    disableGlow = true,
                    onClick = {
                        navigateWebView("esims", title2)
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            VerticalSpacer(16.dp)

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val title = stringResource(R.string.other__shop__discover__refill__title)
                SuggestionCard(
                    gradientColor = Colors.Purple24,
                    title = title,
                    description = stringResource(R.string.other__shop__discover__refill__description),
                    icon = R.drawable.phone,
                    captionColor = Colors.Gray1,
                    disableGlow = true,
                    onClick = {
                        navigateWebView("refill", title)
                    },
                    modifier = Modifier.weight(1f)
                )
                val title2 = stringResource(R.string.other__shop__discover__travel__title)
                SuggestionCard(
                    gradientColor = Colors.Red24,
                    title = title2,
                    description = stringResource(R.string.other__shop__discover__travel__description),
                    icon = R.drawable.rocket_2,
                    disableGlow = true,
                    captionColor = Colors.Gray1,
                    onClick = {
                        navigateWebView("buy/travel", title2)
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            VerticalSpacer(32.dp)

            Text13Up(stringResource(R.string.other__shop__discover__label), color = Colors.White64)

            VerticalSpacer(16.dp)
        }

        items(items = BitrefillCategory.entries.toList(), key = { it.name }) { item ->
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickableAlpha {
                            navigateWebView(item.route, item.title)
                        }
                        .padding(top = 8.5.dp, bottom = 10.5.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(CircleShape)
                            .size(32.dp)
                            .background(Colors.White10)
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            tint = Colors.White64,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    BodyM(
                        text = item.title,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = Colors.White64,
                        modifier = Modifier.size(24.dp)
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MapTabContent(
    modifier: Modifier = Modifier,
) {
    var isLoading by remember { mutableStateOf(true) }

    val webViewClient = remember {
        MapWebViewClient(
            onLoadingStateChanged = { loading -> isLoading = loading }
        )
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            .clip(Shapes.medium)
    ) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )

                    this.webViewClient = webViewClient
                    configureForBasicWebContent()
                    loadUrl(Env.BTC_MAP_URL)
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        ShopDiscoverScreen(onBack = {}, navigateWebView = { _, _ -> })
    }
}

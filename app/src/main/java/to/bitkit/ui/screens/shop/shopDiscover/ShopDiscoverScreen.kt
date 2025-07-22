package to.bitkit.ui.screens.shop.shopDiscover

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.models.BitrefillCategory
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.SuggestionCard
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.configureForBasicWebContent
import to.bitkit.utils.Logger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShopDiscoverScreen(
    onClose: () -> Unit,
    onBack: () -> Unit,
    navigateWebView: (String, String) -> Unit, //Page, Title
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabTitles = listOf(
        stringResource(R.string.other__shop__discover__tabs__shop),
        stringResource(R.string.other__shop__discover__tabs__map),
    )

    ScreenColumn(
        modifier = Modifier.gradientBackground(),
    ) {
        AppTopBar(
            titleText = stringResource(R.string.other__shop__discover__nav_title),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClick = onClose) },
        )

        PrimaryTabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = Color.Transparent,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { Text(title) },
                )
            }
        }

        when (selectedTabIndex) {
            0 -> ShopTabContent(navigateWebView = navigateWebView)
            1 -> MapTabContent()
        }
    }
}

@Composable
private fun ShopTabContent(
    navigateWebView: (String, String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        item {
            VerticalSpacer(16.dp)

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val title = stringResource(R.string.other__shop__discover__gift_cards__title)
                SuggestionCard(
                    modifier = Modifier.weight(1f),
                    gradientColor = Colors.Green,
                    title = title,
                    description = stringResource(R.string.other__shop__discover__gift_cards__description),
                    icon = R.drawable.gift,
                    captionColor = Colors.Gray1,
                    size = 164,
                    onClick = {
                        navigateWebView("gift-cards", title)
                    },
                )
                val title2 = stringResource(R.string.other__shop__discover__esims__title)
                SuggestionCard(
                    modifier = Modifier.weight(1f),
                    gradientColor = Colors.Yellow,
                    title = title2,
                    description = stringResource(R.string.other__shop__discover__esims__description),
                    icon = R.drawable.globe,
                    captionColor = Colors.Gray1,
                    size = 164,
                    onClick = {
                        navigateWebView("esims", title2)
                    },
                )
            }

            VerticalSpacer(16.dp)

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val title = stringResource(R.string.other__shop__discover__refill__title)
                SuggestionCard(
                    modifier = Modifier.weight(1f),
                    gradientColor = Colors.Purple,
                    title = title,
                    description = stringResource(R.string.other__shop__discover__refill__description),
                    icon = R.drawable.phone,
                    captionColor = Colors.Gray1,
                    size = 164,
                    onClick = {
                        navigateWebView("refill", title)
                    },
                )
                val title2 = stringResource(R.string.other__shop__discover__travel__title)
                SuggestionCard(
                    modifier = Modifier.weight(1f),
                    gradientColor = Colors.Red,
                    title = title2,
                    description = stringResource(R.string.other__shop__discover__travel__description),
                    icon = R.drawable.rocket_2,
                    size = 164,
                    captionColor = Colors.Gray1,
                    onClick = {
                        navigateWebView("buy/travel", title2)
                    },
                )
            }

            VerticalSpacer(32.dp)

            Text13Up(stringResource(R.string.other__shop__discover__label), color = Colors.White64)

            VerticalSpacer(16.dp)
        }

        items(items = BitrefillCategory.entries.toList(), key = { it.name }) { item ->
            Column {
                Row(
                    modifier = Modifier
                        .clickableAlpha {
                            navigateWebView(item.route, item.title)
                        }
                        .padding(top = 8.5.dp, bottom = 10.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .size(32.dp)
                            .background(Colors.White10),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            tint = Colors.White64,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    BodyM(
                        text = item.title,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = Colors.White64,
                        modifier = Modifier.size(24.dp),
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MapTabContent() {
    var isLoading by remember { mutableStateOf(true) }

    val webViewClient = remember {
        MapWebViewClient(
            onLoadingStateChanged = { loading -> isLoading = loading }
        )
    }

    Box(
        modifier = Modifier
            .padding(16.dp)
            .clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
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
        ShopDiscoverScreen(onClose = {}, onBack = {}, navigateWebView = { _, _ -> })
    }
}

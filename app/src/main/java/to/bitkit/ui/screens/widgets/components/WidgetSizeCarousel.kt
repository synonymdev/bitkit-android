package to.bitkit.ui.screens.widgets.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.theme.Colors

private const val PAGE_SMALL = 0
private const val PAGE_WIDE = 1
private const val PAGE_COUNT = 2

@Composable
fun WidgetSizeCarousel(
    smallContent: @Composable () -> Unit,
    wideContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })

    Column(
        verticalArrangement = Arrangement.Center,
        modifier = modifier.testTag("widget_size_carousel")
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("widget_size_pager")
        ) { page ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                when (page) {
                    PAGE_SMALL -> smallContent()
                    PAGE_WIDE -> wideContent()
                }
            }
        }

        VerticalSpacer(16.dp)

        Caption13Up(
            text = stringResource(
                if (pagerState.currentPage == PAGE_SMALL) {
                    R.string.widgets__widget__size_small
                } else {
                    R.string.widgets__widget__size_wide
                },
            ),
            color = Colors.White64,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("widget_size_label")
        )

        VerticalSpacer(16.dp)

        Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("page_indicator")
        ) {
            repeat(PAGE_COUNT) { index ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(8.dp)
                        .background(
                            color = if (pagerState.currentPage == index) Colors.White else Colors.White32,
                            shape = CircleShape,
                        )
                )
            }
        }
    }
}

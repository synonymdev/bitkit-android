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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import to.bitkit.R
import to.bitkit.models.WidgetSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.theme.Colors

@Composable
fun WidgetSizeCarousel(
    smallContent: @Composable () -> Unit,
    wideContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportsSmall: Boolean = true,
    initialSize: WidgetSize = WidgetSize.WIDE,
    onSizeSelected: (WidgetSize) -> Unit = {},
) {
    val pageCount = if (supportsSmall) 2 else 1
    val pagerState = rememberPagerState(
        initialPage = initialPageFor(initialSize, supportsSmall),
        pageCount = { pageCount },
    )
    val currentOnSizeSelected by rememberUpdatedState(onSizeSelected)

    // The persisted size can resolve asynchronously after first composition (DataStore read), so
    // realign the pager once it arrives instead of leaving it on the placeholder default page.
    // Guarded so a user swipe (which flows back in via [initialSize]) never fights the gesture.
    LaunchedEffect(initialSize, supportsSmall) {
        val targetPage = initialPageFor(initialSize, supportsSmall)
        if (pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
    }

    // Report only genuine page changes; dropping the initial emission keeps opening the sheet from
    // overwriting the persisted size with the placeholder default before it has resolved.
    LaunchedEffect(pagerState, supportsSmall) {
        snapshotFlow { pagerState.currentPage }
            .drop(1)
            .collect { page ->
                currentOnSizeSelected(pageToSize(page, supportsSmall))
            }
    }

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
                when (pageToSize(page, supportsSmall)) {
                    WidgetSize.SMALL -> smallContent()
                    WidgetSize.WIDE -> wideContent()
                }
            }
        }

        VerticalSpacer(16.dp)

        Caption13Up(
            text = stringResource(
                when (pageToSize(pagerState.currentPage, supportsSmall)) {
                    WidgetSize.SMALL -> R.string.widgets__widget__size_small
                    WidgetSize.WIDE -> R.string.widgets__widget__size_wide
                },
            ),
            color = Colors.White64,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("widget_size_label")
        )

        VerticalSpacer(16.dp)

        if (pageCount > 1) {
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("page_indicator")
            ) {
                repeat(pageCount) { index ->
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
}

private fun pageToSize(page: Int, supportsSmall: Boolean): WidgetSize {
    if (!supportsSmall) return WidgetSize.WIDE
    return if (page == PAGE_SMALL) WidgetSize.SMALL else WidgetSize.WIDE
}

private fun initialPageFor(size: WidgetSize, supportsSmall: Boolean): Int {
    if (!supportsSmall) return 0
    return if (size == WidgetSize.WIDE) PAGE_WIDE else PAGE_SMALL
}

private const val PAGE_SMALL = 0
private const val PAGE_WIDE = 1

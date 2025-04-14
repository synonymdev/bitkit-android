package to.bitkit.ui.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.accompanist.pager.HorizontalPagerIndicator
import to.bitkit.ui.theme.Colors

@Composable
fun PagerWithIndicator(
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    pageContent: @Composable (PagerScope.(page: Int) -> Unit),
) {
    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        HorizontalPager(
            state = pagerState,
            pageContent = pageContent,
            pageSpacing = 20.dp,
            verticalAlignment = Alignment.Top,
            modifier = Modifier.weight(1f)
        )
        @Suppress("DEPRECATION")
        HorizontalPagerIndicator(
            pagerState = pagerState,
            pageCount = pagerState.pageCount,
            indicatorWidth = 8.dp,
            spacing = 8.dp,
            activeColor = Colors.White,
            inactiveColor = Colors.White32,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
        )
    }
}

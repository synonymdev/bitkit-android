package to.bitkit.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.accompanist.pager.HorizontalPagerIndicator

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
        (HorizontalPagerIndicator(
            pagerState = pagerState,
            pageCount = pagerState.pageCount,
            indicatorWidth = 8.dp,
            spacing = 8.dp,
            modifier = Modifier
                .padding(8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.large)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .align(Alignment.CenterHorizontally)
        ))
    }
}

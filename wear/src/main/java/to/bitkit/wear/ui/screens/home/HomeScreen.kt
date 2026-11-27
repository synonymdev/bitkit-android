package to.bitkit.wear.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.HorizontalPagerScaffold
import kotlinx.collections.immutable.ImmutableList
import to.bitkit.wear.ui.screens.settings.SettingsScreen

/**
 * The app's top level: pages swiped horizontally with a page indicator, opening on the balance. Swiping right on the
 * first page leaves the app; the pager leaves that edge to swipe-to-dismiss.
 */
@Composable
fun HomeScreen(
    onAboutClick: () -> Unit,
    onWalletRemove: () -> Unit,
    onSeedKeyLoss: () -> Unit,
    modifier: Modifier = Modifier,
    pages: ImmutableList<HomePage> = HOME_PAGES,
) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    HorizontalPagerScaffold(pagerState = pagerState, modifier = modifier) {
        HorizontalPager(state = pagerState, modifier = Modifier.testTag("WatchHomePager")) { page ->
            AnimatedPage(pageIndex = page, pagerState = pagerState) {
                when (pages[page]) {
                    HomePage.Balance -> BalancePage(
                        onSeedKeyLoss = onSeedKeyLoss,
                        onWalletMissing = onWalletRemove,
                    )
                    HomePage.Settings -> SettingsScreen(onAboutClick = onAboutClick, onWalletRemove = onWalletRemove)
                }
            }
        }
    }
}

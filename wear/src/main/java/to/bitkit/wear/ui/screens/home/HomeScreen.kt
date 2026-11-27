package to.bitkit.wear.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.HorizontalPagerScaffold
import kotlinx.collections.immutable.ImmutableList
import to.bitkit.wear.ui.screens.settings.SettingsScreen

/**
 * The app's top level: pages swiped horizontally with a page indicator, opening on the balance. Swiping right on the
 * first page leaves the app; the pager leaves that edge to swipe-to-dismiss. Leaving Home because the wallet is gone
 * is decided here, above the pager, from the wallet's state: the pager composes only the page shown, so a page that
 * was swiped away could not do it.
 */
@Composable
fun HomeScreen(
    onAboutClick: () -> Unit,
    onWalletRemove: () -> Unit,
    onSeedKeyLoss: () -> Unit,
    modifier: Modifier = Modifier,
    pages: ImmutableList<HomePage> = HOME_PAGES,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    WalletExitEffect(
        isRemoved = uiState.isRemoved,
        isMissing = uiState.isMissing,
        onSeedKeyLoss = onSeedKeyLoss,
        onWalletRemove = onWalletRemove,
    )
    val pagerState = rememberPagerState(pageCount = { pages.size })
    HorizontalPagerScaffold(pagerState = pagerState, modifier = modifier) {
        HorizontalPager(state = pagerState, modifier = Modifier.testTag("WatchHomePager")) { page ->
            AnimatedPage(pageIndex = page, pagerState = pagerState) {
                when (pages[page]) {
                    HomePage.Balance -> BalancePage(viewModel = viewModel)
                    HomePage.Settings -> SettingsScreen(onAboutClick = onAboutClick)
                }
            }
        }
    }
}

/**
 * Leaves Home when the wallet is gone: [onSeedKeyLoss] when it was deleted because its seed key was lost,
 * [onWalletRemove] when no wallet is stored, as after Remove wallet.
 */
@Composable
internal fun WalletExitEffect(
    isRemoved: Boolean,
    isMissing: Boolean,
    onSeedKeyLoss: () -> Unit,
    onWalletRemove: () -> Unit,
) {
    val currentOnSeedKeyLoss by rememberUpdatedState(onSeedKeyLoss)
    val currentOnWalletRemove by rememberUpdatedState(onWalletRemove)
    LaunchedEffect(isRemoved, isMissing) {
        if (isRemoved) {
            currentOnSeedKeyLoss()
        } else if (isMissing) {
            currentOnWalletRemove()
        }
    }
}

package to.bitkit.ui.screens.wallets.activity

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BottomSheetType
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.screens.wallets.activity.components.ActivityListFilter
import to.bitkit.ui.screens.wallets.activity.components.ActivityListGrouped
import to.bitkit.ui.screens.wallets.activity.components.ActivityTab
import to.bitkit.ui.screens.wallets.activity.utils.previewActivityItems
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.ActivityListViewModel
import uniffi.bitkitcore.Activity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllActivityScreen(
    viewModel: ActivityListViewModel,
    onBack: () -> Unit,
    onActivityItemClick: (String) -> Unit,
) {
    val app = appViewModel ?: return
    val filteredActivities by viewModel.filteredActivities.collectAsStateWithLifecycle()

    val searchText by viewModel.searchText.collectAsStateWithLifecycle()
    val selectedTags by viewModel.selectedTags.collectAsStateWithLifecycle()
    val startDate by viewModel.startDate.collectAsStateWithLifecycle()

    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val tabs = ActivityTab.entries
    val currentTabIndex = tabs.indexOf(selectedTab)

    BackHandler { onBack() }

    AllActivityScreenContent(
        filteredActivities = filteredActivities,
        searchText = searchText,
        onSearchTextChange = { viewModel.setSearchText(it) },
        hasTagFilter = selectedTags.isNotEmpty(),
        hasDateRangeFilter = startDate != null,
        tabs = tabs,
        currentTabIndex = currentTabIndex,
        onTabChange = { viewModel.setTab(tabs[it]) },
        onBackClick = onBack,
        onTagClick = { app.showSheet(BottomSheetType.ActivityTagSelector) },
        onDateRangeClick = { app.showSheet(BottomSheetType.ActivityDateRangeSelector) },
        onActivityItemClick = onActivityItemClick,
        onEmptyActivityRowClick = { app.showSheet(BottomSheetType.Receive) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllActivityScreenContent(
    filteredActivities: List<Activity>?,
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    hasTagFilter: Boolean,
    hasDateRangeFilter: Boolean,
    tabs: List<ActivityTab>,
    currentTabIndex: Int,
    onTabChange: (Int) -> Unit,
    onBackClick: () -> Unit,
    onTagClick: () -> Unit,
    onDateRangeClick: () -> Unit,
    onActivityItemClick: (String) -> Unit,
    onEmptyActivityRowClick: () -> Unit,
) {
    Column(
        modifier = Modifier.background(Colors.Black)
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                .background(
                    Brush.horizontalGradient(listOf(Color(0xFF1e1e1e), Color(0xFF161616)))
                )
        ) {
            AppTopBar(stringResource(R.string.wallet__activity_all), onBackClick)
            Column(
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                ActivityListFilter(
                    searchText = searchText,
                    onSearchTextChange = onSearchTextChange,
                    hasTagFilter = hasTagFilter,
                    hasDateRangeFilter = hasDateRangeFilter,
                    onTagClick = onTagClick,
                    onDateRangeClick = onDateRangeClick,
                    tabs = tabs,
                    currentTabIndex = currentTabIndex,
                    onTabChange = { onTabChange(tabs.indexOf(it)) },
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
        ActivityListGrouped(
            items = filteredActivities,
            onActivityItemClick = onActivityItemClick,
            onEmptyActivityRowClick = onEmptyActivityRowClick,
            modifier = Modifier
                .swipeToChangeTab(
                    currentTabIndex = currentTabIndex,
                    tabCount = tabs.size,
                    onTabChange = onTabChange,
                )
                .padding(horizontal = 16.dp)
        )
    }
}

private fun Modifier.swipeToChangeTab(currentTabIndex: Int, tabCount: Int, onTabChange: (Int) -> Unit) = composed {
    val threshold = remember { 1500f }
    val velocityTracker = remember { VelocityTracker() }

    pointerInput(currentTabIndex) {
        detectHorizontalDragGestures(
            onHorizontalDrag = { change, _ ->
                velocityTracker.addPosition(change.uptimeMillis, change.position)
            },
            onDragEnd = {
                val velocity = velocityTracker.calculateVelocity().x
                when {
                    velocity >= threshold && currentTabIndex > 0 -> onTabChange(currentTabIndex - 1)
                    velocity <= -threshold && currentTabIndex < tabCount - 1 -> onTabChange(currentTabIndex + 1)
                }
                velocityTracker.resetTracking()
            },
            onDragCancel = {
                velocityTracker.resetTracking()
            },
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        AllActivityScreenContent(
            filteredActivities = previewActivityItems,
            searchText = "",
            onSearchTextChange = {},
            hasTagFilter = false,
            hasDateRangeFilter = false,
            tabs = ActivityTab.entries,
            currentTabIndex = 0,
            onTabChange = {},
            onBackClick = {},
            onTagClick = {},
            onDateRangeClick = {},
            onActivityItemClick = {},
            onEmptyActivityRowClick = {},
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewEmpty() {
    AppThemeSurface {
        AllActivityScreenContent(
            filteredActivities = emptyList(),
            searchText = "",
            onSearchTextChange = {},
            hasTagFilter = false,
            hasDateRangeFilter = false,
            tabs = ActivityTab.entries,
            currentTabIndex = 0,
            onTabChange = {},
            onBackClick = {},
            onTagClick = {},
            onDateRangeClick = {},
            onActivityItemClick = {},
            onEmptyActivityRowClick = {},
        )
    }
}

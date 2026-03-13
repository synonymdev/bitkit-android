package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synonym.bitkitcore.Activity
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import to.bitkit.R
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.screens.wallets.activity.components.ActivityListFilter
import to.bitkit.ui.screens.wallets.activity.components.ActivityListGrouped
import to.bitkit.ui.screens.wallets.activity.components.ActivityTab
import to.bitkit.ui.screens.wallets.activity.utils.previewActivityItems
import to.bitkit.ui.shared.modifiers.swipeToChangeTab
import to.bitkit.ui.shared.util.screen
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.ActivityListViewModel

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

    DisposableEffect(Unit) {
        onDispose { viewModel.clearFilters() }
    }

    AllActivityScreenContent(
        filteredActivities = filteredActivities,
        searchText = searchText,
        onSearchTextChange = { viewModel.setSearchText(it) },
        hasTagFilter = selectedTags.isNotEmpty(),
        selectedTags = selectedTags,
        hasDateRangeFilter = startDate != null,
        tabs = tabs,
        currentTabIndex = currentTabIndex,
        onRemoveTag = { viewModel.toggleTag(it) },
        onTabChange = { viewModel.setTab(tabs[it]) },
        onBackClick = onBack,
        onTagClick = { app.showSheet(Sheet.ActivityTagSelector) },
        onDateRangeClick = { app.showSheet(Sheet.ActivityDateRangeSelector) },
        onActivityItemClick = onActivityItemClick,
        onEmptyActivityRowClick = { app.showSheet(Sheet.Receive) },
    )
}

@Composable
@OptIn(ExperimentalHazeMaterialsApi::class)
private fun AllActivityScreenContent(
    filteredActivities: List<Activity>?,
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    hasTagFilter: Boolean,
    selectedTags: Set<String>,
    hasDateRangeFilter: Boolean,
    tabs: List<ActivityTab>,
    currentTabIndex: Int,
    onRemoveTag: (String) -> Unit,
    onTabChange: (Int) -> Unit,
    onBackClick: () -> Unit,
    onTagClick: () -> Unit,
    onDateRangeClick: () -> Unit,
    onActivityItemClick: (String) -> Unit,
    onEmptyActivityRowClick: () -> Unit,
) {
    Column(
        modifier = Modifier.screen()
    ) {
        AppTopBar(
            titleText = stringResource(R.string.wallet__activity_all),
            onBackClick = onBackClick,
            actions = {
                DrawerNavIcon()
            },
        )

        ActivityListFilter(
            searchText = searchText,
            onSearchTextChange = onSearchTextChange,
            hasTagFilter = hasTagFilter,
            hasDateRangeFilter = hasDateRangeFilter,
            onTagClick = onTagClick,
            selectedTags = selectedTags,
            onRemoveTag = onRemoveTag,
            onDateRangeClick = onDateRangeClick,
            tabs = tabs,
            currentTabIndex = currentTabIndex,
            onTabChange = { onTabChange(tabs.indexOf(it)) },
            modifier = Modifier.padding(horizontal = 16.dp)

        )
        Spacer(modifier = Modifier.height(16.dp))

        // List
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            ActivityListGrouped(
                items = filteredActivities,
                onActivityItemClick = onActivityItemClick,
                onEmptyActivityRowClick = onEmptyActivityRowClick,
                contentPadding = PaddingValues(top = 0.dp),
                modifier = Modifier
                    .swipeToChangeTab(
                        currentTabIndex = currentTabIndex,
                        tabCount = tabs.size,
                        onTabChange = onTabChange,
                    )
                    .padding(horizontal = 16.dp)
                    .testTag("ActivityList")
            )
        }
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
            selectedTags = setOf(),
            hasDateRangeFilter = false,
            tabs = ActivityTab.entries,
            currentTabIndex = 0,
            onTabChange = {},
            onBackClick = {},
            onTagClick = {},
            onDateRangeClick = {},
            onActivityItemClick = {},
            onRemoveTag = {},
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
            selectedTags = setOf("tag1", "tag2"),
            hasDateRangeFilter = false,
            tabs = ActivityTab.entries,
            currentTabIndex = 0,
            onTabChange = {},
            onBackClick = {},
            onTagClick = {},
            onDateRangeClick = {},
            onRemoveTag = {},
            onActivityItemClick = {},
            onEmptyActivityRowClick = {},
        )
    }
}

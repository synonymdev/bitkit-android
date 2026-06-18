package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synonym.bitkitcore.Activity
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import to.bitkit.R
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.PinnedTabsScaffold
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
    val hardwareIds by viewModel.hardwareIds.collectAsStateWithLifecycle()

    val searchText by viewModel.searchText.collectAsStateWithLifecycle()
    val selectedTags by viewModel.selectedTags.collectAsStateWithLifecycle()
    val startDate by viewModel.startDate.collectAsStateWithLifecycle()

    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val tabs = activityTabs
    val currentTabIndex = tabs.indexOf(selectedTab)

    AllActivityScreenContent(
        filteredActivities = filteredActivities,
        hardwareIds = hardwareIds,
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
        onEmptyActivityRowClick = { app.showSheet(Sheet.Receive()) },
    )
}

@Composable
private fun AllActivityScreenContent(
    filteredActivities: ImmutableList<Activity>?,
    searchText: String,
    hardwareIds: ImmutableSet<String> = persistentSetOf(),
    onSearchTextChange: (String) -> Unit,
    hasTagFilter: Boolean,
    selectedTags: ImmutableSet<String>,
    hasDateRangeFilter: Boolean,
    tabs: ImmutableList<ActivityTab>,
    currentTabIndex: Int,
    onRemoveTag: (String) -> Unit,
    onTabChange: (Int) -> Unit,
    onBackClick: () -> Unit,
    onTagClick: () -> Unit,
    onDateRangeClick: () -> Unit,
    onActivityItemClick: (String) -> Unit,
    onEmptyActivityRowClick: () -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(currentTabIndex) {
        listState.scrollToItem(0)
    }

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

        PinnedTabsScaffold(
            header = {
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
            }
        ) { topPadding ->
            ActivityListGrouped(
                items = filteredActivities,
                onActivityItemClick = onActivityItemClick,
                onEmptyActivityRowClick = onEmptyActivityRowClick,
                hardwareIds = hardwareIds,
                listState = listState,
                contentPadding = PaddingValues(top = topPadding + 16.dp),
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

private val activityTabs = ActivityTab.entries.toImmutableList()

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        AllActivityScreenContent(
            filteredActivities = previewActivityItems,
            searchText = "",
            onSearchTextChange = {},
            hasTagFilter = false,
            selectedTags = persistentSetOf(),
            hasDateRangeFilter = false,
            tabs = activityTabs,
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
            filteredActivities = persistentListOf(),
            searchText = "",
            onSearchTextChange = {},
            hasTagFilter = false,
            selectedTags = persistentSetOf("tag1", "tag2"),
            hasDateRangeFilter = false,
            tabs = activityTabs,
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

package to.bitkit.ui.screens.wallets.activity

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synonym.bitkitcore.Activity
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.CupertinoMaterials
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.rememberHazeState
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

@Composable
@OptIn(ExperimentalHazeMaterialsApi::class)
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
    hazeState: HazeState = rememberHazeState(),
) {
    val density = LocalDensity.current
    var headerHeight by remember { mutableStateOf(120.dp) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Colors.Black)
    ) {
        // Header
        val (gradientStart, gradientEnd) = Color(0xFF1e1e1e) to Color(0xFF161616)
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                .background(Brush.horizontalGradient(listOf(gradientStart, gradientEnd)))
                .hazeEffect(
                    state = hazeState,
                    style = CupertinoMaterials.ultraThin(containerColor = gradientEnd)
                )
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(0f to gradientEnd, 0.5f to Color.Transparent)
                    )
                )
                .background(Brush.horizontalGradient(listOf(Colors.White06, Color.Transparent)))
                .onGloballyPositioned { coords -> headerHeight = with(density) { coords.size.height.toDp() } }
                .zIndex(1f)
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
        // List
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState)
                .zIndex(0f)
        ) {
            ActivityListGrouped(
                items = filteredActivities,
                onActivityItemClick = onActivityItemClick,
                onEmptyActivityRowClick = onEmptyActivityRowClick,
                contentPadding = PaddingValues(top = headerHeight + 20.dp),
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
            hazeState = rememberHazeState(),
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
            hazeState = rememberHazeState(),
            onTabChange = {},
            onBackClick = {},
            onTagClick = {},
            onDateRangeClick = {},
            onActivityItemClick = {},
            onEmptyActivityRowClick = {},
        )
    }
}

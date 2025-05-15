package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BottomSheetType
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.screens.wallets.activity.components.ActivityListFilter
import to.bitkit.viewmodels.ActivityListViewModel
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import to.bitkit.ui.screens.wallets.activity.components.ActivityListGrouped
import to.bitkit.ui.screens.wallets.activity.components.ActivityTab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllActivityScreen(
    viewModel: ActivityListViewModel,
    onBackClick: () -> Unit,
    onActivityItemClick: (String) -> Unit,
) {
    val app = appViewModel ?: return
    val filteredActivities by viewModel.filteredActivities.collectAsState()

    val searchText by viewModel.searchText.collectAsState()
    val selectedTags by viewModel.selectedTags.collectAsState()
    val startDate by viewModel.startDate.collectAsState()

    var selectedTab by remember { mutableStateOf(ActivityTab.ALL) }
    val tabs = ActivityTab.entries
    val currentTabIndex = tabs.indexOf(selectedTab)

    LaunchedEffect(selectedTab) {
        // TODO on tab change: update filtered activities
        println("Selected filter tab: $selectedTab")
    }

    Column {
        // Header with gradient background
        var headerWidth by remember { mutableFloatStateOf(0f) }
        Column(
            modifier = Modifier
                .onSizeChanged { headerWidth = it.width.toFloat() }
                .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF1e1e1e), Color(0xFF161616)),
                        start = Offset(0f, 0f),
                        end = Offset(headerWidth, 0f),
                    ),
                ),
        ) {
            AppTopBar(stringResource(R.string.wallet__activity_all), onBackClick)
            Column(
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                ActivityListFilter(
                    searchText = searchText,
                    onSearchTextChange = { viewModel.setSearchText(it) },
                    hasTagFilter = selectedTags.isNotEmpty(),
                    hasDateRangeFilter = startDate != null,
                    onTagClick = { app.showSheet(BottomSheetType.ActivityTagSelector) },
                    onDateRangeClick = { app.showSheet(BottomSheetType.ActivityDateRangeSelector) },
                    tabs = tabs,
                    currentTabIndex = currentTabIndex,
                    onTabChange = { selectedTab = it },
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
        ActivityListGrouped(
            items = filteredActivities,
            onActivityItemClick = onActivityItemClick,
            onEmptyActivityRowClick = { app.showSheet(BottomSheetType.Receive) },
            modifier = Modifier
                .swipeToChangeTab(
                    currentTabIndex = currentTabIndex,
                    tabCount = tabs.size,
                    onTabChange = { selectedTab = tabs[it] }
                )
                .padding(horizontal = 16.dp)
        )
    }
}

fun Modifier.swipeToChangeTab(
    currentTabIndex: Int,
    tabCount: Int,
    onTabChange: (Int) -> Unit,
) = composed(
    inspectorInfo = {
        name = "swipeToChangeTab"
        value = currentTabIndex
    }
) {
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
                    velocity >= threshold && currentTabIndex > 0 -> {
                        onTabChange(currentTabIndex - 1)
                    }
                    velocity <= -threshold && currentTabIndex < tabCount - 1 -> {
                        onTabChange(currentTabIndex + 1)
                    }
                }
                velocityTracker.resetTracking()
            },
            onDragCancel = {
                velocityTracker.resetTracking()
            },
        )
    }
}

// endregion

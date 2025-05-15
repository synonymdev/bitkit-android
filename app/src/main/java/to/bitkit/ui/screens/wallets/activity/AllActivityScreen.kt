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
import uniffi.bitkitcore.Activity
import uniffi.bitkitcore.LightningActivity
import uniffi.bitkitcore.OnchainActivity
import uniffi.bitkitcore.PaymentState
import uniffi.bitkitcore.PaymentType
import java.util.Calendar
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

val testActivityItems = buildList {
    val today: Calendar = Calendar.getInstance()
    val yesterday: Calendar = Calendar.getInstance().apply { add(Calendar.DATE, -1) }
    val thisWeek: Calendar = Calendar.getInstance().apply { add(Calendar.DATE, -3) }
    val thisMonth: Calendar = Calendar.getInstance().apply { add(Calendar.DATE, -10) }
    val lastYear: Calendar = Calendar.getInstance().apply { add(Calendar.YEAR, -1) }

    fun Calendar.epochSecond() = (timeInMillis / 1000).toULong()

    // Today
    add(
        Activity.Onchain(
            OnchainActivity(
                id = "1",
                txType = PaymentType.RECEIVED,
                txId = "01",
                value = 42_000_000_u,
                fee = 200_u,
                feeRate = 1_u,
                address = "bc1",
                confirmed = true,
                timestamp = today.epochSecond(),
                isBoosted = false,
                isTransfer = true,
                doesExist = true,
                confirmTimestamp = today.epochSecond(),
                channelId = "channelId",
                transferTxId = "transferTxId",
                createdAt = today.epochSecond(),
                updatedAt = today.epochSecond(),
            )
        )
    )

    // Yesterday
    add(
        Activity.Lightning(
            LightningActivity(
                id = "2",
                txType = PaymentType.SENT,
                status = PaymentState.PENDING,
                value = 30_000_u,
                fee = 15_u,
                invoice = "lnbc2",
                message = "Custom message",
                timestamp = yesterday.epochSecond(),
                preimage = "preimage1",
                createdAt = yesterday.epochSecond(),
                updatedAt = yesterday.epochSecond(),
            )
        )
    )

    // This Week
    add(
        Activity.Lightning(
            LightningActivity(
                id = "3",
                txType = PaymentType.RECEIVED,
                status = PaymentState.FAILED,
                value = 217_000_u,
                fee = 17_u,
                invoice = "lnbc3",
                message = "",
                timestamp = thisWeek.epochSecond(),
                preimage = "preimage2",
                createdAt = thisWeek.epochSecond(),
                updatedAt = thisWeek.epochSecond(),
            )
        )
    )

    // This Month
    add(
        Activity.Onchain(
            OnchainActivity(
                id = "4",
                txType = PaymentType.RECEIVED,
                txId = "04",
                value = 950_000_u,
                fee = 110_u,
                feeRate = 1_u,
                address = "bc1",
                confirmed = false,
                timestamp = thisMonth.epochSecond(),
                isBoosted = false,
                isTransfer = true,
                doesExist = true,
                confirmTimestamp = today.epochSecond() + 3600u,
                channelId = "channelId",
                transferTxId = "transferTxId",
                createdAt = thisMonth.epochSecond(),
                updatedAt = thisMonth.epochSecond(),
            )
        )
    )

    // Last Year
    add(
        Activity.Lightning(
            LightningActivity(
                id = "5",
                txType = PaymentType.SENT,
                status = PaymentState.SUCCEEDED,
                value = 200_000_u,
                fee = 1_u,
                invoice = "lnbc…",
                message = "",
                timestamp = lastYear.epochSecond(),
                preimage = null,
                createdAt = lastYear.epochSecond(),
                updatedAt = lastYear.epochSecond(),
            )
        )
    )
}
// endregion

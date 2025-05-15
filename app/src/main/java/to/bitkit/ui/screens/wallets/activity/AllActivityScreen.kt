package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetType
import to.bitkit.ui.components.TertiaryButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.screens.wallets.activity.components.ActivityListFilter
import to.bitkit.ui.screens.wallets.activity.components.ActivityRow
import to.bitkit.ui.screens.wallets.activity.components.EmptyActivityRow
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.ActivityListViewModel
import uniffi.bitkitcore.Activity
import uniffi.bitkitcore.LightningActivity
import uniffi.bitkitcore.OnchainActivity
import uniffi.bitkitcore.PaymentState
import uniffi.bitkitcore.PaymentType
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Calendar
import java.util.Locale
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
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
        ActivityListWithHeaders(
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

@Composable
fun ActivityListWithHeaders(
    items: List<Activity>?,
    modifier: Modifier = Modifier,
    showFooter: Boolean = false,
    onAllActivityButtonClick: () -> Unit = {},
    onActivityItemClick: (String) -> Unit,
    onEmptyActivityRowClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxSize()
    ) {
        if (items != null && items.isNotEmpty()) {
            val groupedItems = groupActivityItems(items)

            LazyColumn(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(groupedItems) { index, item ->
                    when (item) {
                        is String -> {
                            Text(
                                text = item,
                                style = MaterialTheme.typography.titleSmall,
                                color = Colors.White64,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            )
                        }

                        is Activity -> {
                            ActivityRow(item, onActivityItemClick)
                            val hasNextItem = index < groupedItems.size - 1 && groupedItems[index + 1] !is String
                            if (hasNextItem) {
                                HorizontalDivider()
                            }
                        }
                    }
                }
                if (showFooter) {
                    item {
                        TertiaryButton(
                            text = stringResource(R.string.wallet__activity_show_all),
                            onClick = onAllActivityButtonClick,
                            modifier = Modifier
                                .wrapContentWidth()
                                .padding(top = 8.dp)
                        )
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(120.dp))
                }
            }
        } else {
            if (showFooter) {
                // In Spending and Savings wallet
                EmptyActivityRow(onClick = onEmptyActivityRowClick)
            } else {
                // On all activity screen when filtered list is empty
                BodyM(
                    text = stringResource(R.string.wallet__activity_no),
                    color = Colors.White64,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

// region utils
private fun groupActivityItems(activityItems: List<Activity>): List<Any> {
    val now = Instant.now()
    val zoneId = ZoneId.systemDefault()
    val today = now.atZone(zoneId).truncatedTo(ChronoUnit.DAYS)

    val startOfDay = today.toInstant().epochSecond
    val startOfYesterday = today.minusDays(1).toInstant().epochSecond
    val startOfWeek = today.with(TemporalAdjusters.previousOrSame(WeekFields.of(Locale.getDefault()).firstDayOfWeek))
        .toInstant().epochSecond
    val startOfMonth = today.withDayOfMonth(1).toInstant().epochSecond
    val startOfYear = today.withDayOfYear(1).toInstant().epochSecond

    val todayItems = mutableListOf<Activity>()
    val yesterdayItems = mutableListOf<Activity>()
    val weekItems = mutableListOf<Activity>()
    val monthItems = mutableListOf<Activity>()
    val yearItems = mutableListOf<Activity>()
    val earlierItems = mutableListOf<Activity>()

    for (item in activityItems) {
        val timestamp = when (item) {
            is Activity.Lightning -> item.v1.timestamp.toLong()
            is Activity.Onchain -> item.v1.timestamp.toLong()
        }
        when {
            timestamp >= startOfDay -> todayItems.add(item)
            timestamp >= startOfYesterday -> yesterdayItems.add(item)
            timestamp >= startOfWeek -> weekItems.add(item)
            timestamp >= startOfMonth -> monthItems.add(item)
            timestamp >= startOfYear -> yearItems.add(item)
            else -> earlierItems.add(item)
        }
    }

    return buildList {
        if (todayItems.isNotEmpty()) {
            add("TODAY")
            addAll(todayItems)
        }
        if (yesterdayItems.isNotEmpty()) {
            add("YESTERDAY")
            addAll(yesterdayItems)
        }
        if (weekItems.isNotEmpty()) {
            add("THIS WEEK")
            addAll(weekItems)
        }
        if (monthItems.isNotEmpty()) {
            add("THIS MONTH")
            addAll(monthItems)
        }
        if (yearItems.isNotEmpty()) {
            add("THIS YEAR")
            addAll(yearItems)
        }
        if (earlierItems.isNotEmpty()) {
            add("EARLIER")
            addAll(earlierItems)
        }
    }
}
// endregion

// region preview
@Preview
@Composable
private fun PreviewActivityListWithHeadersView() {
    AppThemeSurface {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            ActivityListWithHeaders(
                testActivityItems,
                onAllActivityButtonClick = {},
                onActivityItemClick = {},
                onEmptyActivityRowClick = {},
            )
        }
    }
}

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

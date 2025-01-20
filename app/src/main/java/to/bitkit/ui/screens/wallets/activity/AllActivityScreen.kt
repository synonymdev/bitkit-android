package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.SheetHost
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.util.DarkModePreview
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.ActivityListViewModel
import uniffi.bitkitcore.Activity
import uniffi.bitkitcore.LightningActivity
import uniffi.bitkitcore.OnchainActivity
import uniffi.bitkitcore.PaymentState
import uniffi.bitkitcore.PaymentType
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllActivityScreen(
    viewModel: ActivityListViewModel,
    onBackCLick: () -> Unit,
    onActivityItemClick: (String) -> Unit,
) {
    ScreenColumn {
        AppTopBar(stringResource(R.string.all_activity), onBackCLick)
        val dateRangeState = rememberDateRangePickerState()
        var currentSheet by remember { mutableStateOf<ActivityFilterSheet?>(null) }

        SheetHost(
            shouldExpand = currentSheet != null,
            onDismiss = { currentSheet = null },
            sheets = {
                when (currentSheet) {
                    is ActivityFilterSheet.DateRangeSelector -> {
                        DateRangeSelectorSheet(
                            dateRangeState = dateRangeState,
                            onClearClick = {
                                dateRangeState.setSelection(null, null)
                                viewModel.clearDateRange()
                                currentSheet = null
                            },
                            onApplyClick = {
                                viewModel.setDateRange(
                                    startDate = dateRangeState.selectedStartDateMillis,
                                    endDate = dateRangeState.selectedEndDateMillis,
                                )
                                currentSheet = null
                            },
                        )
                    }

                    is ActivityFilterSheet.TagSelector -> {
                        TagSelectorSheet(
                            viewModel = viewModel,
                            onClearClick = {
                                viewModel.clearTags()
                                currentSheet = null
                            },
                            onApplyClick = {
                                currentSheet = null
                            },
                        )
                    }

                    null -> Unit
                }
            }
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                ActivityListFilter(
                    viewModel = viewModel,
                    onTagClick = { currentSheet = ActivityFilterSheet.TagSelector },
                    onDateRangeClick = { currentSheet = ActivityFilterSheet.DateRangeSelector },
                )
                Spacer(modifier = Modifier.height(16.dp))
                val filteredActivities by viewModel.filteredActivities.collectAsState()
                ActivityListWithHeaders(
                    items = filteredActivities,
                    onActivityItemClick = onActivityItemClick,
                )
            }
        }
    }
}

@Composable
fun ActivityListWithHeaders(
    items: List<Activity>?,
    showFooter: Boolean = false,
    onAllActivityButtonClick: () -> Unit = { },
    onActivityItemClick: (String) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize()
    ) {
        if (items != null) {
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
                            if (index < groupedItems.size - 1 && groupedItems[index + 1] !is String) {
                                HorizontalDivider(color = Colors.White10)
                            }
                        }
                    }
                }
                if (showFooter) {
                    item {
                        if (items.isEmpty()) {
                            Text("No activity", Modifier.padding(16.dp))
                        } else {
                            TextButton(
                                onClick = onAllActivityButtonClick,
                                modifier = Modifier.padding(8.dp)
                            ) {
                                Text("Show All Activity")
                            }
                        }
                    }
                }
            }
        } else {
            Text("No activity", Modifier.padding(16.dp))
        }
    }
}

@Composable
fun ActivityList(
    items: List<Activity>?,
    onAllActivityClick: () -> Unit,
    onActivityItemClick: (String) -> Unit,
) {
    if (items != null) {
        LazyColumn(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            items(
                items = items,
                key = {
                    when (it) {
                        is Activity.Onchain -> it.v1.id
                        is Activity.Lightning -> it.v1.id
                    }
                }
            ) { item ->
                ActivityRow(item, onActivityItemClick)
                HorizontalDivider(color = Colors.White10)
            }
            item {
                if (items.isEmpty()) {
                    Text("No activity", Modifier.padding(16.dp))
                } else {
                    TextButton(
                        onClick = onAllActivityClick,
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text("Show All Activity")
                    }
                }
            }
        }
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("No activity available.", Modifier.padding(16.dp))
        }
    }
}

// region utils
private fun groupActivityItems(activityItems: List<Activity>): List<Any> {
    val date = Calendar.getInstance()

    val beginningOfDay = date.apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val beginningOfYesterday = date.apply {
        timeInMillis = beginningOfDay
        add(Calendar.DATE, -1)
    }.timeInMillis

    val beginningOfWeek = date.apply {
        set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val beginningOfMonth = date.apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val beginningOfYear = date.apply {
        set(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val today = mutableListOf<Activity>()
    val yesterday = mutableListOf<Activity>()
    val week = mutableListOf<Activity>()
    val month = mutableListOf<Activity>()
    val year = mutableListOf<Activity>()
    val earlier = mutableListOf<Activity>()

    for (item in activityItems) {
        val timestamp = when (item) {
            is Activity.Lightning -> item.v1.timestamp.toLong()
            is Activity.Onchain -> item.v1.timestamp.toLong()
        }
        when {
            timestamp >= beginningOfDay -> today.add(item)
            timestamp >= beginningOfYesterday -> yesterday.add(item)
            timestamp >= beginningOfWeek -> week.add(item)
            timestamp >= beginningOfMonth -> month.add(item)
            timestamp >= beginningOfYear -> year.add(item)
            else -> earlier.add(item)
        }
    }

    val result = mutableListOf<Any>()
    if (today.isNotEmpty()) {
        result.add("TODAY")
        result.addAll(today)
    }
    if (yesterday.isNotEmpty()) {
        result.add("YESTERDAY")
        result.addAll(yesterday)
    }
    if (week.isNotEmpty()) {
        result.add("THIS WEEK")
        result.addAll(week)
    }
    if (month.isNotEmpty()) {
        result.add("THIS MONTH")
        result.addAll(month)
    }
    if (year.isNotEmpty()) {
        result.add("THIS YEAR")
        result.addAll(year)
    }
    if (earlier.isNotEmpty()) {
        result.add("EARLIER")
        result.addAll(earlier)
    }

    return result
}
// endregion

// region preview
@DarkModePreview
@Composable
private fun PreviewActivityListWithHeadersView() {
    AppThemeSurface {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            ActivityListWithHeaders(
                testActivityItems,
                onAllActivityButtonClick = { },
                onActivityItemClick = { },
            )
        }
    }
}

@DarkModePreview
@Composable
private fun PreviewActivityListItems() {
    AppThemeSurface {
        ActivityList(
            testActivityItems,
            onAllActivityClick = { },
            onActivityItemClick = { },
        )
    }
}

@DarkModePreview
@Composable
private fun PreviewActivityListEmpty() {
    AppThemeSurface {
        ActivityList(
            items = emptyList(),
            onAllActivityClick = { },
            onActivityItemClick = { },
        )
    }
}

@DarkModePreview
@Composable
private fun PreviewActivityListNull() {
    AppThemeSurface {
        ActivityList(
            items = null,
            onAllActivityClick = { },
            onActivityItemClick = { },
        )
    }
}

private val today: Calendar = Calendar.getInstance()
private val yesterday: Calendar = Calendar.getInstance().apply { add(Calendar.DATE, -1) }
private val thisWeek: Calendar = Calendar.getInstance().apply { add(Calendar.DATE, -3) }
private val thisMonth: Calendar = Calendar.getInstance().apply { add(Calendar.DATE, -10) }

val testActivityItems: List<Activity> = listOf(
    // Today
    Activity.Onchain(
        OnchainActivity(
            id = "1",
            txType = PaymentType.RECEIVED,
            txId = "01",
            value = 42_000_000_u,
            fee = 200_u,
            feeRate = 1_u,
            address = "bcrt1",
            confirmed = true,
            timestamp = today.timeInMillis.toULong() / 1000u,
            isBoosted = false,
            isTransfer = true,
            doesExist = true,
            confirmTimestamp = today.timeInMillis.toULong() / 1000u,
            channelId = "channelId",
            transferTxId = "transferTxId",
            createdAt = today.timeInMillis.toULong() / 1000u,
            updatedAt = today.timeInMillis.toULong() / 1000u,
        )
    ),
    // Yesterday
    Activity.Lightning(
        LightningActivity(
            id = "2",
            txType = PaymentType.SENT,
            status = PaymentState.SUCCEEDED,
            value = 30_000_u,
            fee = 15_u,
            invoice = "lnbcrt2",
            message = "Custom message",
            timestamp = yesterday.timeInMillis.toULong() / 1000u,
            preimage = "preimage1",
            createdAt = yesterday.timeInMillis.toULong() / 1000u,
            updatedAt = yesterday.timeInMillis.toULong() / 1000u,
        )
    ),
    // This Week
    Activity.Lightning(
        LightningActivity(
            id = "3",
            txType = PaymentType.RECEIVED,
            status = PaymentState.FAILED,
            value = 217_000_u,
            fee = 17_u,
            invoice = "lnbcrt3",
            message = "",
            timestamp = thisWeek.timeInMillis.toULong() / 1000u,
            preimage = "preimage2",
            createdAt = thisWeek.timeInMillis.toULong() / 1000u,
            updatedAt = thisWeek.timeInMillis.toULong() / 1000u,
        )
    ),
    // This Month
    Activity.Onchain(
        OnchainActivity(
            id = "4",
            txType = PaymentType.RECEIVED,
            txId = "04",
            value = 950_000_u,
            fee = 110_u,
            feeRate = 1_u,
            address = "bcrt1",
            confirmed = false,
            timestamp = thisMonth.timeInMillis.toULong() / 1000u,
            isBoosted = false,
            isTransfer = true,
            doesExist = true,
            confirmTimestamp = (today.timeInMillis + 3600_000).toULong() / 1000u,
            channelId = "channelId",
            transferTxId = "transferTxId",
            createdAt = thisMonth.timeInMillis.toULong() / 1000u,
            updatedAt = thisMonth.timeInMillis.toULong() / 1000u,
        )
    ),
)
// endregion

sealed class ActivityFilterSheet {
    data object DateRangeSelector : ActivityFilterSheet()
    data object TagSelector : ActivityFilterSheet()
}

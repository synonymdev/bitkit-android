package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BottomSheetType
import to.bitkit.ui.components.TertiaryButton
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
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllActivityScreen(
    viewModel: ActivityListViewModel,
    onBackCLick: () -> Unit,
    onActivityItemClick: (String) -> Unit,
) {
    val app = appViewModel ?: return

    ScreenColumn {
        AppTopBar(stringResource(R.string.wallet__activity_all), onBackCLick)

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            ActivityListFilter(
                viewModel = viewModel,
                onTagClick = { app.showSheet(BottomSheetType.ActivityTagSelector) },
                onDateRangeClick = { app.showSheet(BottomSheetType.ActivityDateRangeSelector) },
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
                            BodyMSB(stringResource(R.string.wallet__activity_no), Modifier.padding(16.dp))
                        } else {
                            TertiaryButton(
                                text = stringResource(R.string.wallet__activity_show_all),
                                onClick = onAllActivityButtonClick,
                                modifier = Modifier
                                    .wrapContentWidth()
                                    .padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
        } else {
            BodyMSB(stringResource(R.string.wallet__activity_no), Modifier.padding(16.dp))
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
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            items.forEach { item ->
                ActivityRow(item, onActivityItemClick)
                HorizontalDivider(color = Colors.White10)
            }
            if (items.isEmpty()) {
                BodyMSB(stringResource(R.string.wallet__activity_no), Modifier.padding(16.dp))
            } else {
                TertiaryButton(
                    text = stringResource(R.string.wallet__activity_show_all),
                    onClick = onAllActivityClick,
                    modifier = Modifier
                        .wrapContentWidth()
                        .padding(top = 8.dp)
                )
            }
        }
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            BodyMSB(stringResource(R.string.wallet__activity_no), Modifier.padding(16.dp))
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
    val startOfWeek = today.with(TemporalAdjusters.previousOrSame(WeekFields.of(Locale.getDefault()).firstDayOfWeek)).toInstant().epochSecond
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
private val lastYear: Calendar = Calendar.getInstance().apply { add(Calendar.YEAR, -1) }

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
            address = "bc1",
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
            status = PaymentState.PENDING,
            value = 30_000_u,
            fee = 15_u,
            invoice = "lnbc2",
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
            invoice = "lnbc3",
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
            address = "bc1",
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
    // Last Year
    Activity.Lightning(
        LightningActivity(
            id = "5",
            txType = PaymentType.SENT,
            status = PaymentState.SUCCEEDED,
            value = 200_000_u,
            fee = 1_u,
            invoice = "lnbc…",
            message = "",
            timestamp = (lastYear.timeInMillis.toULong() / 1000u),
            preimage = null,
            createdAt = (lastYear.timeInMillis.toULong() / 1000u),
            updatedAt = (lastYear.timeInMillis.toULong() / 1000u),
        )
    ),
)
// endregion

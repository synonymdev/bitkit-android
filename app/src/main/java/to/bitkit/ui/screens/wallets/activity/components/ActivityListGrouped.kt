package to.bitkit.ui.screens.wallets.activity.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.synonym.bitkitcore.Activity
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.TertiaryButton
import to.bitkit.ui.screens.wallets.activity.utils.previewActivityItems
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

@Composable
fun ActivityListGrouped(
    items: List<Activity>?,
    onActivityItemClick: (String) -> Unit,
    onEmptyActivityRowClick: () -> Unit,
    modifier: Modifier = Modifier,
    showFooter: Boolean = false,
    onAllActivityButtonClick: () -> Unit = {},
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxSize()
    ) {
        if (items != null && items.isNotEmpty()) {
            val groupedItems = groupActivityItems(items)

            LazyColumn(
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(top = 20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(groupedItems) { index, item ->
                    when (item) {
                        is String -> {
                            Caption13Up(
                                text = item,
                                color = Colors.White64,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            )
                        }

                        is Activity -> {
                            ActivityRow(item, onActivityItemClick)
                            val hasNextItem =
                                index < groupedItems.size - 1 && groupedItems[index + 1] !is String
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
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

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            ActivityListGrouped(
                items = previewActivityItems,
                onActivityItemClick = {},
                onEmptyActivityRowClick = {},
            )
        }
    }
}

@Preview
@Composable
private fun PreviewEmpty() {
    AppThemeSurface {
        ActivityListGrouped(
            items = emptyList(),
            onActivityItemClick = {},
            onEmptyActivityRowClick = {},
        )
    }
}

@Preview
@Composable
private fun PreviewEmptyWithFooter() {
    AppThemeSurface {
        ActivityListGrouped(
            items = emptyList(),
            onActivityItemClick = {},
            onEmptyActivityRowClick = {},
            showFooter = true,
        )
    }
}

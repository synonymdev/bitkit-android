package to.bitkit.ui.screens.wallets.activity.components

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synonym.bitkitcore.Activity
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import to.bitkit.R
import to.bitkit.ext.activityKey
import to.bitkit.ext.scopedId
import to.bitkit.ui.activityListViewModel
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.TertiaryButton
import to.bitkit.ui.components.VerticalSpacer
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
    items: ImmutableList<Activity>?,
    onActivityItemClick: (String, String) -> Unit,
    onEmptyActivityRowClick: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    showFooter: Boolean = false,
    onAllActivityButtonClick: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(top = 20.dp),
    activityTestTagPrefix: String = "Activity",
    showContactAvatar: Boolean = true,
    hardwareIds: ImmutableSet<String> = persistentSetOf(),
    titleProvider: @Composable (Activity) -> String? = { null },
) {
    val contacts by activityListViewModel?.contacts?.collectAsStateWithLifecycle() ?: remember {
        mutableStateOf(persistentListOf())
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxSize()
    ) {
        if (!items.isNullOrEmpty()) {
            val groupedItems = groupActivityItems(items)

            LazyColumn(
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(
                    items = groupedItems,
                    key = { index, item ->
                        when (item) {
                            is String -> "header_$item"
                            is Activity -> item.activityKey()
                            else -> "item_$index"
                        }
                    }
                ) { index, item ->
                    when (item) {
                        is String -> {
                            Caption13Up(
                                text = item,
                                color = Colors.White64,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .animateItem(
                                        fadeInSpec = tween(durationMillis = 300),
                                        fadeOutSpec = tween(durationMillis = 300),
                                        placementSpec = tween(durationMillis = 300)
                                    )
                            )
                        }

                        is Activity -> {
                            Column(
                                modifier = Modifier
                                    .animateItem(
                                        fadeInSpec = tween(durationMillis = 300),
                                        fadeOutSpec = tween(durationMillis = 300),
                                        placementSpec = tween(durationMillis = 300)
                                    )
                            ) {
                                ActivityRow(
                                    item = item,
                                    onClick = onActivityItemClick,
                                    testTag = "$activityTestTagPrefix-$index",
                                    title = titleProvider(item) ?: contactActivityTitle(item, contacts),
                                    isHardware = item.scopedId() in hardwareIds,
                                    contact = if (showContactAvatar) contactForActivity(item, contacts) else null,
                                )
                                VerticalSpacer(16.dp)
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
                    VerticalSpacer(120.dp)
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
                        .padding(top = contentPadding.calculateTopPadding())
                        .padding(16.dp)
                )
            }
        }
    }
}

@Suppress("LongMethod", "LongParameterList")
fun LazyListScope.activityListGroupedItems(
    items: ImmutableList<Activity>?,
    onActivityItemClick: (String, String) -> Unit,
    onEmptyActivityRowClick: () -> Unit,
    showFooter: Boolean = false,
    onAllActivityButtonClick: () -> Unit = {},
    hardwareIds: ImmutableSet<String> = persistentSetOf(),
    footerContent: (@Composable () -> Unit)? = null,
) {
    if (!items.isNullOrEmpty()) {
        val groupedItems = groupActivityItems(items)
        itemsIndexed(
            items = groupedItems,
            key = { index, item ->
                when (item) {
                    is String -> "header_$item"
                    is Activity -> item.activityKey()
                    else -> "item_$index"
                }
            },
        ) { index, item ->
            when (item) {
                is String -> {
                    Caption13Up(
                        text = item,
                        color = Colors.White64,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .animateItem(
                                fadeInSpec = tween(durationMillis = 300),
                                fadeOutSpec = tween(durationMillis = 300),
                                placementSpec = tween(durationMillis = 300),
                            )
                    )
                }

                is Activity -> {
                    Column(
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = tween(durationMillis = 300),
                                fadeOutSpec = tween(durationMillis = 300),
                                placementSpec = tween(durationMillis = 300),
                            )
                    ) {
                        ActivityRow(
                            item = item,
                            onClick = onActivityItemClick,
                            testTag = "Activity-$index",
                            isHardware = item.scopedId() in hardwareIds,
                        )
                        VerticalSpacer(16.dp)
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
        footerContent?.let { content ->
            item { content() }
        }
        item {
            VerticalSpacer(120.dp)
        }
    } else {
        if (showFooter) {
            item { EmptyActivityRow(onClick = onEmptyActivityRowClick) }
        } else {
            item {
                BodyM(
                    text = stringResource(R.string.wallet__activity_no),
                    color = Colors.White64,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            }
        }
        footerContent?.let { content ->
            item { content() }
            item { VerticalSpacer(120.dp) }
        }
    }
}

// region utils
@Suppress("CyclomaticComplexMethod")
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
                onActivityItemClick = { _, _ -> },
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
            items = persistentListOf(),
            onActivityItemClick = { _, _ -> },
            onEmptyActivityRowClick = {},
        )
    }
}

@Preview
@Composable
private fun PreviewEmptyWithFooter() {
    AppThemeSurface {
        ActivityListGrouped(
            items = persistentListOf(),
            onActivityItemClick = { _, _ -> },
            onEmptyActivityRowClick = {},
            showFooter = true,
        )
    }
}

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
import com.synonym.bitkitcore.UsdtTransfer
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import to.bitkit.R
import to.bitkit.ext.scopedId
import to.bitkit.ui.activityListViewModel
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.TertiaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.screens.wallets.activity.utils.previewActivityItems
import to.bitkit.ui.screens.wallets.usdt.UsdtActivityRow
import to.bitkit.ui.settingsViewModel
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
    onActivityItemClick: (Activity) -> Unit,
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
    usdtItems: ImmutableList<UsdtTransfer> = persistentListOf(),
) {
    val app = appViewModel
    val settings = settingsViewModel
    val hideBalance by settings?.hideBalance?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxSize()
    ) {
        if (!items.isNullOrEmpty() || usdtItems.isNotEmpty()) {
            val groupedItems = remember(items, usdtItems) { groupActivityItems(items.orEmpty(), usdtItems) }

            LazyColumn(
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(
                    items = groupedItems,
                    key = { index, item ->
                        groupedActivityKey(index, item)
                    }
                ) { index, item ->
                    when (item) {
                        is UsdtTransfer -> {
                            UsdtActivityRow(item, hideBalance, onClick = { app?.navigateToUsdtActivity(item.id) })
                            if (index < groupedItems.lastIndex) VerticalSpacer(16.dp)
                        }

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
                                ActivityContactRow(
                                    item,
                                    onActivityItemClick,
                                    "$activityTestTagPrefix-$index",
                                    titleProvider(item),
                                    hardwareIds,
                                    showContactAvatar
                                )
                                if (index < groupedItems.lastIndex) {
                                    VerticalSpacer(16.dp)
                                }
                            }
                        }
                    }
                }
                if (showFooter) {
                    item {
                        TertiaryButton(
                            text = stringResource(R.string.wallet__activity_show_all),
                            onClick = onAllActivityButtonClick,
                            modifier = Modifier.wrapContentWidth()
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

@Composable
private fun ActivityContactRow(
    item: Activity,
    onClick: (Activity) -> Unit,
    testTag: String,
    title: String?,
    hardwareIds: ImmutableSet<String>,
    showContactAvatar: Boolean,
) {
    val contacts by activityListViewModel?.contacts?.collectAsStateWithLifecycle() ?: remember {
        mutableStateOf(persistentListOf())
    }
    ActivityRow(
        item = item,
        onClick = onClick,
        testTag = testTag,
        title = title ?: contactActivityTitle(item, contacts),
        isHardware = item.scopedId() in hardwareIds,
        contact = if (showContactAvatar) contactForActivity(item, contacts) else null
    )
}

private fun groupedActivityKey(index: Int, item: Any): String = when (item) {
    is UsdtTransfer -> "usdt_${item.id}"
    is String -> "header_$item"
    is Activity.Lightning -> "lightning_${item.scopedId()}"
    is Activity.Onchain -> "onchain_${item.scopedId()}"
    else -> "item_$index"
}

@Suppress("LongMethod", "LongParameterList")
fun LazyListScope.activityListGroupedItems(
    items: ImmutableList<Activity>?,
    onActivityItemClick: (Activity) -> Unit,
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
                groupedActivityKey(index, item)
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
                        if (index < groupedItems.lastIndex) {
                            VerticalSpacer(16.dp)
                        }
                    }
                }
            }
        }
        if (showFooter) {
            item {
                TertiaryButton(
                    text = stringResource(R.string.wallet__activity_show_all),
                    onClick = onAllActivityButtonClick,
                    modifier = Modifier.wrapContentWidth()
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
private fun groupActivityItems(activityItems: List<Activity>, usdtItems: List<UsdtTransfer> = emptyList()): List<Any> {
    val now = Instant.now()
    val zoneId = ZoneId.systemDefault()
    val today = now.atZone(zoneId).truncatedTo(ChronoUnit.DAYS)

    val startOfDay = today.toInstant().epochSecond
    val startOfYesterday = today.minusDays(1).toInstant().epochSecond
    val startOfWeek = today.with(TemporalAdjusters.previousOrSame(WeekFields.of(Locale.getDefault()).firstDayOfWeek))
        .toInstant().epochSecond
    val startOfMonth = today.withDayOfMonth(1).toInstant().epochSecond
    val startOfYear = today.withDayOfYear(1).toInstant().epochSecond

    val todayItems = mutableListOf<Any>()
    val yesterdayItems = mutableListOf<Any>()
    val weekItems = mutableListOf<Any>()
    val monthItems = mutableListOf<Any>()
    val yearItems = mutableListOf<Any>()
    val earlierItems = mutableListOf<Any>()

    val entries = (
        activityItems.map {
            WalletActivity.Bitcoin(it)
        } + usdtItems.map { WalletActivity.Usdt(it) }
        ).sortedByDescending { it.timestamp }
    for (entry in entries) {
        val item: Any = when (entry) {
            is WalletActivity.Bitcoin -> entry.item
            is WalletActivity.Usdt -> entry.item
        }
        val timestamp = entry.timestamp.toLong()
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
        listOf(
            "TODAY" to todayItems,
            "YESTERDAY" to yesterdayItems,
            "THIS WEEK" to weekItems,
            "THIS MONTH" to monthItems,
            "THIS YEAR" to yearItems,
            "EARLIER" to earlierItems,
        ).forEach { (title, items) ->
            if (items.isNotEmpty()) {
                add(title)
                addAll(items)
            }
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
            items = persistentListOf(),
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
            items = persistentListOf(),
            onActivityItemClick = {},
            onEmptyActivityRowClick = {},
            showFooter = true,
        )
    }
}

fun activityGroupTitleResource(timestamp: ULong): Int {
    val today = Instant.now().atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS)
    val week = today.with(TemporalAdjusters.previousOrSame(WeekFields.of(Locale.getDefault()).firstDayOfWeek))
    val date = Instant.ofEpochSecond(timestamp.toLong())
    return when {
        date >= today.toInstant() -> R.string.wallet__activity_group_today
        date >= today.minusDays(1).toInstant() -> R.string.wallet__activity_group_yesterday
        date >= week.toInstant() -> R.string.wallet__activity_group_week
        date >= today.withDayOfMonth(1).toInstant() -> R.string.wallet__activity_group_month
        date >= today.withDayOfYear(1).toInstant() -> R.string.wallet__activity_group_year
        else -> R.string.wallet__activity_group_earlier
    }
}

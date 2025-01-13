package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ext.toActivityItemDate
import to.bitkit.models.ConvertedAmount
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.util.DarkModePreview
import to.bitkit.ui.shared.util.LightModePreview
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.secondaryColor
import to.bitkit.viewmodels.ActivityListViewModel
import to.bitkit.viewmodels.PrimaryDisplay
import uniffi.bitkitcore.*
import java.util.*

@Composable
fun AllActivityScreen(
    viewModel: ActivityListViewModel,
    onBackCLick: () -> Unit,
    onActivityItemClick: (String) -> Unit,
) {
    ScreenColumn {
        AppTopBar(stringResource(R.string.all_activity), onBackCLick)
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            val activities = viewModel.filteredActivities.collectAsState()
            ActivityListWithHeaders(
                items = activities.value,
                onAllActivityButtonClick = { }, // Do Nothing - button is not shown
                onActivityItemClick = onActivityItemClick,
            )
        }
    }
}

@Composable
fun ActivityListWithHeaders(
    items: List<Activity>?,
    showFooter: Boolean = false,
    onAllActivityButtonClick: () -> Unit,
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

@Composable
private fun ActivityRow(
    item: Activity,
    onClick: (String) -> Unit,
) {
    val id = when (item) {
        is Activity.Onchain -> item.v1.id
        is Activity.Lightning -> item.v1.id
    }
    val status: PaymentState? = when (item) {
        is Activity.Lightning -> item.v1.status
        is Activity.Onchain -> null
    }
    val isLightning = item is Activity.Lightning
    val timestamp = when (item) {
        is Activity.Lightning -> item.v1.timestamp
        is Activity.Onchain -> item.v1.timestamp
    }
    val txType: PaymentType = when (item) {
        is Activity.Lightning -> item.v1.txType
        is Activity.Onchain -> item.v1.txType
    }
    val amountPrefix = when (item) {
        is Activity.Lightning -> if (item.v1.txType == PaymentType.SENT) "-" else "+"
        is Activity.Onchain -> if (item.v1.txType == PaymentType.SENT) "-" else "+"
    }
    val confirmed: Boolean? = when (item) {
        is Activity.Lightning -> null
        is Activity.Onchain -> item.v1.confirmed
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = { onClick(id) })
            .padding(horizontal = 0.dp, vertical = 16.dp)
    ) {
        TransactionIcon(item)
        Spacer(modifier = Modifier.width(12.dp))

        val lightningStatus = when {
            txType == PaymentType.SENT -> when (status) {
                PaymentState.FAILED -> "Sending Failed"
                PaymentState.PENDING -> "Sending..."
                PaymentState.SUCCEEDED -> "Sent"
                else -> ""
            }

            else -> when (status) {
                PaymentState.FAILED -> "Receive Failed"
                PaymentState.PENDING -> "Receiving..."
                PaymentState.SUCCEEDED -> "Received"
                else -> ""
            }
        }
        val onchainStatus = when {
            txType == PaymentType.SENT -> if (confirmed == true) "Sent" else "Sending..."
            else -> if (confirmed == true) "Received" else "Receiving..."
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = if (isLightning) lightningStatus else onchainStatus,
                fontWeight = FontWeight.Bold,
            )
            val subtitleText = when (item) {
                is Activity.Lightning -> {
                    val message = item.v1.message
                    if (message.isNotEmpty()) message else timestamp.toActivityItemDate()
                }

                else -> timestamp.toActivityItemDate()
            }
            Text(
                text = subtitleText,
                color = Colors.White64,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        val amount: ULong = when (item) {
            is Activity.Lightning -> item.v1.value
            is Activity.Onchain -> item.v1.value
        }
        amount.let { sats ->
            val currency = currencyViewModel ?: return
            val (rates, _, _, _, displayUnit, primaryDisplay) = LocalCurrencies.current
            val converted: ConvertedAmount? = if (rates.isNotEmpty()) currency.convert(sats = sats.toLong()) else null

            converted?.let { converted ->
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (primaryDisplay == PrimaryDisplay.BITCOIN) {
                        val btcComponents = converted.bitcoinDisplay(displayUnit)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Text(
                                text = amountPrefix,
                                color = secondaryColor,
                            )
                            Text(text = btcComponents.value)
                        }
                        Text(
                            text = "${converted.symbol} ${converted.formatted}",
                            style = MaterialTheme.typography.bodySmall,
                            color = secondaryColor,
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Text(
                                text = amountPrefix,
                                color = secondaryColor,
                            )
                            Text(text = "${converted.symbol} ${converted.formatted}")
                        }

                        val btcComponents = converted.bitcoinDisplay(displayUnit)
                        Text(
                            text = btcComponents.value,
                            style = MaterialTheme.typography.bodySmall,
                            color = secondaryColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionIcon(item: Activity) {
    val isLightning = item is Activity.Lightning
    val status: PaymentState? = when (item) {
        is Activity.Lightning -> item.v1.status
        is Activity.Onchain -> null
    }
    val confirmed: Boolean? = when (item) {
        is Activity.Lightning -> null
        is Activity.Onchain -> item.v1.confirmed
    }
    val txType: PaymentType = when (item) {
        is Activity.Lightning -> item.v1.txType
        is Activity.Onchain -> item.v1.txType
    }

    if (isLightning) {
        if (status == PaymentState.FAILED) {
            IconInCircle(
                icon = Icons.Default.Close,
                tint = Colors.Red,
            )
        } else {
            val icon = if (txType == PaymentType.SENT) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward
            IconInCircle(
                icon = icon,
                tint = Colors.Purple,
            )
        }
    } else {
        val icon = if (txType == PaymentType.SENT) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward
        IconInCircle(
            icon = icon,
            tint = if (confirmed == true) Colors.Brand else Colors.Brand50,
        )
    }
}

@Composable
private fun IconInCircle(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(32.dp)
            .background(color = tint.copy(alpha = 0.16f), shape = CircleShape)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
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
fun PreviewActivityListWithHeadersView() {
    AppThemeSurface {
        val sampleItems = PaymentDetailsPreviewProvider().values.toList()
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            ActivityListWithHeaders(sampleItems, onAllActivityButtonClick = { }, onActivityItemClick = { })
        }
    }
}

@DarkModePreview
@Composable
fun PreviewActivityListItems() {
    AppThemeSurface {
        val sampleItems = PaymentDetailsPreviewProvider().values.toList()
        ActivityList(
            sampleItems,
            onAllActivityClick = { },
            onActivityItemClick = { },
        )
    }
}

@DarkModePreview
@Composable
fun PreviewActivityListEmpty() {
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
fun PreviewActivityListNull() {
    AppThemeSurface {
        ActivityList(
            items = null,
            onAllActivityClick = { },
            onActivityItemClick = { },
        )
    }
}

private val today: Calendar = Calendar.getInstance()
private val yesteday: Calendar = Calendar.getInstance().apply { add(Calendar.DATE, -1) }
private val thisWeek: Calendar = Calendar.getInstance().apply { add(Calendar.DATE, -3) }
private val thisMonth: Calendar = Calendar.getInstance().apply { add(Calendar.DATE, -10) }
private val earlier: Calendar = Calendar.getInstance().apply { add(Calendar.MONTH, -2) }

val testActivityItems: Sequence<Activity> = sequenceOf(
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
            timestamp = today.timeInMillis.toULong(),
            isBoosted = false,
            isTransfer = true,
            doesExist = true,
            confirmTimestamp = today.timeInMillis.toULong(),
            channelId = "channelId",
            transferTxId = "transferTxId",
            createdAt = today.timeInMillis.toULong(),
            updatedAt = today.timeInMillis.toULong(),
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
            timestamp = yesteday.timeInMillis.toULong(),
            preimage = "preimage1",
            createdAt = yesteday.timeInMillis.toULong(),
            updatedAt = yesteday.timeInMillis.toULong(),
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
            timestamp = thisWeek.timeInMillis.toULong(),
            preimage = "preimage2",
            createdAt = thisWeek.timeInMillis.toULong(),
            updatedAt = thisWeek.timeInMillis.toULong(),
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
            timestamp = thisMonth.timeInMillis.toULong(),
            isBoosted = false,
            isTransfer = true,
            doesExist = true,
            confirmTimestamp = (today.timeInMillis + 3600_000).toULong(),
            channelId = "channelId",
            transferTxId = "transferTxId",
            createdAt = thisMonth.timeInMillis.toULong(),
            updatedAt = thisMonth.timeInMillis.toULong(),
        )
    ),
    //    // Earlier
    //    PaymentDetails(
    //        id = "5",
    //        kind = PaymentKind.Onchain,
    //        amountMsat = 5000_000u,
    //        direction = PaymentDirection.INBOUND,
    //        status = PaymentStatus.PENDING,
    //        latestUpdateTimestamp = (Calendar.getInstance().apply { add(Calendar.MONTH, -2) }.timeInMillis / 1000).toULong()
    //    ),
)

private class PaymentDetailsPreviewProvider : PreviewParameterProvider<Activity> {
    override val values: Sequence<Activity>
        get() = testActivityItems
}

@LightModePreview
@Composable
private fun ActivityRowPreview(@PreviewParameter(PaymentDetailsPreviewProvider::class) item: Activity) {
    AppThemeSurface {
        ActivityRow(item, onClick = { })
    }
}
// endregion

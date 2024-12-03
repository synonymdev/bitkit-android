package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ContentAlpha
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import to.bitkit.R
import to.bitkit.ext.amountSats
import to.bitkit.ext.toActivityItemDate
import to.bitkit.models.ConvertedAmount
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.WalletViewModel
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.util.LightModePreview
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Orange500
import to.bitkit.ui.theme.Purple500
import to.bitkit.viewmodels.PrimaryDisplay
import java.util.Calendar

@Composable
fun AllActivityScreen(
    viewModel: WalletViewModel,
    onBackCLick: () -> Unit,
    onActivityItemClick: (String) -> Unit,
) {
    ScreenColumn {
        AppTopBar(stringResource(R.string.all_activity), onBackCLick)
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            ActivityListWithHeaders(
                items = viewModel.activityItems.value,
                onAllActivityButtonClick = { }, // Do Nothing - button is not shown
                onActivityItemClick = onActivityItemClick,
            )
        }
    }
}

@Composable
fun ActivityListWithHeaders(
    items: List<PaymentDetails>?,
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

                        is PaymentDetails -> {
                            ActivityRow(item, onActivityItemClick)
                            if (index < groupedItems.size - 1 && groupedItems[index + 1] !is String) {
                                HorizontalDivider(color = DividerDefaults.color.copy(alpha = 0.25f))
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
    items: List<PaymentDetails>?,
    onAllActivityClick: () -> Unit,
    onActivityItemClick: (String) -> Unit,
) {
    if (items != null) {
        LazyColumn(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            items(items = items, key = { it.id }) { item ->
                ActivityRow(item, onActivityItemClick)
                HorizontalDivider(color = DividerDefaults.color.copy(alpha = 0.25f))
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
    item: PaymentDetails,
    onClick: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = { onClick(item.id) })
            .padding(horizontal = 0.dp, vertical = 16.dp)
    ) {
        PaymentStatusIcon(item)
        Spacer(modifier = Modifier.width(12.dp))
        val displayText = when {
            item.direction == PaymentDirection.OUTBOUND -> when (item.status) {
                PaymentStatus.FAILED -> "Sending Failed"
                PaymentStatus.PENDING -> "Sending..."
                PaymentStatus.SUCCEEDED -> "Sent"
            }

            else -> when (item.status) {
                PaymentStatus.FAILED -> "Receive Failed"
                PaymentStatus.PENDING -> "Receiving..."
                PaymentStatus.SUCCEEDED -> "Received"
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = displayText,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = item.latestUpdateTimestamp.toActivityItemDate(),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        val amountPrefix = if (item.direction == PaymentDirection.OUTBOUND) "-" else "+"
        item.amountSats?.let { sats ->
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
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = ContentAlpha.medium)
                            )
                            Text(text = btcComponents.value)
                        }
                        Text(
                            text = "${converted.symbol} ${converted.formatted}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = ContentAlpha.medium)
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Text(
                                text = amountPrefix,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = ContentAlpha.medium)
                            )
                            Text(text = "${converted.symbol} ${converted.formatted}")
                        }

                        val btcComponents = converted.bitcoinDisplay(displayUnit)
                        Text(
                            text = btcComponents.value,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = ContentAlpha.medium)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PaymentStatusIcon(item: PaymentDetails) {
    when {
        item.status == PaymentStatus.FAILED -> {
            IconInCircle(
                icon = Icons.Default.Close,
                tint = Color.Red,
            )
        }

        else -> {
            val icon = when (item.direction) {
                PaymentDirection.OUTBOUND -> Icons.Default.ArrowUpward
                else -> Icons.Default.ArrowDownward
            }
            val color = if (item.kind == PaymentKind.Onchain) Orange500 else Purple500
            IconInCircle(
                icon = icon,
                tint = color,
            )
        }
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
private fun groupActivityItems(activityItems: List<PaymentDetails>): List<Any> {
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

    val today = mutableListOf<PaymentDetails>()
    val yesterday = mutableListOf<PaymentDetails>()
    val week = mutableListOf<PaymentDetails>()
    val month = mutableListOf<PaymentDetails>()
    val year = mutableListOf<PaymentDetails>()
    val earlier = mutableListOf<PaymentDetails>()

    for (item in activityItems) {
        val itemTimestampMillis = item.latestUpdateTimestamp.toLong() * 1000L
        when {
            itemTimestampMillis >= beginningOfDay -> today.add(item)
            itemTimestampMillis >= beginningOfYesterday -> yesterday.add(item)
            itemTimestampMillis >= beginningOfWeek -> week.add(item)
            itemTimestampMillis >= beginningOfMonth -> month.add(item)
            itemTimestampMillis >= beginningOfYear -> year.add(item)
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
@LightModePreview
@Composable
fun PreviewActivityListWithHeadersView() {
    AppThemeSurface {
        val sampleItems = PaymentDetailsPreviewProvider().values.toList()
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            ActivityListWithHeaders(sampleItems, onAllActivityButtonClick = { }, onActivityItemClick = { })
        }
    }
}

@LightModePreview
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

@LightModePreview
@Composable
fun PreviewActivityListEmpty() {
    ActivityList(
        items = emptyList(),
        onAllActivityClick = { },
        onActivityItemClick = { },
    )
}

@LightModePreview
@Composable
fun PreviewActivityListNull() {
    ActivityList(
        items = null,
        onAllActivityClick = { },
        onActivityItemClick = { },
    )
}

val testActivityItems: Sequence<PaymentDetails> = sequenceOf(
    // Today
    PaymentDetails(
        id = "1",
        kind = PaymentKind.Bolt11("bolt11", null, null),
        amountMsat = 10_000u,
        direction = PaymentDirection.INBOUND,
        status = PaymentStatus.SUCCEEDED,
        latestUpdateTimestamp = (Calendar.getInstance().timeInMillis / 1000).toULong()
    ),
    // Yesterday
    PaymentDetails(
        id = "2",
        kind = PaymentKind.Onchain,
        amountMsat = 2000_000u,
        direction = PaymentDirection.OUTBOUND,
        status = PaymentStatus.PENDING,
        latestUpdateTimestamp = (Calendar.getInstance().apply { add(Calendar.DATE, -1) }.timeInMillis / 1000).toULong()
    ),
    // This Week
    PaymentDetails(
        id = "3",
        kind = PaymentKind.Bolt11("bolt11", null, null),
        amountMsat = 30_000u,
        direction = PaymentDirection.INBOUND,
        status = PaymentStatus.FAILED,
        latestUpdateTimestamp = (Calendar.getInstance().apply { add(Calendar.DATE, -3) }.timeInMillis / 1000).toULong()
    ),
    // This Month
    PaymentDetails(
        id = "4",
        kind = PaymentKind.Onchain,
        amountMsat = 4000_000u,
        direction = PaymentDirection.OUTBOUND,
        status = PaymentStatus.SUCCEEDED,
        latestUpdateTimestamp = (Calendar.getInstance().apply { add(Calendar.DATE, -15) }.timeInMillis / 1000).toULong()
    ),
    // Earlier
    PaymentDetails(
        id = "5",
        kind = PaymentKind.Onchain,
        amountMsat = 5000_000u,
        direction = PaymentDirection.INBOUND,
        status = PaymentStatus.PENDING,
        latestUpdateTimestamp = (Calendar.getInstance().apply { add(Calendar.MONTH, -2) }.timeInMillis / 1000).toULong()
    ),
)

private class PaymentDetailsPreviewProvider : PreviewParameterProvider<PaymentDetails> {
    override val values: Sequence<PaymentDetails>
        get() = testActivityItems
}

@LightModePreview
@Composable
private fun ActivityRowPreview(@PreviewParameter(PaymentDetailsPreviewProvider::class) item: PaymentDetails) {
    ActivityRow(item, onClick = { })
}
// endregion

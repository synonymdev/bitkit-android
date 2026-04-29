package to.bitkit.ui.screens.widgets.blocks

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.screens.widgets.components.WidgetCardDimens
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Suppress("CyclomaticComplexMethod")
@Composable
fun BlockCard(
    modifier: Modifier = Modifier,
    showBlock: Boolean,
    showTime: Boolean,
    showDate: Boolean,
    showTransactions: Boolean,
    showSize: Boolean,
    showFees: Boolean,
    showSource: Boolean,
    block: String,
    time: String,
    date: String,
    transactions: String,
    size: String,
    fees: String,
    source: String,
) {
    Box(
        modifier = modifier
            .clip(shape = MaterialTheme.shapes.medium)
            .background(Colors.White10)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            if (showBlock && block.isNotEmpty()) {
                WidgetDataRow(
                    icon = R.drawable.ic_cube,
                    label = stringResource(R.string.widgets__blocks__field__block),
                    value = block,
                    testTagPrefix = "block",
                )
            }
            if (showTime && time.isNotEmpty()) {
                WidgetDataRow(
                    icon = R.drawable.ic_clock,
                    label = stringResource(R.string.widgets__blocks__field__time),
                    value = time,
                    testTagPrefix = "time",
                )
            }
            if (showDate && date.isNotEmpty()) {
                WidgetDataRow(
                    icon = R.drawable.ic_calendar,
                    label = stringResource(R.string.widgets__blocks__field__date),
                    value = date,
                    testTagPrefix = "date",
                )
            }
            if (showTransactions && transactions.isNotEmpty()) {
                WidgetDataRow(
                    icon = R.drawable.ic_transfer,
                    label = stringResource(R.string.widgets__blocks__field__transactions),
                    value = transactions,
                    testTagPrefix = "transactions",
                )
            }
            if (showSize && size.isNotEmpty()) {
                WidgetDataRow(
                    icon = R.drawable.ic_file_text,
                    label = stringResource(R.string.widgets__blocks__field__size),
                    value = size,
                    testTagPrefix = "size",
                )
            }
            if (showFees && fees.isNotEmpty()) {
                WidgetDataRow(
                    icon = R.drawable.ic_coins,
                    label = stringResource(R.string.widgets__blocks__field__fees),
                    value = fees,
                    testTagPrefix = "fees",
                )
            }
            if (showSource && source.isNotEmpty()) {
                WidgetDataRow(
                    icon = R.drawable.ic_globe,
                    label = stringResource(R.string.widgets__widget__source),
                    value = source,
                    testTagPrefix = "source",
                )
            }
        }
    }
}

@Suppress("CyclomaticComplexMethod")
@Composable
fun BlockCardSmall(
    modifier: Modifier = Modifier,
    showBlock: Boolean,
    showTime: Boolean,
    showDate: Boolean,
    showTransactions: Boolean,
    showSize: Boolean,
    showFees: Boolean,
    showSource: Boolean,
    block: String,
    time: String,
    date: String,
    transactions: String,
    size: String,
    fees: String,
    source: String,
) {
    Box(
        modifier = modifier
            .size(WidgetCardDimens.COMPACT_CARD_SIZE)
            .clip(shape = MaterialTheme.shapes.medium)
            .background(Colors.White10)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            if (showBlock && block.isNotEmpty()) {
                SmallDataRow(
                    icon = R.drawable.ic_cube,
                    value = block,
                    testTagPrefix = "block",
                )
            }
            if (showTime && time.isNotEmpty()) {
                SmallDataRow(
                    icon = R.drawable.ic_clock,
                    value = time,
                    testTagPrefix = "time",
                )
            }
            if (showDate && date.isNotEmpty()) {
                SmallDataRow(
                    icon = R.drawable.ic_calendar,
                    value = date,
                    testTagPrefix = "date",
                )
            }
            if (showTransactions && transactions.isNotEmpty()) {
                SmallDataRow(
                    icon = R.drawable.ic_transfer,
                    value = transactions,
                    testTagPrefix = "transactions",
                )
            }
            if (showSize && size.isNotEmpty()) {
                SmallDataRow(
                    icon = R.drawable.ic_file_text,
                    value = size,
                    testTagPrefix = "size",
                )
            }
            if (showFees && fees.isNotEmpty()) {
                SmallDataRow(
                    icon = R.drawable.ic_coins,
                    value = fees,
                    testTagPrefix = "fees",
                )
            }
            if (showSource && source.isNotEmpty()) {
                SmallDataRow(
                    icon = R.drawable.ic_globe,
                    value = source,
                    testTagPrefix = "source",
                )
            }
        }
    }
}

@Composable
private fun WidgetDataRow(
    @DrawableRes icon: Int,
    label: String,
    value: String,
    testTagPrefix: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("${testTagPrefix}_row")
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = Colors.Brand,
            modifier = Modifier
                .size(20.dp)
                .testTag("${testTagPrefix}_icon")
        )
        BodyM(
            text = label,
            color = Colors.White80,
            modifier = Modifier
                .weight(1f)
                .testTag("${testTagPrefix}_label")
        )
        BodyMSB(
            text = value,
            color = Colors.White,
            modifier = Modifier.testTag("${testTagPrefix}_text")
        )
    }
}

@Composable
private fun SmallDataRow(
    @DrawableRes icon: Int,
    value: String,
    testTagPrefix: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("${testTagPrefix}_row")
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = Colors.Brand,
            modifier = Modifier
                .size(20.dp)
                .testTag("${testTagPrefix}_icon")
        )
        BodySSB(
            text = value,
            color = Colors.White,
            modifier = Modifier.testTag("${testTagPrefix}_text")
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewLargeAll() {
    AppThemeSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            BlockCard(
                showBlock = true,
                showTime = true,
                showDate = true,
                showTransactions = true,
                showSize = true,
                showFees = true,
                showSource = true,
                block = "761,405",
                time = "01:31:42 UTC",
                date = "11/2/2022",
                transactions = "2,175",
                size = "1,606Kb",
                fees = "25 059 357",
                source = "mempool.io",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewLargeDefault() {
    AppThemeSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            BlockCard(
                showBlock = true,
                showTime = true,
                showDate = true,
                showTransactions = true,
                showSize = false,
                showFees = false,
                showSource = false,
                block = "761,405",
                time = "01:31:42 UTC",
                date = "11/2/2022",
                transactions = "2,175",
                size = "",
                fees = "",
                source = "",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewSmall() {
    AppThemeSurface {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            BlockCardSmall(
                showBlock = true,
                showTime = true,
                showDate = true,
                showTransactions = true,
                showSize = false,
                showFees = false,
                showSource = false,
                block = "761,405",
                time = "01:31:42 UTC",
                date = "11/2/2022",
                transactions = "2,175",
                size = "",
                fees = "",
                source = ""
            )
        }
    }
}

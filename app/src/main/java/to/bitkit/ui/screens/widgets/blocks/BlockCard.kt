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
import to.bitkit.models.widget.BlockModel
import to.bitkit.models.widget.BlocksPreferences
import to.bitkit.models.widget.MAX_BLOCKS_FIELDS
import to.bitkit.models.widget.enabledFields
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.screens.widgets.components.WidgetCardDimens
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun BlockCard(
    modifier: Modifier = Modifier,
    preferences: BlocksPreferences,
    block: BlockModel,
) {
    val fields = preferences.enabledFields().filter { it.value(block).isNotEmpty() }

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
            fields.forEach { field ->
                WidgetDataRow(
                    icon = field.icon,
                    label = stringResource(field.labelRes),
                    value = field.value(block),
                    testTagPrefix = field.testTag,
                )
            }
        }
    }
}

@Composable
fun BlockCardSmall(
    modifier: Modifier = Modifier,
    preferences: BlocksPreferences,
    block: BlockModel,
) {
    val fields = preferences.enabledFields()
        .filter { it.value(block).isNotEmpty() }
        .take(MAX_BLOCKS_FIELDS)

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
            fields.forEach { field ->
                SmallDataRow(
                    icon = field.icon,
                    value = field.value(block),
                    testTagPrefix = field.testTag,
                )
            }
        }
    }
}

@Composable
private fun WidgetDataRow(
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int,
    label: String,
    value: String,
    testTagPrefix: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
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
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int,
    value: String,
    testTagPrefix: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
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
                preferences = BlocksPreferences(
                    showBlock = true,
                    showTime = true,
                    showDate = true,
                    showTransactions = true,
                ),
                block = BlockModel(
                    height = "761,405",
                    time = "01:31:42 UTC",
                    date = "11/2/2022",
                    transactionCount = "2,175",
                    size = "1,606Kb",
                    fees = "25 059 357",
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewLargeSizeAndFees() {
    AppThemeSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            BlockCard(
                preferences = BlocksPreferences(
                    showBlock = true,
                    showTime = true,
                    showDate = false,
                    showTransactions = false,
                    showSize = true,
                    showFees = true,
                ),
                block = BlockModel(
                    height = "761,405",
                    time = "01:31:42 UTC",
                    date = "11/2/2022",
                    transactionCount = "2,175",
                    size = "1,606Kb",
                    fees = "25 059 357",
                ),
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
                preferences = BlocksPreferences(),
                block = BlockModel(
                    height = "761,405",
                    time = "01:31:42 UTC",
                    date = "11/2/2022",
                    transactionCount = "2,175",
                    size = "",
                    fees = "",
                ),
            )
        }
    }
}

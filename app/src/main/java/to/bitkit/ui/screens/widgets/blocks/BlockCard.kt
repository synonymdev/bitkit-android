package to.bitkit.ui.screens.widgets.blocks

import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodySB
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun BlockCard(
    modifier: Modifier = Modifier,
    showWidgetTitle: Boolean,
    showBlock: Boolean,
    showTime: Boolean,
    showDate: Boolean,
    showTransactions: Boolean,
    showSize: Boolean,
    showSource: Boolean,
    block: String,
    time: String,
    date: String,
    transactions: String,
    size: String,
    source: String,
) {
    Box(
        modifier = modifier
            .clip(shape = MaterialTheme.shapes.medium)
            .background(Colors.White10)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showWidgetTitle) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .testTag("block_card_widget_title_row")
                ) {
                    Icon(
                        painter = painterResource(R.drawable.widget_cube),
                        contentDescription = null,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("block_card_widget_title_icon"),
                        tint = Color.Unspecified
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    BodyMSB(
                        text = stringResource(R.string.widgets__blocks__name),
                        modifier = Modifier.testTag("block_card_widget_title_text")
                    )
                }
            }

            if (showBlock && block.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("block_card_block_row"),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    BodySB(
                        text = "Block",
                        color = Colors.White64,
                        modifier = Modifier.testTag("block_card_block_label")
                    )

                    BodySB(
                        text = block,
                        color = Colors.White,
                        modifier = Modifier.testTag("block_card_block_text")
                    )
                }
            }

            if (showTime && time.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("block_card_time_row"),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    BodySB(
                        text = "Time",
                        color = Colors.White64,
                        modifier = Modifier.testTag("block_card_time_label")
                    )

                    BodySB(
                        text = time,
                        color = Colors.White,
                        modifier = Modifier.testTag("block_card_time_text")
                    )
                }
            }

            if (showDate && date.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("block_card_date_row"),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    BodySB(
                        text = "Date",
                        color = Colors.White64,
                        modifier = Modifier.testTag("block_card_date_label")
                    )

                    BodySB(
                        text = date,
                        color = Colors.White,
                        modifier = Modifier.testTag("block_card_date_text")
                    )
                }
            }

            if (showTransactions && transactions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("block_card_transactions_row"),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    BodySB(
                        text = "Transactions",
                        color = Colors.White64,
                        modifier = Modifier.testTag("block_card_transactions_label")
                    )

                    BodySB(
                        text = transactions,
                        color = Colors.White,
                        modifier = Modifier.testTag("block_card_transactions_text")
                    )
                }
            }

            if (showSize && size.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("block_card_size_row"),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    BodySB(
                        text = "Size",
                        color = Colors.White64,
                        modifier = Modifier.testTag("block_card_size_label")
                    )

                    BodySB(
                        text = size,
                        color = Colors.White,
                        modifier = Modifier.testTag("block_card_size_text")
                    )
                }
            }

            if (showSource && source.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag("block_card_source_row"),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    BodySB(
                        text = stringResource(R.string.widgets__widget__source),
                        color = Colors.White64,
                        modifier = Modifier.testTag("block_card_source_label")
                    )

                    CaptionB(
                        text = source,
                        color = Colors.White64,
                        modifier = Modifier.testTag("block_card_source_text")
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun FullBlockCardPreview() {
    AppThemeSurface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BlockCard(
                showWidgetTitle = true,
                showBlock = true,
                showTime = true,
                showDate = true,
                showTransactions = true,
                showSize = true,
                showSource = true,
                block = "761,405",
                time = "01:31:42 UTC",
                date = "11/2/2022",
                transactions = "2,175",
                size = "1,606Kb",
                source = "mempool.io",
            )

            BlockCard(
                showWidgetTitle = false,
                showBlock = true,
                showTime = true,
                showDate = true,
                showTransactions = true,
                showSize = true,
                showSource = false,
                block = "761,405",
                time = "01:31:42 UTC",
                date = "11/2/2022",
                transactions = "2,175",
                size = "1,606Kb",
                source = "mempool.io", // Source text is still provided but won't be shown
            )

            BlockCard(
                showWidgetTitle = true,
                showBlock = true,
                showTime = false,
                showDate = false,
                showTransactions = false,
                showSize = false,
                showSource = false,
                block = "761,405",
                time = "",
                date = "",
                transactions = "",
                size = "",
                source = "",
            )
        }
    }
}

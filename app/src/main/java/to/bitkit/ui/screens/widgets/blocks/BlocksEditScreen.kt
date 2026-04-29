package to.bitkit.ui.screens.widgets.blocks

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.widget.BlockModel
import to.bitkit.models.widget.BlocksPreferences
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun BlocksEditScreen(
    blocksViewModel: BlocksViewModel,
    onBack: () -> Unit,
    navigatePreview: () -> Unit,
) {
    val customPreference by blocksViewModel.customPreferences.collectAsStateWithLifecycle()
    val currentBlock by blocksViewModel.currentBlock.collectAsStateWithLifecycle()

    val blockPlaceholder = BlockModel(
        height = "",
        time = "",
        date = "",
        transactionCount = "",
        size = "",
        source = "",
        fees = "",
    )

    BlocksEditContent(
        onBack = onBack,
        blocksPreferences = customPreference,
        block = currentBlock ?: blockPlaceholder,
        onClickShowBlock = { blocksViewModel.toggleShowBlock() },
        onClickShowTime = { blocksViewModel.toggleShowTime() },
        onClickShowDate = { blocksViewModel.toggleShowDate() },
        onClickShowTransactions = { blocksViewModel.toggleShowTransactions() },
        onClickShowSize = { blocksViewModel.toggleShowSize() },
        onClickShowFees = { blocksViewModel.toggleShowFees() },
        onClickShowSource = { blocksViewModel.toggleShowSource() },
        onClickReset = { blocksViewModel.resetCustomPreferences() },
        onClickPreview = navigatePreview,
    )
}

@Composable
fun BlocksEditContent(
    onBack: () -> Unit,
    onClickShowBlock: () -> Unit,
    onClickShowTime: () -> Unit,
    onClickShowDate: () -> Unit,
    onClickShowTransactions: () -> Unit,
    onClickShowSize: () -> Unit,
    onClickShowFees: () -> Unit,
    onClickShowSource: () -> Unit,
    onClickReset: () -> Unit,
    onClickPreview: () -> Unit,
    blocksPreferences: BlocksPreferences,
    block: BlockModel,
) {
    ScreenColumn(
        noBackground = true,
        modifier = Modifier
            .background(Colors.Gray7)
            .testTag("blocks_edit_screen")
    ) {
        AppTopBar(
            titleText = stringResource(R.string.widgets__blocks__name),
            onBackClick = onBack,
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .testTag("WidgetEditScrollView")
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Caption13Up(
                text = stringResource(R.string.widgets__widget__data),
                color = Colors.White64,
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .testTag("data_section_header")
            )

            BlockEditOptionRow(
                leadingIcon = R.drawable.ic_cube,
                label = stringResource(R.string.widgets__blocks__field__block),
                value = block.height,
                isEnabled = blocksPreferences.showBlock,
                onClick = onClickShowBlock,
                testTagPrefix = "block",
            )

            BlockEditOptionRow(
                leadingIcon = R.drawable.ic_clock,
                label = stringResource(R.string.widgets__blocks__field__time),
                value = block.time,
                isEnabled = blocksPreferences.showTime,
                onClick = onClickShowTime,
                testTagPrefix = "time",
            )

            BlockEditOptionRow(
                leadingIcon = R.drawable.ic_calendar,
                label = stringResource(R.string.widgets__blocks__field__date),
                value = block.date,
                isEnabled = blocksPreferences.showDate,
                onClick = onClickShowDate,
                testTagPrefix = "date",
            )

            BlockEditOptionRow(
                leadingIcon = R.drawable.ic_transfer,
                label = stringResource(R.string.widgets__blocks__field__transactions),
                value = block.transactionCount,
                isEnabled = blocksPreferences.showTransactions,
                onClick = onClickShowTransactions,
                testTagPrefix = "transactions",
            )

            BlockEditOptionRow(
                leadingIcon = R.drawable.ic_file_text,
                label = stringResource(R.string.widgets__blocks__field__size),
                value = block.size,
                isEnabled = blocksPreferences.showSize,
                onClick = onClickShowSize,
                testTagPrefix = "size",
            )

            BlockEditOptionRow(
                leadingIcon = R.drawable.ic_coins,
                label = stringResource(R.string.widgets__blocks__field__fees),
                value = block.fees,
                isEnabled = blocksPreferences.showFees,
                onClick = onClickShowFees,
                testTagPrefix = "fees",
            )

            BlockEditOptionRow(
                leadingIcon = R.drawable.ic_globe,
                label = stringResource(R.string.widgets__widget__source),
                value = block.source,
                isEnabled = blocksPreferences.showSource,
                onClick = onClickShowSource,
                testTagPrefix = "source",
            )

            Spacer(modifier = Modifier.weight(1f))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .padding(vertical = 21.dp)
                    .fillMaxWidth()
                    .testTag("buttons_row")
            ) {
                SecondaryButton(
                    text = stringResource(R.string.common__reset),
                    enabled = blocksPreferences != BlocksPreferences(),
                    fullWidth = false,
                    onClick = onClickReset,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("WidgetEditReset")
                )

                PrimaryButton(
                    text = stringResource(R.string.common__preview),
                    enabled = blocksPreferences.run {
                        showBlock || showTime || showDate || showTransactions || showSize || showFees || showSource
                    },
                    fullWidth = false,
                    onClick = onClickPreview,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("WidgetEditPreview")
                )
            }
        }
    }
}

@Composable
private fun BlockEditOptionRow(
    @DrawableRes leadingIcon: Int,
    label: String,
    value: String,
    isEnabled: Boolean,
    onClick: () -> Unit,
    testTagPrefix: String,
) {
    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("${testTagPrefix}_setting_row")
        ) {
            Icon(
                painter = painterResource(leadingIcon),
                contentDescription = null,
                tint = Colors.Brand,
                modifier = Modifier
                    .size(20.dp)
                    .testTag("${testTagPrefix}_leading_icon")
            )

            BodySSB(
                text = label,
                color = Colors.White80,
                modifier = Modifier
                    .weight(1f)
                    .testTag("${testTagPrefix}_label")
            )

            if (value.isNotEmpty()) {
                BodySSB(
                    text = value,
                    color = Colors.White,
                    modifier = Modifier.testTag("${testTagPrefix}_text")
                )
            }

            IconButton(
                onClick = onClick,
                modifier = Modifier.testTag("${testTagPrefix}_toggle_button")
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_checkmark),
                    contentDescription = null,
                    tint = if (isEnabled) Colors.Brand else Colors.White50,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("${testTagPrefix}_toggle_icon")
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.testTag("${testTagPrefix}_divider")
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BlocksEditContent(
            onBack = {},
            onClickShowBlock = {},
            onClickShowTime = {},
            onClickShowDate = {},
            onClickShowTransactions = {},
            onClickShowSize = {},
            onClickShowFees = {},
            onClickShowSource = {},
            onClickReset = {},
            onClickPreview = {},
            blocksPreferences = BlocksPreferences(),
            block = BlockModel(
                height = "761,405",
                time = "01:31:42 UTC",
                date = "01/2/2022",
                transactionCount = "2,175",
                size = "1,606kB",
                source = "mempool.io",
                fees = "25 059 357",
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewWithSomeOptionsEnabled() {
    AppThemeSurface {
        BlocksEditContent(
            onBack = {},
            onClickShowBlock = {},
            onClickShowTime = {},
            onClickShowDate = {},
            onClickShowTransactions = {},
            onClickShowSize = {},
            onClickShowFees = {},
            onClickShowSource = {},
            onClickReset = {},
            onClickPreview = {},
            blocksPreferences = BlocksPreferences(
                showBlock = true,
                showTime = true,
                showDate = false,
                showTransactions = true,
                showSize = false,
                showFees = false,
                showSource = true,
            ),
            block = BlockModel(
                height = "",
                time = "",
                date = "",
                transactionCount = "",
                size = "",
                source = "",
                fees = "",
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewWithAllDisabled() {
    AppThemeSurface {
        BlocksEditContent(
            onBack = {},
            onClickShowBlock = {},
            onClickShowTime = {},
            onClickShowDate = {},
            onClickShowTransactions = {},
            onClickShowSize = {},
            onClickShowFees = {},
            onClickShowSource = {},
            onClickReset = {},
            onClickPreview = {},
            blocksPreferences = BlocksPreferences(
                showBlock = false,
                showTime = false,
                showDate = false,
                showTransactions = false,
                showSize = false,
                showFees = false,
                showSource = false,
            ),
            block = BlockModel(
                height = "",
                time = "",
                date = "",
                transactionCount = "",
                size = "",
                source = "",
                fees = "",
            ),
        )
    }
}

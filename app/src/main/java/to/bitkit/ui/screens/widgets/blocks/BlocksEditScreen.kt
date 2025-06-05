package to.bitkit.ui.screens.widgets.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun BlocksEditScreen(
    blocksViewModel: BlocksViewModel,
    onClose: () -> Unit,
    onBack: () -> Unit,
    navigatePreview: () -> Unit
) {
    val customPreference by blocksViewModel.customPreferences.collectAsStateWithLifecycle()
    val currentBlock by blocksViewModel.currentBlock.collectAsStateWithLifecycle()

    val blockPlaceholder = BlockModel(
        height = "",
        time = "",
        date = "",
        transactionCount = "",
        size = "",
        source = ""
    )

    BlocksEditContent(
        onClose = onClose,
        onBack = onBack,
        blocksPreferences = customPreference,
        block = currentBlock ?: blockPlaceholder,
        onClickShowBlock = { blocksViewModel.toggleShowBlock() },
        onClickShowTime = { blocksViewModel.toggleShowTime() },
        onClickShowDate = { blocksViewModel.toggleShowDate() },
        onClickShowTransactions = { blocksViewModel.toggleShowTransactions() },
        onClickShowSize = { blocksViewModel.toggleShowSize() },
        onClickShowSource = { blocksViewModel.toggleShowSource() },
        onClickReset = { blocksViewModel.resetCustomPreferences() },
        onClickPreview = navigatePreview,
    )
}

@Composable
fun BlocksEditContent(
    onClose: () -> Unit,
    onBack: () -> Unit,
    onClickShowBlock: () -> Unit,
    onClickShowTime: () -> Unit,
    onClickShowDate: () -> Unit,
    onClickShowTransactions: () -> Unit,
    onClickShowSize: () -> Unit,
    onClickShowSource: () -> Unit,
    onClickReset: () -> Unit,
    onClickPreview: () -> Unit,
    blocksPreferences: BlocksPreferences,
    block: BlockModel,
) {
    ScreenColumn(
        modifier = Modifier.testTag("blocks_edit_screen")
    ) {
        AppTopBar(
            titleText = stringResource(R.string.widgets__widget__edit),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClick = onClose) },
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .testTag("main_content")
        ) {
            Spacer(modifier = Modifier.height(26.dp))

            BodyM(
                text = stringResource(R.string.widgets__widget__edit_description).replace(
                    "{name}",
                    stringResource(R.string.widgets__blocks__name)
                ),
                color = Colors.White64,
                modifier = Modifier.testTag("edit_description")
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Block number toggle
            BlockEditOptionRow(
                label = "Block",
                value = block.height,
                isEnabled = blocksPreferences.showBlock,
                onClick = onClickShowBlock,
                testTagPrefix = "block"
            )

            // Time toggle
            BlockEditOptionRow(
                label = "Time",
                value = block.time,
                isEnabled = blocksPreferences.showTime,
                onClick = onClickShowTime,
                testTagPrefix = "time"
            )

            // Date toggle
            BlockEditOptionRow(
                label = "Date",
                value = block.date,
                isEnabled = blocksPreferences.showDate,
                onClick = onClickShowDate,
                testTagPrefix = "date"
            )

            // Transactions toggle
            BlockEditOptionRow(
                label = "Transactions",
                value = block.transactionCount,
                isEnabled = blocksPreferences.showTransactions,
                onClick = onClickShowTransactions,
                testTagPrefix = "transactions"
            )

            // Size toggle
            BlockEditOptionRow(
                label = "Size",
                value = block.size,
                isEnabled = blocksPreferences.showSize,
                onClick = onClickShowSize,
                testTagPrefix = "size"
            )

            // Source toggle
            BlockEditOptionRow(
                label = stringResource(R.string.widgets__widget__source),
                value = block.source,
                isEnabled = blocksPreferences.showSource,
                onClick = onClickShowSource,
                testTagPrefix = "source"
            )
        }

        Row(
            modifier = Modifier
                .padding(vertical = 21.dp, horizontal = 16.dp)
                .fillMaxWidth()
                .testTag("buttons_row"),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SecondaryButton(
                text = stringResource(R.string.common__reset),
                modifier = Modifier
                    .weight(1f)
                    .testTag("reset_button"),
                enabled = blocksPreferences != BlocksPreferences(),
                fullWidth = false,
                onClick = onClickReset
            )

            PrimaryButton(
                text = stringResource(R.string.common__preview),
                enabled = blocksPreferences.run { showBlock || showTime || showDate || showTransactions || showSize || showSource },
                modifier = Modifier
                    .weight(1f)
                    .testTag("preview_button"),
                fullWidth = false,
                onClick = onClickPreview
            )
        }
    }
}

@Composable
private fun BlockEditOptionRow(
    label: String,
    value: String,
    isEnabled: Boolean,
    onClick: () -> Unit,
    testTagPrefix: String
) {
    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(vertical = 21.dp)
                .fillMaxWidth()
                .testTag("${testTagPrefix}_setting_row")
        ) {
            BodySSB(
                text = label,
                color = Colors.White64,
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
                        .testTag("${testTagPrefix}_toggle_icon"),
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
            onClose = {},
            onBack = {},
            onClickShowBlock = {},
            onClickShowTime = {},
            onClickShowDate = {},
            onClickShowTransactions = {},
            onClickShowSize = {},
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
                source = "mempool.io"
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewWithSomeOptionsEnabled() {
    AppThemeSurface {
        BlocksEditContent(
            onClose = {},
            onBack = {},
            onClickShowBlock = {},
            onClickShowTime = {},
            onClickShowDate = {},
            onClickShowTransactions = {},
            onClickShowSize = {},
            onClickShowSource = {},
            onClickReset = {},
            onClickPreview = {},
            blocksPreferences = BlocksPreferences(
                showBlock = true,
                showTime = true,
                showDate = false,
                showTransactions = true,
                showSize = false,
                showSource = true
            ),
            block = BlockModel(
                height = "",
                time = "",
                date = "",
                transactionCount = "",
                size = "",
                source = ""
            ),
        )
    }
}


@Preview(showBackground = true)
@Composable
private fun PreviewWithAllDisabled() {
    AppThemeSurface {
        BlocksEditContent(
            onClose = {},
            onBack = {},
            onClickShowBlock = {},
            onClickShowTime = {},
            onClickShowDate = {},
            onClickShowTransactions = {},
            onClickShowSize = {},
            onClickShowSource = {},
            onClickReset = {},
            onClickPreview = {},
            blocksPreferences = BlocksPreferences(
                showBlock = false,
                showTime = false,
                showDate = false,
                showTransactions = false,
                showSize = false,
                showSource = false
            ),
            block = BlockModel(
                height = "",
                time = "",
                date = "",
                transactionCount = "",
                size = "",
                source = ""
            ),
        )
    }
}

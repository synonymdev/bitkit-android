package to.bitkit.ui.screens.widgets.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.widget.BlockModel
import to.bitkit.models.widget.BlocksPreferences
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Headline
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun BlocksPreviewScreen(
    blocksViewModel: BlocksViewModel,
    onClose: () -> Unit,
    onBack: () -> Unit,
    navigateEditWidget: () -> Unit,
) {
    val showWidgetTitles by blocksViewModel.showWidgetTitles.collectAsStateWithLifecycle()
    val customBlocksPreferences by blocksViewModel.customPreferences.collectAsStateWithLifecycle()
    val currentBlock by blocksViewModel.currentBlock.collectAsStateWithLifecycle()
    val isBlocksWidgetEnabled by blocksViewModel.isBlocksWidgetEnabled.collectAsStateWithLifecycle()

    BlocksPreviewContent(
        onClose = onClose,
        onBack = onBack,
        isBlocksWidgetEnabled = isBlocksWidgetEnabled,
        blocksPreferences = customBlocksPreferences,
        showWidgetTitles = showWidgetTitles,
        block = currentBlock,
        onClickEdit = navigateEditWidget,
        onClickDelete = {
            blocksViewModel.removeWidget()
            onClose()
        },
        onClickSave = {
            blocksViewModel.savePreferences()
            onClose()
        },
    )
}

@Composable
fun BlocksPreviewContent(
    onClose: () -> Unit,
    onBack: () -> Unit,
    onClickEdit: () -> Unit,
    onClickDelete: () -> Unit,
    onClickSave: () -> Unit,
    showWidgetTitles: Boolean,
    isBlocksWidgetEnabled: Boolean,
    blocksPreferences: BlocksPreferences,
    block: BlockModel?,
) {
    ScreenColumn(
        modifier = Modifier.testTag("blocks_preview_screen")
    ) {
        AppTopBar(
            titleText = stringResource(R.string.widgets__widget__nav_title),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClick = onClose) },
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .testTag("main_content")
        ) {
            Spacer(modifier = Modifier.height(26.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("header_row"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Headline(
                    text = AnnotatedString(stringResource(R.string.widgets__blocks__name)),
                    modifier = Modifier
                        .width(200.dp)
                        .testTag("widget_title")
                )
                Icon(
                    painter = painterResource(R.drawable.widget_cube),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .size(64.dp)
                        .testTag("widget_icon")
                )
            }

            BodyM(
                text = stringResource(R.string.widgets__blocks__description),
                color = Colors.White64,
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .testTag("widget_description")
            )

            HorizontalDivider(
                modifier = Modifier.testTag("divider")
            )

            SettingsButtonRow(
                title = stringResource(R.string.widgets__widget__edit),
                value = SettingsButtonValue.StringValue(
                    if (blocksPreferences == BlocksPreferences()) {
                        stringResource(R.string.widgets__widget__edit_default)
                    } else {
                        stringResource(R.string.widgets__widget__edit_custom)
                    }
                ),
                onClick = onClickEdit,
                modifier = Modifier.testTag("edit_settings_button")
            )

            Spacer(modifier = Modifier.weight(1f))

            Text13Up(
                stringResource(R.string.common__preview),
                color = Colors.White64,
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .testTag("preview_label")
            )

            block?.let {
                BlockCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("block_card"),
                    showWidgetTitle = showWidgetTitles,
                    showBlock = blocksPreferences.showBlock,
                    showTime = blocksPreferences.showTime,
                    showDate = blocksPreferences.showDate,
                    showTransactions = blocksPreferences.showTransactions,
                    showSize = blocksPreferences.showSize,
                    showSource = blocksPreferences.showSource,
                    block = block.height,
                    time = block.time,
                    date = block.date,
                    transactions = block.transactionCount,
                    size = block.size,
                    source = block.source,
                )
            }

            Row(
                modifier = Modifier
                    .padding(vertical = 21.dp)
                    .fillMaxWidth()
                    .testTag("buttons_row"),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isBlocksWidgetEnabled) {
                    SecondaryButton(
                        text = stringResource(R.string.common__delete),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("delete_button"),
                        fullWidth = false,
                        onClick = onClickDelete
                    )
                }

                PrimaryButton(
                    text = stringResource(R.string.common__save),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("save_button"),
                    fullWidth = false,
                    onClick = onClickSave
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BlocksPreviewContent(
            onClose = {},
            onBack = {},
            showWidgetTitles = true,
            onClickEdit = {},
            onClickDelete = {},
            onClickSave = {},
            blocksPreferences = BlocksPreferences(),
            block = BlockModel(
                height = "123456",
                time = "01:31:42 UTC",
                date = "2023-01-01",
                transactionCount = "2,175",
                size = "1,606kB",
                source = "mempool.space"
            ),
            isBlocksWidgetEnabled = false
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview2() {
    AppThemeSurface {
        BlocksPreviewContent(
            onClose = {},
            onBack = {},
            showWidgetTitles = false,
            onClickEdit = {},
            onClickDelete = {},
            onClickSave = {},
            blocksPreferences = BlocksPreferences(
                showBlock = true,
                showTime = true,
                showDate = false,
                showTransactions = true,
                showSize = false,
                showSource = true
            ),
            block = BlockModel(
                height = "123456",
                time = "01:31:42 UTC",
                date = "2023-01-01",
                transactionCount = "2,175",
                size = "1,606kB",
                source = "mempool.space"
            ),
            isBlocksWidgetEnabled = true
        )
    }
}

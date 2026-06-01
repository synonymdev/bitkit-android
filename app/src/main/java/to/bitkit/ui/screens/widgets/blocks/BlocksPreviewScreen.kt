package to.bitkit.ui.screens.widgets.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.widget.BlockModel
import to.bitkit.models.widget.BlocksPreferences
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.widgets.components.WidgetSizeCarousel
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun BlocksPreviewScreen(
    blocksViewModel: BlocksViewModel,
    onClose: () -> Unit,
    onBack: () -> Unit,
    navigateEditWidget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val customBlocksPreferences by blocksViewModel.customPreferences.collectAsStateWithLifecycle()
    val currentBlock by blocksViewModel.currentBlock.collectAsStateWithLifecycle()
    val isBlocksWidgetEnabled by blocksViewModel.isBlocksWidgetEnabled.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        blocksViewModel.refreshOnDisplay()
    }

    Content(
        onBack = onBack,
        isBlocksWidgetEnabled = isBlocksWidgetEnabled,
        blocksPreferences = customBlocksPreferences,
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
        modifier = modifier
    )
}

@Composable
private fun Content(
    onBack: () -> Unit,
    onClickEdit: () -> Unit,
    onClickDelete: () -> Unit,
    onClickSave: () -> Unit,
    isBlocksWidgetEnabled: Boolean,
    blocksPreferences: BlocksPreferences,
    block: BlockModel?,
    modifier: Modifier = Modifier,
) {
    ScreenColumn(
        modifier = modifier.testTag("blocks_preview_screen")
    ) {
        AppTopBar(
            titleText = stringResource(R.string.widgets__blocks__name),
            onBackClick = onBack,
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .weight(1f)
        ) {
            VerticalSpacer(16.dp)

            BodyM(
                text = stringResource(R.string.widgets__blocks__description),
                color = Colors.White64,
                modifier = Modifier.testTag("widget_description")
            )

            VerticalSpacer(16.dp)

            HorizontalDivider(
                modifier = Modifier.testTag("divider")
            )

            SettingsButtonRow(
                title = stringResource(R.string.widgets__widget__settings),
                value = SettingsButtonValue.StringValue(
                    if (blocksPreferences == BlocksPreferences()) {
                        stringResource(R.string.widgets__widget__edit_default)
                    } else {
                        stringResource(R.string.widgets__widget__edit_custom)
                    },
                ),
                onClick = onClickEdit,
                modifier = Modifier.testTag("WidgetEdit")
            )

            block?.let {
                WidgetSizeCarousel(
                    smallContent = {
                        BlockCardSmall(
                            preferences = blocksPreferences,
                            block = it,
                            modifier = Modifier.testTag("block_card_small")
                        )
                    },
                    wideContent = {
                        BlockCard(
                            preferences = blocksPreferences,
                            block = it,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("block_card_wide")
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("blocks_preview_carousel")
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp,
                    top = 22.dp,
                )
                .fillMaxWidth()
                .testTag("buttons_row")
        ) {
            if (isBlocksWidgetEnabled) {
                SecondaryButton(
                    text = stringResource(R.string.common__delete),
                    fullWidth = false,
                    onClick = onClickDelete,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("WidgetDelete")
                )
            }

            PrimaryButton(
                text = stringResource(R.string.widgets__widget__save),
                fullWidth = false,
                onClick = onClickSave,
                modifier = Modifier
                    .weight(1f)
                    .testTag("WidgetSave")
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            onBack = {},
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
                fees = "25 059 357",
            ),
            isBlocksWidgetEnabled = false,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview2() {
    AppThemeSurface {
        Content(
            onBack = {},
            onClickEdit = {},
            onClickDelete = {},
            onClickSave = {},
            blocksPreferences = BlocksPreferences(
                showBlock = true,
                showTime = true,
                showDate = false,
                showTransactions = true,
                showSize = false,
            ),
            block = BlockModel(
                height = "123456",
                time = "01:31:42 UTC",
                date = "2023-01-01",
                transactionCount = "2,175",
                size = "1,606kB",
                fees = "25 059 357",
            ),
            isBlocksWidgetEnabled = true,
        )
    }
}

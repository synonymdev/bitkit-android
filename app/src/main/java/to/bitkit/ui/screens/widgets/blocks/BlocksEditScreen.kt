package to.bitkit.ui.screens.widgets.blocks

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import to.bitkit.models.widget.BlocksWidgetField
import to.bitkit.models.widget.enabledFields
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun BlocksEditScreen(
    blocksViewModel: BlocksViewModel,
    onBack: () -> Unit,
    navigatePreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val customPreference by blocksViewModel.customPreferences.collectAsStateWithLifecycle()
    val currentBlock by blocksViewModel.currentBlock.collectAsStateWithLifecycle()

    val blockPlaceholder = BlockModel(
        height = "",
        time = "",
        date = "",
        transactionCount = "",
        size = "",
        fees = "",
    )

    Content(
        onBack = onBack,
        blocksPreferences = customPreference,
        block = currentBlock ?: blockPlaceholder,
        onToggleField = { blocksViewModel.toggleField(it) },
        onClickReset = { blocksViewModel.resetCustomPreferences() },
        onClickPreview = navigatePreview,
        modifier = modifier
    )
}

@Composable
private fun Content(
    onBack: () -> Unit,
    onToggleField: (BlocksWidgetField) -> Unit,
    onClickReset: () -> Unit,
    onClickPreview: () -> Unit,
    blocksPreferences: BlocksPreferences,
    block: BlockModel,
    modifier: Modifier = Modifier,
) {
    ScreenColumn(
        modifier = modifier.testTag("blocks_edit_screen")
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
            VerticalSpacer(16.dp)

            Caption13Up(
                text = stringResource(R.string.widgets__blocks__data_header),
                color = Colors.White64,
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .testTag("data_section_header")
            )

            BlocksWidgetField.entries.forEach { field ->
                BlockEditOptionRow(
                    leadingIcon = field.icon,
                    label = stringResource(field.labelRes),
                    value = field.value(block),
                    isEnabled = field.isEnabled(blocksPreferences),
                    onClick = { onToggleField(field) },
                    testTagPrefix = field.testTag,
                )
            }

            FillHeight()

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
                    enabled = blocksPreferences.enabledFields().isNotEmpty(),
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
                    tint = if (isEnabled) Colors.Brand else Colors.Gray3,
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
        Content(
            onBack = {},
            onToggleField = {},
            onClickReset = {},
            onClickPreview = {},
            blocksPreferences = BlocksPreferences(),
            block = BlockModel(
                height = "761,405",
                time = "01:31:42 UTC",
                date = "01/2/2022",
                transactionCount = "2,175",
                size = "1,606kB",
                fees = "25 059 357",
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewWithSomeOptionsEnabled() {
    AppThemeSurface {
        Content(
            onBack = {},
            onToggleField = {},
            onClickReset = {},
            onClickPreview = {},
            blocksPreferences = BlocksPreferences(
                showBlock = true,
                showTime = true,
                showDate = false,
                showTransactions = true,
                showSize = false,
                showFees = false,
            ),
            block = BlockModel(
                height = "",
                time = "",
                date = "",
                transactionCount = "",
                size = "",
                fees = "",
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewWithAllDisabled() {
    AppThemeSurface {
        Content(
            onBack = {},
            onToggleField = {},
            onClickReset = {},
            onClickPreview = {},
            blocksPreferences = BlocksPreferences(
                showBlock = false,
                showTime = false,
                showDate = false,
                showTransactions = false,
                showSize = false,
                showFees = false,
            ),
            block = BlockModel(
                height = "",
                time = "",
                date = "",
                transactionCount = "",
                size = "",
                fees = "",
            ),
        )
    }
}

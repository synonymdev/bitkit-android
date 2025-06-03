package to.bitkit.ui.screens.widgets.facts

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
import to.bitkit.models.widget.FactsPreferences
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
fun FactsPreviewScreen(
    factsViewModel: FactsViewModel,
    onClose: () -> Unit,
    onBack: () -> Unit,
    navigateEditWidget: () -> Unit,
) {
    val showWidgetTitles by factsViewModel.showWidgetTitles.collectAsStateWithLifecycle()
    val customFactsPreferences by factsViewModel.customPreferences.collectAsStateWithLifecycle()
    val fact by factsViewModel.currentFact.collectAsStateWithLifecycle()
    val isFactsWidgetEnabled by factsViewModel.isFactsWidgetEnabled.collectAsStateWithLifecycle()

    FactsPreviewContent(
        onClose = onClose,
        onBack = onBack,
        isFactsWidgetEnabled = isFactsWidgetEnabled,
        factsPreferences = customFactsPreferences,
        showWidgetTitles = showWidgetTitles,
        fact = fact,
        onClickEdit = navigateEditWidget,
        onClickDelete = {
            factsViewModel.removeWidget()
            onClose()
        },
        onClickSave = {
            factsViewModel.savePreferences()
            onClose()
        },
    )
}

@Composable
fun FactsPreviewContent(
    onClose: () -> Unit,
    onBack: () -> Unit,
    onClickEdit: () -> Unit,
    onClickDelete: () -> Unit,
    onClickSave: () -> Unit,
    showWidgetTitles: Boolean,
    isFactsWidgetEnabled: Boolean,
    factsPreferences: FactsPreferences,
    fact: String
) {
    ScreenColumn(
        modifier = Modifier.testTag("facts_preview_screen")
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
                    text = AnnotatedString(stringResource(R.string.widgets__facts__name)),
                    modifier = Modifier
                        .width(200.dp)
                        .testTag("widget_title")
                )
                Icon(
                    painter = painterResource(R.drawable.widget_lightbulb),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .size(64.dp)
                        .testTag("widget_icon")
                )
            }

            BodyM(
                text = stringResource(R.string.widgets__facts__description),
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
                    if (factsPreferences == FactsPreferences()) {
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

            FactsCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("fact_card"),
                showWidgetTitle = showWidgetTitles,
                showSource = factsPreferences.showSource,
                headline = fact,
            )

            Row(
                modifier = Modifier
                    .padding(vertical = 21.dp)
                    .fillMaxWidth()
                    .testTag("buttons_row"),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isFactsWidgetEnabled) {
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
        FactsPreviewContent(
            onClose = {},
            onBack = {},
            showWidgetTitles = true,
            onClickEdit = {},
            onClickDelete = {},
            onClickSave = {},
            factsPreferences = FactsPreferences(),
            fact = "Bitcoin doesn’t need your personal information",
            isFactsWidgetEnabled = false
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview2() {
    AppThemeSurface {
        FactsPreviewContent(
            onClose = {},
            onBack = {},
            showWidgetTitles = false,
            onClickEdit = {},
            onClickDelete = {},
            onClickSave = {},
            factsPreferences = FactsPreferences(showSource = true),
            fact = "Bitcoin doesn’t need your personal information",
            isFactsWidgetEnabled = true
        )
    }
}

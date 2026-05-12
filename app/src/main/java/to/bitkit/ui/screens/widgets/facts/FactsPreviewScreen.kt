package to.bitkit.ui.screens.widgets.facts

import androidx.compose.foundation.background
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
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.widgets.components.WidgetSizeCarousel
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun FactsPreviewScreen(
    factsViewModel: FactsViewModel,
    onClose: () -> Unit,
    onBack: () -> Unit,
) {
    val fact by factsViewModel.currentFact.collectAsStateWithLifecycle()
    val isFactsWidgetEnabled by factsViewModel.isFactsWidgetEnabled.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        factsViewModel.refreshOnDisplay()
    }

    FactsPreviewContent(
        onBack = onBack,
        isFactsWidgetEnabled = isFactsWidgetEnabled,
        fact = fact,
        onClickDelete = {
            factsViewModel.removeWidget()
            onClose()
        },
        onClickSave = {
            factsViewModel.saveWidget()
            onClose()
        },
    )
}

@Composable
fun FactsPreviewContent(
    onBack: () -> Unit,
    onClickDelete: () -> Unit,
    onClickSave: () -> Unit,
    isFactsWidgetEnabled: Boolean,
    fact: String,
) {
    ScreenColumn(
        noBackground = true,
        modifier = Modifier
            .background(Colors.Gray7)
            .testTag("facts_preview_screen")
    ) {
        AppTopBar(
            titleText = stringResource(R.string.widgets__facts__name),
            onBackClick = onBack,
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .weight(1f)
        ) {
            VerticalSpacer(16.dp)

            BodyM(
                text = stringResource(R.string.widgets__facts__description),
                color = Colors.White64,
                modifier = Modifier.testTag("widget_description")
            )

            VerticalSpacer(16.dp)

            HorizontalDivider(
                modifier = Modifier.testTag("divider")
            )

            WidgetSizeCarousel(
                smallContent = {
                    FactsCardSmall(
                        headline = fact,
                        modifier = Modifier.testTag("facts_card_small")
                    )
                },
                wideContent = {
                    FactsCard(
                        headline = fact,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("facts_card_wide")
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("facts_preview_carousel")
            )
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
            if (isFactsWidgetEnabled) {
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
        FactsPreviewContent(
            onBack = {},
            onClickDelete = {},
            onClickSave = {},
            fact = "Bitcoin doesn’t need your personal information",
            isFactsWidgetEnabled = false,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview2() {
    AppThemeSurface {
        FactsPreviewContent(
            onBack = {},
            onClickDelete = {},
            onClickSave = {},
            fact = "Bitcoin doesn’t need your personal information",
            isFactsWidgetEnabled = true,
        )
    }
}

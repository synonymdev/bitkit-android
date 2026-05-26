package to.bitkit.ui.screens.widgets.suggestions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.R
import to.bitkit.ext.spaceToNewline
import to.bitkit.models.Suggestion
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.Headline
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.SuggestionCard
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.screens.widgets.components.widgetSheetContent
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.Insets

private val previewSuggestions = persistentListOf(
    Suggestion.BACK_UP,
    Suggestion.SECURE,
    Suggestion.LIGHTNING,
    Suggestion.SUPPORT,
)

@Composable
fun SuggestionsPreviewScreen(
    suggestionsViewModel: SuggestionsViewModel,
    onClose: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSuggestionsWidgetEnabled by suggestionsViewModel.isSuggestionsWidgetEnabled
        .collectAsStateWithLifecycle()

    Content(
        onBack = onBack,
        isSuggestionsWidgetEnabled = isSuggestionsWidgetEnabled,
        onClickDelete = {
            suggestionsViewModel.removeWidget(onComplete = onClose)
        },
        onClickSave = {
            suggestionsViewModel.addWidget(onComplete = onClose)
        },
        modifier = modifier
    )
}

@Composable
private fun Content(
    isSuggestionsWidgetEnabled: Boolean,
    onBack: () -> Unit,
    onClickDelete: () -> Unit,
    onClickSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widgetSheetContent()
            .testTag("suggestions_preview_screen")
    ) {
        SheetTopBar(
            titleText = stringResource(R.string.widgets__suggestions__name),
            onBack = onBack,
        )

        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            VerticalSpacer(26.dp)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Headline(
                    text = AnnotatedString(stringResource(R.string.widgets__suggestions__name).spaceToNewline()),
                )
                Icon(
                    painter = painterResource(R.drawable.widget_suggestions),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(64.dp)
                )
            }

            BodyM(
                text = stringResource(R.string.widgets__suggestions__description),
                color = Colors.White64,
                modifier = Modifier.padding(vertical = 16.dp)
            )

            HorizontalDivider()

            FillHeight()

            Text13Up(
                stringResource(R.string.common__preview),
                color = Colors.White64,
                modifier = Modifier.padding(vertical = 16.dp)
            )

            SuggestionsPreviewGrid(
                onSuggestionClick = {},
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .padding(
                        top = 21.dp,
                        bottom = Insets.Bottom + 21.dp,
                    )
                    .fillMaxWidth()
            ) {
                if (isSuggestionsWidgetEnabled) {
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
                    text = stringResource(R.string.common__save),
                    fullWidth = false,
                    onClick = onClickSave,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("WidgetSave")
                )
            }
        }
    }
}

@Composable
fun SuggestionsPreviewGrid(
    onSuggestionClick: (Suggestion) -> Unit,
    modifier: Modifier = Modifier,
    suggestions: ImmutableList<Suggestion> = previewSuggestions,
) {
    val rows = (suggestions.size + 1) / 2

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val cardSize = (maxWidth - 16.dp) / 2
        val gridHeight = (cardSize * rows) + (16.dp * (rows - 1))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            userScrollEnabled = false,
            modifier = Modifier
                .fillMaxWidth()
                .height(gridHeight)
        ) {
            items(
                items = suggestions,
                key = { it.name },
            ) { item ->
                SuggestionCard(
                    gradientColor = item.color,
                    title = stringResource(item.title),
                    description = stringResource(item.description),
                    icon = item.icon,
                    disableGlow = true,
                    onClick = { onSuggestionClick(item) },
                    modifier = Modifier.testTag("Suggestion-${item.name.lowercase()}")
                )
            }
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            onBack = {},
            isSuggestionsWidgetEnabled = false,
            onClickDelete = {},
            onClickSave = {},
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewWithDelete() {
    AppThemeSurface {
        Content(
            onBack = {},
            isSuggestionsWidgetEnabled = true,
            onClickDelete = {},
            onClickSave = {},
        )
    }
}

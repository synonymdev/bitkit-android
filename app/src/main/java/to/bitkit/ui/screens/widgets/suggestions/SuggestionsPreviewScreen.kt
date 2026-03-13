package to.bitkit.ui.screens.widgets.suggestions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.Suggestion
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.Headline
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.SuggestionCard
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

private val previewSuggestions = listOf(Suggestion.BUY, Suggestion.BACK_UP)

@Composable
fun SuggestionsPreviewScreen(
    suggestionsViewModel: SuggestionsViewModel,
    onClose: () -> Unit,
    onBack: () -> Unit,
) {
    val isSuggestionsWidgetEnabled by suggestionsViewModel.isSuggestionsWidgetEnabled
        .collectAsStateWithLifecycle()

    Content(
        onBack = onBack,
        isSuggestionsWidgetEnabled = isSuggestionsWidgetEnabled,
        onClickDelete = {
            suggestionsViewModel.removeWidget()
            onClose()
        },
        onClickSave = {
            suggestionsViewModel.addWidget()
            onClose()
        },
    )
}

@Composable
private fun Content(
    onBack: () -> Unit,
    isSuggestionsWidgetEnabled: Boolean,
    onClickDelete: () -> Unit,
    onClickSave: () -> Unit,
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.widgets__widget__nav_title),
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )

        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            VerticalSpacer(26.dp)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Headline(
                    text = AnnotatedString(stringResource(R.string.widgets__suggestions__name).replace(" ", "\n")),
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

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                userScrollEnabled = false,
                modifier = Modifier.fillMaxWidth()
            ) {
                items(
                    items = previewSuggestions,
                    key = { it.name }
                ) { item ->
                    SuggestionCard(
                        gradientColor = item.color,
                        title = stringResource(item.title),
                        description = stringResource(item.description),
                        icon = item.icon,
                        disableGlow = true,
                        onClick = {},
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .padding(vertical = 21.dp)
                    .fillMaxWidth(),
            ) {
                if (isSuggestionsWidgetEnabled) {
                    SecondaryButton(
                        text = stringResource(R.string.common__delete),
                        fullWidth = false,
                        onClick = onClickDelete,
                        modifier = Modifier.weight(1f),
                    )
                }

                PrimaryButton(
                    text = stringResource(R.string.common__save),
                    fullWidth = false,
                    onClick = onClickSave,
                    modifier = Modifier.weight(1f),
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

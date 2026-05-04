package to.bitkit.appwidget.config

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.models.widget.HeadlinePreferences
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.Title
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.Colors

@Composable
internal fun HeadlinesConfigContent(
    state: AppWidgetConfigUiState,
    onToggleSource: () -> Unit,
    onToggleTime: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val prefs = state.headlinePreferences
    val previewArticle = state.previewArticle

    ScreenColumn(
        noBackground = true,
        modifier = Modifier.background(Colors.Gray7)
    ) {
        AppTopBar(
            titleText = stringResource(R.string.widgets__news__name),
            onBackClick = onCancel,
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            VerticalSpacer(16.dp)

            Caption13Up(
                text = stringResource(R.string.widgets__widget__content),
                color = Colors.White64,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            ToggleRow(
                content = {
                    Title(
                        text = previewArticle.title,
                        modifier = Modifier.weight(1f)
                    )
                },
                isEnabled = true,
                onToggle = {},
                toggleEnabled = false,
            )
            HorizontalDivider()

            ToggleRow(
                content = {
                    BodySSB(
                        text = previewArticle.publisher,
                        color = Colors.Brand,
                        modifier = Modifier.weight(1f)
                    )
                },
                isEnabled = prefs.showSource,
                onToggle = onToggleSource,
            )
            HorizontalDivider()

            ToggleRow(
                content = {
                    BodySSB(
                        text = previewArticle.timeAgo,
                        color = Colors.White64,
                        modifier = Modifier.weight(1f)
                    )
                },
                isEnabled = prefs.showTime,
                onToggle = onToggleTime,
            )
            HorizontalDivider()
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            SecondaryButton(
                text = stringResource(R.string.common__reset),
                enabled = prefs != HeadlinePreferences(),
                fullWidth = false,
                onClick = onReset,
                modifier = Modifier.weight(1f)
            )
            PrimaryButton(
                text = stringResource(R.string.common__save),
                isLoading = state.isSaving,
                enabled = !state.isSaving,
                fullWidth = false,
                onClick = onSave,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ToggleRow(
    content: @Composable RowScope.() -> Unit,
    isEnabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    toggleEnabled: Boolean = true,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(vertical = 8.dp)
            .fillMaxWidth()
    ) {
        content()
        IconButton(
            onClick = onToggle,
            enabled = toggleEnabled,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_checkmark),
                contentDescription = null,
                tint = if (isEnabled) Colors.Brand else Colors.White50,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

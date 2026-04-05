package to.bitkit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.R
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

private val LINK_SUGGESTIONS = persistentListOf(
    "Email", "Phone", "Website", "Twitter", "Telegram", "Instagram",
    "Facebook", "LinkedIn", "Github", "Calendly", "Vimeo", "YouTube",
    "Twitch", "Pinterest", "TikTok", "Spotify",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddLinkSheet(
    onDismiss: () -> Unit,
    onSave: (label: String, url: String) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var showSuggestions by remember { mutableStateOf(false) }

    BottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        if (showSuggestions) {
            SuggestionsContent(
                title = stringResource(R.string.profile__suggestions_to_add),
                suggestions = LINK_SUGGESTIONS,
                onSelect = {
                    label = it
                    showSuggestions = false
                },
                onBack = { showSuggestions = false },
            )
        } else {
            LinkFormContent(
                label = label,
                url = url,
                onLabelChange = { label = it },
                onUrlChange = { url = it },
                onShowSuggestions = { showSuggestions = true },
                onSave = { onSave(label, url) },
                isSaveEnabled = label.isNotBlank() && url.isNotBlank(),
            )
        }
    }
}

@Composable
private fun LinkFormContent(
    label: String,
    url: String,
    onLabelChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onShowSuggestions: () -> Unit,
    onSave: () -> Unit,
    isSaveEnabled: Boolean,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        SheetTopBar(titleText = stringResource(R.string.profile__add_link))
        VerticalSpacer(16.dp)
        Text13Up(text = stringResource(R.string.profile__add_link_label))
        VerticalSpacer(8.dp)
        TextInput(
            value = label,
            onValueChange = onLabelChange,
            placeholder = stringResource(R.string.profile__add_link_label_placeholder),
            trailingIcon = { SuggestionsButton(onClick = onShowSuggestions) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        VerticalSpacer(16.dp)
        Text13Up(text = stringResource(R.string.profile__add_link_url))
        VerticalSpacer(8.dp)
        TextInput(
            value = url,
            onValueChange = onUrlChange,
            placeholder = stringResource(R.string.profile__add_link_url_placeholder),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        VerticalSpacer(8.dp)
        BodyS(
            text = stringResource(R.string.profile__add_link_note),
            color = Colors.White64,
        )
        VerticalSpacer(24.dp)
        PrimaryButton(
            text = stringResource(R.string.common__save),
            onClick = onSave,
            enabled = isSaveEnabled,
        )
        VerticalSpacer(16.dp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SuggestionsContent(
    title: String,
    suggestions: ImmutableList<String>,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        SheetTopBar(titleText = title, onBack = onBack)
        VerticalSpacer(16.dp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            suggestions.forEach { suggestion ->
                TagButton(text = suggestion, onClick = { onSelect(suggestion) })
            }
        }
        VerticalSpacer(24.dp)
    }
}

@Composable
private fun SuggestionsButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(
            painter = painterResource(R.drawable.ic_lightbulb),
            contentDescription = null,
            tint = Colors.PubkyGreen,
            modifier = Modifier.padding(end = 4.dp),
        )
        BodySSB(
            text = stringResource(R.string.profile__suggestions),
            color = Colors.PubkyGreen,
        )
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        LinkFormContent(
            label = "Twitter",
            url = "@satoshinakamoto",
            onLabelChange = {},
            onUrlChange = {},
            onShowSuggestions = {},
            onSave = {},
            isSaveEnabled = true,
        )
    }
}

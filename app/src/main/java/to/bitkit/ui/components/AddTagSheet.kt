package to.bitkit.ui.components

import androidx.compose.foundation.layout.Column
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
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.R
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

private val TAG_SUGGESTIONS = persistentListOf(
    "Developer", "Designer", "Founder", "CEO", "CTO", "CDO", "CFO",
    "Serious", "Funny", "Candid",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTagSheet(
    onDismiss: () -> Unit,
    onSave: (tag: String) -> Unit,
) {
    var tag by remember { mutableStateOf("") }
    var showSuggestions by remember { mutableStateOf(false) }

    BottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        if (showSuggestions) {
            SuggestionsContent(
                title = stringResource(R.string.profile__suggestions_to_add),
                suggestions = TAG_SUGGESTIONS,
                onSelect = {
                    tag = it
                    showSuggestions = false
                },
                onBack = { showSuggestions = false },
            )
        } else {
            TagFormContent(
                tag = tag,
                onTagChange = { tag = it },
                onShowSuggestions = { showSuggestions = true },
                onSave = { onSave(tag) },
                isSaveEnabled = tag.isNotBlank(),
            )
        }
    }
}

@Composable
private fun TagFormContent(
    tag: String,
    onTagChange: (String) -> Unit,
    onShowSuggestions: () -> Unit,
    onSave: () -> Unit,
    isSaveEnabled: Boolean,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        SheetTopBar(titleText = stringResource(R.string.profile__add_tag))
        VerticalSpacer(16.dp)
        Text13Up(text = stringResource(R.string.profile__add_tag_label))
        VerticalSpacer(8.dp)
        TextInput(
            value = tag,
            onValueChange = onTagChange,
            placeholder = stringResource(R.string.profile__add_tag_placeholder),
            trailingIcon = {
                TextButton(onClick = onShowSuggestions) {
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
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
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

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        TagFormContent(
            tag = "Founder",
            onTagChange = {},
            onShowSuggestions = {},
            onSave = {},
            isSaveEnabled = true,
        )
    }
}

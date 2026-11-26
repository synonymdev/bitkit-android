package to.bitkit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import to.bitkit.R
import to.bitkit.models.PubkyAppLinkError
import to.bitkit.models.pubkyAppLinkError
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

private val LINK_SUGGESTIONS = persistentListOf(
    "Email", "Phone", "Website", "Twitter", "Telegram", "Instagram",
    "Facebook", "LinkedIn", "Github", "Calendly", "Vimeo", "YouTube",
    "Twitch", "Pinterest", "TikTok", "Spotify",
)

/** Suggestions whose links are not web links, which social v1 profiles reject. */
private val NON_WEB_SUGGESTIONS = setOf("Email", "Phone")

/** [webLinksOnly] marks a profile link, which pubky.app publishes: it must be a web link that meets its rules. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddLinkSheet(
    onDismiss: () -> Unit,
    onSave: (label: String, url: String) -> Unit,
    webLinksOnly: Boolean = false,
) {
    var label by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var showSuggestions by remember { mutableStateOf(false) }
    val linkError = if (webLinksOnly) pubkyAppLinkError(label, url, webOnly = true) else null
    val linkErrorText = linkError?.let { pubkyAppLinkErrorText(it) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val dismissKeyboard = {
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    BottomSheet(
        onDismissRequest = {
            dismissKeyboard()
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.imePadding()
    ) {
        if (showSuggestions) {
            SuggestionsContent(
                title = stringResource(R.string.profile__suggestions_to_add),
                suggestions = if (webLinksOnly) {
                    LINK_SUGGESTIONS.filterNot { it in NON_WEB_SUGGESTIONS }.toImmutableList()
                } else {
                    LINK_SUGGESTIONS
                },
                onSelect = {
                    dismissKeyboard()
                    label = it
                    showSuggestions = false
                },
                onBack = { showSuggestions = false },
            )
        } else {
            LinkFormContent(
                label = label,
                url = url,
                onLabelChange = { label = it.filterNot(Char::isISOControl) },
                onUrlChange = { url = it },
                onShowSuggestions = { showSuggestions = true },
                onSave = {
                    dismissKeyboard()
                    onSave(label, url)
                },
                labelError = linkErrorText.takeIf { linkError == PubkyAppLinkError.LABEL_TOO_LONG },
                urlError = linkErrorText.takeIf { linkError?.isLabelError == false },
                isSaveEnabled = label.isNotBlank() && url.isNotBlank() && linkError == null,
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
    labelError: String? = null,
    urlError: String? = null,
) {
    Column(
        modifier = Modifier
            .sheetHeight(SheetSize.COMPACT, isModal = true)
            .gradientBackground()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
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
            isError = labelError != null,
            supportingText = labelError?.let { { FieldError(text = it, testTag = "AddLinkLabelError") } },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("AddLinkLabel"),
        )
        VerticalSpacer(16.dp)
        Text13Up(text = stringResource(R.string.profile__add_link_url))
        VerticalSpacer(8.dp)
        TextInput(
            value = url,
            onValueChange = onUrlChange,
            placeholder = stringResource(R.string.profile__add_link_url_placeholder),
            singleLine = true,
            isError = urlError != null,
            supportingText = urlError?.let { { FieldError(text = it, testTag = "AddLinkUrlError") } },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("AddLinkUrl"),
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
            modifier = Modifier.testTag("AddLinkSave"),
        )
        VerticalSpacer(16.dp)
    }
}

@Composable
fun pubkyAppLinkErrorText(error: PubkyAppLinkError): String = when (error) {
    PubkyAppLinkError.LABEL_EMPTY -> stringResource(R.string.profile__link_error_label_stored)
    PubkyAppLinkError.LABEL_TOO_LONG -> stringResource(R.string.profile__link_error_label_length)
    PubkyAppLinkError.URL_TOO_LONG -> stringResource(R.string.profile__link_error_url_length)
    PubkyAppLinkError.URL_INVALID -> stringResource(R.string.profile__link_error_url_invalid)
}

@Composable
private fun FieldError(text: String, testTag: String) {
    BodyS(
        text = text,
        color = Colors.Red,
        modifier = Modifier.testTag(testTag)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SuggestionsContent(
    title: String,
    suggestions: ImmutableList<String>,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .sheetHeight(SheetSize.COMPACT, isModal = true)
            .gradientBackground()
            .navigationBarsPadding()
    ) {
        SheetTopBar(
            titleText = title,
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        VerticalSpacer(16.dp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            suggestions.forEach { suggestion ->
                PrimaryButton(
                    text = suggestion,
                    onClick = { onSelect(suggestion) },
                    size = ButtonSize.Small,
                    fullWidth = false,
                )
            }
        }
        VerticalSpacer(24.dp)
    }
}

@Composable
private fun SuggestionsButton(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.testTag("AddLinkSuggestions"),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lightbulb),
            contentDescription = null,
            tint = Colors.PubkyGreen,
            modifier = Modifier.padding(end = 4.dp)
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

@Preview
@Composable
private fun PreviewError() {
    AppThemeSurface {
        LinkFormContent(
            label = "Website",
            url = "not a url",
            onLabelChange = {},
            onUrlChange = {},
            onShowSuggestions = {},
            onSave = {},
            isSaveEnabled = false,
            urlError = pubkyAppLinkErrorText(PubkyAppLinkError.URL_INVALID),
        )
    }
}

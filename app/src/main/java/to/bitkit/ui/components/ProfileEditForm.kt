package to.bitkit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.R
import to.bitkit.ui.theme.AppTextFieldDefaults
import to.bitkit.ui.theme.AppTextStyles
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

private const val BIO_MAX_LENGTH = 160

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileEditForm(
    name: String,
    onNameChange: (String) -> Unit,
    publicKey: String,
    bio: String,
    onBioChange: (String) -> Unit,
    links: ImmutableList<ProfileEditLink>,
    onLinkUrlChange: (Int, String) -> Unit,
    onRemoveLink: (Int) -> Unit,
    onAddLink: () -> Unit,
    tags: ImmutableList<String>,
    onRemoveTag: (Int) -> Unit,
    onAddTag: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    isSaveEnabled: Boolean,
    modifier: Modifier = Modifier,
    avatarContent: @Composable () -> Unit = {},
    publicKeyLabel: String? = null,
    bioPlaceholder: String? = null,
    footerNote: String? = null,
    showFooterNote: Boolean = true,
    onDelete: (() -> Unit)? = null,
    deleteLabel: String = "",
) {
    val resolvedPublicKeyLabel = publicKeyLabel ?: stringResource(R.string.profile__your_pubky)
    val resolvedBioPlaceholder = bioPlaceholder ?: stringResource(R.string.profile__edit_bio_placeholder)
    val resolvedFooterNote = footerNote ?: stringResource(R.string.profile__edit_public_note)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val hazeState = rememberHazeState()
    val density = LocalDensity.current
    var footerHeight by remember { mutableStateOf(0.dp) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            ProfileEditHeader(
                name = name,
                onNameChange = onNameChange,
                publicKey = publicKey,
                publicKeyLabel = resolvedPublicKeyLabel,
                avatarContent = avatarContent,
                nameTestTag = "ProfileEditName",
            )
            VerticalSpacer(16.dp)

            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                HorizontalDivider()
                FieldLabel(text = stringResource(R.string.profile__edit_bio)) {
                    TextInput(
                        value = bio,
                        onValueChange = { onBioChange(it.take(BIO_MAX_LENGTH)) },
                        placeholder = resolvedBioPlaceholder,
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ProfileEditBio")
                    )
                }
                HorizontalDivider()

                links.forEachIndexed { index, link ->
                    FieldLabel(text = link.label) {
                        TextInput(
                            value = link.url,
                            onValueChange = { onLinkUrlChange(index, it) },
                            placeholder = stringResource(R.string.profile__add_link_url_placeholder),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(
                                    onClick = { onRemoveLink(index) },
                                    modifier = Modifier.testTag("ProfileEditLinkRemove_$index")
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_trash),
                                        contentDescription = null,
                                        tint = Colors.White64,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ProfileEditLink_$index")
                        )
                    }
                }
                PrimaryButton(
                    text = stringResource(R.string.profile__add_link),
                    onClick = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        onAddLink()
                    },
                    size = ButtonSize.Small,
                    fullWidth = false,
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_link),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.testTag("ProfileEditAddLink")
                )
                HorizontalDivider()

                FieldLabel(text = stringResource(R.string.profile__edit_tags)) {
                    if (tags.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            tags.forEachIndexed { index, tag ->
                                TagButton(
                                    text = tag,
                                    onClick = { onRemoveTag(index) },
                                    displayIconClose = true,
                                )
                            }
                        }
                    }
                    PrimaryButton(
                        text = stringResource(R.string.profile__add_tag),
                        onClick = {
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            onAddTag()
                        },
                        size = ButtonSize.Small,
                        fullWidth = false,
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_tag),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.testTag("ProfileEditAddTag")
                    )
                }

                if (showFooterNote) {
                    HorizontalDivider()
                    BodyS(
                        text = resolvedFooterNote,
                        color = Colors.White64,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (onDelete != null) {
                    HorizontalDivider()
                    FieldLabel(text = stringResource(R.string.profile__edit_delete_section)) {
                        PrimaryButton(
                            text = deleteLabel,
                            onClick = onDelete,
                            size = ButtonSize.Small,
                            fullWidth = false,
                            contentColor = Colors.Brand,
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_trash),
                                    contentDescription = null,
                                    tint = Colors.Brand,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            modifier = Modifier.testTag("ProfileEditDelete")
                        )
                    }
                }
            }

            VerticalSpacer(footerHeight + 16.dp)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { footerHeight = with(density) { it.height.toDp() } }
                .hazeEffect(state = hazeState) {
                    backgroundColor = Colors.Black
                    mask = FooterBlurMask
                    tints = listOf(HazeTint(Colors.Black50))
                }
                .padding(start = 16.dp, top = 32.dp, end = 16.dp, bottom = 16.dp)
        ) {
            SecondaryButton(
                text = stringResource(R.string.common__cancel),
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .testTag("ProfileEditCancel")
            )
            PrimaryButton(
                text = stringResource(R.string.common__save),
                onClick = onSave,
                enabled = isSaveEnabled,
                modifier = Modifier
                    .weight(1f)
                    .testTag("ProfileEditSave")
            )
        }
    }
}

@Composable
fun ProfileEditHeader(
    name: String,
    onNameChange: (String) -> Unit,
    publicKey: String,
    nameTestTag: String,
    modifier: Modifier = Modifier,
    publicKeyLabel: String = stringResource(R.string.profile__your_pubky),
    avatarContent: @Composable () -> Unit = {},
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        VerticalSpacer(16.dp)
        avatarContent()
        VerticalSpacer(16.dp)
        TextInput(
            value = name,
            onValueChange = onNameChange,
            placeholder = stringResource(R.string.profile__edit_name_placeholder),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                autoCorrectEnabled = false,
            ),
            visualTransformation = UppercaseTransformation,
            textStyle = AppTextStyles.Display.copy(textAlign = TextAlign.Center),
            colors = AppTextFieldDefaults.transparent,
            placeholderColor = Colors.White32,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(nameTestTag)
        )
        VerticalSpacer(16.dp)
        HorizontalDivider()
        VerticalSpacer(32.dp)
        Text13Up(
            text = publicKeyLabel,
            color = Colors.White64,
        )
        VerticalSpacer(8.dp)
        BodyMSB(
            text = publicKey,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun FieldLabel(
    text: String,
    content: @Composable () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text13Up(text = text, color = Colors.White64)
        content()
    }
}

private val UppercaseTransformation = VisualTransformation {
    TransformedText(AnnotatedString(it.text.map(Char::uppercaseChar).joinToString("")), OffsetMapping.Identity)
}

private val FooterBlurMask = Brush.verticalGradient(0f to Color.Transparent, 0.4f to Color.Black)

data class ProfileEditLink(val label: String, val url: String)

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        var name by remember { mutableStateOf("Satoshi") }
        var bio by remember { mutableStateOf("Authored the Bitcoin white paper") }
        ProfileEditForm(
            name = name,
            onNameChange = { name = it },
            publicKey = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg",
            bio = bio,
            onBioChange = { bio = it },
            links = persistentListOf(ProfileEditLink("X", "https://x.com/satoshinakamoto")),
            onLinkUrlChange = { _, _ -> },
            onRemoveLink = {},
            onAddLink = {},
            tags = persistentListOf("Founder"),
            onRemoveTag = {},
            onAddTag = {},
            onSave = {},
            onCancel = {},
            isSaveEnabled = true,
            onDelete = {},
            deleteLabel = "Delete Profile",
        )
    }
}

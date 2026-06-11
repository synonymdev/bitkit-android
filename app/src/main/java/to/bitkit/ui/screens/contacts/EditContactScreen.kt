package to.bitkit.ui.screens.contacts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.R
import to.bitkit.ui.components.AddLinkSheet
import to.bitkit.ui.components.AddTagSheet
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.CenteredProfileHeader
import to.bitkit.ui.components.GradientCircularProgressIndicator
import to.bitkit.ui.components.ProfileEditForm
import to.bitkit.ui.components.ProfileEditLink
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun EditContactScreen(
    viewModel: EditContactViewModel,
    onBackClick: () -> Unit,
    onContactDeleted: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.effects.collect {
            when (it) {
                EditContactEffect.SaveSuccess -> onBackClick()
                EditContactEffect.DeleteSuccess -> onContactDeleted()
            }
        }
    }

    Content(
        uiState = uiState,
        onBackClick = onBackClick,
        onRetryClick = { viewModel.retryLoadContact() },
        onNameChange = { viewModel.onNameChange(it) },
        onBioChange = { viewModel.onBioChange(it) },
        onLinkUrlChange = { index, url -> viewModel.updateLinkUrl(index, url) },
        onRemoveLink = { viewModel.removeLink(it) },
        onAddLink = { viewModel.showAddLinkSheet() },
        onRemoveTag = { viewModel.removeTag(it) },
        onAddTag = { viewModel.showAddTagSheet() },
        onSave = { viewModel.save() },
        onDelete = { viewModel.showDeleteConfirmation() },
        onDismissDeleteDialog = { viewModel.dismissDeleteDialog() },
        onConfirmDelete = { viewModel.deleteContact() },
        onDismissAddLinkSheet = { viewModel.dismissAddLinkSheet() },
        onSaveLink = { label, url -> viewModel.addLink(label, url) },
        onDismissAddTagSheet = { viewModel.dismissAddTagSheet() },
        onSaveTag = { viewModel.addTag(it) },
    )
}

@Composable
private fun Content(
    uiState: EditContactUiState,
    onBackClick: () -> Unit,
    onRetryClick: () -> Unit,
    onNameChange: (String) -> Unit,
    onBioChange: (String) -> Unit,
    onLinkUrlChange: (Int, String) -> Unit,
    onRemoveLink: (Int) -> Unit,
    onAddLink: () -> Unit,
    onRemoveTag: (Int) -> Unit,
    onAddTag: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismissDeleteDialog: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissAddLinkSheet: () -> Unit,
    onSaveLink: (label: String, url: String) -> Unit,
    onDismissAddTagSheet: () -> Unit,
    onSaveTag: (String) -> Unit,
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.contacts__edit_contact_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )

        when {
            uiState.isLoading -> LoadingState()
            uiState.isMissing -> EmptyState(onRetryClick = onRetryClick)
            else -> ProfileEditForm(
                name = uiState.name,
                onNameChange = onNameChange,
                publicKey = uiState.publicKey,
                bio = uiState.bio,
                onBioChange = onBioChange,
                links = uiState.links,
                onLinkUrlChange = onLinkUrlChange,
                onRemoveLink = onRemoveLink,
                onAddLink = onAddLink,
                tags = uiState.tags,
                onRemoveTag = onRemoveTag,
                onAddTag = onAddTag,
                onSave = onSave,
                onCancel = onBackClick,
                isSaveEnabled = uiState.name.isNotBlank() && !uiState.isSaving,
                avatarContent = {
                    CenteredProfileHeader(
                        publicKey = uiState.publicKey,
                        name = "",
                        bio = "",
                        imageUrl = uiState.imageUrl,
                    )
                },
                publicKeyLabel = stringResource(R.string.contacts__pubky),
                bioPlaceholder = stringResource(R.string.contacts__edit_bio_placeholder),
                footerNote = stringResource(R.string.contacts__edit_public_note),
                onDelete = onDelete,
                deleteLabel = stringResource(R.string.contacts__delete_contact),
            )
        }
    }

    if (uiState.showDeleteDialog) {
        AppAlertDialog(
            title = stringResource(R.string.contacts__delete_confirm_title, uiState.name),
            text = stringResource(R.string.contacts__delete_confirm_text, uiState.name),
            confirmText = stringResource(R.string.common__delete_yes),
            onConfirm = onConfirmDelete,
            onDismiss = onDismissDeleteDialog,
        )
    }

    if (uiState.showAddLinkSheet) {
        AddLinkSheet(
            onDismiss = onDismissAddLinkSheet,
            onSave = onSaveLink,
        )
    }

    if (uiState.showAddTagSheet) {
        AddTagSheet(
            onDismiss = onDismissAddTagSheet,
            onSave = onSaveTag,
        )
    }
}

@Composable
private fun LoadingState() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        GradientCircularProgressIndicator(modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun EmptyState(onRetryClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
    ) {
        VerticalSpacer(48.dp)
        BodyM(text = stringResource(R.string.contacts__detail_empty_state), color = Colors.White64)
        VerticalSpacer(16.dp)
        SecondaryButton(
            text = stringResource(R.string.profile__retry_load),
            onClick = onRetryClick,
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = EditContactUiState(
                publicKey = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg",
                name = "John Carvalho",
                bio = "CEO at @synonym_to",
                links = persistentListOf(
                    ProfileEditLink("Website", "https://synonym.to"),
                    ProfileEditLink("X", "https://x.com/BitcoinErrorLog"),
                ),
                tags = persistentListOf("CEO", "Founder"),
                isLoading = false,
            ),
            onBackClick = {},
            onRetryClick = {},
            onNameChange = {},
            onBioChange = {},
            onLinkUrlChange = { _, _ -> },
            onRemoveLink = {},
            onAddLink = {},
            onRemoveTag = {},
            onAddTag = {},
            onSave = {},
            onDelete = {},
            onDismissDeleteDialog = {},
            onConfirmDelete = {},
            onDismissAddLinkSheet = {},
            onSaveLink = { _, _ -> },
            onDismissAddTagSheet = {},
            onSaveTag = {},
        )
    }
}

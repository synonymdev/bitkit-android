package to.bitkit.ui.screens.profile

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.R
import to.bitkit.ui.components.AddLinkSheet
import to.bitkit.ui.components.AddTagSheet
import to.bitkit.ui.components.GradientCircularProgressIndicator
import to.bitkit.ui.components.ProfileEditForm
import to.bitkit.ui.components.ProfileEditLink
import to.bitkit.ui.components.PubkyImage
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun EditProfileScreen(
    viewModel: EditProfileViewModel,
    onBackClick: () -> Unit,
    onExitProfile: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.effects.collect {
            when (it) {
                EditProfileEffect.SaveSuccess -> onBackClick()
                EditProfileEffect.DeleteSuccess,
                EditProfileEffect.DisconnectSuccess,
                -> onExitProfile()
            }
        }
    }

    Content(
        uiState = uiState,
        onBackClick = onBackClick,
        onNameChange = viewModel::onNameChange,
        onBioChange = viewModel::onBioChange,
        onLinkUrlChange = viewModel::updateLinkUrl,
        onAvatarSelected = viewModel::onAvatarSelected,
        onAddLink = viewModel::showAddLinkSheet,
        onRemoveLink = viewModel::removeLink,
        onAddTag = viewModel::showAddTagSheet,
        onRemoveTag = viewModel::removeTag,
        onSave = viewModel::save,
        onDelete = viewModel::showDeleteConfirmation,
        onDismissDeleteDialog = viewModel::dismissDeleteDialog,
        onConfirmDelete = viewModel::deleteProfile,
        onRetryDelete = viewModel::retryDeleteProfile,
        onDismissDeleteFailureDialog = viewModel::dismissDeleteFailureDialog,
        onDisconnectProfile = viewModel::disconnectProfile,
        onDismissAddLinkSheet = viewModel::dismissAddLinkSheet,
        onSaveLink = viewModel::addLink,
        onDismissAddTagSheet = viewModel::dismissAddTagSheet,
        onSaveTag = viewModel::addTag,
    )
}

@Suppress("LongParameterList")
@Composable
private fun Content(
    uiState: EditProfileUiState,
    onBackClick: () -> Unit,
    onNameChange: (String) -> Unit,
    onBioChange: (String) -> Unit,
    onLinkUrlChange: (Int, String) -> Unit,
    onAvatarSelected: (Uri) -> Unit,
    onAddLink: () -> Unit,
    onRemoveLink: (Int) -> Unit,
    onAddTag: () -> Unit,
    onRemoveTag: (Int) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismissDeleteDialog: () -> Unit,
    onConfirmDelete: () -> Unit,
    onRetryDelete: () -> Unit,
    onDismissDeleteFailureDialog: () -> Unit,
    onDisconnectProfile: () -> Unit,
    onDismissAddLinkSheet: () -> Unit,
    onSaveLink: (String, String) -> Unit,
    onDismissAddTagSheet: () -> Unit,
    onSaveTag: (String) -> Unit,
) {
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { onAvatarSelected(it) } }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri -> uri?.let { onAvatarSelected(it) } },
    )

    val launchPhotoPicker = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } else {
            galleryLauncher.launch("image/*")
        }
    }

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.profile__edit_nav_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )

        if (uiState.isLoading) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                GradientCircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        } else {
            ProfileEditForm(
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
                    AvatarSection(
                        imageUrl = uiState.imageUrl,
                        newAvatarUri = uiState.newAvatarUri,
                        onClick = launchPhotoPicker,
                    )
                },
                onDelete = onDelete,
                deleteLabel = stringResource(R.string.profile__delete_profile),
            )
        }
    }

    if (uiState.showDeleteDialog) {
        AppAlertDialog(
            title = stringResource(R.string.profile__delete_confirm_title),
            text = stringResource(R.string.profile__delete_confirm_description),
            confirmText = stringResource(R.string.common__delete_yes),
            onConfirm = onConfirmDelete,
            onDismiss = onDismissDeleteDialog,
        )
    }

    if (uiState.showDeleteFailureDialog) {
        AppAlertDialog(
            title = stringResource(R.string.profile__delete_error_title),
            text = stringResource(R.string.profile__delete_error_description),
            confirmText = stringResource(R.string.common__retry),
            dismissText = stringResource(R.string.profile__sign_out),
            onConfirm = onRetryDelete,
            onDismiss = onDisconnectProfile,
            onDismissRequest = onDismissDeleteFailureDialog,
            properties = DialogProperties(
                dismissOnClickOutside = true,
                dismissOnBackPress = true,
            ),
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
private fun AvatarSection(
    imageUrl: String?,
    newAvatarUri: Uri?,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(Colors.Gray5)
            .testTag("EditProfileAvatar")
            .clickable(onClick = onClick)
    ) {
        when {
            newAvatarUri != null -> AsyncImage(
                model = newAvatarUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            imageUrl != null -> PubkyImage(uri = imageUrl, size = 96.dp)
            else -> Icon(
                painter = painterResource(R.drawable.ic_user_square),
                contentDescription = null,
                tint = Colors.White32,
                modifier = Modifier.size(40.dp)
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = EditProfileUiState(
                name = "Satoshi",
                bio = "Authored the Bitcoin white paper",
                links = persistentListOf(ProfileEditLink("X", "https://x.com/satoshi")),
                imageUrl = null,
            ),
            onBackClick = {},
            onNameChange = {},
            onBioChange = {},
            onLinkUrlChange = { _, _ -> },
            onAvatarSelected = {},
            onAddLink = {},
            onRemoveLink = {},
            onAddTag = {},
            onRemoveTag = {},
            onSave = {},
            onDelete = {},
            onDismissDeleteDialog = {},
            onConfirmDelete = {},
            onRetryDelete = {},
            onDismissDeleteFailureDialog = {},
            onDisconnectProfile = {},
            onDismissAddLinkSheet = {},
            onSaveLink = { _, _ -> },
            onDismissAddTagSheet = {},
            onSaveTag = {},
        )
    }
}

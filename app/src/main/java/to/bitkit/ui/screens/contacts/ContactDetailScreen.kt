package to.bitkit.ui.screens.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyProfileLink
import to.bitkit.ui.components.ActionButton
import to.bitkit.ui.components.AddTagSheet
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.CenteredProfileHeader
import to.bitkit.ui.components.GradientCircularProgressIndicator
import to.bitkit.ui.components.LinkRow
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.util.shareText
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun ContactDetailScreen(
    viewModel: ContactDetailViewModel,
    onBackClick: () -> Unit,
    onEditContact: (String) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.effects.collect {
            when (it) {
                ContactDetailEffect.DeleteSuccess -> onBackClick()
            }
        }
    }

    Content(
        uiState = uiState,
        onBackClick = onBackClick,
        onClickEdit = { uiState.profile?.publicKey?.let { onEditContact(it) } },
        onClickCopy = { viewModel.copyPublicKey() },
        onClickShare = { uiState.profile?.publicKey?.let { shareText(context, it) } },
        onClickDelete = { viewModel.showDeleteConfirmation() },
        onClickRetry = { viewModel.loadContact() },
        onAddTag = { viewModel.showAddTagSheet() },
        onRemoveTag = { viewModel.removeTag(it) },
        onDismissAddTagSheet = { viewModel.dismissAddTagSheet() },
        onSaveTag = { viewModel.addTag(it) },
        onDismissDeleteDialog = { viewModel.dismissDeleteDialog() },
        onConfirmDelete = { viewModel.deleteContact() },
    )
}

@Composable
private fun Content(
    uiState: ContactDetailUiState,
    onBackClick: () -> Unit,
    onClickEdit: () -> Unit,
    onClickCopy: () -> Unit,
    onClickShare: () -> Unit,
    onClickDelete: () -> Unit,
    onClickRetry: () -> Unit,
    onAddTag: () -> Unit,
    onRemoveTag: (Int) -> Unit,
    onDismissAddTagSheet: () -> Unit,
    onSaveTag: (String) -> Unit,
    onDismissDeleteDialog: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    val currentProfile = uiState.profile

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.contacts__detail_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )

        when {
            uiState.isLoading && currentProfile == null -> LoadingState()
            currentProfile != null -> ContactBody(
                profile = currentProfile,
                tags = uiState.tags,
                onClickEdit = onClickEdit,
                onClickCopy = onClickCopy,
                onClickShare = onClickShare,
                onClickDelete = onClickDelete,
                onAddTag = onAddTag,
                onRemoveTag = onRemoveTag,
            )
            else -> EmptyState(onClickRetry = onClickRetry)
        }
    }

    if (uiState.showDeleteDialog && currentProfile != null) {
        AppAlertDialog(
            title = stringResource(R.string.contacts__delete_confirm_title, currentProfile.name),
            text = stringResource(R.string.contacts__delete_confirm_text),
            confirmText = stringResource(R.string.contacts__delete_contact),
            onConfirm = onConfirmDelete,
            onDismiss = onDismissDeleteDialog,
        )
    }

    if (uiState.showAddTagSheet) {
        AddTagSheet(
            onDismiss = onDismissAddTagSheet,
            onSave = onSaveTag,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContactBody(
    profile: PubkyProfile,
    tags: ImmutableList<String>,
    onClickEdit: () -> Unit,
    onClickCopy: () -> Unit,
    onClickShare: () -> Unit,
    onClickDelete: () -> Unit,
    onAddTag: () -> Unit,
    onRemoveTag: (Int) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp)
    ) {
        VerticalSpacer(24.dp)

        CenteredProfileHeader(
            publicKey = profile.publicKey,
            name = profile.name,
            bio = profile.bio,
            imageUrl = profile.imageUrl,
        )

        VerticalSpacer(24.dp)

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ActionButton(onClick = onClickCopy, iconRes = R.drawable.ic_copy)
            ActionButton(onClick = onClickShare, iconRes = R.drawable.ic_share)
            ActionButton(onClick = onClickEdit, iconRes = R.drawable.ic_edit)
            ActionButton(onClick = onClickDelete, iconRes = R.drawable.ic_trash)
        }

        VerticalSpacer(32.dp)

        profile.links.forEach { LinkRow(label = it.label, value = it.url) }

        VerticalSpacer(16.dp)
        Text13Up(
            text = stringResource(R.string.profile__edit_tags),
            color = Colors.White64,
            modifier = Modifier.fillMaxWidth()
        )
        VerticalSpacer(8.dp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            tags.forEachIndexed { index, tag ->
                TagButton(
                    text = tag,
                    onClick = { onRemoveTag(index) },
                    displayIconClose = true,
                )
            }
        }
        VerticalSpacer(8.dp)
        Row(modifier = Modifier.fillMaxWidth()) {
            TagButton(
                text = stringResource(R.string.profile__add_tag),
                onClick = onAddTag,
                icon = painterResource(R.drawable.ic_tag),
                displayIconClose = true,
            )
        }
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
private fun EmptyState(onClickRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
    ) {
        BodyM(text = stringResource(R.string.contacts__detail_empty_state), color = Colors.White64)
        VerticalSpacer(16.dp)
        SecondaryButton(
            text = stringResource(R.string.profile__retry_load),
            onClick = onClickRetry,
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = ContactDetailUiState(
                profile = PubkyProfile(
                    publicKey = "pk8e3qm5...gxag",
                    name = "John Carvalho",
                    bio = "CEO at @synonym_to\n// Host of @thebizbtc",
                    imageUrl = null,
                    links = listOf(
                        PubkyProfileLink("Email", "john@synonym.to"),
                        PubkyProfileLink("Website", "https://bitcoinerrorlog.substack.com"),
                    ),
                    tags = listOf("CEO", "Bitcoin"),
                    status = null,
                ),
                tags = persistentListOf("CEO", "Bitcoin"),
            ),
            onBackClick = {},
            onClickEdit = {},
            onClickCopy = {},
            onClickShare = {},
            onClickDelete = {},
            onClickRetry = {},
            onAddTag = {},
            onRemoveTag = {},
            onDismissAddTagSheet = {},
            onSaveTag = {},
            onDismissDeleteDialog = {},
            onConfirmDelete = {},
        )
    }
}

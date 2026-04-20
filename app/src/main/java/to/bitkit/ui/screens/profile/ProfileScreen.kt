package to.bitkit.ui.screens.profile

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyProfileLink
import to.bitkit.ui.components.ActionButton
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.CenteredProfileHeader
import to.bitkit.ui.components.GradientCircularProgressIndicator
import to.bitkit.ui.components.LinkRow
import to.bitkit.ui.components.PubkyImage
import to.bitkit.ui.components.QrCodeImage
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.modifiers.rememberDebouncedClick
import to.bitkit.ui.shared.util.shareText
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onBackClick: () -> Unit,
    onEditProfile: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.effects.collect {
            when (it) {
                ProfileEffect.SignedOut -> onBackClick()
            }
        }
    }

    Content(
        uiState = uiState,
        onBackClick = onBackClick,
        onClickEdit = onEditProfile,
        onClickCopy = { viewModel.copyPublicKey() },
        onClickShare = { uiState.publicKey?.let { shareText(context, it) } },
        onClickSignOut = { viewModel.showSignOutConfirmation() },
        onDismissSignOutDialog = { viewModel.dismissSignOutDialog() },
        onConfirmSignOut = { viewModel.signOut() },
        onClickRetry = { viewModel.loadProfile() },
    )
}

@Composable
private fun Content(
    uiState: ProfileUiState,
    onBackClick: () -> Unit,
    onClickEdit: () -> Unit,
    onClickCopy: () -> Unit,
    onClickShare: () -> Unit,
    onClickSignOut: () -> Unit,
    onDismissSignOutDialog: () -> Unit,
    onConfirmSignOut: () -> Unit,
    onClickRetry: () -> Unit,
) {
    val currentProfile = uiState.profile

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.profile__nav_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )

        when {
            uiState.isLoading && currentProfile == null -> LoadingState()
            currentProfile != null -> ProfileBody(
                profile = currentProfile,
                onClickEdit = onClickEdit,
                onClickCopy = onClickCopy,
                onClickShare = onClickShare,
            )
            else -> EmptyState(onClickRetry = onClickRetry, onClickSignOut = onClickSignOut)
        }
    }

    if (uiState.showSignOutDialog) {
        AppAlertDialog(
            title = stringResource(R.string.profile__sign_out_title),
            text = stringResource(R.string.profile__sign_out_description),
            confirmText = stringResource(R.string.profile__sign_out),
            onConfirm = onConfirmSignOut,
            onDismiss = onDismissSignOutDialog,
        )
    }
}

@Composable
private fun ProfileBody(
    profile: PubkyProfile,
    onClickEdit: () -> Unit,
    onClickCopy: () -> Unit,
    onClickShare: () -> Unit,
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
            nameTestTag = "ProfileViewName",
            notesTestTag = "ProfileViewNotes",
        )

        VerticalSpacer(24.dp)

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            QrCodeImage(
                content = profile.publicKey,
                modifier = Modifier.fillMaxWidth(),
                testTag = "QRCode",
            )
            if (profile.imageUrl != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(68.dp)
                        .background(Color.White, CircleShape)
                ) {
                    PubkyImage(
                        uri = profile.imageUrl,
                        size = 50.dp,
                    )
                }
            }
        }

        VerticalSpacer(24.dp)

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            modifier = Modifier.fillMaxWidth()
        ) {
            ActionButton(
                onClick = onClickEdit,
                iconRes = R.drawable.ic_edit,
                modifier = Modifier.testTag("ProfileEdit")
            )
            ActionButton(
                onClick = onClickCopy,
                iconRes = R.drawable.ic_copy,
                modifier = Modifier.testTag("ProfileCopy")
            )
            ActionButton(
                onClick = onClickShare,
                iconRes = R.drawable.ic_share,
                modifier = Modifier.testTag("ProfileShare")
            )
        }

        VerticalSpacer(32.dp)

        if (profile.links.isNotEmpty()) {
            profile.links.forEachIndexed { index, link ->
                LinkRow(label = link.label, value = link.url, linkIndex = index)
            }
        }

        if (profile.tags.isNotEmpty()) {
            VerticalSpacer(16.dp)
            Text13Up(
                text = stringResource(R.string.profile__edit_tags),
                color = Colors.White64,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ProfileViewTagsHeader"),
            )
            VerticalSpacer(8.dp)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                profile.tags.forEach { tag ->
                    TagButton(text = tag, onClick = null)
                }
            }
        }

        VerticalSpacer(16.dp)
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
private fun EmptyState(
    onClickRetry: () -> Unit,
    onClickSignOut: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
    ) {
        BodyM(text = stringResource(R.string.profile__empty_state), color = Colors.White64)
        VerticalSpacer(16.dp)
        SecondaryButton(
            text = stringResource(R.string.profile__retry_load),
            onClick = onClickRetry,
            modifier = Modifier.testTag("ProfileRetry"),
        )
        VerticalSpacer(8.dp)
        TextButton(
            onClick = rememberDebouncedClick(onClick = onClickSignOut),
            modifier = Modifier.testTag("ProfileEmptySignOut"),
        ) {
            BodyS(text = stringResource(R.string.profile__sign_out), color = Colors.White64)
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = ProfileUiState(
                profile = PubkyProfile(
                    publicKey = "pk8e3qm5...gxag",
                    name = "Satoshi",
                    bio = "Building a peer-to-peer electronic cash system.",
                    imageUrl = null,
                    links = listOf(PubkyProfileLink("Website", "https://bitcoin.org")),
                    tags = listOf("Founder", "Bitcoin"),
                    status = null,
                ),
            ),
            onBackClick = {},
            onClickEdit = {},
            onClickCopy = {},
            onClickShare = {},
            onClickSignOut = {},
            onDismissSignOutDialog = {},
            onConfirmSignOut = {},
            onClickRetry = {},
        )
    }
}

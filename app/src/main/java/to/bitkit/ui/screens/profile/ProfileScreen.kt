package to.bitkit.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyProfileLink
import to.bitkit.ui.components.ActionButton
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.GradientCircularProgressIndicator
import to.bitkit.ui.components.Headline
import to.bitkit.ui.components.LinkRow
import to.bitkit.ui.components.PubkyImage
import to.bitkit.ui.components.QrCodeImage
import to.bitkit.ui.components.SecondaryButton
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
                isSigningOut = uiState.isSigningOut,
                onClickCopy = onClickCopy,
                onClickShare = onClickShare,
                onClickSignOut = onClickSignOut,
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
    isSigningOut: Boolean,
    onClickCopy: () -> Unit,
    onClickShare: () -> Unit,
    onClickSignOut: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp)
    ) {
        VerticalSpacer(24.dp)

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Headline(text = AnnotatedString(profile.name))
                VerticalSpacer(8.dp)
                BodySSB(text = profile.truncatedPublicKey)
            }

            if (profile.imageUrl != null) {
                PubkyImage(uri = profile.imageUrl, size = 64.dp)
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Colors.PubkyGreen)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_user_square),
                        contentDescription = null,
                        tint = Colors.White32,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        VerticalSpacer(16.dp)

        if (profile.bio.isNotEmpty()) {
            BodyM(text = profile.bio, color = Colors.White64)
            VerticalSpacer(8.dp)
        }
        HorizontalDivider()

        VerticalSpacer(24.dp)

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ActionButton(onClick = onClickCopy, iconRes = R.drawable.ic_copy)
            ActionButton(onClick = onClickShare, iconRes = R.drawable.ic_share)
            ActionButton(
                onClick = onClickSignOut,
                imageVector = Icons.AutoMirrored.Filled.Logout,
                enabled = !isSigningOut,
            )
        }

        VerticalSpacer(24.dp)

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            QrCodeImage(
                content = profile.publicKey,
                modifier = Modifier.fillMaxWidth()
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
        VerticalSpacer(12.dp)
        BodyS(
            text = stringResource(R.string.profile__qr_scan_label).replace("{name}", profile.name),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        VerticalSpacer(32.dp)

        profile.links.forEach { LinkRow(label = it.label, value = it.url) }
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
        )
        VerticalSpacer(8.dp)
        TextButton(onClick = rememberDebouncedClick(onClick = onClickSignOut)) {
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
                    status = null,
                ),
            ),
            onBackClick = {},
            onClickCopy = {},
            onClickShare = {},
            onClickSignOut = {},
            onDismissSignOutDialog = {},
            onConfirmSignOut = {},
            onClickRetry = {},
        )
    }
}

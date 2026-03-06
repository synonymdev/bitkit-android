package to.bitkit.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyProfileLink
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Headline
import to.bitkit.ui.components.PubkyImage
import to.bitkit.ui.components.QrCodeImage
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (uiState.profile == null) viewModel.loadProfile()
    }

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
        onCopy = { viewModel.copyPublicKey() },
        onShare = { viewModel.sharePublicKey() },
        onSignOut = { viewModel.showSignOutConfirmation() },
        onDismissSignOutDialog = { viewModel.dismissSignOutDialog() },
        onConfirmSignOut = { viewModel.signOut() },
        onRetry = { viewModel.loadProfile() },
    )
}

@Composable
private fun Content(
    uiState: ProfileUiState,
    onBackClick: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSignOut: () -> Unit,
    onDismissSignOutDialog: () -> Unit,
    onConfirmSignOut: () -> Unit,
    onRetry: () -> Unit,
) {
    val currentProfile = uiState.profile

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.profile__nav_title),
            onBackClick = onBackClick,
        )

        when {
            uiState.isLoading && currentProfile == null -> LoadingState()
            currentProfile != null -> ProfileBody(
                profile = currentProfile,
                isSigningOut = uiState.isSigningOut,
                onCopy = onCopy,
                onShare = onShare,
                onSignOut = onSignOut,
            )
            else -> EmptyState(onRetry = onRetry)
        }
    }

    if (uiState.showSignOutDialog) {
        AlertDialog(
            onDismissRequest = onDismissSignOutDialog,
            title = { Text(stringResource(R.string.profile__sign_out_title)) },
            text = { Text(stringResource(R.string.profile__sign_out_description)) },
            confirmButton = {
                TextButton(onClick = onConfirmSignOut) {
                    Text(stringResource(R.string.profile__sign_out))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissSignOutDialog) {
                    Text(stringResource(R.string.common__dialog_cancel))
                }
            },
        )
    }
}

@Composable
private fun ProfileBody(
    profile: PubkyProfile,
    isSigningOut: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSignOut: () -> Unit,
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
                Text(
                    text = profile.truncatedPublicKey,
                    color = Colors.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
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
            Text(
                text = profile.bio,
                color = Colors.White64,
                fontSize = 18.sp,
                lineHeight = 26.sp,
            )
            VerticalSpacer(8.dp)
        }
        HorizontalDivider()

        VerticalSpacer(24.dp)

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ActionButton(iconRes = R.drawable.ic_copy, onClick = onCopy)
            ActionButton(iconRes = R.drawable.ic_share, onClick = onShare)
            ActionButton(
                imageVector = Icons.AutoMirrored.Filled.Logout,
                onClick = onSignOut,
                enabled = !isSigningOut,
            )
        }

        VerticalSpacer(24.dp)

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            QrCodeImage(
                content = profile.publicKey,
                modifier = Modifier.fillMaxWidth(),
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
        Text(
            text = stringResource(R.string.profile__qr_scan_label).replace("{name}", profile.name),
            color = Colors.White,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        VerticalSpacer(32.dp)

        profile.links.forEach { link ->
            ProfileLinkRow(label = link.label, value = link.url)
        }
    }
}

@Composable
private fun ActionButton(
    iconRes: Int? = null,
    imageVector: ImageVector? = null,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(listOf(Colors.Gray5, Colors.Gray6)),
                CircleShape,
            )
            .border(1.dp, Colors.White10, CircleShape)
    ) {
        val tint = if (enabled) Colors.White else Colors.White32
        when {
            iconRes != null -> Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
            imageVector != null -> Icon(
                imageVector = imageVector,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun ProfileLinkRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        VerticalSpacer(16.dp)
        Text(
            text = label.uppercase(),
            color = Colors.White64,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            letterSpacing = 1.sp,
        )
        VerticalSpacer(8.dp)
        Text(
            text = value,
            color = Colors.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
        )
        VerticalSpacer(16.dp)
        HorizontalDivider()
    }
}

@Composable
private fun LoadingState() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        CircularProgressIndicator(color = Colors.White32)
    }
}

@Composable
private fun EmptyState(onRetry: () -> Unit) {
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
            onClick = onRetry,
        )
    }
}

@Preview(showBackground = true)
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
            onCopy = {},
            onShare = {},
            onSignOut = {},
            onDismissSignOutDialog = {},
            onConfirmSignOut = {},
            onRetry = {},
        )
    }
}

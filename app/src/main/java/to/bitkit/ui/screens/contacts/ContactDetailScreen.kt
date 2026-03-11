package to.bitkit.ui.screens.contacts

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyProfileLink
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Headline
import to.bitkit.ui.components.PubkyImage
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun ContactDetailScreen(
    viewModel: ContactDetailViewModel,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Content(
        uiState = uiState,
        onBackClick = onBackClick,
        onClickCopy = { viewModel.copyPublicKey() },
        onClickShare = { viewModel.sharePublicKey() },
        onClickRetry = { viewModel.loadContact() },
    )
}

@Composable
private fun Content(
    uiState: ContactDetailUiState,
    onBackClick: () -> Unit,
    onClickCopy: () -> Unit,
    onClickShare: () -> Unit,
    onClickRetry: () -> Unit,
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
                onClickCopy = onClickCopy,
                onClickShare = onClickShare,
            )
            else -> EmptyState(onClickRetry = onClickRetry)
        }
    }
}

@Composable
private fun ContactBody(
    profile: PubkyProfile,
    onClickCopy: () -> Unit,
    onClickShare: () -> Unit,
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
            ActionButton(iconRes = R.drawable.ic_copy, onClick = onClickCopy)
            ActionButton(iconRes = R.drawable.ic_share, onClick = onClickShare)
        }

        VerticalSpacer(32.dp)

        profile.links.forEach { LinkRow(label = it.label, value = it.url) }
    }
}

@Composable
private fun ActionButton(
    iconRes: Int,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(listOf(Colors.Gray5, Colors.Gray6)),
                CircleShape,
            )
            .border(1.dp, Colors.White10, CircleShape)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = Colors.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun LinkRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        VerticalSpacer(16.dp)
        Text13Up(text = label, color = Colors.White64)
        VerticalSpacer(8.dp)
        BodySSB(text = value)
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
private fun EmptyState(onClickRetry: () -> Unit) {
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
    }
}

@Preview(showBackground = true)
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
                    status = null,
                ),
            ),
            onBackClick = {},
            onClickCopy = {},
            onClickShare = {},
            onClickRetry = {},
        )
    }
}

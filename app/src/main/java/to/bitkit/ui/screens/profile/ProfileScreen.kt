package to.bitkit.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.Milestone
import to.bitkit.models.MilestoneCategory
import to.bitkit.models.MilestoneId
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyProfileLink
import to.bitkit.ui.components.ActionButton
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.CenteredProfileHeader
import to.bitkit.ui.components.GradientCircularProgressIndicator
import to.bitkit.ui.components.LinkRow
import to.bitkit.ui.components.PrimaryButton
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
    onConnectPubky: () -> Unit = {},
    onClickMilestone: (MilestoneId) -> Unit = {},
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
        onClickConnectPubky = onConnectPubky,
        onClickMilestone = onClickMilestone,
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
    onClickConnectPubky: () -> Unit,
    onClickMilestone: (MilestoneId) -> Unit,
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
                milestones = uiState.milestones,
                onClickEdit = onClickEdit,
                onClickCopy = onClickCopy,
                onClickShare = onClickShare,
                onClickMilestone = onClickMilestone,
            )
            !uiState.isAuthenticated -> DisconnectedState(
                milestones = uiState.milestones,
                onClickConnectPubky = onClickConnectPubky,
                onClickMilestone = onClickMilestone,
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
    milestones: List<Milestone>,
    onClickEdit: () -> Unit,
    onClickCopy: () -> Unit,
    onClickShare: () -> Unit,
    onClickMilestone: (MilestoneId) -> Unit,
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

        VerticalSpacer(24.dp)

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            modifier = Modifier.fillMaxWidth()
        ) {
            ActionButton(onClick = onClickEdit, iconRes = R.drawable.ic_edit)
            ActionButton(onClick = onClickCopy, iconRes = R.drawable.ic_copy)
            ActionButton(onClick = onClickShare, iconRes = R.drawable.ic_share)
        }

        VerticalSpacer(32.dp)

        MilestonesSection(
            milestones = milestones,
            onClickMilestone = onClickMilestone,
        )

        VerticalSpacer(24.dp)

        if (profile.links.isNotEmpty()) {
            profile.links.forEach { LinkRow(label = it.label, value = it.url) }
        }

        if (profile.tags.isNotEmpty()) {
            VerticalSpacer(16.dp)
            Text13Up(
                text = stringResource(R.string.profile__edit_tags),
                color = Colors.White64,
                modifier = Modifier.fillMaxWidth()
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
private fun DisconnectedState(
    milestones: List<Milestone>,
    onClickConnectPubky: () -> Unit,
    onClickMilestone: (MilestoneId) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp)
    ) {
        VerticalSpacer(24.dp)
        BodyM(
            text = stringResource(R.string.profile__milestone_local_description),
            color = Colors.White64,
            modifier = Modifier.fillMaxWidth(),
        )
        VerticalSpacer(24.dp)
        PrimaryButton(
            text = stringResource(R.string.profile__milestone_connect),
            onClick = onClickConnectPubky,
        )
        VerticalSpacer(24.dp)
        MilestonesSection(
            milestones = milestones,
            onClickMilestone = onClickMilestone,
        )
        VerticalSpacer(16.dp)
    }
}

@Composable
private fun MilestonesSection(
    milestones: List<Milestone>,
    onClickMilestone: (MilestoneId) -> Unit,
) {
    if (milestones.isEmpty()) {
        Text13Up(
            text = stringResource(R.string.profile__milestone_title),
            color = Colors.White64,
            modifier = Modifier.fillMaxWidth(),
        )
        VerticalSpacer(8.dp)
        BodyS(
            text = stringResource(R.string.profile__milestone_empty),
            color = Colors.White64,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }

    Text13Up(
        text = stringResource(R.string.profile__milestone_title),
        color = Colors.White64,
        modifier = Modifier.fillMaxWidth(),
    )
    VerticalSpacer(8.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Colors.Gray6, RoundedCornerShape(16.dp))
    ) {
        milestones.forEachIndexed { index, milestone ->
            MilestoneRow(
                milestone = milestone,
                onClick = { onClickMilestone(milestone.id) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
            if (index != milestones.lastIndex) {
                HorizontalDivider(color = Colors.White10)
            }
        }
    }
}

@Composable
private fun MilestoneRow(
    milestone: Milestone,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowAlpha = when {
        milestone.isUnlocked -> 1f
        milestone.progress > 0 -> 0.72f
        else -> 0.56f
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(rowAlpha)
            .clickable(onClick = onClick),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(28.dp)
                    .background(milestoneCategoryColor(milestone.category).copy(alpha = 0.24f), CircleShape)
            ) {
                Icon(
                    painter = painterResource(milestone.iconRes),
                    contentDescription = null,
                    tint = milestoneCategoryColor(milestone.category),
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    BodyM(
                        text = milestone.title,
                        color = Colors.White,
                    )
                    MilestoneStatus(milestone = milestone)
                }
                VerticalSpacer(4.dp)
                BodyS(
                    text = milestone.description,
                    color = Colors.White64,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun milestoneStatus(milestone: Milestone): String = when {
    milestone.isPublished -> stringResource(R.string.profile__milestone_public)
    milestone.isUnlocked -> stringResource(R.string.profile__milestone_private)
    milestone.target > 1 -> "${milestone.progress}/${milestone.target}"
    else -> stringResource(R.string.profile__milestone_locked)
}

@Composable
private fun MilestoneStatus(milestone: Milestone) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            milestone.isPublished -> Icon(
                painter = painterResource(R.drawable.ic_globe),
                contentDescription = null,
                tint = Colors.Green,
                modifier = Modifier.size(12.dp),
            )

            milestone.isUnlocked -> Icon(
                painter = painterResource(R.drawable.ic_lock_key),
                contentDescription = null,
                tint = Colors.Gray1,
                modifier = Modifier.size(12.dp),
            )
        }
        Text13Up(
            text = milestoneStatus(milestone),
            color = milestoneStatusColor(milestone),
        )
    }
}

private fun milestoneStatusColor(milestone: Milestone): Color = when {
    milestone.isPublished -> Colors.Green
    milestone.isUnlocked -> Colors.Gray1
    milestone.progress > 0 -> milestoneCategoryColor(milestone.category)
    else -> Colors.White64
}

private fun milestoneCategoryColor(category: MilestoneCategory): Color = when (category) {
    MilestoneCategory.Pubky -> Colors.PubkyGreen
    MilestoneCategory.Onchain -> Colors.Brand
    MilestoneCategory.Lightning -> Colors.Purple
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
            onClickConnectPubky = {},
            onClickMilestone = {},
        )
    }
}

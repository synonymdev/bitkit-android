package to.bitkit.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ext.toLocalizedTimestamp
import to.bitkit.models.Milestone
import to.bitkit.models.MilestoneCategory
import to.bitkit.models.MilestoneId
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun MilestoneDetailScreen(
    viewModel: ProfileViewModel,
    milestoneId: String,
    onBackClick: () -> Unit,
    onConnectPubky: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val milestone = MilestoneId.fromValue(milestoneId)?.let(viewModel::getMilestone)
    val milestoneIdValue = MilestoneId.fromValue(milestoneId)

    Content(
        milestone = milestone,
        isAuthenticated = uiState.isAuthenticated,
        isPublishing = uiState.publishingMilestoneId == milestoneId,
        onClickPublish = { milestoneIdValue?.let(viewModel::publishMilestone) },
        onConnectPubky = onConnectPubky,
        onBackClick = onBackClick,
    )
}

@Composable
private fun Content(
    milestone: Milestone?,
    isAuthenticated: Boolean,
    isPublishing: Boolean,
    onClickPublish: () -> Unit,
    onConnectPubky: () -> Unit,
    onBackClick: () -> Unit,
) {
    ScreenColumn {
        AppTopBar(
            titleText = milestone?.title ?: stringResource(R.string.profile__milestone_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )

        if (milestone == null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                BodyM(
                    text = stringResource(R.string.profile__milestone_not_found),
                    color = Colors.White64,
                )
            }
            return@ScreenColumn
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp)
        ) {
            VerticalSpacer(32.dp)

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(72.dp)
                    .background(
                        color = milestoneCategoryColor(milestone.category).copy(alpha = 0.24f),
                        shape = CircleShape,
                    )
            ) {
                Icon(
                    painter = painterResource(milestone.iconRes),
                    contentDescription = null,
                    tint = milestoneCategoryColor(milestone.category),
                    modifier = Modifier.size(36.dp),
                )
            }

            VerticalSpacer(24.dp)

            BodyM(
                text = milestone.title,
                color = Colors.White,
                modifier = Modifier.fillMaxWidth(),
            )
            VerticalSpacer(8.dp)
            BodyS(
                text = milestone.description,
                color = Colors.White64,
                modifier = Modifier.fillMaxWidth(),
            )

            VerticalSpacer(24.dp)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Colors.Gray6, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                MilestoneDetailRow(
                    label = stringResource(R.string.profile__milestone_status),
                    value = milestoneStatusLabel(milestone),
                )
                VerticalSpacer(12.dp)
                MilestoneDetailRow(
                    label = stringResource(R.string.profile__milestone_progress),
                    value = "${milestone.progress}/${milestone.target}",
                )
                if (milestone.unlockedAtMs != null) {
                    VerticalSpacer(12.dp)
                    MilestoneDetailRow(
                        label = stringResource(R.string.profile__milestone_unlocked_at),
                        value = milestone.unlockedAtMs.toLocalizedTimestamp(),
                    )
                }
            }

            VerticalSpacer(24.dp)

            when {
                !isAuthenticated -> {
                    BodyS(
                        text = stringResource(R.string.profile__milestone_publish_requires_pubky),
                        color = Colors.White64,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    VerticalSpacer(12.dp)
                    PrimaryButton(
                        text = stringResource(R.string.profile__milestone_connect),
                        onClick = onConnectPubky,
                    )
                }

                isPublishing -> {
                    PrimaryButton(
                        text = stringResource(R.string.profile__milestone_publish),
                        onClick = {},
                        enabled = false,
                        isLoading = true,
                    )
                }

                milestone.isPublished -> {
                    SecondaryButton(
                        text = stringResource(R.string.profile__milestone_published),
                        onClick = {},
                        enabled = false,
                    )
                }

                milestone.isUnlocked -> {
                    PrimaryButton(
                        text = stringResource(R.string.profile__milestone_publish),
                        onClick = onClickPublish,
                    )
                }
            }

            VerticalSpacer(16.dp)
        }
    }
}

@Composable
private fun MilestoneDetailRow(
    label: String,
    value: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text13Up(
            text = label,
            color = Colors.White64,
            modifier = Modifier.fillMaxWidth(),
        )
        VerticalSpacer(4.dp)
        BodyS(
            text = value,
            color = Colors.White,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun milestoneStatusLabel(milestone: Milestone): String = when {
    milestone.isUnlocked -> stringResource(R.string.profile__milestone_unlocked)
    milestone.progress > 0 -> stringResource(R.string.profile__milestone_in_progress)
    else -> stringResource(R.string.profile__milestone_locked)
}

private fun milestoneCategoryColor(category: MilestoneCategory) = when (category) {
    MilestoneCategory.Pubky -> Colors.PubkyGreen
    MilestoneCategory.Onchain -> Colors.Brand
    MilestoneCategory.Lightning -> Colors.Purple
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            milestone = Milestone(
                id = MilestoneId.FirstStack,
                title = "First Stack",
                description = "Received bitcoin on-chain for the first time",
                category = MilestoneCategory.Onchain,
                iconRes = R.drawable.ic_btc_circle,
                progress = 1,
                target = 1,
                isUnlocked = true,
                isPublished = false,
                unlockedAtMs = 1_744_644_800_000,
            ),
            isAuthenticated = true,
            isPublishing = false,
            onClickPublish = {},
            onConnectPubky = {},
            onBackClick = {},
        )
    }
}

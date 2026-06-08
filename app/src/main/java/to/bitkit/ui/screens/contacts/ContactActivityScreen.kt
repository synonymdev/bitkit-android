package to.bitkit.ui.screens.contacts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synonym.bitkitcore.Activity
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.R
import to.bitkit.ext.isSent
import to.bitkit.models.PubkyProfile
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.GradientCircularProgressIndicator
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.wallets.activity.components.ActivityListGrouped
import to.bitkit.ui.screens.wallets.activity.utils.previewActivityItems
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun ContactActivityScreen(
    viewModel: ContactActivityViewModel,
    onBackClick: () -> Unit,
    onActivityItemClick: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Content(
        uiState = uiState,
        onBackClick = onBackClick,
        onRetryClick = { viewModel.loadActivities() },
        onActivityItemClick = onActivityItemClick,
    )
}

@Composable
private fun Content(
    uiState: ContactActivityUiState,
    onBackClick: () -> Unit,
    onRetryClick: () -> Unit,
    onActivityItemClick: (String) -> Unit,
) {
    ScreenColumn {
        AppTopBar(
            titleText = uiState.profile?.name ?: stringResource(R.string.wallet__activity),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                uiState.isLoading && uiState.activities == null -> {
                    GradientCircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                uiState.error != null -> {
                    ErrorState(
                        message = uiState.error,
                        onRetryClick = onRetryClick,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 32.dp)
                    )
                }

                else -> {
                    ContactActivityList(
                        profile = uiState.profile,
                        activities = uiState.activities,
                        onActivityItemClick = onActivityItemClick,
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .testTag("ContactActivityList")
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorState(
    message: String,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth()
    ) {
        BodyM(text = message, color = Colors.White64)
        VerticalSpacer(16.dp)
        SecondaryButton(
            text = stringResource(R.string.common__retry),
            onClick = onRetryClick,
            modifier = Modifier.testTag("ContactActivityRetry")
        )
    }
}

@Composable
private fun ContactActivityList(
    profile: PubkyProfile?,
    activities: ImmutableList<Activity>?,
    onActivityItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = profile?.name
    ActivityListGrouped(
        items = activities,
        onActivityItemClick = onActivityItemClick,
        onEmptyActivityRowClick = {},
        contentPadding = PaddingValues(top = 0.dp),
        activityTestTagPrefix = "ContactActivity",
        showContactAvatar = false,
        titleProvider = { activity ->
            name?.let {
                val titleRes = if (activity.isSent()) {
                    R.string.contacts__activity_sent_to
                } else {
                    R.string.contacts__activity_received_from
                }
                stringResource(titleRes, it)
            }
        },
        modifier = modifier
    )
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = ContactActivityUiState(
                profile = PubkyProfile.placeholder("pubky1"),
                activities = previewActivityItems,
            ),
            onBackClick = {},
            onRetryClick = {},
            onActivityItemClick = {},
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewEmpty() {
    AppThemeSurface {
        Content(
            uiState = ContactActivityUiState(
                profile = PubkyProfile.placeholder("pubky1"),
                activities = persistentListOf(),
            ),
            onBackClick = {},
            onRetryClick = {},
            onActivityItemClick = {},
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewError() {
    AppThemeSurface {
        Content(
            uiState = ContactActivityUiState(
                profile = PubkyProfile.placeholder("pubky1"),
                error = "Failed to load activity",
            ),
            onBackClick = {},
            onRetryClick = {},
            onActivityItemClick = {},
        )
    }
}

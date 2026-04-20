package to.bitkit.ui.screens.profile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.GradientCircularProgressIndicator
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.shared.util.screen
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

private const val PUBKY_RING_PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=to.pubky.ring"
private const val BG_IMAGE_WIDTH_FRACTION = 0.83f
private const val TAG_OFFSET_X = -0.179f
private const val TAG_OFFSET_Y = 0.13f
private const val KEYRING_OFFSET_X = 0.341f
private const val KEYRING_OFFSET_Y = 0.06f
private const val TAG_ALPHA = 0.6f
private const val KEYRING_ALPHA = 0.9f

@Composable
fun PubkyChoiceScreen(
    viewModel: PubkyChoiceViewModel,
    onNavigateToCreateProfile: () -> Unit,
    onNavigateToContactImportOverview: () -> Unit,
    onNavigateToPayContacts: () -> Unit,
    onBackClick: () -> Unit,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.effects.collect {
            when (it) {
                is PubkyChoiceEffect.OpenRingAuth -> runCatching {
                    context.startActivity(it.intent)
                }.onFailure {
                    viewModel.onRingLaunchFailed()
                }
                PubkyChoiceEffect.NavigateToCreateProfile -> onNavigateToCreateProfile()
                PubkyChoiceEffect.NavigateToContactImportOverview -> onNavigateToContactImportOverview()
                PubkyChoiceEffect.NavigateToPayContacts -> onNavigateToPayContacts()
            }
        }
    }

    Content(
        uiState = uiState,
        onBackClick = onBackClick,
        onCreateProfile = onNavigateToCreateProfile,
        onImportWithRing = { viewModel.startRingAuth() },
        onCancelAuth = { viewModel.cancelAuth() },
        onDownloadRing = {
            viewModel.dismissRingNotInstalledDialog()
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PUBKY_RING_PLAY_STORE_URL)))
        },
        onDismissDialog = { viewModel.dismissRingNotInstalledDialog() },
    )
}

@Composable
private fun Content(
    uiState: PubkyChoiceUiState,
    onBackClick: () -> Unit,
    onCreateProfile: () -> Unit,
    onImportWithRing: () -> Unit,
    onCancelAuth: () -> Unit,
    onDownloadRing: () -> Unit,
    onDismissDialog: () -> Unit,
) {
    Box(
        modifier = Modifier
            .screen()
            .clipToBounds()
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(R.drawable.tag_pubky),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth(BG_IMAGE_WIDTH_FRACTION)
                    .align(Alignment.Center)
                    .offset(x = maxWidth * TAG_OFFSET_X, y = maxHeight * TAG_OFFSET_Y)
                    .alpha(TAG_ALPHA)
            )

            Image(
                painter = painterResource(R.drawable.keyring),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth(BG_IMAGE_WIDTH_FRACTION)
                    .align(Alignment.Center)
                    .offset(x = maxWidth * KEYRING_OFFSET_X, y = maxHeight * KEYRING_OFFSET_Y)
                    .alpha(KEYRING_ALPHA)
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(
                titleText = stringResource(R.string.profile__nav_title),
                onBackClick = onBackClick,
                actions = { DrawerNavIcon() },
            )

            Column(modifier = Modifier.padding(horizontal = 32.dp)) {
                VerticalSpacer(24.dp)

                Display(
                    text = stringResource(R.string.profile__choice_title)
                        .withAccent(accentColor = Colors.PubkyGreen),
                    color = Colors.White,
                )
                VerticalSpacer(8.dp)

                BodyM(
                    text = stringResource(R.string.profile__choice_description),
                    color = Colors.White64,
                )
                VerticalSpacer(24.dp)

                if (uiState.isLoadingAfterAuth) {
                    LoadingState(text = stringResource(R.string.profile__choice_loading_profile))
                } else if (uiState.isWaitingForRing) {
                    WaitingForRingState(onCancel = onCancelAuth)
                } else {
                    OptionCard(
                        iconResId = R.drawable.ic_user_plus,
                        text = stringResource(R.string.profile__choice_create),
                        onClick = onCreateProfile,
                        modifier = Modifier.testTag("PubkyChoiceCreate")
                    )
                    VerticalSpacer(8.dp)
                    OptionCard(
                        iconResId = R.drawable.ic_lock_key,
                        text = stringResource(R.string.profile__choice_import),
                        onClick = onImportWithRing,
                        modifier = Modifier.testTag("PubkyChoiceImport")
                    )
                }
            }

            FillHeight()
        }
    }

    if (uiState.showRingNotInstalledDialog) {
        AppAlertDialog(
            title = stringResource(R.string.profile__ring_not_installed_title),
            text = stringResource(R.string.profile__ring_not_installed_description),
            confirmText = stringResource(R.string.profile__ring_download),
            onConfirm = onDownloadRing,
            onDismiss = onDismissDialog,
        )
    }
}

@Composable
private fun OptionCard(
    iconResId: Int,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Colors.Gray6)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .background(Colors.Black, CircleShape)
        ) {
            Icon(
                painter = painterResource(iconResId),
                contentDescription = null,
                tint = Colors.PubkyGreen,
                modifier = Modifier.size(20.dp)
            )
        }
        HorizontalSpacer(16.dp)
        BodyMSB(text = text, color = Colors.White)
    }
}

@Composable
private fun WaitingForRingState(onCancel: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        GradientCircularProgressIndicator(modifier = Modifier.size(20.dp))
        HorizontalSpacer(12.dp)
        BodyM(
            text = stringResource(R.string.profile__choice_waiting_ring),
            color = Colors.White64,
        )
    }
    VerticalSpacer(16.dp)
    SecondaryButton(
        text = stringResource(R.string.common__cancel),
        onClick = onCancel,
    )
}

@Composable
private fun LoadingState(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        GradientCircularProgressIndicator(modifier = Modifier.size(20.dp))
        HorizontalSpacer(12.dp)
        BodyM(text = text, color = Colors.White64)
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = PubkyChoiceUiState(),
            onBackClick = {},
            onCreateProfile = {},
            onImportWithRing = {},
            onCancelAuth = {},
            onDownloadRing = {},
            onDismissDialog = {},
        )
    }
}

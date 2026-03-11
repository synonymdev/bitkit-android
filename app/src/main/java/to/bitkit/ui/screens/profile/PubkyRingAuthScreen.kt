package to.bitkit.ui.screens.profile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.shared.util.screen
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

private const val PUBKY_RING_PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=to.pubky.ring"
private const val BG_IMAGE_WIDTH_FRACTION = 0.83f
private const val TAG_OFFSET_X = -0.179f
private const val TAG_OFFSET_Y = -0.124f
private const val KEYRING_OFFSET_X = 0.341f
private const val KEYRING_OFFSET_Y = -0.195f
private const val TAG_ALPHA = 0.6f
private const val KEYRING_ALPHA = 0.9f

@Composable
fun PubkyRingAuthScreen(
    viewModel: PubkyRingAuthViewModel,
    onBackClick: () -> Unit,
    onAuthenticated: () -> Unit,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.effects.collect {
            when (it) {
                PubkyRingAuthEffect.Authenticated -> onAuthenticated()
            }
        }
    }

    Content(
        uiState = uiState,
        onBackClick = onBackClick,
        onDownload = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PUBKY_RING_PLAY_STORE_URL)))
        },
        onAuthorize = { viewModel.authenticate() },
        onDismissDialog = { viewModel.dismissRingNotInstalledDialog() },
    )
}

@Composable
private fun Content(
    uiState: PubkyRingAuthUiState,
    onBackClick: () -> Unit,
    onDownload: () -> Unit,
    onAuthorize: () -> Unit,
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
            )

            FillHeight()

            Column(modifier = Modifier.padding(horizontal = 32.dp)) {
                Image(
                    painter = painterResource(R.drawable.pubky_ring_logo),
                    contentDescription = null,
                    modifier = Modifier.height(36.dp)
                )
                VerticalSpacer(24.dp)

                Display(
                    text = stringResource(R.string.profile__ring_auth_title)
                        .withAccent(accentColor = Colors.PubkyGreen),
                    color = Colors.White,
                )
                VerticalSpacer(8.dp)

                BodyM(
                    text = if (uiState.isWaitingForRing) {
                        stringResource(R.string.profile__ring_waiting)
                    } else {
                        stringResource(R.string.profile__ring_auth_description)
                    },
                    color = Colors.White64,
                )
                VerticalSpacer(24.dp)

                Row {
                    SecondaryButton(
                        text = stringResource(R.string.profile__ring_download),
                        onClick = onDownload,
                        modifier = Modifier.weight(1f)
                    )
                    HorizontalSpacer(16.dp)
                    PrimaryButton(
                        text = stringResource(R.string.profile__ring_authorize),
                        isLoading = uiState.isAuthenticating,
                        onClick = onAuthorize,
                        modifier = Modifier.weight(1f)
                    )
                }
                VerticalSpacer(16.dp)
            }
        }
    }

    if (uiState.showRingNotInstalledDialog) {
        AppAlertDialog(
            title = stringResource(R.string.profile__ring_not_installed_title),
            text = stringResource(R.string.profile__ring_not_installed_description),
            confirmText = stringResource(R.string.profile__ring_download),
            onConfirm = {
                onDismissDialog()
                onDownload()
            },
            onDismiss = onDismissDialog,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = PubkyRingAuthUiState(),
            onBackClick = {},
            onDownload = {},
            onAuthorize = {},
            onDismissDialog = {},
        )
    }
}

package to.bitkit.ui.sheets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.models.PubkySwapNetwork
import to.bitkit.models.PubkySwapProvider
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Caption
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.PubkyImage
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.components.Title
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun PubkySwapConnectSheet(
    sheet: Sheet.PubkySwapConnect,
    viewModel: PubkySwapConnectViewModel,
    onDismiss: () -> Unit,
    onSetUpProfile: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(sheet.provider) { viewModel.load(sheet.provider) }
    LaunchedEffect(Unit) {
        viewModel.effects.collect {
            when (it) {
                PubkySwapConnectEffect.Dismiss -> onDismiss()
                PubkySwapConnectEffect.SetUpProfile -> onSetUpProfile()
            }
        }
    }

    Content(
        provider = sheet.provider,
        uiState = uiState,
        onCancel = onDismiss,
        onConnect = viewModel::connect,
        onSetUpProfile = viewModel::setUpProfile,
    )
}

@Composable
private fun Content(
    provider: PubkySwapProvider,
    uiState: PubkySwapConnectUiState,
    modifier: Modifier = Modifier,
    onCancel: () -> Unit = {},
    onConnect: () -> Unit = {},
    onSetUpProfile: () -> Unit = {},
) {
    val identity = uiState.identity
    Column(
        modifier = modifier
            .sheetHeight(SheetSize.MEDIUM)
            .gradientBackground()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .testTag("PubkySwapConnectSheet")
    ) {
        SheetTopBar(titleText = stringResource(R.string.pubky_swap__sheet_title))
        VerticalSpacer(16.dp)

        Column(
            modifier = Modifier.weight(1f)
        ) {
            ProviderHeader(
                name = uiState.providerProfile?.name?.takeIf { it.isNotBlank() },
                imageUrl = uiState.providerProfile?.imageUrl,
                publicKey = provider.publicKey,
            )
            VerticalSpacer(24.dp)

            BodyM(
                text = stringResource(R.string.pubky_swap__sheet_description),
                color = Colors.White64,
            )
            VerticalSpacer(20.dp)

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DetailRow(
                    iconRes = R.drawable.ic_transfer,
                    title = stringResource(R.string.pubky_swap__network_label),
                    subtitle = provider.network.wireValue,
                )
                DetailRow(
                    iconRes = R.drawable.ic_user,
                    title = stringResource(R.string.pubky_swap__identity_label),
                    subtitle = identity?.let(PubkyPublicKeyFormat::display)
                        ?: stringResource(R.string.pubky_swap__identity_missing),
                )
                uiState.replacedProvider?.let {
                    DetailRow(
                        iconRes = R.drawable.ic_warning,
                        title = stringResource(R.string.pubky_swap__replaces_label),
                        subtitle = PubkyPublicKeyFormat.display(it.publicKey),
                    )
                }
            }

            when {
                uiState.errorText != null -> uiState.errorText
                identity == null && !uiState.canSetUpProfile -> stringResource(R.string.pubky_swap__profile_disabled)
                else -> null
            }?.let {
                VerticalSpacer(16.dp)
                BodyS(
                    text = it,
                    color = Colors.Red,
                    modifier = Modifier.testTag("PubkySwapConnectError")
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SecondaryButton(
                text = stringResource(R.string.common__cancel),
                onClick = onCancel,
                fullWidth = false,
                enabled = !uiState.isConnecting,
                modifier = Modifier
                    .weight(1f)
                    .testTag("PubkySwapConnectCancel")
            )
            if (identity == null) {
                PrimaryButton(
                    text = stringResource(R.string.pubky_swap__set_up_profile),
                    onClick = onSetUpProfile,
                    enabled = uiState.canSetUpProfile,
                    fullWidth = false,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("PubkySwapConnectSetUpProfile")
                )
            } else {
                PrimaryButton(
                    text = stringResource(R.string.common__connect),
                    onClick = onConnect,
                    isLoading = uiState.isConnecting,
                    fullWidth = false,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("PubkySwapConnectConnect")
                )
            }
        }
        VerticalSpacer(16.dp)
    }
}

@Composable
private fun ProviderHeader(
    name: String?,
    imageUrl: String?,
    publicKey: String,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        if (imageUrl != null) {
            PubkyImage(uri = imageUrl, size = 84.dp)
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(Colors.White08)
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_lightning),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        HorizontalSpacer(16.dp)
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Caption(
                text = stringResource(R.string.pubky_swap__provider_label),
                color = Colors.White64,
            )
            VerticalSpacer(6.dp)
            Title(
                text = name ?: PubkyPublicKeyFormat.display(publicKey),
                color = Colors.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DetailRow(
    iconRes: Int,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Colors.White08)
            .padding(16.dp)
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )
        HorizontalSpacer(12.dp)
        Column(
            modifier = Modifier.weight(1f)
        ) {
            BodyMSB(
                text = title,
                color = Colors.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            VerticalSpacer(4.dp)
            BodyS(
                text = subtitle,
                color = Colors.White64,
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    val provider = PubkySwapProvider(
        publicKey = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xy",
        network = PubkySwapNetwork.REGTEST,
    )
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                provider = provider,
                uiState = PubkySwapConnectUiState(
                    provider = provider,
                    identity = "pubky8pinxxgqs41n4aididenw5apqp1urfmzdztr8jt4abrkdn435ewo",
                ),
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewNoIdentity() {
    val provider = PubkySwapProvider(
        publicKey = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xy",
        network = PubkySwapNetwork.REGTEST,
    )
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                provider = provider,
                uiState = PubkySwapConnectUiState(provider = provider, canSetUpProfile = true),
            )
        }
    }
}

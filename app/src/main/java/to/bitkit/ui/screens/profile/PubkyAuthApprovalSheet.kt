package to.bitkit.ui.screens.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.R
import to.bitkit.models.PubkyAuthPermission
import to.bitkit.models.PubkyProfile
import to.bitkit.ui.components.BiometricsView
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.CenteredProfileHeader
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.rememberBiometricAuthSupported
import to.bitkit.ui.utils.withAccentBoldBright

@Composable
fun PubkyAuthApprovalSheet(
    authUrl: String,
    viewModel: PubkyAuthApprovalViewModel,
    onDismiss: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showBiometrics by remember { mutableStateOf(false) }
    var pendingAuthUrl by remember { mutableStateOf<String?>(null) }

    val settings = settingsViewModel ?: return
    val isPinEnabled by settings.isPinEnabled.collectAsStateWithLifecycle()
    val isBiometricEnabled by settings.isBiometricEnabled.collectAsStateWithLifecycle()
    val isBiometrySupported = rememberBiometricAuthSupported()

    LaunchedEffect(authUrl) { viewModel.load(authUrl) }

    LaunchedEffect(Unit) {
        viewModel.effects.collect {
            when (it) {
                is PubkyAuthApprovalEffect.RequestBiometric -> {
                    if (isPinEnabled && isBiometricEnabled && isBiometrySupported) {
                        pendingAuthUrl = it.authUrl
                        showBiometrics = true
                    } else {
                        viewModel.confirmAuthorize(it.authUrl)
                    }
                }
                PubkyAuthApprovalEffect.Dismiss -> onDismiss()
            }
        }
    }

    Box {
        Content(
            uiState = uiState,
            onAuthorize = { viewModel.requestAuthorize(authUrl) },
            onCancel = { viewModel.dismiss() },
            onDismiss = { viewModel.dismiss() },
        )

        if (showBiometrics) {
            BiometricsView(
                onSuccess = {
                    showBiometrics = false
                    pendingAuthUrl?.let { viewModel.confirmAuthorize(it) }
                    pendingAuthUrl = null
                },
                onFailure = {
                    showBiometrics = false
                    pendingAuthUrl = null
                },
            )
        }
    }
}

@Composable
private fun Content(
    uiState: PubkyAuthApprovalUiState,
    onAuthorize: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val headerTitle = if (uiState.state == ApprovalState.Success) {
        stringResource(R.string.profile__auth_approval_success)
    } else {
        stringResource(R.string.profile__auth_approval_title)
    }

    Column(
        modifier = Modifier
            .sheetHeight(SheetSize.LARGE)
            .gradientBackground()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        SheetTopBar(titleText = headerTitle)

        when (uiState.state) {
            ApprovalState.Authorize -> AuthorizeContent(
                uiState = uiState,
                onAuthorize = onAuthorize,
                onCancel = onCancel,
            )
            ApprovalState.Authorizing -> AuthorizingContent(
                uiState = uiState,
            )
            ApprovalState.Success -> SuccessContent(
                uiState = uiState,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun ColumnScope.AuthorizeContent(
    uiState: PubkyAuthApprovalUiState,
    onAuthorize: () -> Unit,
    onCancel: () -> Unit,
) {
    DescriptionText(serviceName = uiState.serviceName)
    VerticalSpacer(32.dp)

    PermissionsSection(permissions = uiState.permissions)
    VerticalSpacer(16.dp)

    FillHeight()

    TrustWarning()
    VerticalSpacer(16.dp)

    uiState.profile?.let { ProfileCard(it) }
    VerticalSpacer(24.dp)

    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SecondaryButton(
            text = stringResource(R.string.common__cancel),
            onClick = onCancel,
            modifier = Modifier.weight(1f),
        )
        PrimaryButton(
            text = stringResource(R.string.profile__auth_approval_authorize),
            onClick = onAuthorize,
            modifier = Modifier.weight(1f),
        )
    }
    VerticalSpacer(16.dp)
}

@Composable
private fun ColumnScope.AuthorizingContent(
    uiState: PubkyAuthApprovalUiState,
) {
    DescriptionText(serviceName = uiState.serviceName)
    VerticalSpacer(32.dp)

    PermissionsSection(permissions = uiState.permissions)
    VerticalSpacer(16.dp)

    FillHeight()

    TrustWarning()
    VerticalSpacer(16.dp)

    uiState.profile?.let { ProfileCard(it) }
    VerticalSpacer(24.dp)

    PrimaryButton(
        text = stringResource(R.string.profile__auth_approval_authorizing),
        onClick = {},
        isLoading = true,
        enabled = false,
    )
    VerticalSpacer(16.dp)
}

@Composable
private fun ColumnScope.SuccessContent(
    uiState: PubkyAuthApprovalUiState,
    onDismiss: () -> Unit,
) {
    SuccessDescriptionText(
        serviceName = uiState.serviceName,
        truncatedKey = uiState.profile?.truncatedPublicKey ?: "",
    )
    VerticalSpacer(16.dp)

    FillHeight()

    Image(
        painter = painterResource(R.drawable.check),
        contentDescription = null,
        modifier = Modifier
            .size(256.dp)
            .align(Alignment.CenterHorizontally),
    )

    FillHeight()

    PrimaryButton(
        text = stringResource(R.string.profile__auth_approval_ok),
        onClick = onDismiss,
    )
    VerticalSpacer(16.dp)
}

@Composable
private fun DescriptionText(serviceName: String) {
    BodyM(
        text = stringResource(R.string.profile__auth_approval_service, serviceName)
            .withAccentBoldBright(),
        color = Colors.White64,
    )
}

@Composable
private fun SuccessDescriptionText(serviceName: String, truncatedKey: String) {
    BodyM(
        text = stringResource(R.string.profile__auth_approval_success_detail, truncatedKey, serviceName)
            .withAccentBoldBright(),
        color = Colors.White64,
    )
}

@Composable
private fun PermissionsSection(permissions: ImmutableList<PubkyAuthPermission>) {
    Text13Up(
        text = stringResource(R.string.profile__auth_approval_permissions),
        color = Colors.White64,
    )
    VerticalSpacer(8.dp)

    permissions.forEach { permission ->
        PermissionRow(permission)
        VerticalSpacer(4.dp)
    }

    VerticalSpacer(4.dp)
    HorizontalDivider(color = Colors.White10)
}

@Composable
private fun PermissionRow(permission: PubkyAuthPermission) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_folder),
            contentDescription = null,
            tint = Colors.White,
            modifier = Modifier.size(14.dp),
        )
        HorizontalSpacer(4.dp)
        BodySSB(
            text = permission.path,
            modifier = Modifier.weight(1f),
        )
        Text13Up(
            text = permission.displayAccess,
            color = Colors.Gray1,
        )
    }
}

@Composable
private fun TrustWarning() {
    BodyM(
        text = stringResource(R.string.profile__auth_approval_trust_warning),
        color = Colors.White64,
    )
}

@Composable
private fun ProfileCard(profile: PubkyProfile) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .background(Colors.Gray6, RoundedCornerShape(16.dp))
            .padding(24.dp),
    ) {
        CenteredProfileHeader(
            publicKey = profile.publicKey,
            name = profile.name,
            bio = "",
            imageUrl = profile.imageUrl,
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun AuthorizePreview() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                uiState = PubkyAuthApprovalUiState(
                    state = ApprovalState.Authorize,
                    serviceName = "pubky.app",
                    permissions = persistentListOf(
                        PubkyAuthPermission(path = "/pub/pubky.app/", accessLevel = "rw"),
                        PubkyAuthPermission(path = "/pub/paykit/v0/", accessLevel = "rw"),
                    ),
                    profile = PubkyProfile(
                        publicKey = "pk8e3qm5f4kgczagxhertyuiop1gxag",
                        name = "Satoshi Nakamoto",
                        bio = "",
                        imageUrl = null,
                        links = emptyList(),
                        status = null,
                    ),
                ),
                onAuthorize = {},
                onCancel = {},
                onDismiss = {},
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun SuccessPreview() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                uiState = PubkyAuthApprovalUiState(
                    state = ApprovalState.Success,
                    serviceName = "pubky.app",
                ),
                onAuthorize = {},
                onCancel = {},
                onDismiss = {},
            )
        }
    }
}

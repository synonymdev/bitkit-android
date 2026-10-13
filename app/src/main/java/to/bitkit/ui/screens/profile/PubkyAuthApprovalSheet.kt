package to.bitkit.ui.screens.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.R
import to.bitkit.models.PubkyAuthClaim
import to.bitkit.models.PubkyAuthClaim.Item
import to.bitkit.models.PubkyAuthPermission
import to.bitkit.models.PubkyProfile
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.AuthCheckView
import to.bitkit.ui.components.BiometricsView
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.PubkyImage
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SettingsSwitchRow
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.theme.AppSwitchDefaults
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.copyToClipboard
import to.bitkit.ui.utils.rememberBiometricAuthSupported
import to.bitkit.ui.utils.removeAccentTags
import to.bitkit.ui.utils.withAccent
import to.bitkit.ui.utils.withAccentBoldBright

private const val ADDRESS_PREFIX_LENGTH = 8
private const val ADDRESS_SUFFIX_LENGTH = 6

@Composable
fun PubkyAuthApprovalSheet(
    authUrl: String,
    viewModel: PubkyAuthApprovalViewModel,
    onDismiss: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    DisposableEffect(viewModel, authUrl) {
        onDispose { viewModel.cancelLocalAuth(authUrl) }
    }

    LaunchedEffect(authUrl) { viewModel.load(authUrl) }

    Box {
        Content(
            uiState = uiState,
            isCurrentRequest = uiState.authUrl == authUrl,
            onAuthorize = {
                if (uiState.authUrl == authUrl) viewModel.requestAuthorize(authUrl)
            },
            onApproveWatchOnly = {
                if (uiState.authUrl == authUrl) viewModel.approveWatchOnlyConsent(authUrl)
            },
            onBackToWatchOnly = {
                if (uiState.authUrl == authUrl) viewModel.returnToWatchOnlyConsent(authUrl)
            },
            onRetryUsdt = { viewModel.loadUsdtAddress(authUrl) },
            onShareUsdt = viewModel::setShareUsdt,
            onCancel = { viewModel.dismiss() },
            onDismiss = { viewModel.dismiss() },
        )

        PubkyAuthorizationLocalAuth(viewModel = viewModel, onDismiss = onDismiss)
    }
}

@Composable
private fun PubkyAuthorizationLocalAuth(
    viewModel: PubkyAuthApprovalViewModel,
    onDismiss: () -> Unit,
) {
    var showBiometrics by remember { mutableStateOf(false) }
    var showAuthCheck by remember { mutableStateOf(false) }
    var pendingAuthUrl by remember { mutableStateOf<String?>(null) }

    val app = appViewModel ?: return
    val settings = settingsViewModel ?: return
    val isPinEnabled by settings.isPinEnabled.collectAsStateWithLifecycle()
    val isBiometricEnabled by settings.isBiometricEnabled.collectAsStateWithLifecycle()
    val isBiometrySupported = rememberBiometricAuthSupported()

    LaunchedEffect(Unit) {
        viewModel.effects.collect {
            when (it) {
                is PubkyAuthApprovalEffect.RequestLocalAuth -> {
                    pendingAuthUrl = it.authUrl
                    when (
                        resolvePubkyApprovalLocalAuthMode(
                            isPinEnabled = isPinEnabled,
                            isBiometricEnabled = isBiometricEnabled,
                            isBiometrySupported = isBiometrySupported,
                        )
                    ) {
                        PubkyApprovalLocalAuthMode.AuthCheck -> {
                            showBiometrics = false
                            showAuthCheck = true
                        }

                        PubkyApprovalLocalAuthMode.Biometrics -> {
                            showAuthCheck = false
                            showBiometrics = true
                        }

                        PubkyApprovalLocalAuthMode.None -> {
                            pendingAuthUrl = null
                            viewModel.confirmAuthorize(it.authUrl)
                        }
                    }
                }
                PubkyAuthApprovalEffect.Dismiss -> onDismiss()
            }
        }
    }

    if (showAuthCheck) {
        AuthCheckView(
            appViewModel = app,
            settingsViewModel = settings,
            onSuccess = {
                showAuthCheck = false
                pendingAuthUrl?.let { viewModel.confirmAuthorize(it) }
                pendingAuthUrl = null
            },
            onBack = {
                showAuthCheck = false
                pendingAuthUrl?.let(viewModel::cancelLocalAuth)
                pendingAuthUrl = null
            },
        )
    }

    if (showBiometrics) {
        BiometricsView(
            onSuccess = {
                showBiometrics = false
                pendingAuthUrl?.let { viewModel.confirmAuthorize(it) }
                pendingAuthUrl = null
            },
            onFailure = {
                showBiometrics = false
                pendingAuthUrl?.let(viewModel::cancelLocalAuth)
                pendingAuthUrl = null
            },
        )
    }
}

internal enum class PubkyApprovalLocalAuthMode {
    None,
    Biometrics,
    AuthCheck,
}

internal fun resolvePubkyApprovalLocalAuthMode(
    isPinEnabled: Boolean,
    isBiometricEnabled: Boolean,
    isBiometrySupported: Boolean,
): PubkyApprovalLocalAuthMode = when {
    isPinEnabled -> PubkyApprovalLocalAuthMode.AuthCheck
    isBiometricEnabled && isBiometrySupported -> PubkyApprovalLocalAuthMode.Biometrics
    else -> PubkyApprovalLocalAuthMode.None
}

@Composable
private fun Content(
    uiState: PubkyAuthApprovalUiState,
    isCurrentRequest: Boolean,
    onAuthorize: () -> Unit,
    onApproveWatchOnly: () -> Unit,
    onBackToWatchOnly: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onRetryUsdt: () -> Unit = {},
    onShareUsdt: (Boolean) -> Unit = {},
) {
    val approvalState = if (isCurrentRequest) uiState.state else ApprovalState.Loading
    val headerTitle = approvalHeaderTitle(approvalState)
    val onBack = approvalBackAction(
        approvalState = approvalState,
        showsReceivingDetails = uiState.showsReceivingDetails,
        onBackToWatchOnly = onBackToWatchOnly,
        onCancel = onCancel,
        onDismiss = onDismiss,
    )

    Column(
        modifier = Modifier
            .sheetHeight(SheetSize.LARGE)
            .background(Colors.Gray7)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        SheetTopBar(titleText = headerTitle, onBack = onBack)

        when (approvalState) {
            ApprovalState.Loading -> LoadingContent()
            ApprovalState.WatchOnlyConsent -> WatchOnlyConsentContent(
                claim = uiState.bitkitClaim,
                sharesUsdt = uiState.showsUsdtDetails,
                onApprove = onApproveWatchOnly,
                onCancel = onCancel,
            )
            ApprovalState.Authorize -> AuthorizeContent(
                uiState = uiState,
                onAuthorize = onAuthorize,
                onRetryUsdt = onRetryUsdt,
                onShareUsdt = onShareUsdt,
                onCancel = onCancel,
            )
            ApprovalState.Authenticating, ApprovalState.Authorizing -> AuthorizingContent(
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
private fun approvalHeaderTitle(approvalState: ApprovalState): String = when (approvalState) {
    ApprovalState.WatchOnlyConsent -> stringResource(R.string.profile__auth_approval_watch_only_intro_nav_title)
    ApprovalState.Success -> stringResource(R.string.profile__auth_approval_success)
    else -> stringResource(R.string.profile__auth_approval_title)
}

private fun approvalBackAction(
    approvalState: ApprovalState,
    showsReceivingDetails: Boolean,
    onBackToWatchOnly: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
): (() -> Unit)? = when (approvalState) {
    ApprovalState.Authorize if showsReceivingDetails -> onBackToWatchOnly
    ApprovalState.Authorize, ApprovalState.Authenticating, ApprovalState.Authorizing -> onCancel
    ApprovalState.Success -> onDismiss
    else -> null
}

@Composable
private fun ColumnScope.WatchOnlyConsentContent(
    claim: PubkyAuthClaim?,
    sharesUsdt: Boolean,
    onApprove: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .testTag("PubkyAuthWatchOnlyConsent")
    ) {
        FillHeight(min = 32.dp)

        Image(
            painter = painterResource(R.drawable.coin_stack_4),
            contentDescription = null,
            modifier = Modifier
                .size(256.dp)
                .align(Alignment.CenterHorizontally)
                .graphicsLayer {
                    scaleX = COIN_SCALE
                    scaleY = COIN_SCALE
                    translationY = COIN_OFFSET_Y.toPx()
                },
        )

        VerticalSpacer(32.dp)

        Display(
            text = stringResource(
                when {
                    sharesUsdt && claim?.sharesBitcoin != true ->
                        R.string.profile__auth_approval_usdt_intro_title
                    sharesUsdt && claim?.sharesBitcoin == true ->
                        R.string.profile__auth_approval_payment_details_intro_title
                    else -> R.string.profile__auth_approval_watch_only_intro_title
                }
            ).withAccent(
                accentColor = if (sharesUsdt && claim?.sharesBitcoin != true) Colors.Usdt else Colors.Blue
            ),
        )
        VerticalSpacer(8.dp)
        BodyM(
            text = stringResource(
                when {
                    sharesUsdt && claim?.sharesBitcoin != true ->
                        R.string.profile__auth_approval_usdt_intro_description
                    sharesUsdt && claim?.sharesBitcoin == true ->
                        R.string.profile__auth_approval_payment_details_intro_description
                    else -> R.string.profile__auth_approval_watch_only_intro_description
                }
            ),
            color = Colors.White64,
        )

        VerticalSpacer(32.dp)

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SecondaryButton(
                text = stringResource(R.string.common__cancel),
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .testTag("PubkyAuthWatchOnlyCancel"),
            )
            PrimaryButton(
                text = stringResource(R.string.profile__auth_approval_watch_only_intro_approve),
                onClick = onApprove,
                modifier = Modifier
                    .weight(1f)
                    .testTag("PubkyAuthWatchOnlyApprove"),
            )
        }
        VerticalSpacer(16.dp)
    }
}

@Composable
private fun ColumnScope.LoadingContent() {
    FillHeight()
    PrimaryButton(
        text = stringResource(R.string.profile__auth_approval_authorizing),
        onClick = {},
        isLoading = true,
        enabled = false,
    )
    VerticalSpacer(16.dp)
}

@Composable
private fun ColumnScope.AuthorizeContent(
    uiState: PubkyAuthApprovalUiState,
    onRetryUsdt: () -> Unit,
    onShareUsdt: (Boolean) -> Unit,
    onAuthorize: () -> Unit,
    onCancel: () -> Unit,
) {
    ApprovalDetails(uiState = uiState, onRetryUsdt = onRetryUsdt, onShareUsdt = onShareUsdt)

    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SecondaryButton(
            text = stringResource(R.string.common__cancel),
            onClick = onCancel,
            modifier = Modifier.weight(1f),
        )
        PrimaryButton(
            text = stringResource(R.string.profile__auth_approval_authorize),
            onClick = onAuthorize,
            enabled = uiState.canAuthorize,
            modifier = Modifier
                .weight(1f)
                .testTag("PubkyAuthAuthorize")
        )
    }
    VerticalSpacer(16.dp)
}

@Composable
private fun ColumnScope.AuthorizingContent(
    uiState: PubkyAuthApprovalUiState,
) {
    ApprovalDetails(uiState = uiState)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(ButtonSize.Large.height)
    ) {
        BodySSB(
            text = stringResource(R.string.profile__auth_approval_authorizing),
            color = Colors.White32,
        )
    }
    VerticalSpacer(16.dp)
}

@Composable
private fun ColumnScope.ApprovalDetails(
    uiState: PubkyAuthApprovalUiState,
    onRetryUsdt: (() -> Unit)? = null,
    onShareUsdt: ((Boolean) -> Unit)? = null,
) {
    BoxWithConstraints(modifier = Modifier.weight(1f)) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
        ) {
            Column {
                VerticalSpacer(8.dp)

                if (uiState.createsIdentity) {
                    BodyM(text = stringResource(R.string.pubky_auth__signup_description), color = Colors.White64)
                    VerticalSpacer(16.dp)
                }
                if (uiState.permissions.isNotEmpty()) {
                    DescriptionText(clientId = uiState.clientId, serviceName = uiState.serviceName)
                    VerticalSpacer(32.dp)
                }
                if (uiState.showsReceivingDetails) {
                    PaymentDetailsSection(uiState, onRetryUsdt, onShareUsdt)
                    VerticalSpacer(24.dp)
                }
                if (uiState.usdtRequired && !uiState.isUsdtEnabled) {
                    BodyM(text = stringResource(R.string.profile__auth_approval_usdt_disabled), color = Colors.White64)
                    VerticalSpacer(24.dp)
                }
                if (uiState.permissions.isNotEmpty()) {
                    PermissionsSection(permissions = uiState.permissions)
                }
                if (uiState.bitkitClaim?.includesPaykitAccess == true) {
                    VerticalSpacer(32.dp)
                    PaykitAccessSection()
                }
            }

            Column {
                TrustWarning()
                VerticalSpacer(16.dp)

                uiState.homeserverPublicKey?.takeIf { uiState.createsIdentity }?.let { homeserver ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Colors.Gray6, RoundedCornerShape(16.dp))
                            .padding(24.dp)
                            .testTag("PubkySignupHomeserver")
                    ) {
                        Text13Up(text = stringResource(R.string.pubky_auth__homeserver), color = Colors.White64)
                        BodyMSB(text = homeserver)
                    }
                } ?: uiState.profile?.let { ProfileCard(it) }
                VerticalSpacer(24.dp)
            }
        }
    }
}

@Composable
private fun PaykitAccessSection() {
    Section(
        title = stringResource(R.string.profile__auth_approval_details),
        modifier = Modifier.testTag("PubkyAuthPaykitAccess")
    ) {
        BodyS(text = stringResource(R.string.profile__auth_approval_paykit_access_description), letterSpacing = 0.sp)
    }
}

@Composable
private fun ColumnScope.SuccessContent(
    uiState: PubkyAuthApprovalUiState,
    onDismiss: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.weight(1f)) {
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
        ) {
            SuccessDescriptionText(
                clientId = uiState.clientId,
                serviceName = uiState.serviceName,
                truncatedKey = uiState.profile?.authDisplayPublicKey.orEmpty(),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
            )

            uiState.bitkitClaim?.takeIf { it.sharesBitcoin || (it.sharesUsdt && uiState.shareUsdt) }?.let { claim ->
                BodyM(
                    text = stringResource(
                        when {
                            !claim.sharesBitcoin -> R.string.profile__auth_approval_shared_usdt
                            claim.sharesUsdt && uiState.shareUsdt -> R.string.profile__auth_approval_shared_both
                            else -> R.string.profile__auth_approval_shared_bitcoin
                        }
                    ),
                    modifier = Modifier.testTag("PubkyAuthSharedDetails")
                )
            }
            Image(
                painter = painterResource(R.drawable.check),
                contentDescription = null,
                modifier = Modifier
                    .size(256.dp)
                    .graphicsLayer {
                        scaleX = CHECK_SCALE
                        scaleY = CHECK_SCALE
                    },
            )

            VerticalSpacer(16.dp)
        }
    }

    PrimaryButton(
        text = stringResource(R.string.profile__auth_approval_ok),
        onClick = onDismiss,
        modifier = Modifier.testTag("PubkyAuthOK")
    )
    VerticalSpacer(16.dp)
}

@Composable
private fun PaymentDetailsSection(
    uiState: PubkyAuthApprovalUiState,
    onRetryUsdt: (() -> Unit)?,
    onShareUsdt: ((Boolean) -> Unit)?,
) {
    Text13Up(text = stringResource(R.string.profile__auth_approval_payment_details), color = Colors.White64)
    VerticalSpacer(12.dp)
    if (uiState.bitkitClaim?.sharesBitcoin == true) {
        Column(modifier = Modifier.testTag("PubkyAuthBitcoinDetails")) {
            BodySSB(text = "Bitcoin", color = Colors.Brand)
            VerticalSpacer(4.dp)
            BodyS(text = stringResource(R.string.profile__auth_approval_bitcoin_account), color = Colors.White64)
        }
        VerticalSpacer(12.dp)
    }
    if (uiState.showsUsdtDetails) {
        Column(modifier = Modifier.testTag("PubkyAuthUsdtDetails")) {
            if (uiState.usdtRequired) {
                BodySSB(text = "USDT", color = Colors.Usdt)
            } else {
                SettingsSwitchRow(
                    title = stringResource(R.string.profile__auth_approval_share_usdt_optional),
                    isChecked = uiState.shareUsdt,
                    onClick = { onShareUsdt?.invoke(!uiState.shareUsdt) },
                    enabled = onShareUsdt != null,
                    colors = AppSwitchDefaults.colorsUsdt,
                    switchTestTag = "PubkyAuthShareUsdt",
                )
            }
            VerticalSpacer(8.dp)
            uiState.usdtAddress?.let { address ->
                var expanded by remember(address) { mutableStateOf(false) }
                BodyS(
                    text = if (expanded) {
                        address
                    } else {
                        address.take(ADDRESS_PREFIX_LENGTH) + "…" + address.takeLast(ADDRESS_SUFFIX_LENGTH)
                    },
                    modifier = Modifier.clickable { expanded = !expanded }.testTag("PubkyAuthUsdtAddress")
                )
                if (expanded) {
                    VerticalSpacer(8.dp)
                    BodySSB(
                        text = stringResource(R.string.common__copy),
                        color = Colors.Usdt,
                        modifier = Modifier
                            .clickable(onClick = copyToClipboard(address))
                            .testTag("PubkyAuthCopyUsdtAddress")
                    )
                }
            } ?: BodyS(
                text = stringResource(
                    when {
                        uiState.usdtUnavailable && uiState.usdtRequired -> R.string.usdt__error_configuration
                        uiState.usdtUnavailable -> R.string.profile__auth_approval_usdt_unavailable
                        else -> R.string.profile__auth_approval_usdt_loading
                    }
                ),
                color = Colors.White64,
            )
            if (uiState.usdtUnavailable && onRetryUsdt != null) {
                VerticalSpacer(8.dp)
                BodySSB(
                    text = stringResource(R.string.common__retry),
                    color = Colors.Usdt,
                    modifier = Modifier.clickable(onClick = onRetryUsdt)
                )
            }
            VerticalSpacer(8.dp)
            BodyS(text = stringResource(R.string.profile__auth_approval_usdt_privacy), color = Colors.White64)
        }
        VerticalSpacer(12.dp)
    }
    HorizontalDivider(color = Colors.White10)
}

@Composable
private fun DescriptionText(clientId: String, serviceName: String) {
    val requester = clientId.asLiteralRequestText()
    val service = serviceName.asLiteralRequestText()
    val text = if (requester.isNotBlank()) {
        stringResource(R.string.profile__auth_approval_service_named, requester, service)
    } else {
        stringResource(R.string.profile__auth_approval_service, service)
    }
    BodyM(text = text.withAccentBoldBright(), color = Colors.White64, letterSpacing = 0.sp)
}

@Composable
private fun SuccessDescriptionText(
    clientId: String,
    serviceName: String,
    truncatedKey: String,
    modifier: Modifier = Modifier,
) {
    val requester = clientId.asLiteralRequestText()
    val service = serviceName.asLiteralRequestText()
    val text = if (requester.isNotBlank()) {
        stringResource(R.string.profile__auth_approval_success_detail_named, truncatedKey, requester, service)
    } else {
        stringResource(R.string.profile__auth_approval_success_detail, truncatedKey, service)
    }
    BodyM(text = text.withAccentBoldBright(), color = Colors.White64, letterSpacing = 0.sp, modifier = modifier)
}

@Composable
private fun Section(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Text13Up(text = title, color = Colors.White64)
        content()
    }
}

@Composable
private fun PermissionsSection(permissions: ImmutableList<PubkyAuthPermission>) {
    Section(title = stringResource(R.string.profile__auth_approval_permissions)) {
        permissions.forEach { PermissionRow(it) }
    }
}

@Composable
private fun PermissionRow(permission: PubkyAuthPermission) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_folder),
            contentDescription = null,
            tint = Colors.White,
            modifier = Modifier.size(16.dp)
        )
        HorizontalSpacer(4.dp)
        BodySSB(
            text = permission.displayPath,
            modifier = Modifier.weight(1f)
        )
        Text13Up(
            text = permission.displayAccess,
            color = Colors.Gray1,
        )
    }
}

@Composable
private fun TrustWarning() {
    Section(title = stringResource(R.string.profile__auth_approval_before_continue)) {
        BodyS(
            text = stringResource(R.string.profile__auth_approval_trust_warning),
            color = Colors.White64,
            letterSpacing = 0.sp,
        )
    }
}

@Composable
private fun ProfileCard(profile: PubkyProfile) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(Colors.Gray6, RoundedCornerShape(16.dp))
            .padding(24.dp)
            .testTag("PubkyAuthProfile")
    ) {
        if (profile.imageUrl != null) {
            PubkyImage(uri = profile.imageUrl, size = 48.dp)
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Colors.Gray5)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_user_square),
                    contentDescription = null,
                    tint = Colors.White32,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text13Up(
                text = profile.authDisplayPublicKey,
                color = Colors.White64,
                maxLines = 1,
            )
            BodyMSB(
                text = profile.name,
                letterSpacing = 0.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Figma draws the coin illustration at 329dp inside its 256dp slot, shifted 7dp down. */
private const val COIN_SCALE = 329f / 256f
private val COIN_OFFSET_Y = 7.dp

/** Figma draws the check illustration at 274dp inside its 256dp slot. */
private const val CHECK_SCALE = 274f / 256f

private val PubkyProfile.authDisplayPublicKey: String
    get() = pubkyAuthDisplayPublicKey(publicKey)

/** Unicode categories that are dropped from request data: line breaks, bidi and other invisible controls. */
private val HIDDEN_CHARACTER_TYPES = setOf(
    Character.CONTROL,
    Character.FORMAT,
    Character.LINE_SEPARATOR,
    Character.PARAGRAPH_SEPARATOR,
)

/**
 * Request data is shown literally: control and bidi characters are dropped so it cannot add lines or reorder the
 * sentence, and accent tags inside it must not be read as markup by [withAccentBoldBright].
 */
internal fun String.asLiteralRequestText(): String {
    val visible = buildString {
        this@asLiteralRequestText.codePoints()
            .filter { Character.getType(it).toByte() !in HIDDEN_CHARACTER_TYPES }
            .forEach { appendCodePoint(it) }
    }
    return generateSequence(visible) { current -> current.removeAccentTags().takeIf { it != current } }.last()
}

internal fun pubkyAuthDisplayPublicKey(publicKey: String): String {
    val rawKey = publicKey.removePrefix("pubky")
    return if (rawKey.length > 8) "${rawKey.take(4)}...${rawKey.takeLast(4)}" else rawKey
}

@Preview(showSystemUi = true)
@Composable
private fun WatchOnlyConsentPreview() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                uiState = PubkyAuthApprovalUiState(
                    state = ApprovalState.WatchOnlyConsent,
                    serviceName = "paykit",
                    bitkitClaim = PubkyAuthClaim(Item.WATCH_ONLY_ACCOUNT_V1),
                ),
                isCurrentRequest = true,
                onAuthorize = {},
                onApproveWatchOnly = {},
                onBackToWatchOnly = {},
                onCancel = {},
                onDismiss = {},
            )
        }
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
                    clientId = "app.paykit.server",
                    serviceName = "paykit",
                    permissions = persistentListOf(
                        PubkyAuthPermission(path = "/pub/paykit/", accessLevel = "rw"),
                    ),
                    bitkitClaim = PubkyAuthClaim(Item.PAYKIT_ACCESS_V1, Item.WATCH_ONLY_ACCOUNT_V1),
                    profile = PubkyProfile(
                        publicKey = "pk8e3qm5f4kgczagxhertyuiop1gxag",
                        name = "Satoshi Nakamoto",
                        bio = "",
                        imageUrl = null,
                        links = emptyList(),
                        status = null,
                    ),
                ),
                isCurrentRequest = true,
                onAuthorize = {},
                onApproveWatchOnly = {},
                onBackToWatchOnly = {},
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
                    clientId = "app.paykit.server",
                    serviceName = "paykit",
                ),
                isCurrentRequest = true,
                onAuthorize = {},
                onApproveWatchOnly = {},
                onBackToWatchOnly = {},
                onCancel = {},
                onDismiss = {},
            )
        }
    }
}

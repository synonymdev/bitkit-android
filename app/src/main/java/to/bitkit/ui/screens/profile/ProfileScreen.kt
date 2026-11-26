package to.bitkit.ui.screens.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.data.PubkyCachedProfile
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyProfileLink
import to.bitkit.ui.components.ActionButton
import to.bitkit.ui.components.AddTagSheet
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.CenteredProfileHeader
import to.bitkit.ui.components.GradientCircularProgressIndicator
import to.bitkit.ui.components.LinkRow
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.PubkyImage
import to.bitkit.ui.components.QrCodeImage
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.TopBarSpacer
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.modifiers.rememberDebouncedClick
import to.bitkit.ui.shared.util.screen
import to.bitkit.ui.shared.util.shareText
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.TopBarGradient
import to.bitkit.ui.theme.TopBarHeight
import to.bitkit.ui.utils.TABLET_MIN_WIDTH

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onBackClick: () -> Unit,
    onEditProfile: () -> Unit = {},
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
        onClickAddTag = { viewModel.showAddTagSheet() },
        onRemoveTag = { viewModel.removeTag(it) },
        onDismissAddTagSheet = { viewModel.dismissAddTagSheet() },
        onSaveTag = { viewModel.addTag(it) },
        onDismissCopiedPopup = { viewModel.dismissCopiedPopup() },
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
    onClickAddTag: () -> Unit,
    onRemoveTag: (String) -> Unit,
    onDismissAddTagSheet: () -> Unit,
    onSaveTag: (String) -> Unit,
    onDismissCopiedPopup: () -> Unit,
) {
    val currentProfile = uiState.profile
    val cachedProfile = uiState.cachedProfile
    val topBar = @Composable { modifier: Modifier ->
        AppTopBar(
            titleText = stringResource(R.string.profile__nav_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
            modifier = modifier,
        )
    }

    when {
        currentProfile != null -> Box(modifier = Modifier.screen()) {
            ProfileBody(
                profile = currentProfile,
                canEdit = uiState.canEdit,
                isLoading = uiState.isLoading || uiState.isSigningOut,
                onClickEdit = onClickEdit,
                onClickCopy = onClickCopy,
                onClickShare = onClickShare,
                onClickAddTag = onClickAddTag,
                onRemoveTag = onRemoveTag,
                onClickSignOut = onClickSignOut,
                onClickRetry = onClickRetry,
                needsUpdate = uiState.needsUpdate,
            )
            topBar(Modifier.background(TopBarGradient))
            CopiedPopup(
                publicKey = uiState.copiedPublicKey,
                onClick = onDismissCopiedPopup,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        cachedProfile != null -> Box(modifier = Modifier.screen()) {
            CachedProfileBody(profile = cachedProfile)
            topBar(Modifier.background(TopBarGradient))
        }

        else -> ScreenColumn {
            topBar(Modifier)
            if (uiState.isLoading) {
                LoadingState()
            } else {
                EmptyState(
                    needsUpdate = uiState.needsUpdate,
                    onClickRetry = onClickRetry,
                    onClickSignOut = onClickSignOut,
                )
            }
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

    if (uiState.showAddTagSheet) {
        AddTagSheet(
            onDismiss = onDismissAddTagSheet,
            onSave = onSaveTag,
        )
    }
}

/** Gap between the top bar and the profile header */
private val QR_BLOCK_TOP_GAP = 24.dp

/** Space kept under the profile actions so they never touch the screen edge */
private val QR_BLOCK_BOTTOM_GAP = 16.dp

/** Gap between the header, the QR code and the actions */
private val QR_BLOCK_SPACING = 16.dp

/** Side inset of the QR code within the screen padding */
private val QR_SIDE_INSET = 32.dp

/** Smallest QR code edge; below it the block scrolls instead of shrinking the code further */
private val QR_MIN_SIZE = 200.dp

@Composable
private fun ProfileBody(
    profile: PubkyProfile,
    canEdit: Boolean,
    isLoading: Boolean,
    onClickEdit: () -> Unit,
    onClickCopy: () -> Unit,
    onClickShare: () -> Unit,
    onClickAddTag: () -> Unit,
    onRemoveTag: (String) -> Unit,
    onClickSignOut: () -> Unit,
    onClickRetry: () -> Unit,
    needsUpdate: Boolean,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val qrBlockMaxHeight = if (maxWidth >= TABLET_MIN_WIDTH) {
            maxHeight - TopBarHeight - QR_BLOCK_TOP_GAP - QR_BLOCK_BOTTOM_GAP
        } else {
            Dp.Infinity
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            TopBarSpacer()
            VerticalSpacer(QR_BLOCK_TOP_GAP)

            QrFitColumn(
                maxHeight = qrBlockMaxHeight,
                header = {
                    CenteredProfileHeader(
                        publicKey = profile.publicKey,
                        name = profile.name,
                        bio = profile.bio,
                        imageUrl = profile.imageUrl,
                        nameTestTag = "ProfileViewName",
                        notesTestTag = "ProfileViewNotes",
                    )
                },
                qr = {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableAlpha(onClick = onClickCopy)
                    ) {
                        QrCodeImage(
                            content = profile.publicKey,
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "ProfileQRCode",
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
                },
                actions = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ActionButton(
                            onClick = onClickEdit,
                            iconRes = R.drawable.ic_pencil,
                            enabled = canEdit,
                            modifier = Modifier.testTag("ProfileEdit")
                        )
                        ActionButton(
                            onClick = onClickCopy,
                            iconRes = R.drawable.ic_copy_simple,
                            modifier = Modifier.testTag("ProfileCopy")
                        )
                        ActionButton(
                            onClick = onClickShare,
                            iconRes = R.drawable.ic_share,
                            modifier = Modifier.testTag("ProfileShare")
                        )
                    }
                },
            )

            VerticalSpacer(32.dp)
            HorizontalDivider()

            profile.links.forEachIndexed { index, link ->
                LinkRow(label = link.label, value = link.url, linkIndex = index)
            }

            VerticalSpacer(16.dp)
            Text13Up(
                text = stringResource(R.string.profile__edit_tags),
                color = Colors.White64,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ProfileViewTagsHeader")
            )
            VerticalSpacer(8.dp)
            if (profile.tags.isNotEmpty()) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    profile.tags.forEach { tag ->
                        TagButton(
                            text = tag,
                            onClick = if (canEdit) ({ onRemoveTag(tag) }) else null,
                            accessibilityLabel = stringResource(R.string.common__remove_tag, tag).takeIf { canEdit },
                            displayIconClose = canEdit,
                        )
                    }
                }
                VerticalSpacer(8.dp)
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                PrimaryButton(
                    text = stringResource(R.string.profile__add_tag),
                    onClick = onClickAddTag,
                    enabled = canEdit,
                    size = ButtonSize.Small,
                    fullWidth = false,
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_tag),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.testTag("ProfileAddTag")
                )
            }

            VerticalSpacer(16.dp)
            if (!canEdit) {
                if (needsUpdate) UpdateRequiredHint()
                SecondaryButton(
                    text = stringResource(R.string.profile__retry_load),
                    onClick = onClickRetry,
                    enabled = !isLoading,
                    modifier = Modifier.testTag("ProfileRetry")
                )
                VerticalSpacer(8.dp)
                TextButton(
                    onClick = rememberDebouncedClick(onClick = onClickSignOut),
                    modifier = Modifier.testTag("ProfileSignOut")
                ) {
                    BodyS(text = stringResource(R.string.profile__sign_out), color = Colors.White64)
                }
            }
        }
    }
}

@Composable
private fun QrFitColumn(
    maxHeight: Dp,
    header: @Composable () -> Unit,
    qr: @Composable () -> Unit,
    actions: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(header, qr, actions)
    ) { (headerMeasurables, qrMeasurables, actionsMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        val headerPlaceables = headerMeasurables.map { it.measure(loose) }
        val actionsPlaceables = actionsMeasurables.map { it.measure(loose) }
        val spacing = QR_BLOCK_SPACING.roundToPx()
        val fixedHeight = headerPlaceables.sumOf { it.height } + actionsPlaceables.sumOf { it.height } + spacing * 2
        val maxQrWidth = (constraints.maxWidth - QR_SIDE_INSET.roundToPx() * 2).coerceAtLeast(0)
        val qrSize = (maxHeight.roundToPx() - fixedHeight)
            .coerceAtLeast(QR_MIN_SIZE.roundToPx())
            .coerceAtMost(maxQrWidth)
        val qrPlaceables = qrMeasurables.map { it.measure(Constraints(maxWidth = qrSize, maxHeight = qrSize)) }
        val rows = listOf(headerPlaceables, qrPlaceables, actionsPlaceables)
        val height = rows.sumOf { row -> row.maxOfOrNull { it.height } ?: 0 } + spacing * 2
        layout(constraints.maxWidth, height) {
            var y = 0
            rows.forEach { row ->
                row.forEach { it.placeRelative((constraints.maxWidth - it.width) / 2, y) }
                y += (row.maxOfOrNull { it.height } ?: 0) + spacing
            }
        }
    }
}

@Composable
private fun CachedProfileBody(profile: PubkyCachedProfile) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .testTag("ProfileCachedHeader")
    ) {
        TopBarSpacer()
        VerticalSpacer(24.dp)

        CenteredProfileHeader(
            publicKey = profile.publicKey,
            name = profile.name,
            bio = "",
            imageUrl = profile.imageUri,
            nameTestTag = "ProfileCachedName",
        )

        VerticalSpacer(32.dp)
        GradientCircularProgressIndicator(modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun CopiedPopup(
    publicKey: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var lastPublicKey by remember { mutableStateOf("") }
    LaunchedEffect(publicKey) {
        if (publicKey != null) lastPublicKey = publicKey
    }

    AnimatedVisibility(
        visible = publicKey != null,
        enter = fadeIn() + scaleIn(initialScale = 0.95f),
        exit = fadeOut() + scaleOut(targetScale = 0.95f),
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .shadow(elevation = 25.dp, shape = MaterialTheme.shapes.medium, spotColor = Colors.Black)
                .clip(MaterialTheme.shapes.medium)
                .background(Colors.Gray6)
                .clickableAlpha(onClick = onClick)
                .padding(32.dp)
                .testTag("ProfilePubkyCopiedToast")
        ) {
            BodyMSB(
                text = stringResource(R.string.profile__pubky_copied),
                color = Colors.Brand,
                textAlign = TextAlign.Center,
            )
            BodyS(
                text = lastPublicKey,
                color = Colors.White,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LoadingState() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        GradientCircularProgressIndicator(modifier = Modifier.size(24.dp).testTag("ProfileLoading"))
    }
}

@Composable
private fun UpdateRequiredHint() {
    BodyM(
        text = stringResource(R.string.profile__update_required),
        color = Colors.White64,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag("ProfileUpdateRequired")
    )
    VerticalSpacer(16.dp)
}

@Composable
private fun EmptyState(
    needsUpdate: Boolean,
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
        if (needsUpdate) UpdateRequiredHint()
        SecondaryButton(
            text = stringResource(R.string.profile__retry_load),
            onClick = onClickRetry,
            modifier = Modifier.testTag("ProfileRetry")
        )
        VerticalSpacer(8.dp)
        TextButton(
            onClick = rememberDebouncedClick(onClick = onClickSignOut),
            modifier = Modifier.testTag("ProfileEmptySignOut")
        ) {
            BodyS(text = stringResource(R.string.profile__sign_out), color = Colors.White64)
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewCached() {
    AppThemeSurface {
        Content(
            uiState = ProfileUiState(
                cachedProfile = PubkyCachedProfile(
                    publicKey = "pk8e3qm5...gxag",
                    name = "Satoshi",
                    imageUri = null,
                ),
                isLoading = true,
            ),
            onBackClick = {},
            onClickEdit = {},
            onClickCopy = {},
            onClickShare = {},
            onClickSignOut = {},
            onDismissSignOutDialog = {},
            onConfirmSignOut = {},
            onClickRetry = {},
            onClickAddTag = {},
            onRemoveTag = {},
            onDismissAddTagSheet = {},
            onSaveTag = {},
            onDismissCopiedPopup = {},
        )
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
            onClickAddTag = {},
            onRemoveTag = {},
            onDismissAddTagSheet = {},
            onSaveTag = {},
            onDismissCopiedPopup = {},
        )
    }
}

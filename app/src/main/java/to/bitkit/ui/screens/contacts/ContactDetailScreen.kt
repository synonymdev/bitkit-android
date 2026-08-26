package to.bitkit.ui.screens.contacts

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyProfileLink
import to.bitkit.repositories.PrivatePaykitPaymentContext
import to.bitkit.ui.components.ActionButton
import to.bitkit.ui.components.AddTagSheet
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheet
import to.bitkit.ui.components.CenteredProfileHeader
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.GradientCircularProgressIndicator
import to.bitkit.ui.components.LinkRow
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.shared.util.shareText
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun ContactDetailScreen(
    viewModel: ContactDetailViewModel,
    onBackClick: () -> Unit,
    onPayContact: (String, String, PrivatePaykitPaymentContext?) -> Unit,
    onActivityClick: (String) -> Unit,
    canRequestPayment: Boolean = false,
    onRequestPayment: () -> Unit = {},
    showDeleteAction: Boolean = false,
    onContactDeleted: () -> Unit = {},
    onEditContact: (String) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showRequestOrPay by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.effects.collect {
            when (it) {
                is ContactDetailEffect.OpenPayment ->
                    onPayContact(it.paymentRequest, it.publicKey, it.privatePaymentContext)
                ContactDetailEffect.ContactDeleted -> onContactDeleted()
            }
        }
    }

    Content(
        uiState = uiState,
        onBackClick = onBackClick,
        onClickEdit = { uiState.profile?.publicKey?.let { onEditContact(it) } },
        showDeleteAction = showDeleteAction,
        onClickDelete = { viewModel.showDeleteConfirmation() },
        onClickCopy = { viewModel.copyPublicKey() },
        onClickPay = {
            if (canRequestPayment) {
                showRequestOrPay = true
            } else {
                viewModel.payContact()
            }
        },
        onClickActivity = { uiState.profile?.publicKey?.let { onActivityClick(it) } },
        onClickShare = { uiState.profile?.publicKey?.let { shareText(context, it) } },
        onClickRetry = { viewModel.loadContact() },
        onAddTag = { viewModel.showAddTagSheet() },
        onRemoveTag = { viewModel.removeTag(it) },
        onDismissAddTagSheet = { viewModel.dismissAddTagSheet() },
        onSaveTag = { viewModel.addTag(it) },
        onDismissDeleteDialog = { viewModel.dismissDeleteConfirmation() },
        onConfirmDelete = { viewModel.deleteContact() },
    )

    if (showRequestOrPay && uiState.profile != null) {
        RequestOrPaySheet(
            contact = requireNotNull(uiState.profile),
            onDismiss = { showRequestOrPay = false },
            onPay = {
                showRequestOrPay = false
                viewModel.payContact()
            },
            onRequest = {
                showRequestOrPay = false
                onRequestPayment()
            },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun RequestOrPaySheet(
    contact: PubkyProfile,
    onDismiss: () -> Unit,
    onPay: () -> Unit,
    onRequest: () -> Unit,
) {
    BottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .sheetHeight(SheetSize.MEDIUM, isModal = true)
                .gradientBackground()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .testTag("RequestOrPaySheet")
        ) {
            SheetTopBar(titleText = stringResource(R.string.wallet__payment_request_or_pay))
            FillHeight()
            Image(
                painter = painterResource(R.drawable.coin_stack),
                contentDescription = null,
                modifier = Modifier
                    .size(256.dp)
                    .align(Alignment.CenterHorizontally),
            )
            FillHeight()
            Display(
                text = stringResource(R.string.wallet__payment_request_or_pay_headline)
                    .withAccent(accentColor = Colors.Purple),
            )
            VerticalSpacer(12.dp)
            BodyM(
                text = stringResource(R.string.wallet__payment_request_or_pay_description, contact.name),
                color = Colors.White64,
            )
            VerticalSpacer(24.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryButton(
                    text = stringResource(R.string.wallet__payment_request_pay),
                    onClick = onPay,
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_sent),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    text = stringResource(R.string.wallet__payment_request_request),
                    onClick = onRequest,
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_received),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            VerticalSpacer(16.dp)
        }
    }
}

@Composable
private fun Content(
    uiState: ContactDetailUiState,
    onBackClick: () -> Unit,
    onClickEdit: () -> Unit,
    showDeleteAction: Boolean,
    onClickDelete: () -> Unit,
    onClickCopy: () -> Unit,
    onClickPay: () -> Unit,
    onClickActivity: () -> Unit,
    onClickShare: () -> Unit,
    onClickRetry: () -> Unit,
    onAddTag: () -> Unit,
    onRemoveTag: (String) -> Unit,
    onDismissAddTagSheet: () -> Unit,
    onSaveTag: (String) -> Unit,
    onDismissDeleteDialog: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    val currentProfile = uiState.profile

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.contacts__detail_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )

        when {
            uiState.isLoading -> LoadingState()
            currentProfile != null -> ContactBody(
                profile = currentProfile,
                tags = uiState.tags,
                showPayButton = uiState.showPayButton,
                showDeleteAction = showDeleteAction,
                onClickEdit = onClickEdit,
                onClickDelete = onClickDelete,
                onClickCopy = onClickCopy,
                onClickPay = onClickPay,
                onClickActivity = onClickActivity,
                onClickShare = onClickShare,
                onAddTag = onAddTag,
                onRemoveTag = onRemoveTag,
            )
            else -> EmptyState(onClickRetry = onClickRetry)
        }
    }

    if (uiState.showAddTagSheet) {
        AddTagSheet(
            onDismiss = onDismissAddTagSheet,
            onSave = onSaveTag,
        )
    }

    if (uiState.showDeleteDialog && currentProfile != null) {
        AppAlertDialog(
            title = stringResource(R.string.contacts__delete_confirm_title, currentProfile.name),
            text = stringResource(R.string.contacts__delete_confirm_text, currentProfile.name),
            confirmText = stringResource(R.string.common__delete_yes),
            onConfirm = onConfirmDelete,
            onDismiss = onDismissDeleteDialog,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContactBody(
    profile: PubkyProfile,
    tags: ImmutableList<String>,
    showPayButton: Boolean,
    showDeleteAction: Boolean,
    onClickEdit: () -> Unit,
    onClickDelete: () -> Unit,
    onClickCopy: () -> Unit,
    onClickPay: () -> Unit,
    onClickActivity: () -> Unit,
    onClickShare: () -> Unit,
    onAddTag: () -> Unit,
    onRemoveTag: (String) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        VerticalSpacer(24.dp)

        CenteredProfileHeader(
            publicKey = profile.publicKey,
            name = profile.name,
            bio = profile.bio,
            imageUrl = profile.imageUrl,
            nameTestTag = "ContactViewName",
            notesTestTag = "ContactViewNotes",
        )

        if (showDeleteAction) {
            VerticalSpacer(16.dp)
            HorizontalDivider(color = Colors.White10)
            VerticalSpacer(16.dp)
        } else {
            VerticalSpacer(24.dp)
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (showPayButton) {
                ActionButton(
                    onClick = onClickPay,
                    iconRes = R.drawable.ic_coins,
                    modifier = Modifier.testTag("ContactPay")
                )
            }
            ActionButton(
                onClick = onClickActivity,
                iconRes = R.drawable.ic_activity,
                modifier = Modifier.testTag("ContactActivity")
            )
            ActionButton(
                onClick = onClickCopy,
                iconRes = R.drawable.ic_copy,
                modifier = Modifier.testTag("ContactCopy")
            )
            ActionButton(
                onClick = onClickShare,
                iconRes = R.drawable.ic_share,
                modifier = Modifier.testTag("ContactShare")
            )
            if (showDeleteAction) {
                ActionButton(
                    onClick = onClickDelete,
                    iconRes = R.drawable.ic_trash,
                    modifier = Modifier.testTag("ContactDelete")
                )
            } else {
                ActionButton(
                    onClick = onClickEdit,
                    iconRes = R.drawable.ic_edit,
                    modifier = Modifier.testTag("ContactEdit")
                )
            }
        }

        VerticalSpacer(16.dp)
        HorizontalDivider(color = Colors.White10)
        VerticalSpacer(16.dp)

        profile.links.forEachIndexed { index, link ->
            LinkRow(label = link.label, value = link.url, linkIndex = index)
        }

        VerticalSpacer(16.dp)
        Text13Up(
            text = stringResource(R.string.profile__edit_tags),
            color = Colors.White64,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ContactViewTagsHeader")
        )
        VerticalSpacer(8.dp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            tags.forEach { tag ->
                TagButton(
                    text = tag,
                    onClick = { onRemoveTag(tag) },
                    accessibilityLabel = stringResource(R.string.common__remove_tag, tag),
                    displayIconClose = true,
                )
            }
        }
        VerticalSpacer(8.dp)
        Row(modifier = Modifier.fillMaxWidth()) {
            TagButton(
                text = stringResource(R.string.profile__add_tag),
                onClick = onAddTag,
                icon = painterResource(R.drawable.ic_tag),
                displayIconClose = true,
                modifier = Modifier.testTag("ContactAddTag")
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
        GradientCircularProgressIndicator(modifier = Modifier.size(24.dp))
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
        BodyM(text = stringResource(R.string.contacts__detail_empty_state), color = Colors.White64)
        VerticalSpacer(16.dp)
        SecondaryButton(
            text = stringResource(R.string.profile__retry_load),
            onClick = onClickRetry,
            modifier = Modifier.testTag("ContactRetry")
        )
    }
}

@Preview(showSystemUi = true)
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
                    tags = listOf("CEO", "Bitcoin"),
                    status = null,
                ),
                tags = persistentListOf("CEO", "Bitcoin"),
                showPayButton = true,
            ),
            onBackClick = {},
            onClickEdit = {},
            showDeleteAction = true,
            onClickDelete = {},
            onClickCopy = {},
            onClickPay = {},
            onClickActivity = {},
            onClickShare = {},
            onClickRetry = {},
            onAddTag = {},
            onRemoveTag = {},
            onDismissAddTagSheet = {},
            onSaveTag = {},
            onDismissDeleteDialog = {},
            onConfirmDelete = {},
        )
    }
}

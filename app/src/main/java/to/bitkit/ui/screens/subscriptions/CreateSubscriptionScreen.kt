@file:OptIn(ExperimentalTime::class)

package to.bitkit.ui.screens.subscriptions

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.bitkit.R
import to.bitkit.ext.getClipboardText
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.models.Toast
import to.bitkit.repositories.PaykitPaymentRequestDeliveryStatus
import to.bitkit.repositories.PaykitPaymentRequestDraft
import to.bitkit.repositories.PaykitPaymentRequestTarget
import to.bitkit.repositories.PaykitRecurrenceUnit
import to.bitkit.repositories.PaykitSubscription
import to.bitkit.repositories.PaykitSubscriptionDraft
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.MoneyCell
import to.bitkit.ui.components.MoneyDisplay
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.PubkyContactRow
import to.bitkit.ui.components.PubkyImage
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.screens.paymentrequests.PaymentRequestAmountContent
import to.bitkit.ui.screens.paymentrequests.PaymentRequestExpiration
import to.bitkit.ui.screens.paymentrequests.PaymentRequestRecipientContent
import to.bitkit.ui.screens.paymentrequests.title
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.utils.SubscriptionIcon
import to.bitkit.viewmodels.AmountInputViewModel
import to.bitkit.viewmodels.AppViewModel
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Composable
fun CreateSubscriptionSheet(
    appViewModel: AppViewModel,
    amountInputViewModel: AmountInputViewModel = hiltViewModel(key = "SubscriptionAmount"),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contacts by appViewModel.pubkyContacts.collectAsStateWithLifecycle()
    val targets by appViewModel.eligiblePaymentRequestTargets.collectAsStateWithLifecycle()
    val isCreating by appViewModel.isCreatingPaymentRequest.collectAsStateWithLifecycle()
    var step by remember { mutableStateOf(SubscriptionCreationStep.Details) }
    var amountSats by remember { mutableStateOf(0uL) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf(PaykitRecurrenceUnit.Month) }
    var expiration by remember { mutableStateOf(PaymentRequestExpiration.Week) }
    var selectedTarget by remember { mutableStateOf<PaykitPaymentRequestTarget?>(null) }
    var selectedIconUri by remember { mutableStateOf<Uri?>(null) }
    var iconBytes by remember { mutableStateOf<ByteArray?>(null) }
    var isLoadingIcon by remember { mutableStateOf(false) }
    var createdSubscription by remember { mutableStateOf<PaykitSubscription?>(null) }

    when (step) {
        SubscriptionCreationStep.Details -> CreateSubscriptionDetails(
            amountSats = amountSats,
            name = name,
            description = description,
            frequency = frequency,
            selectedIconUri = selectedIconUri,
            isLoadingIcon = isLoadingIcon,
            onAmountClick = { step = SubscriptionCreationStep.Amount },
            onNameChange = { name = it.take(256) },
            onDescriptionChange = { description = it.take(1024) },
            onFrequencyChange = { frequency = it },
            onIconSelected = { uri ->
                isLoadingIcon = true
                scope.launch {
                    val bytes = withContext(Dispatchers.IO) {
                        runSuspendCatching { SubscriptionIcon.load(context.contentResolver, uri) }
                    }
                    bytes.onSuccess {
                        iconBytes = it
                        selectedIconUri = uri
                    }.onFailure {
                        appViewModel.toast(
                            type = Toast.ToastType.ERROR,
                            title = context.getString(R.string.common__error),
                            description = context.getString(R.string.subscriptions__icon_error),
                        )
                    }
                    isLoadingIcon = false
                }
            },
            onChooseRecipient = { step = SubscriptionCreationStep.Recipient },
        )
        SubscriptionCreationStep.Amount -> PaymentRequestAmountContent(
            amountInputViewModel = amountInputViewModel,
            initialDraft = PaykitPaymentRequestDraft(
                amountSats = amountSats,
                note = name,
                expiresAt = Clock.System.now() + expiration.duration,
            ),
            contact = null,
            onBack = { step = SubscriptionCreationStep.Details },
            onContinue = {
                amountSats = it.amountSats
                step = SubscriptionCreationStep.Details
            },
            modifier = Modifier.sheetHeight()
        )
        SubscriptionCreationStep.Recipient -> SubscriptionRecipient(
            targets = targets.toImmutableList(),
            contacts = contacts.toImmutableList(),
            selectedTarget = selectedTarget,
            expiration = expiration,
            isCreating = isCreating,
            isLoadingIcon = isLoadingIcon,
            onBack = { step = SubscriptionCreationStep.Details },
            onPaste = { context.getClipboardText()?.trim().orEmpty() },
            onSelected = { selectedTarget = it },
            onExpirationChange = { expiration = it },
            onPropose = {
                selectedTarget?.let { target ->
                    appViewModel.createSubscription(
                        draft = PaykitSubscriptionDraft(
                            amountSats = amountSats,
                            name = name,
                            description = description,
                            frequency = frequency,
                            expiresAt = Clock.System.now() + expiration.duration,
                            iconBytes = iconBytes,
                        ),
                        target = target,
                    ) {
                        createdSubscription = it
                        step = SubscriptionCreationStep.Sent
                    }
                }
            },
        )
        SubscriptionCreationStep.Sent -> createdSubscription?.let {
            SubscriptionProposalSent(
                subscription = it,
                contact = contacts.firstOrNull { contact ->
                    PubkyPublicKeyFormat.matches(contact.publicKey, it.counterparty)
                } ?: PubkyProfile.placeholder(it.counterparty),
                onDone = appViewModel::hideSheet,
            )
        }
    }
}

@Composable
internal fun CreateSubscriptionDetails(
    amountSats: ULong,
    name: String,
    description: String,
    frequency: PaykitRecurrenceUnit,
    selectedIconUri: Uri?,
    isLoadingIcon: Boolean,
    onAmountClick: () -> Unit,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onFrequencyChange: (PaykitRecurrenceUnit) -> Unit,
    onIconSelected: (Uri) -> Unit,
    onChooseRecipient: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        it?.let(onIconSelected)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        it?.let(onIconSelected)
    }
    val launchPhotoPicker = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } else {
            gallery.launch("image/*")
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .testTag("CreateSubscription")
    ) {
        SheetTopBar(titleText = stringResource(R.string.subscriptions__create_subscription))
        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                Caption13Up(text = stringResource(R.string.wallet__payment_request_amount), color = Colors.White64)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().clickableAlpha(onClick = onAmountClick),
                ) {
                    MoneyDisplay(sats = amountSats.coerceAtMost(Long.MAX_VALUE.toULong()).toLong(), showSymbol = true)
                    Icon(
                        painter = painterResource(R.drawable.ic_pencil_simple),
                        contentDescription = stringResource(R.string.common__edit),
                        tint = Colors.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
                VerticalSpacer(24.dp)
                Caption13Up(text = stringResource(R.string.subscriptions__frequency), color = Colors.White64)
                VerticalSpacer(8.dp)
                SubscriptionFrequencyPicker(frequency, onFrequencyChange)
                VerticalSpacer(20.dp)
                Caption13Up(text = stringResource(R.string.subscriptions__name), color = Colors.White64)
                VerticalSpacer(8.dp)
                TextInput(
                    value = name,
                    onValueChange = onNameChange,
                    placeholder = stringResource(R.string.subscriptions__name_placeholder),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("SubscriptionName"),
                )
                VerticalSpacer(20.dp)
                Caption13Up(text = stringResource(R.string.subscriptions__description), color = Colors.White64)
                VerticalSpacer(8.dp)
                TextInput(
                    value = description,
                    onValueChange = onDescriptionChange,
                    placeholder = stringResource(R.string.subscriptions__description_placeholder),
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth().testTag("SubscriptionDescription"),
                )
                VerticalSpacer(20.dp)
                Caption13Up(text = stringResource(R.string.subscriptions__custom_icon), color = Colors.White64)
                VerticalSpacer(8.dp)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Colors.Gray6)
                        .clickable(enabled = !isLoadingIcon, onClick = launchPhotoPicker)
                        .padding(16.dp)
                        .testTag("SubscriptionIconPicker"),
                ) {
                    if (selectedIconUri == null) {
                        Image(
                            painter = painterResource(R.drawable.subscription_default_icon),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                                .background(Colors.White).padding(5.dp),
                        )
                    } else {
                        AsyncImage(
                            model = selectedIconUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)),
                        )
                    }
                    BodyM(
                        text = stringResource(R.string.subscriptions__custom_icon_description),
                        modifier = Modifier.padding(start = 16.dp).weight(1f),
                        color = Colors.White64,
                    )
                }
                VerticalSpacer(24.dp)
            }
        }
        PrimaryButton(
            text = stringResource(R.string.subscriptions__choose_recipient),
            onClick = onChooseRecipient,
            enabled = amountSats > 0uL && name.isNotBlank() && !isLoadingIcon,
            modifier = Modifier.testTag("SubscriptionChooseRecipient")
        )
        VerticalSpacer(16.dp)
    }
}

@Composable
private fun SubscriptionFrequencyPicker(
    selected: PaykitRecurrenceUnit,
    onSelected: (PaykitRecurrenceUnit) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        creationFrequencies.forEach { option ->
            val isSelected = option == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clickableAlpha { onSelected(option) }
                    .testTag("SubscriptionFrequency${option.name}"),
            ) {
                BodyS(text = option.creationTitle(), color = if (isSelected) Colors.White else Colors.White64)
                VerticalSpacer(8.dp)
                HorizontalDivider(
                    thickness = 2.dp,
                    color = if (isSelected) Colors.White else Colors.White16,
                )
            }
        }
    }
}

@Composable
internal fun SubscriptionRecipient(
    targets: ImmutableList<PaykitPaymentRequestTarget>,
    contacts: ImmutableList<PubkyProfile>,
    selectedTarget: PaykitPaymentRequestTarget?,
    expiration: PaymentRequestExpiration,
    isCreating: Boolean,
    isLoadingIcon: Boolean,
    onBack: () -> Unit,
    onPaste: () -> String,
    onSelected: (PaykitPaymentRequestTarget) -> Unit,
    onExpirationChange: (PaymentRequestExpiration) -> Unit,
    onPropose: () -> Unit,
) {
    PaymentRequestRecipientContent(
        targets = targets,
        contacts = contacts,
        onBack = onBack,
        onPaste = onPaste,
        onSelected = onSelected,
        titleRes = R.string.subscriptions__choose_recipient,
        selectedTarget = selectedTarget,
        testTagPrefix = "Subscription",
        action = { SubscriptionExpirationMenu(expiration, onExpirationChange) },
        footer = {
            PrimaryButton(
                text = stringResource(R.string.subscriptions__propose_subscription),
                onClick = onPropose,
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_sent),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                isLoading = isCreating,
                enabled = selectedTarget != null && !isLoadingIcon,
                modifier = Modifier.testTag("SubscriptionPropose"),
            )
            VerticalSpacer(16.dp)
        },
        modifier = Modifier.sheetHeight()
    )
}

@Composable
private fun SubscriptionExpirationMenu(
    expiration: PaymentRequestExpiration,
    onExpirationChange: (PaymentRequestExpiration) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(
        onClick = { expanded = true },
        modifier = Modifier.testTag("SubscriptionExpiration"),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_timer),
            contentDescription = stringResource(R.string.wallet__payment_request_expires),
            tint = Colors.White,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PaymentRequestExpiration.entries.forEach { option ->
                DropdownMenuItem(
                    text = { BodyM(option.title()) },
                    onClick = {
                        onExpirationChange(option)
                        expanded = false
                    },
                    trailingIcon = if (option == expiration) {
                        {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = Colors.Purple,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
internal fun SubscriptionProposalSent(
    subscription: PaykitSubscription,
    contact: PubkyProfile,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .testTag("SubscriptionProposalSent")
    ) {
        SheetTopBar(
            titleText = stringResource(
                if (subscription.deliveryStatus == PaykitPaymentRequestDeliveryStatus.Sent) {
                    R.string.wallet__payment_request_sent_title
                } else {
                    R.string.subscriptions__proposal_queued_title
                }
            ),
        )
        Column(
            modifier = Modifier.weight(
                1f
            ).verticalScroll(rememberScrollState()).testTag("SubscriptionConfirmationBody"),
        ) {
            VerticalSpacer(16.dp)
            Image(
                painter = painterResource(R.drawable.check),
                contentDescription = null,
                modifier = Modifier.size(256.dp).align(Alignment.CenterHorizontally),
            )
            VerticalSpacer(16.dp)
            Display(
                text = stringResource(
                    if (subscription.deliveryStatus == PaykitPaymentRequestDeliveryStatus.Sent) {
                        R.string.subscriptions__proposal_sent_headline
                    } else {
                        R.string.subscriptions__proposal_queued_headline
                    }
                )
                    .withAccent(accentColor = Colors.Purple),
            )
            VerticalSpacer(12.dp)
            BodyM(
                text = stringResource(
                    if (subscription.deliveryStatus == PaykitPaymentRequestDeliveryStatus.Sent) {
                        R.string.subscriptions__proposal_sent_description
                    } else {
                        R.string.subscriptions__proposal_queued_description
                    }
                ),
                color = Colors.White64,
            )
            VerticalSpacer(16.dp)
            PubkyContactRow(
                profile = contact,
                onClick = {},
                verticalPadding = 16.dp,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Colors.Gray6)
                    .padding(horizontal = 16.dp),
            )
            VerticalSpacer(8.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Colors.Gray6)
                    .padding(16.dp),
            ) {
                val iconUri = subscription.metadata.iconUri
                if (iconUri == null) {
                    Image(
                        painter = painterResource(R.drawable.subscription_default_icon),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                            .background(Colors.White).padding(5.dp),
                    )
                } else {
                    PubkyImage(uri = iconUri, size = 40.dp)
                }
                Column(Modifier.padding(start = 16.dp).weight(1f)) {
                    BodyMSB(
                        text = subscription.note ?: stringResource(R.string.subscriptions__subscription),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    BodyS(
                        text = subscription.frequencyTitle(),
                        color = Colors.White64,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                MoneyCell(sats = subscription.amountSats.coerceAtMost(Long.MAX_VALUE.toULong()).toLong())
            }
            VerticalSpacer(24.dp)
        }
        PrimaryButton(
            text = stringResource(R.string.common__ok),
            onClick = onDone,
        )
        VerticalSpacer(16.dp)
    }
}

@Composable
private fun PaykitRecurrenceUnit.creationTitle(): String = stringResource(
    when (this) {
        PaykitRecurrenceUnit.Day -> R.string.subscriptions__daily
        PaykitRecurrenceUnit.Week -> R.string.subscriptions__weekly
        PaykitRecurrenceUnit.Month -> R.string.subscriptions__monthly
        PaykitRecurrenceUnit.Year -> R.string.subscriptions__yearly
        PaykitRecurrenceUnit.Minute, PaykitRecurrenceUnit.Hour -> R.string.subscriptions__unsupported_frequency
    }
)

@Composable
private fun PaykitSubscription.frequencyTitle(): String = recurrence.unit.creationTitle()

private enum class SubscriptionCreationStep { Details, Amount, Recipient, Sent }

private val creationFrequencies = persistentListOf(
    PaykitRecurrenceUnit.Day,
    PaykitRecurrenceUnit.Week,
    PaykitRecurrenceUnit.Month,
    PaykitRecurrenceUnit.Year,
)

@Preview(showSystemUi = true)
@Composable
private fun CreateSubscriptionDetailsPreview() {
    AppThemeSurface {
        BottomSheetPreview {
            CreateSubscriptionDetails(
                amountSats = 100_000uL,
                name = "Rent Support",
                description = "Monthly support",
                frequency = PaykitRecurrenceUnit.Month,
                selectedIconUri = null,
                isLoadingIcon = false,
                onAmountClick = {},
                onNameChange = {},
                onDescriptionChange = {},
                onFrequencyChange = {},
                onIconSelected = {},
                onChooseRecipient = {},
                modifier = Modifier.sheetHeight()
            )
        }
    }
}

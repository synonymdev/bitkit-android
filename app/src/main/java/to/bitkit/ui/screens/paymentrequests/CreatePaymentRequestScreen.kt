@file:OptIn(ExperimentalTime::class)
@file:Suppress("MatchingDeclarationName")

package to.bitkit.ui.screens.paymentrequests

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import to.bitkit.R
import to.bitkit.ext.getClipboardText
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.repositories.AmountInputHandler
import to.bitkit.repositories.PaykitPaymentRequest
import to.bitkit.repositories.PaykitPaymentRequestDeliveryStatus
import to.bitkit.repositories.PaykitPaymentRequestDraft
import to.bitkit.repositories.PaykitPaymentRequestTarget
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.FillWidth
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadTextField
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.PubkyContactRow
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.UnitButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppTextStyles
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.AmountInputViewModel
import to.bitkit.viewmodels.AppViewModel
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

enum class PaymentRequestExpiration(val duration: Duration) {
    Hour(1.hours),
    Day(1.days),
    Week(7.days),
    Month(30.days);

    companion object {
        fun from(expiration: Instant, now: Instant): PaymentRequestExpiration {
            if (expiration <= now) return Week
            return entries.minBy {
                abs(((now + it.duration) - expiration).inWholeMilliseconds)
            }
        }
    }
}

@Composable
fun PaymentRequestDetailsScreen(
    amountInputViewModel: AmountInputViewModel,
    initialDraft: PaykitPaymentRequestDraft,
    onBack: () -> Unit,
    onContinue: (PaykitPaymentRequestDraft) -> Unit,
    recipient: PubkyProfile? = null,
    isCreating: Boolean = false,
) {
    PaymentRequestDetailsContent(
        amountInputViewModel = amountInputViewModel,
        initialDraft = initialDraft,
        onBack = onBack,
        onContinue = onContinue,
        recipient = recipient,
        isCreating = isCreating,
    )
}

@Composable
internal fun PaymentRequestDetailsContent(
    modifier: Modifier = Modifier,
    amountInputViewModel: AmountInputViewModel,
    initialDraft: PaykitPaymentRequestDraft,
    onBack: () -> Unit,
    onContinue: (PaykitPaymentRequestDraft) -> Unit,
    recipient: PubkyProfile? = null,
    isCreating: Boolean = false,
) {
    val currencies = LocalCurrencies.current
    val amountState by amountInputViewModel.uiState.collectAsStateWithLifecycle()
    var note by remember(initialDraft.note) { mutableStateOf(initialDraft.note) }
    var isEditingAmount by remember { mutableStateOf(false) }
    var expiration by remember(initialDraft.expiresAt) {
        mutableStateOf(PaymentRequestExpiration.from(initialDraft.expiresAt, Clock.System.now()))
    }

    LaunchedEffect(initialDraft.amountSats) {
        amountInputViewModel.setSats(
            initialDraft.amountSats.coerceAtMost(Long.MAX_VALUE.toULong()).toLong(),
            currencies,
        )
    }
    BackHandler(enabled = isCreating) {}

    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("PaymentRequestDetails")
    ) {
        SheetTopBar(
            titleText = stringResource(
                if (isEditingAmount) {
                    R.string.wallet__payment_request_amount
                } else {
                    R.string.wallet__payment_request
                }
            ),
            onBack = onBack,
        )
        BoxWithConstraints {
            val maxHeight = this.maxHeight
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                VerticalSpacer(16.dp)
                if (isEditingAmount) {
                    NumberPadTextField(
                        viewModel = amountInputViewModel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("PaymentRequestAmountField")
                    )
                    FillHeight(min = 12.dp)
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FillWidth()
                        UnitButton(
                            onClick = { amountInputViewModel.switchUnit(currencies) },
                            modifier = Modifier.testTag("PaymentRequestNumberPadUnit")
                        )
                    }
                    VerticalSpacer(16.dp)
                    HorizontalDivider()
                    NumberPad(
                        viewModel = amountInputViewModel,
                        currencies = currencies,
                        availableHeight = maxHeight,
                        modifier = Modifier.testTag("PaymentRequestNumberPad")
                    )
                    PrimaryButton(
                        text = stringResource(R.string.common__continue),
                        enabled = amountState.sats > 0,
                        onClick = { isEditingAmount = false },
                        modifier = Modifier.testTag("PaymentRequestAmountDone")
                    )
                } else {
                    Caption13Up(
                        text = stringResource(R.string.wallet__payment_request_amount),
                        color = Colors.White64,
                    )
                    VerticalSpacer(8.dp)
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        NumberPadTextField(
                            viewModel = amountInputViewModel,
                            onClick = { isEditingAmount = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("PaymentRequestAmountField")
                        )
                        Icon(
                            painter = painterResource(R.drawable.ic_pencil_simple),
                            contentDescription = stringResource(R.string.common__edit),
                            tint = Colors.White,
                            modifier = Modifier
                                .size(24.dp)
                                .clickableAlpha { isEditingAmount = true }
                                .testTag("PaymentRequestEditAmount")
                        )
                    }
                    VerticalSpacer(16.dp)
                    Caption13Up(
                        text = stringResource(R.string.wallet__payment_request_note),
                        color = Colors.White64,
                    )
                    VerticalSpacer(8.dp)
                    TextInput(
                        value = note,
                        onValueChange = { note = it.take(256) },
                        placeholder = stringResource(R.string.wallet__payment_request_note_placeholder),
                        minLines = 1,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("PaymentRequestNote")
                    )
                    if (recipient != null) {
                        VerticalSpacer(16.dp)
                        Caption13Up(
                            text = stringResource(R.string.wallet__payment_request_recipient),
                            color = Colors.White64,
                        )
                        VerticalSpacer(8.dp)
                        PaymentRequestCard(
                            request = PaykitPaymentRequest(
                                paymentRequestId = "preview",
                                counterparty = recipient.publicKey,
                                counterpartyReceiverPath = "",
                                amountValue = amountState.sats.toString(),
                                amountSats = amountState.sats.toULong(),
                                note = note.trim().ifBlank { null },
                                expiresAt = Clock.System.now() + expiration.duration,
                                acceptedPaymentEndpointIdentifiers = emptyList(),
                            ),
                            contact = recipient,
                            compactSubtitle = note.trim().ifBlank { recipient.name },
                        )
                    }
                    VerticalSpacer(16.dp)
                    Caption13Up(
                        text = stringResource(R.string.wallet__payment_request_expires),
                        color = Colors.White64,
                    )
                    VerticalSpacer(8.dp)
                    Row(modifier = Modifier.fillMaxWidth()) {
                        PaymentRequestExpiration.entries.forEach { option ->
                            val isSelected = option == expiration
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickableAlpha { expiration = option }
                                    .semantics {
                                        role = Role.RadioButton
                                        selected = isSelected
                                    }
                                    .testTag("PaymentRequestExpiry${option.name}")
                            ) {
                                BodyS(
                                    text = option.title(),
                                    color = if (isSelected) Colors.White else Colors.White64,
                                )
                                VerticalSpacer(8.dp)
                                HorizontalDivider(
                                    thickness = 2.dp,
                                    color = if (isSelected) Colors.White else Colors.White16,
                                )
                            }
                        }
                    }
                    FillHeight()
                    PrimaryButton(
                        text = stringResource(
                            if (recipient != null) {
                                R.string.wallet__payment_request_send_request
                            } else {
                                R.string.wallet__payment_request_choose_recipient
                            }
                        ),
                        enabled = amountState.sats > 0 && !isCreating,
                        isLoading = isCreating,
                        onClick = {
                            onContinue(
                                PaykitPaymentRequestDraft(
                                    amountSats = amountState.sats.toULong(),
                                    note = note.trim(),
                                    expiresAt = Clock.System.now() + expiration.duration,
                                )
                            )
                        },
                        modifier = Modifier.testTag(
                            if (recipient != null) "PaymentRequestSend" else "PaymentRequestAmountContinue"
                        )
                    )
                }
                VerticalSpacer(16.dp)
            }
        }
    }
}

@Composable
fun PaymentRequestRecipientScreen(
    appViewModel: AppViewModel,
    onBack: () -> Unit,
    onEditExpiration: () -> Unit,
    onRecipientSelected: (PaykitPaymentRequestTarget) -> Unit,
) {
    val context = LocalContext.current
    val targets by appViewModel.eligiblePaymentRequestTargets.collectAsStateWithLifecycle()
    val contacts by appViewModel.pubkyContacts.collectAsStateWithLifecycle()

    PaymentRequestRecipientContent(
        targets = targets.toImmutableList(),
        contacts = contacts.toImmutableList(),
        isCreating = false,
        onBack = onBack,
        onEditExpiration = onEditExpiration,
        onPaste = { context.getClipboardText()?.trim().orEmpty() },
        onSend = onRecipientSelected,
    )
}

@Composable
internal fun PaymentRequestRecipientContent(
    modifier: Modifier = Modifier,
    targets: ImmutableList<PaykitPaymentRequestTarget>,
    contacts: ImmutableList<PubkyProfile>,
    isCreating: Boolean,
    onBack: () -> Unit,
    onEditExpiration: () -> Unit,
    onPaste: () -> String,
    onSend: (PaykitPaymentRequestTarget) -> Unit,
) {
    var selectedTarget by remember { mutableStateOf<PaykitPaymentRequestTarget?>(null) }
    var query by remember { mutableStateOf("") }

    val recipients = remember(targets, contacts, query) {
        targets.mapNotNull { target ->
            contacts.firstOrNull { PubkyPublicKeyFormat.matches(it.publicKey, target.publicKey) }
                ?.let { target to it }
        }.filter { (target, contact) ->
            query.isBlank() ||
                target.publicKey.contains(query.trim(), ignoreCase = true) ||
                contact.name.contains(query.trim(), ignoreCase = true)
        }
    }

    LaunchedEffect(recipients) {
        if (recipients.none { (target, _) -> target == selectedTarget }) selectedTarget = null
    }
    BackHandler(enabled = isCreating) {}

    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("PaymentRequestRecipient")
    ) {
        SheetTopBar(
            titleText = stringResource(R.string.wallet__payment_request_choose_recipient),
            onBack = onBack,
            action = {
                IconButton(
                    onClick = onEditExpiration,
                    enabled = !isCreating,
                    modifier = Modifier.testTag("PaymentRequestEditExpiration")
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_timer),
                        contentDescription = stringResource(R.string.wallet__payment_request_edit_expiration),
                        tint = Colors.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            },
        )
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            VerticalSpacer(16.dp)
            Caption13Up(text = stringResource(R.string.wallet__payment_request_recipient), color = Colors.White64)
            VerticalSpacer(8.dp)
            TextInput(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.wallet__payment_request_enter_pubky),
                singleLine = true,
                textStyle = AppTextStyles.BodyM,
                trailingIcon = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .clickableAlpha {
                                query = PubkyPublicKeyFormat.bounded(onPaste())
                            }
                            .padding(horizontal = 12.dp)
                            .testTag("PaymentRequestRecipientPaste")
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_clipboard_text),
                            contentDescription = null,
                            tint = Colors.White,
                            modifier = Modifier.size(24.dp)
                        )
                        BodyMSB(text = stringResource(R.string.wallet__payment_request_paste))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("PaymentRequestRecipientSearch")
            )
            VerticalSpacer(16.dp)
        }
        Caption13Up(
            text = stringResource(R.string.contacts__contacts_header),
            color = Colors.White64,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (recipients.isEmpty()) {
                item {
                    BodyM(
                        text = stringResource(
                            if (query.isBlank()) {
                                R.string.wallet__payment_request_recipient_unavailable
                            } else {
                                R.string.wallet__payment_request_recipient_no_match
                            }
                        ),
                        color = Colors.White64,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp)
                            .testTag("PaymentRequestRecipientUnavailable")
                    )
                }
            }
            items(
                items = recipients,
                key = { (target, _) -> "${target.publicKey}|${target.receiverPath}" },
            ) { (target, contact) ->
                PubkyContactRow(
                    profile = contact,
                    onClick = { selectedTarget = target },
                    isSelected = target == selectedTarget,
                    isEnabled = !isCreating,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .testTag("PaymentRequestContact${contact.publicKey}")
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
        PrimaryButton(
            text = stringResource(R.string.wallet__payment_request_send_request),
            enabled = !isCreating && selectedTarget != null && selectedTarget in targets,
            isLoading = isCreating,
            onClick = {
                val target = selectedTarget ?: return@PrimaryButton
                onSend(target)
            },
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .testTag("PaymentRequestSend")
        )
        VerticalSpacer(16.dp)
    }
}

@Composable
fun PaymentRequestSentScreen(
    appViewModel: AppViewModel,
    request: PaykitPaymentRequest,
    onDone: () -> Unit,
) {
    val contacts by appViewModel.pubkyContacts.collectAsStateWithLifecycle()
    val contact = contacts.firstOrNull { PubkyPublicKeyFormat.matches(it.publicKey, request.counterparty) }
    PaymentRequestSentContent(request = request, contact = contact, onDone = onDone)
}

@Composable
internal fun PaymentRequestSentContent(
    modifier: Modifier = Modifier,
    request: PaykitPaymentRequest,
    contact: PubkyProfile?,
    onDone: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("PaymentRequestSent")
    ) {
        SheetTopBar(titleText = stringResource(R.string.wallet__payment_request_sent_title))
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            VerticalSpacer(32.dp)
            Image(
                painter = painterResource(R.drawable.check),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(256.dp)
                    .testTag("PaymentRequestSentCheck")
            )
            VerticalSpacer(32.dp)
            Display(
                text = stringResource(R.string.wallet__payment_request_sent_headline)
                    .withAccent(accentColor = Colors.Purple),
            )
            VerticalSpacer(8.dp)
            BodyM(
                text = stringResource(R.string.wallet__payment_request_sent_description),
                color = Colors.White64,
            )
            VerticalSpacer(16.dp)
            PaymentRequestCard(
                request = request,
                contact = contact,
                compactSubtitle = request.note?.takeIf { it.isNotBlank() }
                    ?: if (request.deliveryStatus == PaykitPaymentRequestDeliveryStatus.Sent) {
                        stringResource(R.string.wallet__payment_request_waiting)
                    } else {
                        stringResource(R.string.wallet__payment_request_sending)
                    },
            )
            FillHeight()
            PrimaryButton(
                text = stringResource(R.string.common__ok),
                onClick = onDone,
            )
            VerticalSpacer(16.dp)
        }
    }
}

@Composable
private fun PaymentRequestExpiration.title(): String = stringResource(
    when (this) {
        PaymentRequestExpiration.Hour -> R.string.wallet__payment_request_expiry_hour
        PaymentRequestExpiration.Day -> R.string.wallet__payment_request_expiry_day
        PaymentRequestExpiration.Week -> R.string.wallet__payment_request_expiry_week
        PaymentRequestExpiration.Month -> R.string.wallet__payment_request_expiry_month
    }
)

private val previewDraft = PaykitPaymentRequestDraft(
    amountSats = 25_000uL,
    note = "Dinner",
    expiresAt = Instant.parse("2027-01-15T09:00:00Z"),
)

private val previewTarget = PaykitPaymentRequestTarget(
    publicKey = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg",
    receiverPath = "bitkit/wallet",
)

private val previewCreatedRequest = PaykitPaymentRequest(
    paymentRequestId = "payment-request",
    counterparty = previewTarget.publicKey,
    counterpartyReceiverPath = previewTarget.receiverPath,
    amountValue = "0.00025",
    amountSats = previewDraft.amountSats,
    note = previewDraft.note,
    createdAt = Instant.parse("2027-01-15T08:00:00Z"),
    expiresAt = previewDraft.expiresAt,
    acceptedPaymentEndpointIdentifiers = listOf("btc-lightning-bolt11"),
)

@Preview(showSystemUi = true)
@Composable
private fun PaymentRequestDetailsPreview() {
    AppThemeSurface {
        BottomSheetPreview {
            PaymentRequestDetailsContent(
                amountInputViewModel = AmountInputViewModel(AmountInputHandler.stub()),
                initialDraft = previewDraft,
                onBack = {},
                onContinue = {},
                modifier = Modifier.sheetHeight()
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PaymentRequestDetailsContactPreview() {
    AppThemeSurface {
        BottomSheetPreview {
            PaymentRequestDetailsContent(
                amountInputViewModel = AmountInputViewModel(AmountInputHandler.stub()),
                initialDraft = previewDraft,
                onBack = {},
                onContinue = {},
                recipient = PubkyProfile.placeholder(previewTarget.publicKey),
                modifier = Modifier.sheetHeight()
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PaymentRequestRecipientPreview() {
    AppThemeSurface {
        BottomSheetPreview {
            PaymentRequestRecipientContent(
                targets = persistentListOf(previewTarget),
                contacts = persistentListOf(PubkyProfile.placeholder(previewTarget.publicKey)),
                isCreating = false,
                onBack = {},
                onEditExpiration = {},
                onPaste = { "" },
                onSend = {},
                modifier = Modifier.sheetHeight()
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PaymentRequestSentPreview() {
    AppThemeSurface {
        BottomSheetPreview {
            PaymentRequestSentContent(
                request = previewCreatedRequest,
                contact = PubkyProfile.placeholder(previewTarget.publicKey),
                onDone = {},
                modifier = Modifier.sheetHeight()
            )
        }
    }
}

@file:OptIn(ExperimentalTime::class)
@file:Suppress("MatchingDeclarationName")

package to.bitkit.ui.screens.paymentrequests

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import to.bitkit.R
import to.bitkit.ext.getClipboardText
import to.bitkit.models.PaykitAmount
import to.bitkit.models.PaykitAsset
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.repositories.MethodId
import to.bitkit.repositories.PaykitPaymentRequest
import to.bitkit.repositories.PaykitPaymentRequestDeliveryStatus
import to.bitkit.repositories.PaykitPaymentRequestDirection
import to.bitkit.repositories.PaykitPaymentRequestDraft
import to.bitkit.repositories.PaykitPaymentRequestTarget
import to.bitkit.repositories.paykitRate
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.FillWidth
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadActionButton
import to.bitkit.ui.components.NumberPadAmountText
import to.bitkit.ui.components.NumberPadTextField
import to.bitkit.ui.components.NumberPadType
import to.bitkit.ui.components.PaykitAmountCell
import to.bitkit.ui.components.PaykitAmountDisplay
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.PubkyContactAvatar
import to.bitkit.ui.components.PubkyContactRow
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.UnitButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.NumberPadInputHandler
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
fun PaymentRequestAmountScreen(
    amountInputViewModel: AmountInputViewModel,
    initialDraft: PaykitPaymentRequestDraft,
    contact: PubkyProfile?,
    onBack: () -> Unit,
    onContinue: (PaykitPaymentRequestDraft) -> Unit,
) {
    PaymentRequestAmountContent(
        amountInputViewModel = amountInputViewModel,
        initialDraft = initialDraft,
        contact = contact,
        onBack = onBack,
        onContinue = onContinue,
    )
}

@Composable
internal fun PaymentRequestAmountContent(
    modifier: Modifier = Modifier,
    amountInputViewModel: AmountInputViewModel,
    initialDraft: PaykitPaymentRequestDraft,
    contact: PubkyProfile?,
    onBack: () -> Unit,
    onContinue: (PaykitPaymentRequestDraft) -> Unit,
) {
    val currencies = LocalCurrencies.current
    val amountState by amountInputViewModel.uiState.collectAsStateWithLifecycle()

    var asset by remember(initialDraft.amount.asset) { mutableStateOf(initialDraft.amount.asset) }
    var dollars by remember(initialDraft.amount) {
        mutableStateOf(if (initialDraft.amount.asset != PaykitAsset.BTC) initialDraft.amount.value else "")
    }
    var conversionError by remember { mutableStateOf(false) }
    val entered = if (asset == PaykitAsset.BTC) {
        PaykitAmount(asset, amountState.sats.toULong())
    } else {
        runCatching { PaykitAmount.parse(asset, dollars) }.getOrNull()
    }
    LaunchedEffect(initialDraft.amount) {
        if (initialDraft.amount.asset == PaykitAsset.BTC) {
            amountInputViewModel.setSats(
                initialDraft.amount.atomic.coerceAtMost(Long.MAX_VALUE.toULong()).toLong(),
                currencies
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("PaymentRequestAmount")
    ) {
        SheetTopBar(
            titleText = stringResource(R.string.wallet__payment_request_amount),
            onBack = onBack,
            action = contact?.let {
                {
                    PubkyContactAvatar(
                        profile = it,
                        size = 32.dp,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            },
        )
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val availableHeight = this.maxHeight

            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                VerticalSpacer(16.dp)
                if (asset == PaykitAsset.BTC) {
                    NumberPadTextField(
                        viewModel = amountInputViewModel,
                        modifier = Modifier.fillMaxWidth().testTag("PaymentRequestAmountField")
                    )
                } else {
                    NumberPadAmountText(
                        value = dollars.ifEmpty { "0" },
                        symbol = "$",
                        modifier = Modifier.testTag("PaymentRequestAmountField")
                    )
                }
                VerticalSpacer(16.dp)
                PaymentRequestAssetSelector(
                    asset = asset,
                    amount = entered,
                    onConverted = {
                        asset = it.asset
                        if (it.asset == PaykitAsset.BTC) {
                            amountInputViewModel.setSats(it.atomic.toLong(), currencies)
                        } else {
                            dollars = if (it.atomic == 0uL) "" else it.value
                        }
                        conversionError = false
                    },
                    onUnavailable = { conversionError = true },
                )
                if (conversionError) {
                    BodyS(
                        text = stringResource(R.string.wallet__payment_request_rate_unavailable),
                        color = Colors.White64
                    )
                }
                FillHeight(min = 12.dp)
                if (asset == PaykitAsset.BTC) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        FillWidth()
                        UnitButton(
                            onClick = { amountInputViewModel.switchUnit(currencies) },
                            color = Colors.Brand,
                            modifier = Modifier.testTag("PaymentRequestAmountUnit")
                        )
                    }
                }
                VerticalSpacer(16.dp)
                HorizontalDivider(color = Colors.White10)
                if (asset == PaykitAsset.BTC) {
                    NumberPad(
                        viewModel = amountInputViewModel,
                        currencies = currencies,
                        availableHeight = availableHeight,
                        modifier = Modifier.testTag("PaymentRequestNumberPad")
                    )
                } else {
                    NumberPad(
                        onPress = {
                            dollars = NumberPadInputHandler.handleInput(
                                it, dollars, MAX_USD_INPUT_LENGTH, PaykitAsset.USD.decimals
                            )
                        },
                        type = NumberPadType.DECIMAL,
                        availableHeight = availableHeight,
                        modifier = Modifier.testTag("PaymentRequestNumberPad")
                    )
                }
                PrimaryButton(
                    text = stringResource(R.string.common__continue),
                    enabled = (entered?.atomic ?: 0uL) > 0uL,
                    onClick = {
                        entered?.let { onContinue(initialDraft.copy(amount = it)) }
                    },
                    modifier = Modifier.testTag("PaymentRequestAmountContinue"),
                )
                VerticalSpacer(16.dp)
            }
        }
    }
}

private const val MAX_USD_INPUT_LENGTH = 18

@Composable
private fun PaymentRequestAssetSelector(
    asset: PaykitAsset,
    amount: PaykitAmount?,
    onConverted: (PaykitAmount) -> Unit,
    onUnavailable: () -> Unit,
) {
    val currencies = LocalCurrencies.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(PaykitAsset.BTC, PaykitAsset.USD).forEach { option ->
            NumberPadActionButton(
                text = option.code.uppercase(),
                outlined = asset != option,
                color = if (option == PaykitAsset.BTC) Colors.Brand else Colors.Usdt,
                modifier = Modifier.testTag("PaymentRequestAsset${option.name}"),
                onClick = {
                    if (option != asset) {
                        runCatching {
                            amount?.takeIf { it.atomic > 0uL }?.convertedTo(
                                option, currencies.paykitRate, Clock.System.now().toEpochMilliseconds()
                            ) ?: PaykitAmount(option, 0uL)
                        }.onSuccess(onConverted).onFailure { onUnavailable() }
                    }
                },
            )
        }
    }
}

@Composable
fun PaymentRequestDetailsScreen(
    appViewModel: AppViewModel,
    draft: PaykitPaymentRequestDraft,
    target: PaykitPaymentRequestTarget,
    onBack: () -> Unit,
    onEditAmount: (PaykitPaymentRequestDraft) -> Unit,
    onSent: (PaykitPaymentRequest) -> Unit,
) {
    val contacts by appViewModel.pubkyContacts.collectAsStateWithLifecycle()
    val isCreating by appViewModel.isCreatingPaymentRequest.collectAsStateWithLifecycle()
    val receivingMethods by appViewModel.paykitReceivingMethods.collectAsStateWithLifecycle()
    val contact = contacts.firstOrNull { PubkyPublicKeyFormat.matches(it.publicKey, target.publicKey) }
        ?: PubkyProfile.placeholder(target.publicKey)

    PaymentRequestDetailsContent(
        initialDraft = draft,
        availableMethods = receivingMethods,
        contact = contact,
        isCreating = isCreating,
        onBack = onBack,
        onEditAmount = onEditAmount,
        onSend = { updatedDraft -> appViewModel.createPaymentRequest(updatedDraft, target, onSent) },
    )
}

@Composable
internal fun PaymentRequestDetailsContent(
    initialDraft: PaykitPaymentRequestDraft,
    availableMethods: List<String> = emptyList(),
    contact: PubkyProfile,
    isCreating: Boolean,
    onBack: () -> Unit,
    onEditAmount: (PaykitPaymentRequestDraft) -> Unit,
    onSend: (PaykitPaymentRequestDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    var acceptedMethods by remember(initialDraft.acceptedPaymentEndpointIdentifiers) {
        mutableStateOf(initialDraft.acceptedPaymentEndpointIdentifiers)
    }
    val selectedMethods = acceptedMethods ?: availableMethods
    var note by remember(initialDraft.note) { mutableStateOf(initialDraft.note) }
    var expiration by remember(initialDraft.expiresAt) {
        mutableStateOf(PaymentRequestExpiration.from(initialDraft.expiresAt, Clock.System.now()))
    }
    fun updatedDraft(trimNote: Boolean = false) = initialDraft.copy(
        acceptedPaymentEndpointIdentifiers = selectedMethods,
        note = if (trimNote) note.trim() else note,
        expiresAt = Clock.System.now() + expiration.duration,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .testTag("PaymentRequestDetails")
    ) {
        SheetTopBar(
            titleText = stringResource(R.string.wallet__payment_request),
            onBack = onBack,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            PaykitAmountDisplay(initialDraft.amount, modifier = Modifier.weight(1f))
            IconButton(
                onClick = { onEditAmount(updatedDraft()) },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("PaymentRequestEditAmount"),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_pencil_simple),
                    contentDescription = stringResource(R.string.common__edit),
                    tint = Colors.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        VerticalSpacer(20.dp)
        Caption13Up(text = stringResource(R.string.wallet__payment_request_note), color = Colors.White64)
        VerticalSpacer(8.dp)
        TextInput(
            value = note,
            onValueChange = { note = it.take(256) },
            placeholder = stringResource(R.string.wallet__payment_request_note_placeholder),
            maxLines = 2,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("PaymentRequestNote"),
        )
        VerticalSpacer(20.dp)
        Caption13Up(text = stringResource(R.string.wallet__payment_request_accepted_methods), color = Colors.White64)
        VerticalSpacer(8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val groups = listOf(
                MethodId.entries.filter { it.isOnchain },
                listOf(MethodId.Bolt11, MethodId.Lnurl),
                listOf(MethodId.UsdtArbitrum),
            ).map { methods -> methods.filter { it.rawValue in availableMethods } }.filter { it.isNotEmpty() }
            groups.forEach { methods ->
                val endpoints = methods.map { it.rawValue }
                val selected = endpoints.all { it in selectedMethods }
                val (label, color, id) = when (methods.first()) {
                    MethodId.UsdtArbitrum -> Triple("USDT", Colors.Usdt, "usdt")
                    MethodId.Bolt11, MethodId.Lnurl -> Triple(
                        stringResource(R.string.lightning__spending),
                        Colors.Purple,
                        "spending"
                    )
                    else -> Triple(stringResource(R.string.lightning__savings), Colors.Brand, "savings")
                }
                NumberPadActionButton(
                    text = label,
                    color = color,
                    outlined = !selected,
                    onClick = {
                        acceptedMethods = if (selected) {
                            selectedMethods - endpoints.toSet()
                        } else {
                            selectedMethods + endpoints.filter { it !in selectedMethods }
                        }
                    },
                    modifier = Modifier.testTag("PaymentRequestAccept-$id")
                )
            }
        }
        VerticalSpacer(20.dp)
        Caption13Up(text = stringResource(R.string.wallet__payment_request_recipient), color = Colors.White64)
        VerticalSpacer(8.dp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Colors.Gray6, RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            PubkyContactAvatar(profile = contact, size = 40.dp)
            Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                BodyMSB(text = contact.name, maxLines = 1)
                BodyS(
                    text = note.ifBlank { stringResource(R.string.wallet__payment_request) },
                    color = Colors.White64,
                    maxLines = 1,
                )
            }
            PaykitAmountCell(initialDraft.amount)
        }
        VerticalSpacer(20.dp)
        Caption13Up(text = stringResource(R.string.wallet__payment_request_expires), color = Colors.White64)
        VerticalSpacer(8.dp)
        Row(modifier = Modifier.fillMaxWidth()) {
            PaymentRequestExpiration.entries.forEach { option ->
                val isSelected = option == expiration
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clickableAlpha { expiration = option }
                        .testTag("PaymentRequestExpiry${option.name}"),
                ) {
                    BodyS(text = option.title(), color = if (isSelected) Colors.White else Colors.White64)
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
            text = stringResource(R.string.wallet__payment_request_send_request),
            enabled = !isCreating && selectedMethods.isNotEmpty(),
            isLoading = isCreating,
            onClick = { onSend(updatedDraft(trimNote = true)) },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_sent),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
            modifier = Modifier.testTag("PaymentRequestSend"),
        )
        VerticalSpacer(16.dp)
    }
}

@Composable
fun PaymentRequestRecipientScreen(
    appViewModel: AppViewModel,
    onBack: () -> Unit,
    onSelected: (PaykitPaymentRequestTarget) -> Unit,
) {
    val context = LocalContext.current
    val targets by appViewModel.eligiblePaymentRequestTargets.collectAsStateWithLifecycle()
    val contacts by appViewModel.pubkyContacts.collectAsStateWithLifecycle()

    PaymentRequestRecipientContent(
        targets = targets.toImmutableList(),
        contacts = contacts.toImmutableList(),
        onBack = onBack,
        onPaste = { context.getClipboardText()?.trim().orEmpty() },
        onSelected = onSelected,
    )
}

@Composable
internal fun PaymentRequestRecipientContent(
    modifier: Modifier = Modifier,
    targets: ImmutableList<PaykitPaymentRequestTarget>,
    contacts: ImmutableList<PubkyProfile>,
    onBack: () -> Unit,
    onPaste: () -> String,
    onSelected: (PaykitPaymentRequestTarget) -> Unit,
    @StringRes titleRes: Int = R.string.wallet__payment_request_choose_recipient,
    selectedTarget: PaykitPaymentRequestTarget? = null,
    testTagPrefix: String = "PaymentRequest",
    action: (@Composable () -> Unit)? = null,
    footer: @Composable () -> Unit = {},
) {
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp)
            .testTag("${testTagPrefix}Recipient")
    ) {
        SheetTopBar(
            titleText = stringResource(titleRes),
            action = action,
            onBack = onBack,
        )
        Caption13Up(text = stringResource(R.string.wallet__payment_request_recipient), color = Colors.White64)
        VerticalSpacer(8.dp)
        TextInput(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.wallet__payment_request_enter_pubky),
            singleLine = true,
            trailingIcon = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .clickableAlpha {
                            query = PubkyPublicKeyFormat.bounded(onPaste())
                        }
                        .padding(horizontal = 24.dp)
                        .testTag("${testTagPrefix}RecipientPaste")
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_clipboard_text),
                        contentDescription = null,
                        tint = Colors.White,
                        modifier = Modifier.size(16.dp),
                    )
                    CaptionB(text = stringResource(R.string.wallet__payment_request_paste))
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("${testTagPrefix}RecipientSearch")
        )
        VerticalSpacer(32.dp)
        Caption13Up(text = stringResource(R.string.contacts__contacts_header), color = Colors.White64)
        VerticalSpacer(16.dp)
        HorizontalDivider(color = Colors.White10)
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
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp)
                            .testTag("${testTagPrefix}RecipientUnavailable"),
                    )
                }
            }
            items(
                items = recipients,
                key = { (target, _) -> target.publicKey },
            ) { (target, contact) ->
                PubkyContactRow(
                    profile = contact,
                    onClick = { onSelected(target) },
                    verticalPadding = 24.dp,
                    isSelected = selectedTarget?.let { it == target },
                    selectionColor = Colors.Brand,
                    modifier = Modifier.testTag("${testTagPrefix}Contact${contact.publicKey}"),
                )
                HorizontalDivider(color = Colors.White10)
            }
        }
        footer()
    }
}

@Composable
fun PaymentRequestSentScreen(
    appViewModel: AppViewModel,
    request: PaykitPaymentRequest,
    onDone: () -> Unit,
) {
    val contacts by appViewModel.pubkyContacts.collectAsStateWithLifecycle()
    val history by appViewModel.paymentRequestHistory.collectAsStateWithLifecycle()
    val contact = contacts.firstOrNull { PubkyPublicKeyFormat.matches(it.publicKey, request.counterparty) }
    PaymentRequestSentContent(
        request = request,
        history = history.toImmutableList(),
        contact = contact,
        onDone = onDone,
    )
}

@Composable
internal fun PaymentRequestSentContent(
    modifier: Modifier = Modifier,
    request: PaykitPaymentRequest,
    history: ImmutableList<PaykitPaymentRequest>,
    contact: PubkyProfile?,
    onDone: () -> Unit,
) {
    val currentRequest = history.firstOrNull {
        it.id == request.id && it.direction == PaykitPaymentRequestDirection.Outgoing
    } ?: request
    Column(
        horizontalAlignment = Alignment.Start,
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .testTag("PaymentRequestSent"),
    ) {
        SheetTopBar(titleText = stringResource(R.string.wallet__payment_request_sent_title))
        VerticalSpacer(32.dp)
        Image(
            painter = painterResource(R.drawable.check),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(256.dp)
                .testTag("PaymentRequestSentCheck"),
        )
        VerticalSpacer(32.dp)
        Display(
            text = stringResource(R.string.wallet__payment_request_sent_headline)
                .withAccent(accentColor = Colors.Purple),
        )
        VerticalSpacer(12.dp)
        BodyM(
            text = stringResource(
                if (currentRequest.deliveryStatus == PaykitPaymentRequestDeliveryStatus.Sent) {
                    R.string.wallet__payment_request_sent_description
                } else {
                    R.string.wallet__payment_request_queued_description
                }
            ),
            color = Colors.White64,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        VerticalSpacer(24.dp)
        PaymentRequestCard(
            request = currentRequest,
            contact = contact,
            compactSubtitle = currentRequest.note?.takeIf(String::isNotBlank) ?: paymentRequestStatus(currentRequest),
        )
        VerticalSpacer(32.dp)
        PrimaryButton(
            text = stringResource(R.string.common__ok),
            onClick = onDone,
        )
        VerticalSpacer(16.dp)
    }
}

@Composable
internal fun PaymentRequestExpiration.title(): String = stringResource(
    when (this) {
        PaymentRequestExpiration.Hour -> R.string.wallet__payment_request_expiry_hour
        PaymentRequestExpiration.Day -> R.string.wallet__payment_request_expiry_day
        PaymentRequestExpiration.Week -> R.string.wallet__payment_request_expiry_week
        PaymentRequestExpiration.Month -> R.string.wallet__payment_request_expiry_month
    }
)

private val previewDraft = PaykitPaymentRequestDraft(
    amount = PaykitAmount(PaykitAsset.BTC, 25_000uL),
    note = "Dinner",
    expiresAt = Instant.parse("2027-01-15T09:00:00Z"),
)

private val previewTarget = PaykitPaymentRequestTarget(
    publicKey = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg",
)

private val previewCreatedRequest = PaykitPaymentRequest(
    paymentRequestId = "payment-request",
    counterparty = previewTarget.publicKey,
    amount = previewDraft.amount,
    paymentReference = "preview",
    note = previewDraft.note,
    createdAt = Instant.parse("2027-01-15T08:00:00Z"),
    expiresAt = previewDraft.expiresAt,
    acceptedPaymentEndpointIdentifiers = listOf("btc-lightning-bolt11"),
    direction = PaykitPaymentRequestDirection.Outgoing,
)

@Preview(showSystemUi = true)
@Composable
private fun PaymentRequestDetailsPreview() {
    AppThemeSurface {
        BottomSheetPreview {
            PaymentRequestDetailsContent(
                initialDraft = previewDraft,
                contact = PubkyProfile.placeholder(previewTarget.publicKey),
                isCreating = false,
                onBack = {},
                onEditAmount = {},
                onSend = {},
                modifier = Modifier.sheetHeight(),
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
                onBack = {},
                onPaste = { "" },
                onSelected = {},
                modifier = Modifier.sheetHeight(),
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
                history = persistentListOf(),
                contact = PubkyProfile.placeholder(previewTarget.publicKey),
                onDone = {},
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

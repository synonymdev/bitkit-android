@file:OptIn(ExperimentalTime::class)
@file:Suppress("MatchingDeclarationName")

package to.bitkit.ui.screens.paymentrequests

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.repositories.PaykitPaymentRequest
import to.bitkit.repositories.PaykitPaymentRequestDraft
import to.bitkit.repositories.PaykitPaymentRequestTarget
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.MoneyStack
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadTextField
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.PubkyContactAvatar
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.util.gradientBackground
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
) {
    val currencies = LocalCurrencies.current
    val amountState by amountInputViewModel.uiState.collectAsStateWithLifecycle()
    var note by remember(initialDraft.note) { mutableStateOf(initialDraft.note) }
    var expiration by remember(initialDraft.expiresAt) {
        mutableStateOf(PaymentRequestExpiration.from(initialDraft.expiresAt, Clock.System.now()))
    }

    LaunchedEffect(initialDraft.amountSats) {
        amountInputViewModel.setSats(
            initialDraft.amountSats.coerceAtMost(Long.MAX_VALUE.toULong()).toLong(),
            currencies,
        )
    }

    Column(
        modifier = Modifier
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
        Caption13Up(text = stringResource(R.string.wallet__payment_request_amount), color = Colors.White64)
        Spacer(Modifier.height(8.dp))
        NumberPadTextField(
            viewModel = amountInputViewModel,
            modifier = Modifier.testTag("PaymentRequestAmountField"),
        )
        Spacer(Modifier.height(20.dp))
        Caption13Up(text = stringResource(R.string.wallet__payment_request_note), color = Colors.White64)
        Spacer(Modifier.height(8.dp))
        TextInput(
            value = note,
            onValueChange = { note = it.take(256) },
            placeholder = stringResource(R.string.wallet__payment_request_note_placeholder),
            maxLines = 2,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("PaymentRequestNote"),
        )
        Spacer(Modifier.height(20.dp))
        Caption13Up(text = stringResource(R.string.wallet__payment_request_expires), color = Colors.White64)
        Spacer(Modifier.height(8.dp))
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
                        .testTag("PaymentRequestExpiry_${option.name}"),
                ) {
                    BodyS(text = option.title(), color = if (isSelected) Colors.White else Colors.White64)
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(
                        thickness = 2.dp,
                        color = if (isSelected) Colors.Purple else Colors.White16,
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        NumberPad(
            viewModel = amountInputViewModel,
            availableHeight = 210.dp,
            modifier = Modifier.testTag("PaymentRequestNumberPad"),
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            text = stringResource(R.string.wallet__payment_request_choose_recipient),
            enabled = amountState.sats > 0,
            onClick = {
                onContinue(
                    PaykitPaymentRequestDraft(
                        amountSats = amountState.sats.toULong(),
                        note = note.trim(),
                        expiresAt = Clock.System.now() + expiration.duration,
                    )
                )
            },
            modifier = Modifier.testTag("PaymentRequestAmountContinue"),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun PaymentRequestRecipientScreen(
    appViewModel: AppViewModel,
    draft: PaykitPaymentRequestDraft,
    onBack: () -> Unit,
    onSent: (PaykitPaymentRequest) -> Unit,
) {
    val targets by appViewModel.eligiblePaymentRequestTargets.collectAsStateWithLifecycle()
    val contacts by appViewModel.pubkyContacts.collectAsStateWithLifecycle()
    val isCreating by appViewModel.isCreatingPaymentRequest.collectAsStateWithLifecycle()
    var selectedTarget by remember { mutableStateOf<PaykitPaymentRequestTarget?>(null) }

    LaunchedEffect(targets) {
        if (selectedTarget !in targets) selectedTarget = null
    }
    BackHandler(enabled = isCreating) {}

    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .testTag("PaymentRequestRecipient")
    ) {
        SheetTopBar(
            titleText = stringResource(R.string.wallet__payment_request_choose_recipient),
            onBack = if (isCreating) null else onBack,
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(targets, key = { "${it.publicKey}|${it.receiverPath}" }) { target ->
                val contact = contacts.firstOrNull { PubkyPublicKeyFormat.matches(it.publicKey, target.publicKey) }
                    ?: PubkyProfile.placeholder(target.publicKey)
                PaymentRequestContactRow(
                    contact = contact,
                    selected = target == selectedTarget,
                    enabled = !isCreating,
                    onClick = { selectedTarget = target },
                )
                HorizontalDivider(color = Colors.White10)
            }
        }
        PrimaryButton(
            text = stringResource(R.string.wallet__payment_request_send_request),
            enabled = !isCreating && selectedTarget != null && selectedTarget in targets,
            isLoading = isCreating,
            onClick = {
                val target = selectedTarget ?: return@PrimaryButton
                appViewModel.createPaymentRequest(draft, target, onSent)
            },
            modifier = Modifier.testTag("PaymentRequestSend"),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PaymentRequestContactRow(
    contact: PubkyProfile,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickableAlpha(enabled = enabled, onClick = onClick)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
                if (!enabled) disabled()
            }
            .padding(vertical = 16.dp)
            .testTag("PaymentRequestContact_${contact.publicKey}"),
    ) {
        PubkyContactAvatar(profile = contact)
        Column(modifier = Modifier.weight(1f)) {
            BodyS(
                text = contact.truncatedPublicKey,
                color = Colors.White64,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BodySSB(text = contact.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = Colors.Purple,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
fun PaymentRequestSentScreen(
    request: PaykitPaymentRequest,
    onDone: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .testTag("PaymentRequestSent"),
    ) {
        SheetTopBar(titleText = stringResource(R.string.wallet__payment_request_sent_title))
        MoneyStack(sats = request.amountSats.coerceAtMost(Long.MAX_VALUE.toULong()).toLong())
        Spacer(Modifier.weight(1f))
        Image(
            painter = painterResource(R.drawable.check),
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(24.dp))
        Display(text = stringResource(R.string.wallet__payment_request_sent_headline).withAccent())
        Spacer(Modifier.height(12.dp))
        BodyM(
            text = if (request.deliveryStatus == to.bitkit.repositories.PaykitPaymentRequestDeliveryStatus.Sent) {
                stringResource(R.string.wallet__payment_request_sent_description)
            } else {
                stringResource(R.string.wallet__payment_request_queued_description)
            },
            color = Colors.White64,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        PrimaryButton(
            text = stringResource(R.string.common__ok),
            onClick = onDone,
        )
        Spacer(Modifier.height(16.dp))
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

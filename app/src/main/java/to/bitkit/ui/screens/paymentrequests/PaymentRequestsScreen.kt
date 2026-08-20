@file:OptIn(ExperimentalTime::class)

package to.bitkit.ui.screens.paymentrequests

import androidx.compose.foundation.border
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ext.formatInvoiceExpiryRelative
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.repositories.PaykitPaymentRequest
import to.bitkit.repositories.PaykitPaymentRequestDeliveryStatus
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.PubkyContactAvatar
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.AppViewModel
import java.text.DateFormat
import java.util.Date
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Composable
fun PaymentRequestsSheet(
    appViewModel: AppViewModel,
    onNotNow: () -> Unit,
    onSeeAll: () -> Unit,
) {
    val requests by appViewModel.pendingPaymentRequests.collectAsStateWithLifecycle()
    val contacts by appViewModel.pubkyContacts.collectAsStateWithLifecycle()

    LaunchedEffect(requests.isEmpty()) {
        if (requests.isEmpty()) onNotNow()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .testTag("PaymentRequestsSheet")
    ) {
        SheetTopBar(titleText = stringResource(R.string.wallet__payment_requests))
        BodyM(
            text = stringResource(R.string.wallet__payment_requests_review),
            color = Colors.White64,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(24.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(requests.take(3), key = { it.lazyListKey }) { request ->
                PaymentRequestCard(
                    request = request,
                    contact = contacts.contactFor(request),
                    onPay = { appViewModel.openIncomingPaymentRequest(request.id) },
                    onReject = { appViewModel.rejectIncomingPaymentRequest(request) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(
                text = stringResource(R.string.wallet__payment_requests_not_now),
                onClick = onNotNow,
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                text = stringResource(R.string.wallet__payment_requests_see_all),
                onClick = onSeeAll,
                modifier = Modifier
                    .weight(1f)
                    .testTag("PaymentRequestsSeeAll"),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun PaymentRequestsScreen(
    appViewModel: AppViewModel,
    onBack: () -> Unit,
) {
    val incoming by appViewModel.pendingPaymentRequests.collectAsStateWithLifecycle()
    val sent by appViewModel.sentPaymentRequests.collectAsStateWithLifecycle()
    val contacts by appViewModel.pubkyContacts.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("PaymentRequestsScreen")
    ) {
        AppTopBar(
            titleText = stringResource(R.string.wallet__payment_requests),
            onBackClick = onBack,
        )
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            if (incoming.isEmpty() && sent.isEmpty()) {
                item {
                    BodyM(
                        text = stringResource(R.string.wallet__payment_requests_empty),
                        color = Colors.White64,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 120.dp),
                    )
                }
            }
            if (incoming.isNotEmpty()) {
                item {
                    Caption13Up(
                        text = stringResource(R.string.wallet__payment_requests_incoming),
                        color = Colors.White64,
                    )
                }
                items(incoming, key = { it.lazyListKey }) { request ->
                    PaymentRequestCard(
                        request = request,
                        contact = contacts.contactFor(request),
                        onPay = { appViewModel.openIncomingPaymentRequest(request.id) },
                        onReject = { appViewModel.rejectIncomingPaymentRequest(request) },
                    )
                }
            }
            if (sent.isNotEmpty()) {
                item {
                    Caption13Up(
                        text = stringResource(R.string.wallet__payment_requests_sent),
                        color = Colors.White64,
                    )
                }
                items(sent, key = { it.lazyListKey }) { request ->
                    PaymentRequestCard(
                        request = request,
                        contact = contacts.contactFor(request),
                        status = if (request.deliveryStatus == PaykitPaymentRequestDeliveryStatus.Sent) {
                            stringResource(R.string.wallet__payment_request_waiting)
                        } else {
                            stringResource(R.string.wallet__payment_request_sending)
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}

@Composable
private fun PaymentRequestCard(
    request: PaykitPaymentRequest,
    contact: PubkyProfile?,
    status: String? = null,
    onPay: (() -> Unit)? = null,
    onReject: (suspend () -> Result<Unit>)? = null,
) {
    val scope = rememberCoroutineScope()
    var isRejecting by remember(request.id) { mutableStateOf(false) }
    val displayContact = contact ?: PubkyProfile.placeholder(request.counterparty)
    val subtitle = remember(request.createdAt, displayContact.name) {
        request.createdAt?.let {
            val timestamp = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(it.toEpochMilliseconds()))
            "${displayContact.name} · $timestamp"
        } ?: displayContact.name
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Colors.Gray6),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Colors.Purple.copy(alpha = 0.32f), MaterialTheme.shapes.medium)
            .testTag("PaymentRequestRow_${request.paymentRequestId}"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            PubkyContactAvatar(profile = displayContact, size = 40.dp)
            Column(modifier = Modifier.weight(1f)) {
                BodyMSB(
                    text = request.note ?: stringResource(R.string.wallet__payment_request),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BodyS(
                    text = subtitle,
                    color = Colors.White64,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MoneySSB(
                sats = request.amountSats.coerceAtMost(Long.MAX_VALUE.toULong()).toLong(),
                showSymbol = true,
            )
        }
        if (status != null) {
            HorizontalDivider(color = Colors.White10)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                BodyS(text = status, color = Colors.Purple)
                Spacer(Modifier.weight(1f))
                request.expiresAt?.let {
                    val remainingSeconds = (it - Clock.System.now()).inWholeSeconds.coerceAtLeast(0).toULong()
                    BodyS(text = formatInvoiceExpiryRelative(remainingSeconds), color = Colors.White64)
                }
            }
        } else if (onPay != null || onReject != null) {
            HorizontalDivider(color = Colors.White10)
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                SecondaryButton(
                    text = stringResource(R.string.wallet__payment_request_reject),
                    onClick = {
                        if (isRejecting || onReject == null) return@SecondaryButton
                        isRejecting = true
                        scope.launch {
                            onReject()
                            isRejecting = false
                        }
                    },
                    isLoading = isRejecting,
                    enabled = !isRejecting,
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_x),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    size = ButtonSize.Small,
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    text = stringResource(R.string.wallet__payment_request_pay),
                    onClick = { onPay?.invoke() },
                    enabled = !isRejecting,
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_coins),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    size = ButtonSize.Small,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun List<PubkyProfile>.contactFor(request: PaykitPaymentRequest): PubkyProfile? =
    firstOrNull { PubkyPublicKeyFormat.matches(it.publicKey, request.counterparty) }

private val PaykitPaymentRequest.lazyListKey: String
    get() = "$paymentRequestId|$counterparty|$counterpartyReceiverPath"

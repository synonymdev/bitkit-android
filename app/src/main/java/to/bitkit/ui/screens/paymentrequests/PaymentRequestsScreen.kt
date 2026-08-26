@file:OptIn(ExperimentalTime::class)

package to.bitkit.ui.screens.paymentrequests

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synonym.paykit.PaymentRequestLifecycleState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import to.bitkit.R
import to.bitkit.ext.UiDateStyle
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.repositories.PaykitPaymentRequest
import to.bitkit.repositories.PaykitPaymentRequestDeliveryStatus
import to.bitkit.repositories.PaykitPaymentRequestDirection
import to.bitkit.repositories.PaykitPaymentRequestId
import to.bitkit.repositories.PaykitSubscription
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.MoneyCell
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.PubkyContactAvatar
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.screens.wallets.activity.components.CircularIcon
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.shared.util.outerGlow
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.uiDateText
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.AppViewModel
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import java.time.Instant as JavaInstant

@Composable
fun PaymentRequestsSheet(
    appViewModel: AppViewModel,
    onNotNow: () -> Unit,
    onSeeAll: () -> Unit,
    onDetails: (PaykitPaymentRequestId) -> Unit,
) {
    val requests by appViewModel.pendingPaymentRequests.collectAsStateWithLifecycle()
    val contacts by appViewModel.pubkyContacts.collectAsStateWithLifecycle()
    val subscriptions by appViewModel.subscriptions.collectAsStateWithLifecycle()

    LaunchedEffect(requests.isEmpty()) {
        if (requests.isEmpty()) onNotNow()
    }

    PaymentRequestsSheetContent(
        requests = requests.toImmutableList(),
        contacts = contacts.toImmutableList(),
        subscriptions = subscriptions.toImmutableList(),
        onNotNow = onNotNow,
        onSeeAll = onSeeAll,
        onPay = appViewModel::openIncomingPaymentRequest,
        onDismiss = appViewModel::dismissIncomingPaymentRequest,
        onDetails = onDetails,
    )
}

@Composable
internal fun PaymentRequestsSheetContent(
    modifier: Modifier = Modifier,
    requests: ImmutableList<PaykitPaymentRequest>,
    contacts: ImmutableList<PubkyProfile>,
    subscriptions: ImmutableList<PaykitSubscription>,
    onNotNow: () -> Unit,
    onSeeAll: () -> Unit,
    onPay: (PaykitPaymentRequestId) -> Unit,
    onDismiss: suspend (PaykitPaymentRequest) -> Result<Unit>,
    onDetails: (PaykitPaymentRequestId) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .sheetHeight()
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
        VerticalSpacer(24.dp)
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(requests.take(3), key = { it.lazyListKey }) { request ->
                PaymentRequestCard(
                    request = request,
                    contact = contacts.contactFor(request),
                    compactSubtitle = subscriptions.nameFor(request),
                    onClick = { onDetails(request.id) },
                    onPay = { onPay(request.id) },
                    onDismiss = { onDismiss(request) },
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
        VerticalSpacer(16.dp)
    }
}

@Composable
fun PaymentRequestsScreen(
    appViewModel: AppViewModel,
    onBack: () -> Unit,
    onRequestPayment: () -> Unit,
    onDetails: (PaykitPaymentRequestId) -> Unit,
    showsNavigationBar: Boolean = true,
) {
    val pending by appViewModel.pendingPaymentRequests.collectAsStateWithLifecycle()
    val history by appViewModel.paymentRequestHistory.collectAsStateWithLifecycle()
    val contacts by appViewModel.pubkyContacts.collectAsStateWithLifecycle()
    val targets by appViewModel.eligiblePaymentRequestTargets.collectAsStateWithLifecycle()
    val subscriptions by appViewModel.subscriptions.collectAsStateWithLifecycle()

    PaymentRequestsContent(
        requests = (pending + history).distinctBy { it.id }.toImmutableList(),
        pending = pending.toImmutableList(),
        contacts = contacts.toImmutableList(),
        subscriptions = subscriptions.toImmutableList(),
        canRequestPayment = targets.isNotEmpty(),
        onBack = onBack,
        onRequestPayment = onRequestPayment,
        onPay = appViewModel::openIncomingPaymentRequest,
        onDismiss = appViewModel::dismissIncomingPaymentRequest,
        onDetails = onDetails,
        showsNavigationBar = showsNavigationBar,
    )
}

@Composable
internal fun PaymentRequestsContent(
    modifier: Modifier = Modifier,
    requests: ImmutableList<PaykitPaymentRequest>,
    pending: ImmutableList<PaykitPaymentRequest>,
    contacts: ImmutableList<PubkyProfile>,
    subscriptions: ImmutableList<PaykitSubscription>,
    canRequestPayment: Boolean,
    onBack: () -> Unit,
    onRequestPayment: () -> Unit,
    onPay: (PaykitPaymentRequestId) -> Unit,
    onDismiss: suspend (PaykitPaymentRequest) -> Result<Unit>,
    onDetails: (PaykitPaymentRequestId) -> Unit,
    showsNavigationBar: Boolean = true,
) {
    val sections = paymentRequestSections(requests, pending, Clock.System.now())

    Column(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (showsNavigationBar) {
                    Modifier.gradientBackground().navigationBarsPadding()
                } else {
                    Modifier
                }
            )
            .testTag("PaymentRequestsScreen")
    ) {
        if (showsNavigationBar) {
            AppTopBar(
                titleText = stringResource(R.string.wallet__payment_requests),
                onBackClick = onBack,
                actions = { DrawerNavIcon() },
            )
        }
        if (requests.isEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            ) {
                FillHeight()
                Image(
                    painter = painterResource(R.drawable.restore),
                    contentDescription = null,
                    modifier = Modifier
                        .size(256.dp)
                        .align(Alignment.CenterHorizontally)
                        .testTag("PaymentRequestsEmptyIllustration"),
                )
                FillHeight()
                Display(
                    text = stringResource(R.string.wallet__payment_requests_empty_headline)
                        .withAccent(accentColor = Colors.Purple),
                )
                VerticalSpacer(12.dp)
                BodyM(
                    text = stringResource(R.string.wallet__payment_requests_empty_description),
                    color = Colors.White64,
                )
                VerticalSpacer(24.dp)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(top = 24.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            ) {
                if (sections.active.isNotEmpty()) {
                    item {
                        Caption13Up(
                            text = stringResource(R.string.wallet__payment_requests_section),
                            color = Colors.White64,
                        )
                    }
                    items(sections.active, key = { it.lazyListKey }) { request ->
                        ActivePaymentRequestCard(
                            request = request,
                            isIncoming = pending.any { it.id == request.id },
                            isRejecting = request.id in rejectingRequestIds,
                            contact = contacts.contactFor(request),
                            subscriptionNote = subscriptions.nameFor(request),
                            onPay = onPay,
                            onDismiss = onDismiss,
                            onDetails = onDetails,
                        )
                    }
                }
                sections.history.forEach { section ->
                    item(key = "history-${section.period.name}") {
                        Caption13Up(
                            text = paymentRequestHistorySectionTitle(section.period),
                            color = Colors.White64,
                        )
                    }
                    items(section.requests, key = { it.lazyListKey }) { request ->
                        PaymentRequestCard(
                            request = request,
                            contact = contacts.contactFor(request),
                            compactSubtitle = subscriptions.nameFor(request)
                                ?: request.note?.takeIf(String::isNotBlank)
                                ?: paymentRequestDate(request),
                            showSignedAmount = true,
                            onClick = { onDetails(request.id) },
                        )
                    }
                }
                item { VerticalSpacer(8.dp) }
            }
        }
        if (canRequestPayment) {
            PrimaryButton(
                text = stringResource(R.string.wallet__payment_request_request_payment),
                onClick = onRequestPayment,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .testTag("PaymentRequestCreate"),
            )
            VerticalSpacer(16.dp)
        }
    }
}

private data class PaymentRequestSections(
    val active: List<PaykitPaymentRequest>,
    val history: List<PaymentRequestHistorySection>,
)

private data class PaymentRequestHistorySection(
    val period: PaymentRequestHistoryPeriod,
    val requests: List<PaykitPaymentRequest>,
)

private enum class PaymentRequestHistoryPeriod {
    Today,
    ThisWeek,
    ThisMonth,
    ThisYear,
    Earlier,
}

private fun paymentRequestSections(
    requests: List<PaykitPaymentRequest>,
    pending: List<PaykitPaymentRequest>,
    now: Instant,
): PaymentRequestSections {
    val pendingIds = pending.mapTo(mutableSetOf()) { it.id }
    val active = requests.filter { request ->
        request.id in pendingIds ||
            request.direction == PaykitPaymentRequestDirection.Outgoing &&
            request.lifecycleState == PaymentRequestLifecycleState.PROPOSED &&
            !request.isExpired(now)
    }
    val activeIds = active.mapTo(mutableSetOf()) { it.id }
    val groupedHistory = requests
        .filterNot { it.id in activeIds }
        .sortedWith { first, second -> compareValues(second.createdAt, first.createdAt) }
        .groupBy { it.historyPeriod(now) }
    val history = PaymentRequestHistoryPeriod.entries.mapNotNull { period ->
        groupedHistory[period]?.let { PaymentRequestHistorySection(period, it) }
    }
    return PaymentRequestSections(active, history)
}

@Composable
private fun ActivePaymentRequestCard(
    request: PaykitPaymentRequest,
    isIncoming: Boolean,
    isRejecting: Boolean,
    contact: PubkyProfile?,
    subscriptionNote: String?,
    onPay: (PaykitPaymentRequestId) -> Unit,
    onDismiss: suspend (PaykitPaymentRequest) -> Result<Unit>,
    onDetails: (PaykitPaymentRequestId) -> Unit,
) {
    if (isIncoming) {
        PaymentRequestCard(
            request = request,
            contact = contact,
            compactSubtitle = subscriptionNote,
            onClick = { onDetails(request.id) },
            onPay = { onPay(request.id) },
            onDismiss = { onDismiss(request) },
        )
    } else {
        PaymentRequestCard(
            request = request,
            contact = contact,
            onClick = { onDetails(request.id) },
            compactSubtitle = stringResource(
                R.string.wallet__payment_request_waiting_for_recipient,
                contact?.name ?: PubkyProfile.placeholder(request.counterparty).name,
            ),
        )
    }
}

@Composable
private fun paymentRequestHistorySectionTitle(period: PaymentRequestHistoryPeriod): String = when (period) {
    PaymentRequestHistoryPeriod.Today -> stringResource(R.string.wallet__payment_requests_today)
    PaymentRequestHistoryPeriod.ThisWeek -> stringResource(R.string.wallet__payment_requests_this_week)
    PaymentRequestHistoryPeriod.ThisMonth -> stringResource(R.string.wallet__payment_requests_this_month)
    PaymentRequestHistoryPeriod.ThisYear -> stringResource(R.string.wallet__payment_requests_this_year)
    PaymentRequestHistoryPeriod.Earlier -> stringResource(R.string.wallet__payment_requests_earlier)
}

private fun PaykitPaymentRequest.historyPeriod(
    now: Instant,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): PaymentRequestHistoryPeriod {
    val date = createdAt?.let {
        JavaInstant.ofEpochMilli(it.toEpochMilliseconds()).atZone(zoneId).toLocalDate()
    } ?: return PaymentRequestHistoryPeriod.Earlier
    val today = JavaInstant.ofEpochMilli(now.toEpochMilliseconds()).atZone(zoneId).toLocalDate()
    val startOfWeek = today.with(TemporalAdjusters.previousOrSame(WeekFields.of(locale).firstDayOfWeek))

    return when {
        date == today -> PaymentRequestHistoryPeriod.Today
        !date.isBefore(startOfWeek) -> PaymentRequestHistoryPeriod.ThisWeek
        date.year == today.year && date.month == today.month -> PaymentRequestHistoryPeriod.ThisMonth
        date.year == today.year -> PaymentRequestHistoryPeriod.ThisYear
        else -> PaymentRequestHistoryPeriod.Earlier
    }
}

@Composable
private fun paymentRequestDate(request: PaykitPaymentRequest): String = request.createdAt?.let {
    uiDateText(it.epochSeconds.toULong(), UiDateStyle.DATE)
} ?: paymentRequestStatus(request)

@Composable
private fun paymentRequestStatus(request: PaykitPaymentRequest): String {
    if (request.lifecycleState == PaymentRequestLifecycleState.PROPOSED && request.isExpired(Clock.System.now())) {
        return stringResource(R.string.wallet__payment_request_status_expired)
    }

    return when (request.lifecycleState) {
        PaymentRequestLifecycleState.PROPOSED -> {
            if (request.direction == PaykitPaymentRequestDirection.Incoming) {
                stringResource(R.string.wallet__payment_request_status_unavailable)
            } else if (request.deliveryStatus == PaykitPaymentRequestDeliveryStatus.Sent) {
                stringResource(R.string.wallet__payment_request_waiting)
            } else {
                stringResource(R.string.wallet__payment_request_sending)
            }
        }
        PaymentRequestLifecycleState.PROPOSAL_EXPIRED ->
            stringResource(R.string.wallet__payment_request_status_expired)
        PaymentRequestLifecycleState.ACCEPTED ->
            stringResource(R.string.wallet__payment_request_status_accepted)
        PaymentRequestLifecycleState.REJECTED ->
            stringResource(R.string.wallet__payment_request_status_rejected)
        PaymentRequestLifecycleState.CANCELED ->
            stringResource(R.string.wallet__payment_request_status_canceled)
        PaymentRequestLifecycleState.PROOF_SUBMITTED ->
            stringResource(R.string.wallet__payment_request_status_proof_submitted)
        PaymentRequestLifecycleState.RECOVERY_REQUIRED ->
            stringResource(R.string.wallet__payment_request_status_action_required)
        PaymentRequestLifecycleState.INVALID_CONFLICT,
        PaymentRequestLifecycleState.ACTIVE_RECURRING,
        PaymentRequestLifecycleState.UNKNOWN,
        -> stringResource(R.string.wallet__payment_request_status_unavailable)
    }
}

@Composable
internal fun PaymentRequestCard(
    request: PaykitPaymentRequest,
    contact: PubkyProfile?,
    compactSubtitle: String? = null,
    isOutgoingPayment: Boolean = false,
    showSignedAmount: Boolean = false,
    onClick: (() -> Unit)? = null,
    onPay: (() -> Unit)? = null,
    onDismiss: (suspend () -> Result<Unit>)? = null,
) {
    val scope = rememberCoroutineScope()
    var isDismissing by remember(request.id) { mutableStateOf(false) }
    val displayContact = contact ?: PubkyProfile.placeholder(request.counterparty)
    val subtitle = compactSubtitle ?: request.note?.takeIf(String::isNotBlank) ?: paymentRequestDate(request)
    val amountPrefix = request.amountPrefix(isOutgoingPayment, showSignedAmount)

    Card(
        colors = CardDefaults.cardColors(containerColor = Colors.Gray6),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onPay != null || onDismiss != null) {
                    Modifier
                        .outerGlow(
                            glowColor = Colors.Brand,
                            glowOpacity = 0.16f,
                            glowRadius = 64.dp,
                            cornerRadius = 16.dp,
                        )
                        .border(1.dp, Colors.Brand.copy(alpha = 0.5f), MaterialTheme.shapes.medium)
                } else {
                    Modifier
                }
            )
            .clickableAlpha(enabled = onClick != null) { onClick?.invoke() }
            .testTag("PaymentRequestRow${request.paymentRequestId}"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            if (isOutgoingPayment) {
                CircularIcon(
                    icon = painterResource(R.drawable.ic_sent),
                    iconColor = Colors.Brand,
                    backgroundColor = Colors.Brand16,
                    size = 40.dp,
                )
            } else {
                PubkyContactAvatar(profile = displayContact, size = 40.dp)
            }
            Column(modifier = Modifier.weight(1f)) {
                BodyMSB(
                    text = displayContact.name,
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
            MoneyCell(
                sats = request.amountSats.coerceAtMost(Long.MAX_VALUE.toULong()).toLong(),
                prefix = amountPrefix,
            )
        }
        if (onPay != null || onDismiss != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Colors.Gray5)
                    .padding(16.dp),
            ) {
                SecondaryButton(
                    text = stringResource(R.string.wallet__payment_request_dismiss),
                    onClick = {
                        if (isDismissing || onDismiss == null) return@SecondaryButton
                        isDismissing = true
                        scope.launch {
                            onDismiss()
                            isDismissing = false
                        }
                    },
                    isLoading = isDismissing,
                    enabled = !isDismissing,
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
                    enabled = !isDismissing,
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

private fun PaykitPaymentRequest.amountPrefix(isOutgoingPayment: Boolean, showSignedAmount: Boolean): String = when {
    isOutgoingPayment -> "-"
    showSignedAmount && direction == PaykitPaymentRequestDirection.Incoming -> "-"
    showSignedAmount -> "+"
    else -> ""
}

private fun List<PubkyProfile>.contactFor(request: PaykitPaymentRequest): PubkyProfile? =
    firstOrNull { PubkyPublicKeyFormat.matches(it.publicKey, request.counterparty) }

@Composable
private fun List<PaykitSubscription>.nameFor(request: PaykitPaymentRequest): String? {
    val subscription = firstOrNull(request::belongsTo) ?: return null
    return subscription.note?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.subscriptions__subscription)
}

private val PaykitPaymentRequest.lazyListKey: String
    get() = "$paymentRequestId|$counterparty|$counterpartyReceiverPath|${billingPeriod?.startsAt ?: ""}"

private val previewRequest = PaykitPaymentRequest(
    paymentRequestId = "payment-request",
    counterparty = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg",
    counterpartyReceiverPath = "bitkit/wallet",
    amountValue = "0.00025",
    amountSats = 25_000uL,
    note = "Dinner",
    createdAt = Instant.parse("2027-01-15T08:00:00Z"),
    expiresAt = Instant.parse("2027-01-15T09:00:00Z"),
    acceptedPaymentEndpointIdentifiers = listOf("btc-lightning-bolt11"),
)

@Preview(showSystemUi = true)
@Composable
private fun PaymentRequestsSheetPreview() {
    AppThemeSurface {
        BottomSheetPreview {
            PaymentRequestsSheetContent(
                requests = persistentListOf(previewRequest),
                contacts = persistentListOf(),
                subscriptions = persistentListOf(),
                onNotNow = {},
                onSeeAll = {},
                onPay = {},
                onDismiss = { Result.success(Unit) },
                onDetails = {},
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PaymentRequestsPreview() {
    AppThemeSurface {
        PaymentRequestsContent(
            requests = persistentListOf(
                previewRequest,
                previewRequest.copy(
                    paymentRequestId = "sent-payment-request",
                    direction = PaykitPaymentRequestDirection.Outgoing,
                )
            ),
            pending = persistentListOf(previewRequest),
            contacts = persistentListOf(),
            subscriptions = persistentListOf(),
            canRequestPayment = true,
            onBack = {},
            onRequestPayment = {},
            onPay = {},
            onDismiss = { Result.success(Unit) },
            onDetails = {},
        )
    }
}

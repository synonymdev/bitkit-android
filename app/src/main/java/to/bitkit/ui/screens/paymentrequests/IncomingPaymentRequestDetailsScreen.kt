@file:OptIn(ExperimentalTime::class)

package to.bitkit.ui.screens.paymentrequests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
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
import to.bitkit.ext.UiDateStyle
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.repositories.PaykitPaymentRequest
import to.bitkit.repositories.PaykitPaymentRequestDirection
import to.bitkit.repositories.PaykitPaymentRequestId
import to.bitkit.ui.components.AddTagButton
import to.bitkit.ui.components.AddTagSheet
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.FillWidth
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.PubkyContactAvatar
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.rememberMoneyText
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.screens.wallets.activity.components.CircularIcon
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.removeAccentTags
import to.bitkit.ui.utils.uiDateText
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.AppViewModel
import kotlin.time.ExperimentalTime

@Composable
fun IncomingPaymentRequestDetailsScreen(
    appViewModel: AppViewModel,
    id: PaykitPaymentRequestId,
    onBack: () -> Unit,
) {
    val pending by appViewModel.pendingPaymentRequests.collectAsStateWithLifecycle()
    val history by appViewModel.paymentRequestHistory.collectAsStateWithLifecycle()
    val contacts by appViewModel.pubkyContacts.collectAsStateWithLifecycle()
    val request = pending.firstOrNull { it.id == id } ?: history.firstOrNull { it.id == id }
    val contact = request?.let { paymentRequest ->
        contacts.firstOrNull { PubkyPublicKeyFormat.matches(it.publicKey, paymentRequest.counterparty) }
            ?: PubkyProfile.placeholder(paymentRequest.counterparty)
    }
    val isPending = pending.any { it.id == id }

    IncomingPaymentRequestDetailsContent(
        request = request,
        contact = contact,
        isPending = isPending,
        onBack = onBack,
        onPay = { appViewModel.openIncomingPaymentRequestWithTags(id, it) },
        onDismiss = request?.let { { appViewModel.dismissIncomingPaymentRequest(it) } },
    )
}

@Composable
private fun IncomingPaymentRequestDetailsContent(
    request: PaykitPaymentRequest?,
    contact: PubkyProfile?,
    isPending: Boolean,
    onBack: () -> Unit,
    onPay: (List<String>) -> Unit,
    onDismiss: (suspend () -> Result<Unit>)?,
) {
    val scope = rememberCoroutineScope()
    var isDismissing by remember(request?.id) { mutableStateOf(false) }
    var selectedTags by remember(request?.id) { mutableStateOf(emptyList<String>()) }
    var isAddingTag by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("PaymentRequestDetailsScreen")
    ) {
        AppTopBar(
            titleText = stringResource(R.string.wallet__payment_request),
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )
        if (request == null || contact == null) {
            FillHeight()
            BodyM(
                text = stringResource(R.string.wallet__payment_request_status_unavailable),
                color = Colors.White64,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            FillHeight()
            return@Column
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
        ) {
            VerticalSpacer(16.dp)
            rememberMoneyText(
                sats = request.amountSats.coerceAtMost(Long.MAX_VALUE.toULong()).toLong(),
                reversed = true,
                showSymbol = true,
            )?.let {
                Caption13Up(text = it.removeAccentTags(), color = Colors.White64)
            }
            rememberMoneyText(
                sats = request.amountSats.coerceAtMost(Long.MAX_VALUE.toULong()).toLong(),
                showSymbol = true,
            )?.let {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Display(
                        text = "${request.detailsAmountPrefix()}$it".withAccent(accentColor = Colors.White64),
                    )
                    FillWidth()
                    CircularIcon(
                        icon = painterResource(
                            if (request.direction == PaykitPaymentRequestDirection.Incoming) {
                                R.drawable.ic_received
                            } else {
                                R.drawable.ic_sent
                            }
                        ),
                        iconColor = Colors.Purple,
                        backgroundColor = Colors.Purple16,
                        size = 48.dp,
                    )
                }
            }
            VerticalSpacer(24.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RequestDetailCell(
                    title = stringResource(R.string.wallet__payment_request_date),
                    value = request.createdAt?.let { uiDateText(it.epochSeconds.toULong(), UiDateStyle.DATE) }
                        ?: stringResource(R.string.wallet__payment_request_status_unavailable),
                    iconRes = R.drawable.ic_calendar,
                    modifier = Modifier.weight(1f),
                )
                RequestDetailCell(
                    title = stringResource(R.string.wallet__payment_request_time),
                    value = request.createdAt?.let { uiDateText(it.epochSeconds.toULong(), UiDateStyle.TIME) }
                        ?: stringResource(R.string.wallet__payment_request_status_unavailable),
                    iconRes = R.drawable.ic_clock,
                    modifier = Modifier.weight(1f),
                )
            }
            VerticalSpacer(20.dp)
            Caption13Up(text = stringResource(R.string.wallet__payment_request_contact), color = Colors.White64)
            VerticalSpacer(8.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Colors.Gray6, RoundedCornerShape(16.dp))
                    .padding(16.dp),
            ) {
                PubkyContactAvatar(profile = contact, size = 40.dp)
                BodyMSB(text = contact.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            VerticalSpacer(20.dp)
            PaymentRequestTags(
                tags = selectedTags,
                onRemove = { selectedTags -= it },
                onAdd = { isAddingTag = true },
            )
            VerticalSpacer(20.dp)
            Caption13Up(text = stringResource(R.string.wallet__payment_request_note), color = Colors.White64)
            VerticalSpacer(8.dp)
            BodyMSB(
                text = request.note ?: stringResource(R.string.wallet__payment_request),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Colors.Gray6, RoundedCornerShape(16.dp))
                    .padding(16.dp),
            )
        }

        if (isPending) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                SecondaryButton(
                    text = stringResource(R.string.wallet__payment_request_dismiss),
                    enabled = !isDismissing,
                    isLoading = isDismissing,
                    onClick = {
                        val dismiss = onDismiss ?: return@SecondaryButton
                        isDismissing = true
                        scope.launch {
                            dismiss().onSuccess { onBack() }
                            isDismissing = false
                        }
                    },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_x),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    text = stringResource(R.string.wallet__payment_request_pay),
                    enabled = !isDismissing,
                    onClick = { onPay(selectedTags) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_coins),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (isAddingTag) {
        AddTagSheet(
            onDismiss = { isAddingTag = false },
            onSave = { tag ->
                selectedTags = (selectedTags + tag.trim()).filter(String::isNotBlank).distinct()
                isAddingTag = false
            },
        )
    }
}

@Composable
private fun PaymentRequestTags(
    tags: List<String>,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Caption13Up(text = stringResource(R.string.wallet__tags), color = Colors.White64)
    VerticalSpacer(8.dp)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tags.forEach { tag ->
            TagButton(
                text = tag,
                displayIconClose = true,
                onClick = { onRemove(tag) },
            )
        }
        AddTagButton(
            onClick = onAdd,
            modifier = Modifier.testTag("PaymentRequestAddTag"),
        )
    }
}

private fun PaykitPaymentRequest.detailsAmountPrefix(): String =
    if (direction == PaykitPaymentRequestDirection.Incoming) "-" else "+"

@Composable
private fun RequestDetailCell(
    title: String,
    value: String,
    @androidx.annotation.DrawableRes iconRes: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Caption13Up(text = title, color = Colors.White64)
        VerticalSpacer(8.dp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = Colors.Purple,
                modifier = Modifier.size(16.dp),
            )
            BodySSB(text = value, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        VerticalSpacer(12.dp)
        HorizontalDivider(color = Colors.White10)
    }
}

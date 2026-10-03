package to.bitkit.ui.screens.subscriptions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.PaykitAllowance
import to.bitkit.repositories.PaykitAllowanceLimits
import to.bitkit.ui.components.AllowanceRoute
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.MoneyCaptionB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.PubkyContactAvatar
import to.bitkit.ui.components.PubkyContactRow
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.Slider
import to.bitkit.ui.components.SwipeToConfirm
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.AppViewModel
import kotlin.time.Duration.Companion.seconds

/** Delay before a shown proposal counts as seen: another sheet's dismissal can close this one right after it opens. */
private val PROPOSAL_SEEN_DELAY = 1.seconds

private const val DEFAULT_PER_PAYMENT_INDEX = 1
private const val DEFAULT_MONTHLY_INDEX = 2

@Composable
fun AllowanceSheet(
    appViewModel: AppViewModel,
    route: AllowanceRoute,
    viewModel: AllowancesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    if (!uiState.isLoaded) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .sheetHeight()
                .gradientBackground(startColor = Colors.Gray6, endColor = Colors.Black)
        )
        return
    }

    when (route) {
        AllowanceRoute.Set -> AllowanceSetFlow(
            uiState = uiState,
            onSave = { contact, perPaymentUsd, monthlyUsd ->
                viewModel.propose(contact, perPaymentUsd, monthlyUsd, onSuccess = appViewModel::hideSheet)
            },
        )

        is AllowanceRoute.Review -> {
            val allowance = uiState.allowances.firstOrNull { it.id == route.entryId }
            if (allowance == null) {
                AllowanceUnavailableContent(onClose = appViewModel::hideSheet, modifier = Modifier.sheetHeight())
            } else {
                LaunchedEffect(route.entryId) {
                    delay(PROPOSAL_SEEN_DELAY)
                    viewModel.markProposalPresented(route.entryId)
                }
                AllowanceReviewContent(
                    allowance = allowance,
                    isWorking = uiState.isWorking,
                    onClickDecline = { viewModel.decline(allowance.id, onSuccess = appViewModel::hideSheet) },
                    onClickAccept = { viewModel.accept(allowance.id, onSuccess = appViewModel::hideSheet) },
                    modifier = Modifier.sheetHeight()
                )
            }
        }

        is AllowanceRoute.Details -> {
            val allowance = uiState.allowances.firstOrNull { it.id == route.entryId }
            if (allowance == null) {
                AllowanceUnavailableContent(onClose = appViewModel::hideSheet, modifier = Modifier.sheetHeight())
            } else {
                AllowanceDetailContent(
                    allowance = allowance,
                    isWorking = uiState.isWorking,
                    onEnd = { viewModel.end(allowance.id, onSuccess = appViewModel::hideSheet) },
                    modifier = Modifier.sheetHeight()
                )
            }
        }
    }
}

@Composable
private fun AllowanceSetFlow(
    uiState: AllowancesUiState,
    onSave: (contact: PubkyProfile, perPaymentUsd: Int, monthlyUsd: Int) -> Unit,
) {
    var contactKey by rememberSaveable { mutableStateOf<String?>(null) }
    val contact = uiState.contacts.firstOrNull { it.publicKey == contactKey }

    if (contact == null) {
        AllowanceContactContent(
            contacts = uiState.contacts,
            onClickContact = { contactKey = it.publicKey },
            modifier = Modifier.sheetHeight()
        )
    } else {
        SetAllowanceContent(
            contact = contact,
            isWorking = uiState.isWorking,
            onBack = { contactKey = null },
            onClickSave = { perPaymentUsd, monthlyUsd -> onSave(contact, perPaymentUsd, monthlyUsd) },
            modifier = Modifier.sheetHeight()
        )
    }
}

@Composable
private fun AllowanceContactContent(
    contacts: ImmutableList<PubkyProfile>,
    onClickContact: (PubkyProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    AllowanceSheetColumn(modifier = modifier) {
        SheetTopBar(titleText = stringResource(R.string.subscriptions__allowance_choose_contact))
        if (contacts.isEmpty()) {
            VerticalSpacer(16.dp)
            BodyM(
                text = stringResource(R.string.subscriptions__allowance_no_contacts),
                color = Colors.White64,
            )
            FillHeight()
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(contacts, key = { it.publicKey }) { contact ->
                    PubkyContactRow(
                        profile = contact,
                        onClick = { onClickContact(contact) },
                        modifier = Modifier.testTag("AllowanceContact-${contact.name}")
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SetAllowanceContent(
    contact: PubkyProfile,
    isWorking: Boolean,
    onBack: () -> Unit,
    onClickSave: (perPaymentUsd: Int, monthlyUsd: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val perPaymentStops = remember { PaykitAllowanceLimits.PER_PAYMENT_STOPS_USD.toImmutableList() }
    val monthlyStops = remember { PaykitAllowanceLimits.MONTHLY_STOPS_USD.toImmutableList() }
    var perPaymentUsd by rememberSaveable { mutableIntStateOf(perPaymentStops[DEFAULT_PER_PAYMENT_INDEX]) }
    var monthlyUsd by rememberSaveable { mutableIntStateOf(monthlyStops[DEFAULT_MONTHLY_INDEX]) }

    AllowanceSheetColumn(modifier = modifier.testTag("SetAllowance")) {
        SheetTopBar(titleText = stringResource(R.string.subscriptions__allowance_set_title), onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            AllowanceCounterpartyCard(counterparty = contact)
            VerticalSpacer(16.dp)
            BodyM(
                text = stringResource(R.string.subscriptions__allowance_set_explanation)
                    .replace("{name}", contact.name),
                color = Colors.White64,
            )
            VerticalSpacer(16.dp)
            AllowanceLimitSlider(
                title = stringResource(R.string.subscriptions__allowance_payment_limit),
                value = perPaymentUsd,
                stops = perPaymentStops,
                onValueChange = { perPaymentUsd = it },
                testTag = "AllowancePerPayment",
            )
            VerticalSpacer(16.dp)
            HorizontalDivider()
            AllowanceLimitSlider(
                title = stringResource(R.string.subscriptions__allowance_monthly_allowance),
                value = monthlyUsd,
                stops = monthlyStops,
                onValueChange = { monthlyUsd = it },
                testTag = "AllowanceMonthly",
            )
            VerticalSpacer(16.dp)
            HorizontalDivider()
            VerticalSpacer(16.dp)
            BodyS(
                text = stringResource(R.string.subscriptions__allowance_set_summary)
                    .replace("{perPayment}", AllowanceAmountText.short(perPaymentUsd))
                    .replace("{monthly}", AllowanceAmountText.short(monthlyUsd)),
                color = Colors.White64,
                modifier = Modifier.testTag("AllowanceSummary")
            )
            VerticalSpacer(16.dp)
        }
        PrimaryButton(
            text = stringResource(R.string.subscriptions__allowance_save),
            onClick = { onClickSave(perPaymentUsd, monthlyUsd) },
            isLoading = isWorking,
            modifier = Modifier.testTag("AllowanceSave")
        )
        VerticalSpacer(16.dp)
    }
}

@Composable
private fun AllowanceLimitSlider(
    title: String,
    value: Int,
    stops: ImmutableList<Int>,
    onValueChange: (Int) -> Unit,
    testTag: String,
) {
    Caption13Up(
        text = title,
        color = Colors.White64,
        modifier = Modifier.padding(vertical = 16.dp)
    )
    Slider(
        value = value,
        steps = stops,
        onValueChange = onValueChange,
        formatLabel = AllowanceAmountText::short,
        activeColor = Colors.Purple,
        trackColor = Colors.Purple32,
        stopTestTag = { "${testTag}Stop-$it" },
        modifier = Modifier.testTag(testTag)
    )
}

@Composable
private fun AllowanceReviewContent(
    allowance: AllowanceUi,
    isWorking: Boolean,
    onClickDecline: () -> Unit,
    onClickAccept: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AllowanceSheetColumn(modifier = modifier.testTag("AllowanceReview")) {
        SheetTopBar(titleText = stringResource(R.string.subscriptions__allowance_title))
        VerticalSpacer(16.dp)
        Display(text = allowance.reviewHeadline.withAccent(accentColor = Colors.Purple))
        VerticalSpacer(16.dp)
        AllowanceCounterpartyCard(counterparty = allowance.counterparty, isCard = true)
        VerticalSpacer(24.dp)
        AllowanceLimitsGrid(allowance = allowance)
        VerticalSpacer(24.dp)
        BodyM(text = allowance.reviewExplanation, color = Colors.White64)
        FillHeight()
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SecondaryButton(
                text = stringResource(R.string.subscriptions__allowance_decline),
                onClick = onClickDecline,
                enabled = !isWorking,
                modifier = Modifier
                    .weight(1f)
                    .testTag("AllowanceDecline")
            )
            PrimaryButton(
                text = stringResource(R.string.subscriptions__allowance_accept),
                onClick = onClickAccept,
                isLoading = isWorking,
                modifier = Modifier
                    .weight(1f)
                    .testTag("AllowanceAccept")
            )
        }
        VerticalSpacer(16.dp)
    }
}

@Composable
private fun AllowanceDetailContent(
    allowance: AllowanceUi,
    isWorking: Boolean,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AllowanceSheetColumn(modifier = modifier.testTag("AllowanceDetail")) {
        SheetTopBar(titleText = stringResource(R.string.subscriptions__allowance_title))
        AllowanceCounterpartyCard(counterparty = allowance.counterparty, isCard = true)
        VerticalSpacer(24.dp)
        AllowanceLimitsGrid(allowance = allowance)
        VerticalSpacer(24.dp)
        Caption13Up(text = stringResource(R.string.subscriptions__allowance_paid_so_far), color = Colors.White64)
        VerticalSpacer(8.dp)
        BodySSB(text = allowance.paidSoFar, modifier = Modifier.testTag("AllowancePaidSoFar"))
        VerticalSpacer(24.dp)
        BodyM(
            text = allowance.explanation,
            color = Colors.White64,
            modifier = Modifier.testTag("AllowanceDetailStatus")
        )
        FillHeight()
        if (allowance.canEnd) {
            SwipeToConfirm(
                text = stringResource(
                    if (allowance.isProposal) {
                        R.string.subscriptions__allowance_swipe_withdraw
                    } else {
                        R.string.subscriptions__allowance_swipe_end
                    }
                ),
                color = Colors.Purple,
                loading = isWorking,
                onConfirm = onEnd,
            )
            VerticalSpacer(16.dp)
        }
    }
}

@Composable
private fun AllowanceUnavailableContent(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AllowanceSheetColumn(modifier = modifier) {
        SheetTopBar(titleText = stringResource(R.string.subscriptions__allowance_title))
        FillHeight()
        BodyM(text = stringResource(R.string.subscriptions__allowance_error_unavailable), color = Colors.White64)
        FillHeight()
        PrimaryButton(text = stringResource(R.string.common__close), onClick = onClose)
        VerticalSpacer(16.dp)
    }
}

@Composable
private fun AllowanceSheetColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground(startColor = Colors.Gray6, endColor = Colors.Black)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
        content = content,
    )
}

@Composable
private fun AllowanceCounterpartyCard(
    counterparty: PubkyProfile,
    isCard: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isCard) {
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Colors.Gray6)
                        .padding(16.dp)
                } else {
                    Modifier
                }
            )
            .testTag("AllowanceCounterparty")
    ) {
        PubkyContactAvatar(profile = counterparty, size = 48.dp)
        Column(
            modifier = Modifier
                .padding(start = 16.dp)
                .weight(1f)
        ) {
            Caption13Up(
                text = counterparty.truncatedPublicKey,
                color = Colors.White64,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BodyMSB(
                text = counterparty.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AllowanceLimitsGrid(allowance: AllowanceUi) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        AllowanceLimitCell(
            title = stringResource(R.string.subscriptions__allowance_per_payment),
            upTo = allowance.perPaymentUpTo,
            sats = allowance.perPaymentSats,
            testTag = "AllowancePerPaymentValue",
            modifier = Modifier.weight(1f)
        )
        AllowanceLimitCell(
            title = stringResource(R.string.subscriptions__allowance_each_month),
            upTo = allowance.monthlyUpTo,
            sats = allowance.monthlySats,
            testTag = "AllowanceMonthlyValue",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun AllowanceLimitCell(
    title: String,
    upTo: String,
    sats: Long?,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        Caption13Up(text = title, color = Colors.White64)
        BodySSB(text = upTo, modifier = Modifier.testTag(testTag))
        sats?.let { MoneyCaptionB(sats = it, color = Colors.White64, symbol = true) }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            AllowanceContactContent(
                contacts = persistentListOf(
                    previewAllowance(name = "Leo").counterparty,
                    PubkyProfile.forDisplay(
                        publicKey = "pubkyqzx4bxnsmp6n1u3y3uyt6hbjmaqwzs5tbaxkh7wwcbzjb8sccq3o",
                        name = "Mia",
                        imageUrl = null,
                    ),
                ),
                onClickContact = {},
                modifier = Modifier.sheetHeight()
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview2() {
    AppThemeSurface {
        BottomSheetPreview {
            AllowanceContactContent(
                contacts = persistentListOf(),
                onClickContact = {},
                modifier = Modifier.sheetHeight()
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview3() {
    AppThemeSurface {
        BottomSheetPreview {
            SetAllowanceContent(
                contact = previewAllowance(name = "Leo").counterparty,
                isWorking = false,
                onBack = {},
                onClickSave = { _, _ -> },
                modifier = Modifier.sheetHeight()
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview4() {
    AppThemeSurface {
        BottomSheetPreview {
            AllowanceReviewContent(
                allowance = previewAllowance(name = "Ana").copy(
                    status = PaykitAllowance.Status.AWAITING_MY_ANSWER,
                    subtitle = "Waiting for your answer",
                    isAnswerable = true,
                    isProposal = true,
                ),
                isWorking = false,
                onClickDecline = {},
                onClickAccept = {},
                modifier = Modifier.sheetHeight()
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview5() {
    AppThemeSurface {
        BottomSheetPreview {
            AllowanceDetailContent(
                allowance = previewAllowance(),
                isWorking = false,
                onEnd = {},
                modifier = Modifier.sheetHeight()
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview6() {
    AppThemeSurface {
        BottomSheetPreview {
            AllowanceDetailContent(
                allowance = previewAllowance().copy(
                    status = PaykitAllowance.Status.ENDED,
                    subtitle = "Ended · $2.00 paid automatically",
                    explanation = "This allowance has ended. Every request asks again.",
                    canEnd = false,
                ),
                isWorking = false,
                onEnd = {},
                modifier = Modifier.sheetHeight()
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview7() {
    AppThemeSurface {
        BottomSheetPreview {
            AllowanceUnavailableContent(onClose = {}, modifier = Modifier.sheetHeight())
        }
    }
}

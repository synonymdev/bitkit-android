package to.bitkit.ui.screens.subscriptions

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.models.USD_SYMBOL
import to.bitkit.repositories.PaykitAllowance
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.PubkyContactAvatar
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

/** Row opacity for an allowance that no longer pays: ended, declined, expired or in conflict. */
private const val INACTIVE_ROW_ALPHA = 0.64f

/** The Allowances tab on the Subscriptions screen: the empty state or the allowance list, plus Add Allowance. */
@Composable
fun AllowancesScreen(
    topPadding: Dp,
    onClickAdd: () -> Unit,
    onClickAllowance: (AllowanceUi) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AllowancesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refresh() }

    AllowancesContent(
        uiState = uiState,
        topPadding = topPadding,
        onClickAdd = onClickAdd,
        onClickAllowance = onClickAllowance,
        modifier = modifier
    )
}

@Composable
private fun AllowancesContent(
    uiState: AllowancesUiState,
    topPadding: Dp,
    onClickAdd: () -> Unit,
    onClickAllowance: (AllowanceUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var footerHeight by remember { mutableStateOf(0.dp) }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            !uiState.isLoaded -> Unit
            uiState.allowances.isEmpty() -> AllowancesEmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topPadding, bottom = footerHeight)
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = topPadding + 32.dp,
                    bottom = footerHeight + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(uiState.allowances, key = { it.id }) { allowance ->
                    AllowanceRow(
                        allowance = allowance,
                        onClick = { onClickAllowance(allowance) },
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { footerHeight = with(density) { it.height.toDp() } }
                .navigationBarsPadding()
        ) {
            VerticalSpacer(16.dp)
            PrimaryButton(
                text = stringResource(R.string.subscriptions__allowance_add),
                onClick = onClickAdd,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .testTag("AllowanceAdd")
            )
            VerticalSpacer(16.dp)
        }
    }
}

@Composable
private fun AllowancesEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 32.dp)
            .testTag("AllowancesEmpty")
    ) {
        FillHeight()
        Image(
            painter = painterResource(R.drawable.group),
            contentDescription = null,
            modifier = Modifier
                .size(256.dp)
                .align(Alignment.CenterHorizontally)
        )
        VerticalSpacer(32.dp)
        Display(
            text = stringResource(R.string.subscriptions__allowances_empty_headline)
                .withAccent(accentColor = Colors.Purple),
        )
        VerticalSpacer(8.dp)
        BodyM(text = stringResource(R.string.subscriptions__allowances_empty_description), color = Colors.White64)
    }
}

@Composable
private fun AllowanceRow(
    allowance: AllowanceUi,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (allowance.isInactive) INACTIVE_ROW_ALPHA else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Colors.Gray6)
            .clickableAlpha(onClick = onClick)
            .padding(16.dp)
            .testTag("AllowanceRow-${allowance.id}")
    ) {
        PubkyContactAvatar(profile = allowance.counterparty, size = 40.dp)
        Column(
            modifier = Modifier
                .padding(start = 16.dp)
                .weight(1f)
        ) {
            BodyMSB(
                text = allowance.counterparty.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            CaptionB(
                text = allowance.subtitle,
                color = Colors.White64,
                maxLines = 1,
                modifier = Modifier.testTag("AllowanceRowStatus")
            )
        }
        HorizontalSpacer(8.dp)
        Column(horizontalAlignment = Alignment.End) {
            AllowanceUsd(amount = allowance.monthlyAmount)
            CaptionB(
                text = stringResource(R.string.subscriptions__allowance_monthly_limit),
                color = Colors.White64,
                maxLines = 1,
            )
        }
    }
}

/** A dollar amount with a dimmed currency symbol, as the allowance rows show limits. */
@Composable
internal fun AllowanceUsd(amount: String, modifier: Modifier = Modifier) {
    BodyMSB(
        text = "<accent>$USD_SYMBOL</accent> $amount".withAccent(accentColor = Colors.White64),
        modifier = modifier
    )
}

internal fun previewAllowance(id: String = "allowance-1", name: String = "Leo") = AllowanceUi(
    id = id,
    counterparty = PubkyProfile.forDisplay(
        publicKey = "pubky8pinxcsrt5ufsyu3n1pzkq5e3bdi7gbq67pcu7tsnjj65ntx1xy",
        name = name,
        imageUrl = null,
    ),
    status = PaykitAllowance.Status.ACTIVE,
    subtitle = "Active · $5 a payment",
    explanation = "Requests within these limits are paid automatically. Anything above them asks you first.",
    reviewHeadline = "Ana offers\n<accent>an allowance</accent>",
    reviewExplanation = "Ana's wallet will pay your requests within these limits without asking each time. " +
        "Either of you can end it.",
    monthlyAmount = "50.00",
    perPaymentUpTo = "Up to $5.00",
    monthlyUpTo = "Up to $50.00",
    perPaymentSats = 4_512L,
    monthlySats = 45_120L,
    paidSoFar = "$2.00",
    isAnswerable = false,
    canEnd = true,
    isProposal = false,
)

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        AllowancesContent(
            uiState = AllowancesUiState(isLoaded = true),
            topPadding = 0.dp,
            onClickAdd = {},
            onClickAllowance = {},
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview2() {
    AppThemeSurface {
        AllowancesContent(
            uiState = AllowancesUiState(
                isLoaded = true,
                allowances = persistentListOf(
                    previewAllowance(),
                    previewAllowance(id = "allowance-2", name = "Mia").copy(
                        status = PaykitAllowance.Status.AWAITING_ANSWER,
                        subtitle = "Waiting for an answer",
                        isProposal = true,
                    ),
                    previewAllowance(id = "allowance-3", name = "John Carvalho").copy(
                        status = PaykitAllowance.Status.ENDED,
                        subtitle = "Ended · $2.00 paid automatically",
                        canEnd = false,
                    ),
                ),
            ),
            topPadding = 0.dp,
            onClickAdd = {},
            onClickAllowance = {},
        )
    }
}

package to.bitkit.ui.screens.transfer.spark

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.repositories.SparkClaimOffer
import to.bitkit.repositories.SparkEarlyClaim
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FeeInfo
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.transfer.spark.SparkFundingContract.Deposit
import to.bitkit.ui.screens.transfer.spark.SparkFundingContract.UiState
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

/** Roughly how long a block takes, to turn confirmations into minutes. */
private const val MINUTES_PER_CONFIRMATION = 10u

@Composable
fun SparkDepositScreen(
    viewModel: SparkFundingViewModel,
    onContinueClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Content(
        uiState = uiState,
        onClaimEarlyClick = viewModel::onClaimEarly,
        onContinueClick = onContinueClick,
    )
}

@Composable
private fun Content(
    uiState: UiState,
    onClaimEarlyClick: () -> Unit = {},
    onContinueClick: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(titleText = stringResource(R.string.lightning__transfer__nav_title), onBackClick = null)
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .testTag("SparkDeposit")
        ) {
            VerticalSpacer(32.dp)
            Display(stringResource(R.string.lightning__savings_progress__title).withAccent(accentColor = Colors.Purple))
            VerticalSpacer(8.dp)

            when (val deposit = uiState.deposit) {
                Deposit.Looking -> Text(stringResource(R.string.lightning__spark_deposit__looking))
                is Deposit.Offered -> Offered(deposit.offer, uiState.isLoading, onClaimEarlyClick)
                is Deposit.Claimed -> Claimed(deposit)
            }

            FillHeight()

            Image(
                painter = painterResource(id = R.drawable.hourglass),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(256.dp)
                    .align(alignment = CenterHorizontally)
            )

            val isChoice = (uiState.deposit as? Deposit.Offered)?.offer is SparkClaimOffer.Choice
            SecondaryButton(
                text = stringResource(
                    if (isChoice) R.string.lightning__spark_deposit__wait_button else R.string.common__continue
                ),
                onClick = onContinueClick,
                enabled = !uiState.isLoading,
                modifier = Modifier.testTag("SparkDepositContinue")
            )
            VerticalSpacer(16.dp)
        }
    }
}

@Composable
private fun Offered(offer: SparkClaimOffer, isLoading: Boolean, onClaimEarlyClick: () -> Unit) {
    when (offer) {
        is SparkClaimOffer.Standard -> {
            Text(standardText(offer.confirmations))
            Fees(stringResource(R.string.lightning__spark_claim_fee) to offer.feeSats)
        }

        is SparkClaimOffer.Automatic -> {
            Text(stringResource(R.string.lightning__spark_deposit__waiting).replace("{when}", whenText(offer.confirmations)))
            Fees(stringResource(R.string.lightning__spark_claim_fee) to offer.feeSats)
        }

        is SparkClaimOffer.Choice -> {
            val early = whenText(offer.earlyConfirmations)
            Text(
                stringResource(R.string.lightning__spark_deposit__choice)
                    .replace("{when}", early)
                    .replace("{standard}", offer.standardConfirmations.toString())
                    .replace("{minutes}", (offer.standardConfirmations * MINUTES_PER_CONFIRMATION).toString())
            )
            Fees(
                stringResource(R.string.lightning__spark_deposit__early_fee).replace("{when}", early) to
                    offer.earlyFeeSats,
                stringResource(R.string.lightning__spark_deposit__standard_fee) to offer.standardFeeSats,
            )
            VerticalSpacer(24.dp)
            PrimaryButton(
                text = stringResource(R.string.lightning__spark_deposit__claim_early).replace("{when}", early),
                onClick = onClaimEarlyClick,
                isLoading = isLoading,
                modifier = Modifier.testTag("SparkDepositClaimEarly")
            )
        }
    }
}

@Composable
private fun Claimed(deposit: Deposit.Claimed) {
    val offer = deposit.offer
    when (deposit.claim) {
        SparkEarlyClaim.Claimed -> {
            Text(stringResource(R.string.lightning__spark_deposit__claimed))
            Fees(stringResource(R.string.lightning__spark_claim_fee) to offer.earlyFeeSats)
        }

        SparkEarlyClaim.Waiting -> {
            Text(stringResource(R.string.lightning__spark_deposit__waiting_next))
            Fees(stringResource(R.string.lightning__spark_claim_fee) to offer.earlyFeeSats)
        }

        SparkEarlyClaim.Declined, is SparkEarlyClaim.Repriced -> {
            Text(standardText(offer.standardConfirmations))
            Fees(stringResource(R.string.lightning__spark_claim_fee) to offer.standardFeeSats)
        }
    }
}

@Composable
private fun Text(text: String) {
    BodyM(text = text, color = Colors.White64)
}

@Composable
private fun Fees(vararg fees: Pair<String, ULong>) {
    VerticalSpacer(16.dp)
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.height(IntrinsicSize.Min)
    ) {
        fees.forEach { (label, sats) -> FeeInfo(label = label, amount = sats.toLong()) }
    }
}

@Composable
private fun standardText(confirmations: UInt): String =
    stringResource(R.string.lightning__spark_deposit__standard)
        .replace("{standard}", confirmations.toString())
        .replace("{minutes}", (confirmations * MINUTES_PER_CONFIRMATION).toString())

@Composable
private fun whenText(confirmations: UInt): String = when (confirmations) {
    0u -> stringResource(R.string.lightning__spark_deposit__now)
    1u -> stringResource(R.string.lightning__spark_deposit__later_one)
    else -> stringResource(R.string.lightning__spark_deposit__later).replace("{count}", confirmations.toString())
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = UiState(
                deposit = Deposit.Offered(
                    SparkClaimOffer.Choice(
                        txid = "",
                        vout = 0u,
                        earlyFeeSats = 1_350uL,
                        earlyConfirmations = 0u,
                        standardFeeSats = 907uL,
                        standardConfirmations = 3u,
                    )
                ),
            ),
        )
    }
}

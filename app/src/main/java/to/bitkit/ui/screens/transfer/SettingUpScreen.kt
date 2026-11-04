package to.bitkit.ui.screens.transfer

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Devices.NEXUS_5
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.R
import to.bitkit.models.formatToModernDisplay
import to.bitkit.repositories.SparkClaimOffer
import to.bitkit.repositories.SparkDepositClaim
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.transfer.components.ProgressSteps
import to.bitkit.ui.screens.transfer.components.TransferAnimationView
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.localizedRandom
import to.bitkit.ui.utils.withAccent

/** Confirmations Spark waits for before it adds a deposit by itself. */
private const val MATURE_CONFIRMATIONS = 3

/** About how long [MATURE_CONFIRMATIONS] take. */
private const val MATURE_MINUTES = 30

@Composable
fun SettingUpScreen(
    viewModel: SparkFundingViewModel,
    onContinueClick: () -> Unit = {},
) {
    val app = appViewModel ?: return
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        app.enabledTransactionSheet(false)
        onDispose {
            app.enabledTransactionSheet(true)
        }
    }

    Content(
        claim = state.claim,
        onPayClick = viewModel::onPayRepriced,
        onWaitClick = viewModel::onWait,
        onContinueClick = onContinueClick,
    )
}

@Composable
private fun Content(
    claim: SparkDepositClaim,
    onPayClick: () -> Unit = {},
    onWaitClick: () -> Unit = {},
    onContinueClick: () -> Unit = {},
) {
    val inProgress = claim != SparkDepositClaim.Claimed
    ScreenColumn(
        modifier = Modifier.testTag(if (inProgress) "LightningSettingUp" else "TransferSuccess")
    ) {
        AppTopBar(
            titleText = when {
                inProgress -> stringResource(R.string.lightning__transfer__nav_title)
                else -> stringResource(R.string.lightning__transfer_success__nav_title)
            },
            onBackClick = null,
            actions = { DrawerNavIcon() },
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            VerticalSpacer(12.dp)
            if (inProgress) {
                Display(
                    text = stringResource(R.string.lightning__savings_progress__title)
                        .withAccent(accentColor = Colors.Purple),
                    modifier = Modifier.fillMaxWidth(),
                )
                VerticalSpacer(8.dp)
                BodyM(
                    text = claimText(claim),
                    color = Colors.White64,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("SettingUpText")
                )
            } else {
                Display(
                    text = stringResource(R.string.lightning__transfer_success__title_spending)
                        .withAccent(accentColor = Colors.Purple),
                    modifier = Modifier.fillMaxWidth(),
                )
                VerticalSpacer(8.dp)
                BodyM(
                    text = stringResource(R.string.lightning__transfer_success__text_spending),
                    color = Colors.White64,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            VerticalSpacer(28.dp)
            if (inProgress) {
                TransferAnimationView(
                    largeCircleRes = R.drawable.ln_sync_large,
                    smallCircleRes = R.drawable.ln_sync_small,
                    modifier = Modifier.weight(1f)
                )
                VerticalSpacer(16.dp)
                val steps = persistentListOf(
                    stringResource(R.string.lightning__setting_up__step_sent),
                    stringResource(R.string.lightning__setting_up__step_seen),
                    stringResource(R.string.lightning__setting_up__step_adding),
                )
                ProgressSteps(
                    steps = steps,
                    activeStepIndex = claim.stepIndex(),
                    modifier = Modifier.align(alignment = Alignment.CenterHorizontally)
                )
                VerticalSpacer(16.dp)
            } else {
                Image(
                    painter = painterResource(id = R.drawable.check),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .weight(1f)
                        .padding(16.dp)
                )
            }

            if (claim is SparkDepositClaim.Repriced) {
                PrimaryButton(
                    text = stringResource(R.string.lightning__setting_up__pay)
                        .replace("{amount}", claim.offer.earlyFeeSats.formatToModernDisplay()),
                    onClick = onPayClick,
                    modifier = Modifier.testTag("SettingUpPay")
                )
                VerticalSpacer(16.dp)
                SecondaryButton(
                    text = stringResource(R.string.lightning__spark_deposit__wait_button),
                    onClick = onWaitClick,
                    modifier = Modifier.testTag("SettingUpWait")
                )
            } else {
                val randomOkText = localizedRandom(R.string.common__ok_random)
                PrimaryButton(
                    text = if (inProgress) stringResource(R.string.lightning__setting_up_button) else randomOkText,
                    onClick = onContinueClick,
                    modifier = Modifier.testTag("TransferSuccess-button")
                )
            }
            VerticalSpacer(16.dp)
        }
    }
}

@Composable
private fun claimText(claim: SparkDepositClaim): String = when (claim) {
    SparkDepositClaim.Looking -> stringResource(R.string.lightning__spark_deposit__looking)
    SparkDepositClaim.Adding, SparkDepositClaim.Claimed -> stringResource(R.string.lightning__spark_deposit__claimed)
    is SparkDepositClaim.Repriced -> stringResource(R.string.lightning__setting_up__repriced)
        .replace("{amount}", claim.offer.earlyFeeSats.formatToModernDisplay())

    SparkDepositClaim.Unavailable -> stringResource(R.string.lightning__setting_up__unavailable)
    SparkDepositClaim.Later -> stringResource(R.string.lightning__spark_deposit__standard)
        .replace("{standard}", MATURE_CONFIRMATIONS.toString())
        .replace("{minutes}", MATURE_MINUTES.toString())

    SparkDepositClaim.NotSeen -> stringResource(R.string.lightning__setting_up__not_seen)
}

private fun SparkDepositClaim.stepIndex(): Int = when (this) {
    SparkDepositClaim.Looking, SparkDepositClaim.Later, SparkDepositClaim.NotSeen -> 1
    SparkDepositClaim.Adding, is SparkDepositClaim.Repriced, SparkDepositClaim.Unavailable -> 2
    SparkDepositClaim.Claimed -> 3
}

private val previewRepriced = SparkDepositClaim.Repriced(
    SparkClaimOffer.Choice(
        txid = "txid",
        vout = 0u,
        earlyFeeSats = 1_850uL,
        earlyConfirmations = 0u,
        standardFeeSats = 198uL,
        standardConfirmations = 3u,
    )
)

@Preview(name = "Looking", showSystemUi = true, showBackground = true)
@Composable
private fun PreviewLooking() {
    AppThemeSurface { Content(claim = SparkDepositClaim.Looking) }
}

@Preview(name = "Looking", showSystemUi = true, showBackground = true, device = NEXUS_5)
@Composable
private fun PreviewLookingSmall() {
    AppThemeSurface { Content(claim = SparkDepositClaim.Looking) }
}

@Preview(name = "Adding", showSystemUi = true, showBackground = true)
@Composable
private fun PreviewAdding() {
    AppThemeSurface { Content(claim = SparkDepositClaim.Adding) }
}

@Preview(name = "Repriced", showSystemUi = true, showBackground = true)
@Composable
private fun PreviewRepriced() {
    AppThemeSurface { Content(claim = previewRepriced) }
}

@Preview(name = "Repriced", showSystemUi = true, showBackground = true, device = NEXUS_5)
@Composable
private fun PreviewRepricedSmall() {
    AppThemeSurface { Content(claim = previewRepriced) }
}

@Preview(name = "Unavailable", showSystemUi = true, showBackground = true)
@Composable
private fun PreviewUnavailable() {
    AppThemeSurface { Content(claim = SparkDepositClaim.Unavailable) }
}

@Preview(name = "Later", showSystemUi = true, showBackground = true)
@Composable
private fun PreviewLater() {
    AppThemeSurface { Content(claim = SparkDepositClaim.Later) }
}

@Preview(name = "Not seen", showSystemUi = true, showBackground = true)
@Composable
private fun PreviewNotSeen() {
    AppThemeSurface { Content(claim = SparkDepositClaim.NotSeen) }
}

@Preview(name = "Success", showSystemUi = true, showBackground = true)
@Composable
private fun PreviewSuccess() {
    AppThemeSurface { Content(claim = SparkDepositClaim.Claimed) }
}

@Preview(name = "Success", showSystemUi = true, showBackground = true, device = NEXUS_5)
@Composable
private fun PreviewSuccessSmall() {
    AppThemeSurface { Content(claim = SparkDepositClaim.Claimed) }
}

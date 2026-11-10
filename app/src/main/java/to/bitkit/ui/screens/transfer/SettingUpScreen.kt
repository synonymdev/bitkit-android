package to.bitkit.ui.screens.transfer

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import to.bitkit.repositories.SparkClaimSpeed
import to.bitkit.repositories.SparkDepositClaim
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Caption13Up
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
import to.bitkit.ui.utils.screenWidth
import to.bitkit.ui.utils.withAccent
import to.bitkit.ui.utils.withAccentBoldBright

/** Confirmations Spark waits for before it adds a deposit by itself. */
private const val MATURE_CONFIRMATIONS = 3

/** The success check's width against the screen's, as iOS draws its 256 pt check on a 402 pt screen. */
private const val CHECK_SCREEN_SHARE = 256f / 402f

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
        speed = state.speed,
        onPayClick = viewModel::onPayRepriced,
        onWaitClick = viewModel::onWait,
        onContinueClick = onContinueClick,
    )
}

@Composable
internal fun Content(
    claim: SparkDepositClaim,
    speed: SparkClaimSpeed = SparkClaimSpeed.Instant,
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
                    text = claimText(claim).withAccentBoldBright(),
                    color = Colors.White64,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("SettingUpText")
                )
                VerticalSpacer(8.dp)
                Caption13Up(
                    text = speedText(claim, speed),
                    color = Colors.White64,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("SettingUpSpeed")
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
                    keepAssetRatios = true,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
                VerticalSpacer(16.dp)
                val steps = persistentListOf(
                    stringResource(R.string.lightning__setting_up_step1),
                    stringResource(R.string.lightning__setting_up_step2),
                    stringResource(R.string.lightning__setting_up__step_adding),
                )
                ProgressSteps(
                    steps = steps,
                    activeStepIndex = claim.stepIndex(),
                    modifier = Modifier.align(alignment = Alignment.CenterHorizontally)
                )
                VerticalSpacer(16.dp)
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.check),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .width(screenWidth() * CHECK_SCREEN_SHARE)
                            .aspectRatio(1f)
                            .testTag("TransferSuccessCheck")
                    )
                }
            }

            if (claim is SparkDepositClaim.Repriced) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SecondaryButton(
                        text = stringResource(R.string.lightning__spark_deposit__wait_button),
                        onClick = onWaitClick,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("SettingUpWait")
                    )
                    PrimaryButton(
                        text = stringResource(R.string.lightning__setting_up__pay)
                            .replace("{amount}", claim.offer.earlyFeeSats.formatToModernDisplay()),
                        onClick = onPayClick,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("SettingUpPay")
                    )
                }
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

@Composable
private fun speedText(claim: SparkDepositClaim, speed: SparkClaimSpeed): String {
    // Unavailable and not seen add the transfer after 3 confirmations, whatever speed the user chose.
    val isLater = speed == SparkClaimSpeed.Later || claim == SparkDepositClaim.Later ||
        claim == SparkDepositClaim.Unavailable || claim == SparkDepositClaim.NotSeen
    return stringResource(R.string.lightning__setting_up__speed).replace(
        "{speed}",
        stringResource(if (isLater) R.string.common__later else R.string.fee__instant__title),
    )
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

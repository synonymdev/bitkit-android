package to.bitkit.ui.screens.transfer

import androidx.annotation.StringRes
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
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
import to.bitkit.ui.utils.screenWidth
import to.bitkit.ui.utils.withAccent
import to.bitkit.ui.utils.withAccentBoldBright

/** The success check's width against the screen's, as iOS draws its 256 pt check on a 402 pt screen. */
private const val CHECK_SCREEN_SHARE = 256f / 402f

/** The coins' side against the outer ring, as Figma draws them: 329 pt on a 311 pt ring. */
private const val COINS_FIGURE_SHARE = 329.2f / 311f

/** How far below the rings' centre Figma draws the coins, against the outer ring. */
private const val COINS_OFFSET_SHARE = 6.9f / 311f

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
        isDepositSeen = state.isDepositSeen,
        isFeeApproved = state.isFeeApproved,
        hasWaited = state.hasWaited,
        onApproveClick = viewModel::onPayRepriced,
        onWaitClick = viewModel::onWait,
        onContinueClick = onContinueClick,
    )
}

@Composable
internal fun Content(
    claim: SparkDepositClaim,
    isDepositSeen: Boolean = false,
    isFeeApproved: Boolean = false,
    hasWaited: Boolean = false,
    onApproveClick: () -> Unit = {},
    onWaitClick: () -> Unit = {},
    onContinueClick: () -> Unit = {},
) {
    val status = remember(claim, isDepositSeen, isFeeApproved, hasWaited) {
        transferStatusOf(claim, isDepositSeen, isFeeApproved, hasWaited)
    }
    val frame = status.frame
    val isSuccess = frame == TransferStatusFrame.Success
    ScreenColumn(
        modifier = Modifier.testTag(if (isSuccess) "TransferSuccess" else "LightningSettingUp")
    ) {
        AppTopBar(
            titleText = when {
                isSuccess -> stringResource(R.string.lightning__transfer_success__nav_title)
                else -> stringResource(R.string.lightning__transfer__nav_title)
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
            Display(
                text = stringResource(frame.titleRes).withAccent(accentColor = Colors.Purple),
                modifier = Modifier.fillMaxWidth(),
            )
            VerticalSpacer(8.dp)
            BodyM(
                text = statusText(status, claim).withAccentBoldBright(),
                color = Colors.White64,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("SettingUpText")
            )
            VerticalSpacer(28.dp)
            if (isSuccess) {
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
            } else {
                Illustration(
                    hasCoins = frame.hasCoins,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
                VerticalSpacer(16.dp)
                ProgressSteps(
                    steps = steps(frame),
                    activeStepIndex = frame.stepIndex,
                    modifier = Modifier.align(alignment = Alignment.CenterHorizontally)
                )
                VerticalSpacer(16.dp)
            }
            Buttons(
                frame = frame,
                onApproveClick = onApproveClick,
                onWaitClick = onWaitClick,
                onContinueClick = onContinueClick,
            )
            VerticalSpacer(16.dp)
        }
    }
}

@Composable
private fun Illustration(hasCoins: Boolean, modifier: Modifier = Modifier) {
    if (hasCoins) {
        TransferAnimationView(
            largeCircleRes = R.drawable.ln_sync_large,
            smallCircleRes = R.drawable.ln_sync_small,
            contentRes = R.drawable.coin_stack_4,
            rotateContent = false,
            keepAssetRatios = true,
            figureShare = COINS_FIGURE_SHARE,
            figureOffsetShare = COINS_OFFSET_SHARE,
            modifier = modifier.testTag("SettingUpCoins")
        )
        return
    }
    TransferAnimationView(
        largeCircleRes = R.drawable.ln_sync_large,
        smallCircleRes = R.drawable.ln_sync_small,
        keepAssetRatios = true,
        modifier = modifier
    )
}

@Composable
private fun Buttons(
    frame: TransferStatusFrame,
    onApproveClick: () -> Unit,
    onWaitClick: () -> Unit,
    onContinueClick: () -> Unit,
) {
    if (frame == TransferStatusFrame.FeeChanged) {
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
                text = stringResource(R.string.lightning__setting_up__approve_button),
                onClick = onApproveClick,
                modifier = Modifier
                    .weight(1f)
                    .testTag("SettingUpPay")
            )
        }
        return
    }
    val randomOkText = localizedRandom(R.string.common__ok_random)
    PrimaryButton(
        text = when (frame) {
            TransferStatusFrame.Success -> randomOkText
            else -> stringResource(R.string.lightning__setting_up_button)
        },
        onClick = onContinueClick,
        modifier = Modifier.testTag("TransferSuccess-button")
    )
}

@Composable
private fun statusText(status: TransferStatus, claim: SparkDepositClaim): String = when {
    status.frame == TransferStatusFrame.Success -> stringResource(R.string.lightning__transfer_success__text_spending)
    claim is SparkDepositClaim.Repriced -> stringResource(R.string.lightning__setting_up__fee_changed_text)
        .replace("{amount}", claim.offer.earlyFeeSats.formatToModernDisplay())

    status.isNormalSpeed -> stringResource(R.string.lightning__setting_up__text_normal)
    else -> stringResource(R.string.lightning__setting_up__text_fast)
}

@Composable
private fun steps(frame: TransferStatusFrame): ImmutableList<String> {
    val labels = mutableListOf(
        stringResource(R.string.lightning__setting_up_step1),
        stringResource(R.string.lightning__setting_up_step2),
        stringResource(frame.stepRes),
    )
    labels[frame.stepIndex] = stringResource(frame.stepRes)
    return labels.toImmutableList()
}

/** The frames of the Funds In Transfer (spark) design, in the order a transfer moves through them. */
enum class TransferStatusFrame(
    val stepIndex: Int,
    @StringRes val titleRes: Int,
    @StringRes val stepRes: Int,
    val hasCoins: Boolean = false,
) {
    /** Spark has not seen the deposit yet. */
    Processing(0, R.string.lightning__savings_progress__title, R.string.lightning__setting_up_step1),

    /** Spark has seen the deposit. */
    PaymentSuccessful(1, R.string.lightning__savings_progress__title, R.string.lightning__setting_up_step2),

    /** The fast transfer fee rose above what the user accepted; Approve pays it, Wait takes the normal speed. */
    FeeChanged(
        2,
        R.string.lightning__setting_up__fee_changed_title,
        R.string.lightning__setting_up__step_fee_changed,
        hasCoins = true,
    ),

    /** Spark offers no fast transfer for the deposit, so it takes the normal speed. */
    FastUnavailable(
        2,
        R.string.lightning__setting_up__unavailable_title,
        R.string.lightning__setting_up__step_normal,
        hasCoins = true,
    ),

    /** The user chose Wait over the changed fast transfer fee. */
    NormalSpeed(2, R.string.lightning__savings_progress__title, R.string.lightning__setting_up__step_normal),

    /** Spark is adding the deposit to the spending balance. */
    Adding(2, R.string.lightning__savings_progress__title, R.string.lightning__setting_up__step_adding_balance),

    /** The deposit is in the spending balance. */
    Success(
        2,
        R.string.lightning__transfer_success__title_spending,
        R.string.lightning__setting_up__step_adding_balance,
    ),
}

/** The frame a transfer shows, and whether its text names the normal speed's 30 minutes. */
@Immutable
data class TransferStatus(
    val frame: TransferStatusFrame,
    val isNormalSpeed: Boolean = false,
)

/**
 * Maps a deposit's claim onto the design's frames. [isFeeApproved] keeps an approved fee change on the last step while
 * the claim looks again, and [hasWaited] keeps it there at the normal speed after Wait.
 */
internal fun transferStatusOf(
    claim: SparkDepositClaim,
    isDepositSeen: Boolean,
    isFeeApproved: Boolean,
    hasWaited: Boolean,
): TransferStatus {
    val seenOrProcessing = if (isDepositSeen) TransferStatusFrame.PaymentSuccessful else TransferStatusFrame.Processing
    return when (claim) {
        SparkDepositClaim.Claimed -> TransferStatus(TransferStatusFrame.Success)
        is SparkDepositClaim.Repriced -> TransferStatus(TransferStatusFrame.FeeChanged)
        SparkDepositClaim.Unavailable -> TransferStatus(TransferStatusFrame.FastUnavailable, isNormalSpeed = true)
        SparkDepositClaim.Adding -> TransferStatus(TransferStatusFrame.Adding)
        SparkDepositClaim.Later if hasWaited ->
            TransferStatus(TransferStatusFrame.NormalSpeed, isNormalSpeed = true)

        SparkDepositClaim.Later, SparkDepositClaim.NotSeen -> TransferStatus(seenOrProcessing, isNormalSpeed = true)
        SparkDepositClaim.Looking if isFeeApproved -> TransferStatus(TransferStatusFrame.Adding)
        SparkDepositClaim.Looking -> TransferStatus(seenOrProcessing)
    }
}

private val previewRepriced = SparkDepositClaim.Repriced(
    SparkClaimOffer.Choice(
        txid = "txid",
        vout = 0u,
        earlyFeeSats = 2_134uL,
        earlyConfirmations = 0u,
        standardFeeSats = 198uL,
        standardConfirmations = 3u,
    )
)

@Preview(name = "Processing", showSystemUi = true, showBackground = true)
@Composable
private fun PreviewProcessing() {
    AppThemeSurface { Content(claim = SparkDepositClaim.Looking) }
}

@Preview(name = "Processing", showSystemUi = true, showBackground = true, device = NEXUS_5)
@Composable
private fun PreviewProcessingSmall() {
    AppThemeSurface { Content(claim = SparkDepositClaim.Looking) }
}

@Preview(name = "Payment successful", showSystemUi = true, showBackground = true)
@Composable
private fun PreviewSeen() {
    AppThemeSurface { Content(claim = SparkDepositClaim.Looking, isDepositSeen = true) }
}

@Preview(name = "Fee changed", showSystemUi = true, showBackground = true)
@Composable
private fun PreviewFeeChanged() {
    AppThemeSurface { Content(claim = previewRepriced) }
}

@Preview(name = "Fee changed", showSystemUi = true, showBackground = true, device = NEXUS_5)
@Composable
private fun PreviewFeeChangedSmall() {
    AppThemeSurface { Content(claim = previewRepriced) }
}

@Preview(name = "Fast unavailable", showSystemUi = true, showBackground = true)
@Composable
private fun PreviewUnavailable() {
    AppThemeSurface { Content(claim = SparkDepositClaim.Unavailable) }
}

@Preview(name = "Waited", showSystemUi = true, showBackground = true)
@Composable
private fun PreviewWaited() {
    AppThemeSurface { Content(claim = SparkDepositClaim.Later, isDepositSeen = true, hasWaited = true) }
}

@Preview(name = "Adding", showSystemUi = true, showBackground = true)
@Composable
private fun PreviewAdding() {
    AppThemeSurface { Content(claim = SparkDepositClaim.Adding) }
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

package to.bitkit.ui.sheets

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.repositories.MoveToSparkOutcome
import to.bitkit.repositories.MoveToSparkQuote
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.rememberMoneyText
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.settings.lightning.MoveToSparkFailure
import to.bitkit.ui.settings.lightning.MoveToSparkUiState
import to.bitkit.ui.settings.lightning.MoveToSparkViewModel
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.ui.utils.withAccentBoldBright

/**
 * The Update Spending Balance modal: quotes moving the channels' balance to Spark when it opens, moves it on Update and
 * reports the outcome in place, from Spending Balance Updated to the states that need another step.
 */
@Composable
fun MoveToSparkSheet(
    viewModel: MoveToSparkViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(viewModel) {
        viewModel.open()
        onDispose { viewModel.close() }
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Content(
        uiState = uiState,
        onDismiss = onDismiss,
        onUpdate = viewModel::move,
        onQuote = viewModel::quote,
        modifier = modifier,
    )
}

@Composable
private fun Content(
    uiState: MoveToSparkUiState,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
    onUpdate: () -> Unit = {},
    onQuote: (ULong?) -> Unit = {},
) {
    when (uiState) {
        MoveToSparkUiState.Quoting -> Update(null, true, onDismiss, onUpdate, modifier)
        is MoveToSparkUiState.Confirm -> Update(uiState.quote.feeSats, uiState.isMoving, onDismiss, onUpdate, modifier)
        is MoveToSparkUiState.Done -> Done(uiState, onDismiss, onQuote, modifier)
        is MoveToSparkUiState.TooLarge -> TooLarge(uiState, onDismiss, onQuote, modifier)
        is MoveToSparkUiState.Failed -> Failed(uiState.failure, onDismiss, onQuote, modifier)
    }
}

@Composable
private fun Update(
    feeSats: ULong?,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = stringResource(R.string.lightning__move_to_spark__update_text)
    val fee = feeSats?.let { stringResource(R.string.lightning__move_to_spark__update_fee, plainMoney(it)) }
    Modal(
        navTitle = stringResource(R.string.lightning__move_to_spark__update_nav_title),
        drawable = R.drawable.lightning,
        title = stringResource(R.string.lightning__move_to_spark__update_title),
        text = fee?.let { "$text <accent>$it</accent>" } ?: text,
        testTag = "UpdateSpendingBalance",
        modifier = modifier,
    ) {
        SecondaryButton(
            stringResource(R.string.common__later),
            onClick = onDismiss,
            testTag = "UpdateSpendingBalanceLater",
            modifier = Modifier.weight(1f)
        )
        PrimaryButton(
            stringResource(R.string.lightning__move_to_spark__update),
            onClick = onUpdate,
            isLoading = isLoading,
            enabled = !isLoading,
            modifier = Modifier.weight(1f).testTag("UpdateSpendingBalanceUpdate")
        )
    }
}

@Composable
private fun Done(
    uiState: MoveToSparkUiState.Done,
    onDismiss: () -> Unit,
    onQuote: (ULong?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // While the payment is in flight its amount may still return to the channel, so nothing is closed yet.
    if (uiState.outcome is MoveToSparkOutcome.InFlight) {
        Modal(
            navTitle = stringResource(R.string.lightning__move_to_spark__update_nav_title),
            drawable = R.drawable.lightning,
            title = stringResource(R.string.lightning__move_to_spark__in_flight_title),
            text = stringResource(R.string.lightning__move_to_spark__in_flight_text),
            testTag = "MoveToSparkInFlight",
            modifier = modifier,
        ) {
            PrimaryButton(stringResource(R.string.common__ok), onClick = onDismiss, modifier = Modifier.weight(1f))
        }
        return
    }
    if (uiState.hasRest) {
        Modal(
            navTitle = stringResource(R.string.lightning__move_to_spark__update_nav_title),
            drawable = R.drawable.check,
            title = stringResource(R.string.lightning__move_to_spark__done_title),
            text = stringResource(R.string.lightning__move_to_spark__updated_rest_text),
            testTag = "MoveToSparkPartlyMoved",
            modifier = modifier,
        ) {
            SecondaryButton(
                stringResource(R.string.common__later),
                onClick = onDismiss,
                modifier = Modifier.weight(1f)
            )
            PrimaryButton(
                stringResource(R.string.lightning__move_to_spark__move_rest),
                onClick = { onQuote(null) },
                modifier = Modifier.weight(1f).testTag("MoveToSparkMoveRest")
            )
        }
        return
    }
    Modal(
        navTitle = stringResource(R.string.lightning__move_to_spark__updated_nav_title),
        drawable = R.drawable.check,
        title = stringResource(R.string.lightning__move_to_spark__updated_title),
        text = stringResource(R.string.lightning__move_to_spark__updated_text),
        testTag = "SpendingBalanceUpdated",
        modifier = modifier,
    ) {
        PrimaryButton(
            stringResource(R.string.common__ok),
            onClick = onDismiss,
            modifier = Modifier.weight(1f).testTag("SpendingBalanceUpdatedOk")
        )
    }
}

@Composable
private fun TooLarge(
    uiState: MoveToSparkUiState.TooLarge,
    onDismiss: () -> Unit,
    onQuote: (ULong?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val amount = "<accent>${plainMoney(uiState.amountSats)}</accent>"
    val smallerSats = uiState.smallerSats
    val next = stringResource(
        if (smallerSats == null) {
            R.string.lightning__move_to_spark__too_large_none
        } else {
            R.string.lightning__move_to_spark__too_large_smaller
        }
    )
    Modal(
        navTitle = stringResource(R.string.lightning__move_to_spark__update_nav_title),
        drawable = R.drawable.exclamation_mark,
        title = stringResource(R.string.lightning__move_to_spark__too_large_title),
        text = stringResource(R.string.lightning__move_to_spark__too_large_text, amount) + " " + next,
        testTag = "MoveToSparkTooLarge",
        modifier = modifier,
    ) {
        if (smallerSats == null) {
            PrimaryButton(stringResource(R.string.common__ok), onClick = onDismiss, modifier = Modifier.weight(1f))
            return@Modal
        }
        SecondaryButton(stringResource(R.string.common__cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
        PrimaryButton(
            stringResource(R.string.lightning__move_to_spark__move_half),
            onClick = { onQuote(smallerSats) },
            modifier = Modifier.weight(1f).testTag("MoveToSparkTrySmaller")
        )
    }
}

@Composable
private fun Failed(
    failure: MoveToSparkFailure,
    onDismiss: () -> Unit,
    onQuote: (ULong?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Modal(
        navTitle = stringResource(R.string.lightning__move_to_spark__update_nav_title),
        drawable = R.drawable.cross,
        title = stringResource(R.string.lightning__move_to_spark__failed_title),
        text = stringResource(
            when (failure) {
                MoveToSparkFailure.NOTHING_TO_MOVE -> R.string.lightning__move_to_spark__failed_nothing
                MoveToSparkFailure.SPARK_UNAVAILABLE -> R.string.lightning__move_to_spark__failed_spark
                MoveToSparkFailure.UNKNOWN -> R.string.lightning__move_to_spark__failed_unknown
            }
        ),
        testTag = "MoveToSparkFailed",
        modifier = modifier,
    ) {
        if (failure == MoveToSparkFailure.NOTHING_TO_MOVE) {
            PrimaryButton(stringResource(R.string.common__ok), onClick = onDismiss, modifier = Modifier.weight(1f))
            return@Modal
        }
        SecondaryButton(stringResource(R.string.common__cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
        PrimaryButton(
            stringResource(R.string.common__try_again),
            onClick = { onQuote(null) },
            modifier = Modifier.weight(1f).testTag("MoveToSparkRetry")
        )
    }
}

/** The amount in the primary currency with its symbol, without the symbol's accent. */
@Composable
private fun plainMoney(sats: ULong): String =
    rememberMoneyText(sats.toLong(), showSymbol = true)?.replace(Regex("</?accent>"), "") ?: sats.toString()

@Composable
private fun Modal(
    navTitle: String,
    @DrawableRes drawable: Int,
    title: String,
    text: String,
    testTag: String,
    modifier: Modifier = Modifier,
    buttons: @Composable FlowRowScope.() -> Unit,
) {
    Column(
        modifier = modifier.sheetHeight().navigationBarsPadding().testTag(testTag)
    ) {
        SheetTopBar(navTitle)
        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            // The illustration shrinks on small screens so the copy below it stays whole.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                Image(
                    painter = painterResource(drawable),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .sizeIn(maxWidth = 256.dp, maxHeight = 256.dp)
                        .aspectRatio(1f)
                        .scale(illustrationScale(drawable))
                )
            }
            VerticalSpacer(32.dp)
            Display(title.withAccent(accentColor = Colors.Purple))
            VerticalSpacer(8.dp)
            BodyM(text.withAccentBoldBright(), color = Colors.White64)
        }
        VerticalSpacer(32.dp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            maxItemsInEachRow = if (LocalDensity.current.fontScale > 1.3f) 1 else 2,
            modifier = Modifier.padding(horizontal = 16.dp),
            content = buttons,
        )
        VerticalSpacer(16.dp)
    }
}

/** Scales each illustration so its drawing fills its box like the zap; the others have wider transparent margins. */
private fun illustrationScale(@DrawableRes drawable: Int): Float = when (drawable) {
    R.drawable.check -> 1.25f
    R.drawable.cross -> 1.4f
    R.drawable.exclamation_mark -> 1.1f
    else -> 1f
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewUpdate() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(uiState = MoveToSparkUiState.Confirm(MoveToSparkQuote("lnbc", 98_500uL, 12uL)))
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewUpdated() {
    AppThemeSurface {
        BottomSheetPreview { Content(uiState = MoveToSparkUiState.Done(MoveToSparkOutcome.Moved(98_500uL, 9uL))) }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewTooLarge() {
    AppThemeSurface {
        BottomSheetPreview { Content(uiState = MoveToSparkUiState.TooLarge(98_500uL, 49_250uL)) }
    }
}

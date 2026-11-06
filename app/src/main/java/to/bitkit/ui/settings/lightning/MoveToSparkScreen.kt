package to.bitkit.ui.settings.lightning

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
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
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.GradientCircularProgressIndicator
import to.bitkit.ui.components.MoneyCaptionB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.SwipeToConfirm
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.rememberMoneyText
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

/** Moves the channels' balance to the user's Spark wallet over Lightning, then offers to close the emptied channel. */
@Composable
fun MoveToSparkScreen(
    viewModel: MoveToSparkViewModel,
    onBack: () -> Unit,
    onCloseChannel: (channelId: String?) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Content(
        uiState = uiState,
        onBack = onBack,
        onMove = viewModel::move,
        onRetry = viewModel::quote,
        onCloseChannel = onCloseChannel,
    )
}

@Composable
private fun Content(
    uiState: MoveToSparkUiState,
    onBack: () -> Unit = {},
    onMove: () -> Unit = {},
    onRetry: (ULong?) -> Unit = {},
    onCloseChannel: (String?) -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__move_to_spark__nav_title),
            onBackClick = onBack,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            when (uiState) {
                MoveToSparkUiState.Quoting -> Quoting()
                is MoveToSparkUiState.Confirm -> Confirm(uiState.quote, uiState.isMoving, onMove)
                is MoveToSparkUiState.Done -> Done(uiState, onBack, onRetry, onCloseChannel)
                is MoveToSparkUiState.TooLarge -> TooLarge(uiState, onBack, onRetry)
                is MoveToSparkUiState.Failed -> Failed(uiState.failure, onBack, onRetry)
            }
            VerticalSpacer(16.dp)
        }
    }
}

@Composable
private fun Header(title: String, text: String) {
    VerticalSpacer(32.dp)
    Display(text = title.withAccent(accentColor = Colors.Purple))
    VerticalSpacer(8.dp)
    BodyM(text = text, color = Colors.White64)
}

/** Takes the height between the content and the bottom buttons, so the buttons stay at the screen's bottom. */
@Composable
private fun ColumnScope.Illustration(@DrawableRes drawable: Int) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(vertical = 16.dp)
    ) {
        Image(
            painter = painterResource(drawable),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.sizeIn(maxWidth = 256.dp, maxHeight = 256.dp)
        )
    }
}

@Composable
private fun ColumnScope.Quoting() {
    Header(
        title = stringResource(R.string.lightning__move_to_spark__confirm_title),
        text = stringResource(R.string.lightning__move_to_spark__quoting),
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
    ) {
        GradientCircularProgressIndicator(modifier = Modifier.size(64.dp))
    }
}

@Composable
private fun ColumnScope.Confirm(quote: MoveToSparkQuote, isMoving: Boolean, onMove: () -> Unit) {
    Header(
        title = stringResource(R.string.lightning__move_to_spark__confirm_title),
        text = stringResource(R.string.lightning__move_to_spark__confirm_text),
    )
    VerticalSpacer(32.dp)
    AmountRow(stringResource(R.string.lightning__move_to_spark__amount), quote.amountSats, "MoveToSparkAmount")
    AmountRow(stringResource(R.string.lightning__move_to_spark__fee), quote.feeSats, "MoveToSparkFee")
    Illustration(R.drawable.lightning)
    SwipeToConfirm(
        text = stringResource(R.string.lightning__move_to_spark__swipe),
        color = Colors.Purple,
        loading = isMoving,
        enabled = !isMoving,
        onConfirm = onMove,
        modifier = Modifier.testTag("MoveToSparkSwipe")
    )
}

@Composable
private fun AmountRow(label: String, sats: ULong, testTag: String) {
    SectionRow(
        name = label,
        valueContent = { MoneyCaptionB(sats = sats.toLong(), symbol = true, modifier = Modifier.testTag(testTag)) },
    )
}

@Composable
private fun ColumnScope.Done(
    uiState: MoveToSparkUiState.Done,
    onDone: () -> Unit,
    onMoveRest: (ULong?) -> Unit,
    onCloseChannel: (String?) -> Unit,
) {
    val outcome = uiState.outcome
    Header(
        title = stringResource(R.string.lightning__move_to_spark__done_title),
        text = stringResource(
            when {
                outcome is MoveToSparkOutcome.InFlight -> R.string.lightning__move_to_spark__in_flight_text
                uiState.hasRest -> R.string.lightning__move_to_spark__done_rest_text
                else -> R.string.lightning__move_to_spark__done_text
            }
        ),
    )
    VerticalSpacer(32.dp)
    AmountRow(stringResource(R.string.lightning__move_to_spark__amount), outcome.amountSats, "MoveToSparkMoved")
    if (outcome is MoveToSparkOutcome.Moved) {
        AmountRow(stringResource(R.string.lightning__move_to_spark__fee), outcome.feeSats, "MoveToSparkFeePaid")
    }
    Illustration(R.drawable.check)
    // While the payment is in flight its amount may still return to the channel, so closing is not offered yet.
    if (outcome is MoveToSparkOutcome.InFlight) {
        PrimaryButton(text = stringResource(R.string.common__ok), onClick = onDone)
        return
    }
    if (uiState.hasRest) {
        Buttons(
            secondaryText = stringResource(R.string.common__later),
            onSecondary = onDone,
            primaryText = stringResource(R.string.lightning__move_to_spark__move_rest),
            onPrimary = { onMoveRest(null) },
            primaryTestTag = "MoveToSparkMoveRest",
        )
        return
    }
    Buttons(
        secondaryText = stringResource(R.string.common__later),
        onSecondary = onDone,
        primaryText = stringResource(R.string.common__close),
        onPrimary = { onCloseChannel(uiState.closeChannelId) },
        primaryTestTag = "MoveToSparkClose",
    )
}

@Composable
private fun ColumnScope.TooLarge(
    uiState: MoveToSparkUiState.TooLarge,
    onBack: () -> Unit,
    onRetry: (ULong?) -> Unit,
) {
    val amount = amountText(uiState.amountSats)
    Header(
        title = stringResource(R.string.lightning__move_to_spark__too_large_title),
        text = stringResource(R.string.lightning__move_to_spark__too_large_text, amount) + " " + stringResource(
            when (uiState.smallerSats) {
                null -> R.string.lightning__move_to_spark__too_large_none
                else -> R.string.lightning__move_to_spark__too_large_smaller
            }
        ),
    )
    Illustration(R.drawable.exclamation_mark)
    val smallerSats = uiState.smallerSats
    if (smallerSats == null) {
        PrimaryButton(text = stringResource(R.string.common__back), onClick = onBack)
        return
    }
    Buttons(
        secondaryText = stringResource(R.string.common__cancel),
        onSecondary = onBack,
        primaryText = stringResource(R.string.lightning__move_to_spark__move_half),
        onPrimary = { onRetry(smallerSats) },
        primaryTestTag = "MoveToSparkTrySmaller",
    )
}

@Composable
private fun ColumnScope.Failed(failure: MoveToSparkFailure, onBack: () -> Unit, onRetry: (ULong?) -> Unit) {
    Header(
        title = stringResource(R.string.lightning__move_to_spark__failed_title),
        text = stringResource(
            when (failure) {
                MoveToSparkFailure.NOTHING_TO_MOVE -> R.string.lightning__move_to_spark__failed_nothing
                MoveToSparkFailure.SPARK_UNAVAILABLE -> R.string.lightning__move_to_spark__failed_spark
                MoveToSparkFailure.UNKNOWN -> R.string.lightning__move_to_spark__failed_unknown
            }
        ),
    )
    Illustration(R.drawable.cross)
    if (failure == MoveToSparkFailure.NOTHING_TO_MOVE) {
        PrimaryButton(text = stringResource(R.string.common__back), onClick = onBack)
        return
    }
    Buttons(
        secondaryText = stringResource(R.string.common__cancel),
        onSecondary = onBack,
        primaryText = stringResource(R.string.common__try_again),
        onPrimary = { onRetry(null) },
        primaryTestTag = "MoveToSparkRetry",
    )
}

@Composable
private fun amountText(sats: ULong): String =
    rememberMoneyText(sats.toLong(), showSymbol = true)?.replace(Regex("</?accent>"), "") ?: sats.toString()

@Composable
private fun Buttons(
    secondaryText: String,
    onSecondary: () -> Unit,
    primaryText: String,
    onPrimary: () -> Unit,
    primaryTestTag: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        SecondaryButton(text = secondaryText, onClick = onSecondary, modifier = Modifier.weight(1f))
        PrimaryButton(
            text = primaryText,
            onClick = onPrimary,
            modifier = Modifier
                .weight(1f)
                .testTag(primaryTestTag)
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewConfirm() {
    AppThemeSurface {
        Content(uiState = MoveToSparkUiState.Confirm(MoveToSparkQuote("lnbc", 98_500uL, 12uL)))
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewDone() {
    AppThemeSurface {
        Content(uiState = MoveToSparkUiState.Done(MoveToSparkOutcome.Moved(98_500uL, 9uL), "channel"))
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewTooLarge() {
    AppThemeSurface {
        Content(uiState = MoveToSparkUiState.TooLarge(98_500uL, 49_250uL))
    }
}

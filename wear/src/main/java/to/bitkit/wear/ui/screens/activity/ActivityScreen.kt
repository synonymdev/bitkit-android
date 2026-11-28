package to.bitkit.wear.ui.screens.activity

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.wear.R
import to.bitkit.wear.models.BITCOIN_SYMBOL
import to.bitkit.wear.models.PaymentState
import to.bitkit.wear.models.WatchPayment
import to.bitkit.wear.models.formatToModernDisplay
import to.bitkit.wear.ui.components.BezelLoading
import to.bitkit.wear.ui.components.CenteredScreen
import to.bitkit.wear.ui.components.ListScreen
import to.bitkit.wear.ui.components.WearPreview
import to.bitkit.wear.ui.components.WearPreviewDevices
import to.bitkit.wear.ui.components.isAmbient
import to.bitkit.wear.ui.theme.Colors

/** The size of the empty state's icon. */
private val EMPTY_ICON_SIZE = 40.dp

/** The opacity of the icons that stand in for the list while it is empty, loading or hidden. */
private const val DIM_ALPHA = 0.4f

/**
 * The activity page of the home pager: the newest payments, each with its direction, amount and how long ago it
 * was. A tap opens its detail ([onPaymentClick]). Ambient mode hides the amounts.
 */
@Composable
fun ActivityScreen(
    onPaymentClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ActivityViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Content(
        uiState = uiState,
        isAmbient = isAmbient(),
        onPaymentClick = onPaymentClick,
        modifier = modifier
    )
}

@Composable
private fun Content(
    uiState: ActivityUiState,
    isAmbient: Boolean,
    onPaymentClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        isAmbient -> CenteredScreen(modifier = modifier) { DimIcon(modifier = Modifier.testTag("WatchActivityHidden")) }
        !uiState.isLoaded -> CenteredScreen(overlay = { BezelLoading() }, modifier = modifier) { DimIcon() }
        uiState.payments.isEmpty() -> CenteredScreen(modifier = modifier) {
            DimIcon()
            Text(
                text = stringResource(R.string.activity__empty),
                style = MaterialTheme.typography.titleSmall,
                color = Colors.White64,
                modifier = Modifier.testTag("WatchActivityEmpty")
            )
        }
        else -> List(uiState = uiState, onPaymentClick = onPaymentClick, modifier = modifier)
    }
}

@Composable
private fun List(
    uiState: ActivityUiState,
    onPaymentClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        ListScreen { spec ->
            item {
                ListHeader(
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier.transformedHeight(this, spec)
                ) {
                    Text(stringResource(R.string.activity__title))
                }
            }
            uiState.payments.forEachIndexed { index, payment ->
                item(key = payment.id) {
                    FilledTonalButton(
                        onClick = { onPaymentClick(payment.id) },
                        icon = { DirectionIcon(payment = payment) },
                        label = { Text(text = payment.amountText(), maxLines = 1) },
                        secondaryLabel = {
                            Text(text = relativeTime(payment.timestampSecs.toLong(), uiState.nowSecs), maxLines = 1)
                        },
                        transformation = SurfaceTransformation(spec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, spec)
                            .testTag("WatchActivityItem-$index")
                    )
                }
            }
        }
    }
}

/** The payment's direction, or its state when it is not completed. */
@Composable
internal fun DirectionIcon(
    payment: WatchPayment,
    modifier: Modifier = Modifier,
) {
    val (icon, description) = when {
        payment.state == PaymentState.Pending -> R.drawable.ic_schedule to R.string.activity__status_pending
        payment.state == PaymentState.Failed -> R.drawable.ic_close to R.string.activity__status_failed
        payment.isSent -> R.drawable.ic_north_east to R.string.activity__sent
        else -> R.drawable.ic_south_west to R.string.activity__received
    }
    Icon(
        painter = painterResource(icon),
        contentDescription = stringResource(description),
        tint = if (payment.state == PaymentState.Failed) MaterialTheme.colorScheme.error else Colors.Brand,
        modifier = modifier
    )
}

/** The payment's amount in bitcoin, with the phone's sign for its direction. */
internal fun WatchPayment.amountText(): String {
    val sign = if (isSent) "-" else "+"
    return "$sign $BITCOIN_SYMBOL ${amountSats.toLong().formatToModernDisplay()}"
}

/** How long ago [timestampSecs] was; a time ahead of the watch's clock, set by Spark's servers, counts as now. */
private fun relativeTime(timestampSecs: Long, nowSecs: Long): String = DateUtils.getRelativeTimeSpanString(
    timestampSecs.coerceAtMost(nowSecs) * DateUtils.SECOND_IN_MILLIS,
    nowSecs * DateUtils.SECOND_IN_MILLIS,
    DateUtils.MINUTE_IN_MILLIS,
    DateUtils.FORMAT_ABBREV_RELATIVE,
).toString()

@Composable
private fun DimIcon(modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(R.drawable.ic_history),
        contentDescription = null,
        tint = Colors.White.copy(alpha = DIM_ALPHA),
        modifier = modifier.size(EMPTY_ICON_SIZE)
    )
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        Content(
            uiState = ActivityUiState(
                payments = persistentListOf(
                    PreviewPayment,
                    PreviewPayment.copy(id = "2", isSent = false, amountSats = 50_000uL, timestampSecs = 1_000uL),
                    PreviewPayment.copy(id = "3", state = PaymentState.Pending),
                ),
                isLoaded = true,
                nowSecs = 4_000,
            ),
            isAmbient = false,
            onPaymentClick = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun PreviewEmpty() {
    WearPreview {
        Content(uiState = ActivityUiState(isLoaded = true), isAmbient = false, onPaymentClick = {})
    }
}

internal val PreviewPayment = WatchPayment(
    id = "1",
    isSent = true,
    amountSats = 21_000uL,
    feeSats = 3uL,
    timestampSecs = 3_400uL,
    state = PaymentState.Completed,
)

package to.bitkit.ui.screens.wallets.send

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.screens.wallets.send.SendPendingUiState.Resolution
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.utils.Logger

@Composable
fun SendPendingScreen(
    paymentHash: String,
    amount: Long,
    onPaymentSuccess: (NewTransactionSheetDetails) -> Unit,
    onPaymentError: () -> Unit,
    onClose: () -> Unit,
    onViewDetails: (String) -> Unit,
    viewModel: SendPendingViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.init(paymentHash, amount) }

    uiState.resolution?.let { resolution ->
        LaunchedEffect(resolution) {
            runCatching {
                when (resolution) {
                    is Resolution.Success -> onPaymentSuccess(resolution.details)
                    is Resolution.Error -> onPaymentError()
                }
            }.onFailure { Logger.error("Failed handling payment resolution", it) }
            viewModel.onResolutionHandled()
        }
    }

    Content(
        amount = uiState.amount,
        activityId = uiState.activityId,
        onClose = onClose,
        onViewDetails = onViewDetails,
    )
}

@Composable
private fun Content(
    amount: Long,
    activityId: String?,
    onClose: () -> Unit,
    onViewDetails: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
    ) {
        SheetTopBar(stringResource(R.string.wallet__send_pending__nav_title))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            VerticalSpacer(16.dp)
            BalanceHeaderView(sats = amount, modifier = Modifier.fillMaxWidth())

            VerticalSpacer(32.dp)
            BodyM(stringResource(R.string.wallet__send_pending__description), color = Colors.White64)

            FillHeight()
            HourglassAnimation(modifier = Modifier.align(Alignment.CenterHorizontally))
            FillHeight()

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryButton(
                    text = stringResource(R.string.wallet__send_details),
                    enabled = activityId != null,
                    onClick = { activityId?.let(onViewDetails) },
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    text = stringResource(R.string.common__close),
                    onClick = onClose,
                    modifier = Modifier.weight(1f),
                )
            }
            VerticalSpacer(16.dp)
        }
    }
}

@Composable
private fun HourglassAnimation(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "hourglass")
    val rotation by infiniteTransition.animateFloat(
        initialValue = -16f,
        targetValue = 16f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "hourglassRotation",
    )
    Image(
        painter = painterResource(R.drawable.hourglass),
        contentDescription = null,
        modifier = modifier
            .size(256.dp)
            .rotate(rotation),
    )
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                amount = 50_000L,
                activityId = null,
                onClose = {},
                onViewDetails = {},
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import to.bitkit.models.WalletScope
import to.bitkit.repositories.PendingPaymentResolution
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun SendPendingScreen(
    paymentHash: String,
    amount: Long,
    observeResolution: Boolean = true,
    isOnchain: Boolean = false,
    onPaymentSuccess: (String, Long) -> Unit,
    onPaymentError: (PendingPaymentResolution.Failure) -> Unit,
    onClose: () -> Unit,
    onViewDetails: (String) -> Unit,
    viewModel: SendPendingViewModel,
    walletId: String = WalletScope.default,
    refusalReason: String? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val txid = paymentHash.takeIf { isOnchain && it.matches(Regex("[0-9a-fA-F]{64}")) }
    LaunchedEffect(Unit) {
        if (isOnchain) {
            viewModel.initOnchain(txid, amount, walletId)
        } else if (observeResolution) {
            viewModel.init(paymentHash, amount)
        }
    }

    uiState.resolution?.takeIf { observeResolution }?.let { resolution ->
        LaunchedEffect(resolution) {
            when (resolution) {
                is PendingPaymentResolution.Success -> onPaymentSuccess(
                    resolution.paymentHash,
                    resolution.amountWithFeeSats ?: amount,
                )
                is PendingPaymentResolution.Failure -> onPaymentError(resolution)
            }
            viewModel.onResolutionHandled()
        }
    }

    SendPendingContent(
        amount = if (observeResolution) uiState.amount else amount,
        isOnchain = isOnchain,
        activityId = uiState.activityId,
        txid = txid,
        refusalReason = refusalReason,
        onClose = onClose,
        onViewDetails = onViewDetails,
    )
}

@Composable
internal fun SendPendingContent(
    amount: Long,
    isOnchain: Boolean,
    activityId: String?,
    onClose: () -> Unit,
    onViewDetails: (String) -> Unit,
    modifier: Modifier = Modifier,
    txid: String? = null,
    refusalReason: String? = null,
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
            BodyM(
                stringResource(
                    if (isOnchain) R.string.wallet__send_pending__onchain_description
                    else R.string.wallet__send_pending__description,
                ),
                color = Colors.White64,
            )

            if (isOnchain) {
                refusalReason?.let {
                    VerticalSpacer(16.dp)
                    BodyM(stringResource(R.string.wallet__send_pending__refusal, it), color = Colors.White64)
                }
                txid?.let {
                    VerticalSpacer(16.dp)
                    SelectionContainer {
                        BodyM(stringResource(R.string.wallet__send_pending__txid, it), color = Colors.White64)
                    }
                }
            }

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
            SendPendingContent(
                amount = 50_000L,
                isOnchain = false,
                activityId = null,
                onClose = {},
                onViewDetails = {},
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

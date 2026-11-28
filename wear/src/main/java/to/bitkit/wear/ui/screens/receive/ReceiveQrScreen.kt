package to.bitkit.wear.ui.screens.receive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import to.bitkit.wear.R
import to.bitkit.wear.ui.components.BezelLoading
import to.bitkit.wear.ui.components.CenteredScreen
import to.bitkit.wear.ui.components.QrCode
import to.bitkit.wear.ui.components.ScreenAwake
import to.bitkit.wear.ui.components.SmallAmountRow
import to.bitkit.wear.ui.components.WearPreview
import to.bitkit.wear.ui.components.WearPreviewDevices
import to.bitkit.wear.ui.components.isAmbient
import to.bitkit.wear.ui.modifiers.clickableAlpha
import to.bitkit.wear.ui.screens.home.DisplayAmount
import to.bitkit.wear.ui.theme.Colors

/** The QR's side as a share of the screen: the largest square inside the round screen (1/√2). */
private const val QR_SIZE_FRACTION = 0.707f

/** The size of the check and of the dimmed QR icon. */
private val MARK_SIZE = 56.dp

/** The opacity of the QR icon that stands in for the hidden QR in ambient mode. */
private const val HIDDEN_ALPHA = 0.4f

/**
 * The receive QR, as large as the round screen allows, at full brightness and with the screen kept on while it
 * shows. Ambient mode hides it. A paid invoice turns into a check with the amount and its own haptic; a tap on the
 * check makes a new invoice.
 */
@Composable
fun ReceiveQrScreen(
    modifier: Modifier = Modifier,
    viewModel: ReceiveViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val isPaid = uiState.paidAmount != null
    LaunchedEffect(isPaid) {
        // A firm click, unlike the confirm pattern a send ends with, so the two never feel alike.
        if (isPaid) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    Content(
        uiState = uiState,
        isAmbient = isAmbient(),
        onRefreshClick = viewModel::onRefreshClick,
        modifier = modifier
    )
}

@Composable
private fun Content(
    uiState: ReceiveUiState,
    isAmbient: Boolean,
    onRefreshClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val qrContent = uiState.qrContent
    val paidAmount = uiState.paidAmount
    when {
        paidAmount != null -> Paid(amount = paidAmount, onClick = onRefreshClick, modifier = modifier)
        qrContent != null && isAmbient -> Hidden(modifier = modifier)
        qrContent != null -> Qr(content = qrContent, modifier = modifier)
        uiState.isError -> Error(onRetryClick = onRefreshClick, modifier = modifier)
        else -> CenteredScreen(
            overlay = { BezelLoading() },
            modifier = modifier
        ) {
            QrIcon(modifier = Modifier.testTag("WatchReceiveLoading"))
        }
    }
}

@Composable
private fun Qr(
    content: String,
    modifier: Modifier = Modifier,
) {
    ScreenAwake(isBright = true)
    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxSize()
    ) {
        QrCode(
            content = content,
            contentDescription = stringResource(R.string.receive__title),
            modifier = Modifier
                .size(minOf(maxWidth, maxHeight) * QR_SIZE_FRACTION)
                .testTag("WatchReceiveQr")
        )
    }
}

@Composable
private fun Hidden(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.receive__hidden)
    CenteredScreen(modifier = modifier) {
        QrIcon(
            modifier = Modifier
                .semantics { contentDescription = description }
                .testTag("WatchReceiveHidden")
        )
    }
}

@Composable
private fun QrIcon(modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(R.drawable.ic_qr_code),
        contentDescription = null,
        tint = Colors.White.copy(alpha = HIDDEN_ALPHA),
        modifier = modifier.size(MARK_SIZE)
    )
}

@Composable
private fun Paid(
    amount: DisplayAmount,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredScreen(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .clickableAlpha(onClickLabel = stringResource(R.string.receive__new), onClick = onClick)
                .testTag("WatchReceivePaid")
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = stringResource(R.string.receive__paid),
                tint = Colors.Brand,
                modifier = Modifier.size(MARK_SIZE)
            )
            SmallAmountRow(amount = amount, amountTag = "WatchReceivePaidAmount")
        }
    }
}

@Composable
private fun Error(
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredScreen(modifier = modifier) {
        Text(
            text = stringResource(R.string.receive__error),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("WatchReceiveError")
        )
        FilledTonalIconButton(
            onClick = onRetryClick,
            shapes = IconButtonDefaults.animatedShapes(),
            modifier = Modifier.testTag("WatchReceiveRetry")
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_refresh),
                contentDescription = stringResource(R.string.common__retry),
            )
        }
    }
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        Content(
            uiState = ReceiveUiState(qrContent = PREVIEW_INVOICE),
            isAmbient = false,
            onRefreshClick = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun PreviewPaid() {
    WearPreview {
        Content(
            uiState = ReceiveUiState(paidAmount = DisplayAmount(symbol = "₿", text = "21 000")),
            isAmbient = false,
            onRefreshClick = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun PreviewAmbient() {
    WearPreview {
        Content(
            uiState = ReceiveUiState(qrContent = PREVIEW_INVOICE),
            isAmbient = true,
            onRefreshClick = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun PreviewError() {
    WearPreview {
        Content(
            uiState = ReceiveUiState(isError = true),
            isAmbient = false,
            onRefreshClick = {},
        )
    }
}

private const val PREVIEW_INVOICE = "LNBCRT1P5EXAMPLEPP5QQQSYQCYQ5RQWZQFQQQSYQCYQ5RQWZQFQQQSYQCYQ5RQWZQFQYPQDQ0XQRRSS"

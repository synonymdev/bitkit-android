package to.bitkit.ui.screens.transfer.hardware

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synonym.bitkitcore.IBtOrder
import to.bitkit.R
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FeeInfo
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.HardwareTransferIllustration
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SIGN_VISUAL_TOP_RATIO
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.transfer.previewBtOrder
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.TransferViewModel

@Composable
fun SpendingHwSignScreen(
    deviceId: String,
    viewModel: TransferViewModel,
    onBackClick: () -> Unit,
    onCloseClick: () -> Unit,
    onLearnMoreClick: () -> Unit,
    onAdvancedClick: () -> Unit,
) {
    val state by viewModel.spendingUiState.collectAsStateWithLifecycle()

    val order = state.order ?: run {
        onCloseClick()
        return
    }

    LaunchedEffect(deviceId) {
        viewModel.warmUpHardwareConnection(deviceId)
    }

    DisposableEffect(viewModel) {
        onDispose(viewModel::cancelHardwareTransfer)
    }

    Content(
        order = order,
        isAdvanced = state.isAdvanced,
        isSigning = state.isSigning,
        hasPendingBroadcast = state.hasPendingHwBroadcast,
        onBackClick = onBackClick,
        onLearnMoreClick = onLearnMoreClick,
        onAdvancedClick = onAdvancedClick,
        onUseDefaultLspBalanceClick = viewModel::onUseDefaultLspBalanceClick,
        onOpenConnect = { viewModel.onTransferToSpendingHwConfirm(order, deviceId) },
    )
}

@Composable
private fun Content(
    order: IBtOrder,
    isAdvanced: Boolean = false,
    isSigning: Boolean = false,
    hasPendingBroadcast: Boolean = false,
    onBackClick: () -> Unit = {},
    onLearnMoreClick: () -> Unit = {},
    onAdvancedClick: () -> Unit = {},
    onUseDefaultLspBalanceClick: () -> Unit = {},
    onOpenConnect: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__transfer__nav_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )
        Box(modifier = Modifier.fillMaxSize()) {
            HardwareTransferIllustration(
                drawableRes = R.drawable.trezor,
                topRatio = SIGN_VISUAL_TOP_RATIO,
            )

            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxSize()
                    .testTag("HardwareTransferSign")
            ) {
                VerticalSpacer(32.dp)
                Display(
                    text = stringResource(
                        if (hasPendingBroadcast) {
                            R.string.lightning__transfer_hw__signed_title
                        } else {
                            R.string.lightning__transfer_hw__sign_title
                        }
                    )
                        .withAccent(accentColor = Colors.Purple),
                    modifier = Modifier.fillMaxWidth()
                )
                VerticalSpacer(16.dp)

                SpendingHwFeeGrid(order = order)

                VerticalSpacer(24.dp)

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PrimaryButton(
                        text = stringResource(R.string.common__learn_more),
                        size = ButtonSize.Small,
                        fullWidth = false,
                        enabled = !isSigning && !hasPendingBroadcast,
                        onClick = onLearnMoreClick,
                        modifier = Modifier.testTag("HardwareTransferSignLearnMore")
                    )
                    PrimaryButton(
                        text = stringResource(
                            if (isAdvanced) R.string.lightning__spending_confirm__default else R.string.common__advanced
                        ),
                        size = ButtonSize.Small,
                        fullWidth = false,
                        enabled = !isSigning && !hasPendingBroadcast,
                        onClick = { if (isAdvanced) onUseDefaultLspBalanceClick() else onAdvancedClick() },
                        modifier = Modifier.testTag(
                            if (isAdvanced) "HardwareTransferSignDefault" else "HardwareTransferSignAdvanced"
                        )
                    )
                }

                FillHeight()

                PrimaryButton(
                    text = stringResource(
                        if (hasPendingBroadcast) {
                            R.string.common__retry
                        } else {
                            R.string.lightning__transfer_hw__open_connect
                        }
                    ),
                    onClick = onOpenConnect,
                    enabled = !isSigning,
                    isLoading = isSigning,
                    modifier = Modifier.testTag("HardwareTransferOpenTrezorConnect")
                )
                VerticalSpacer(16.dp)
            }
        }
    }
}

@Composable
internal fun SpendingHwFeeGrid(
    order: IBtOrder,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.height(IntrinsicSize.Min)
        ) {
            FeeInfo(
                label = stringResource(R.string.lightning__spending_confirm__network_fee),
                amount = order.networkFeeSat.toLong(),
            )
            FeeInfo(
                label = stringResource(R.string.lightning__spending_confirm__lsp_fee),
                amount = order.serviceFeeSat.toLong(),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.height(IntrinsicSize.Min)
        ) {
            FeeInfo(
                label = stringResource(R.string.lightning__spending_confirm__amount),
                amount = order.clientBalanceSat.toLong(),
            )
            FeeInfo(
                label = stringResource(R.string.lightning__spending_confirm__total),
                amount = order.feeSat.toLong(),
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            order = previewBtOrder(),
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewAdvanced() {
    AppThemeSurface {
        Content(
            order = previewBtOrder(),
            isAdvanced = true,
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewSigning() {
    AppThemeSurface {
        Content(
            order = previewBtOrder(),
            isSigning = true,
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewPendingBroadcast() {
    AppThemeSurface {
        Content(
            order = previewBtOrder(),
            hasPendingBroadcast = true,
        )
    }
}

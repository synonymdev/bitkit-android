package to.bitkit.ui.settings.general

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import to.bitkit.R
import to.bitkit.models.HwWallet
import to.bitkit.models.TransportType
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.HwDeviceIllustrations
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.wallets.HwWalletViewModel
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

private const val ILLUSTRATIONS_HEIGHT_FRACTION = 0.8f

@Composable
fun HardwareWalletsSettingsScreen(
    navController: NavController,
    onClickAdd: () -> Unit,
    viewModel: HwWalletViewModel = hiltViewModel(),
) {
    val wallets by viewModel.wallets.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Content(
        wallets = wallets,
        isPendingRemoval = uiState.isPendingRemoval,
        onBack = { navController.popBackStack() },
        onClickAdd = onClickAdd,
        onRemoveClick = viewModel::onRemoveClick,
        onConfirmRemove = { viewModel.removeDevice(it.id) },
        onDismissRemoveDialog = viewModel::onDismissRemoveDialog,
    )
}

@Composable
private fun Content(
    wallets: ImmutableList<HwWallet>,
    isPendingRemoval: HwWallet?,
    onBack: () -> Unit = {},
    onClickAdd: () -> Unit = {},
    onRemoveClick: (HwWallet) -> Unit = {},
    onConfirmRemove: (HwWallet) -> Unit = {},
    onDismissRemoveDialog: () -> Unit = {},
) {
    ScreenColumn(
        modifier = Modifier.testTag("HardwareWalletsScreen")
    ) {
        AppTopBar(
            titleText = stringResource(R.string.settings__hardware_wallets__nav_title),
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            HwDeviceIllustrations(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(ILLUSTRATIONS_HEIGHT_FRACTION)
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                if (wallets.isEmpty()) {
                    EmptyState(modifier = Modifier.weight(1f))
                } else {
                    wallets.forEach { wallet ->
                        HwWalletRow(wallet = wallet, onRemoveClick = onRemoveClick)
                        HorizontalDivider(color = Colors.White10)
                    }
                    FillHeight()
                }

                PrimaryButton(
                    text = stringResource(R.string.settings__hardware_wallets__add_button),
                    onClick = onClickAdd,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("AddHardwareWallet")
                )
                VerticalSpacer(16.dp)
            }
        }
    }

    isPendingRemoval?.let { wallet ->
        AppAlertDialog(
            title = stringResource(R.string.hardware__remove_dialog_title, wallet.name),
            text = stringResource(R.string.hardware__remove_dialog_text),
            confirmText = stringResource(R.string.common__remove),
            dismissText = stringResource(R.string.common__cancel),
            onConfirm = { onConfirmRemove(wallet) },
            onDismiss = onDismissRemoveDialog,
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxWidth()
    ) {
        Display(text = stringResource(R.string.settings__hardware_wallets__nav_title))
        VerticalSpacer(8.dp)
        BodyM(
            text = stringResource(R.string.settings__hardware_wallets__empty_text),
            color = Colors.White64,
        )
    }
}

@Composable
private fun HwWalletRow(
    wallet: HwWallet,
    onRemoveClick: (HwWallet) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag("HardwareWalletRow")
    ) {
        HwConnectionBadge(transportType = wallet.transportType, isConnected = wallet.isConnected)
        HorizontalSpacer(12.dp)
        BodyM(
            text = wallet.name,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        HorizontalSpacer(8.dp)
        MoneySSB(
            sats = wallet.balanceSats.toLong(),
            color = Colors.White64,
            accent = Colors.White64,
            showSymbol = true,
        )
        IconButton(
            onClick = { onRemoveClick(wallet) },
            modifier = Modifier.testTag("HardwareWalletRowDelete")
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_trash),
                contentDescription = stringResource(R.string.common__remove),
                tint = Colors.White64,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun HwConnectionBadge(
    transportType: TransportType,
    isConnected: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (isConnected) Colors.Green16 else Colors.White16)
    ) {
        Icon(
            painter = painterResource(
                id = when (transportType) {
                    TransportType.BLUETOOTH -> R.drawable.ic_bluetooth_connected
                    TransportType.USB -> R.drawable.ic_usb_connected
                }
            ),
            contentDescription = null,
            tint = if (isConnected) Colors.Green else Colors.White64,
            modifier = Modifier.size(16.dp)
        )
    }
}

private fun previewWallet(
    id: String = "dev1",
    name: String = "Trezor Safe 3",
    transportType: TransportType = TransportType.BLUETOOTH,
    isConnected: Boolean = true,
    balanceSats: ULong = 10_562_411uL,
) = HwWallet(
    id = id,
    name = name,
    model = name.removePrefix("Trezor ").ifEmpty { null },
    transportType = transportType,
    isConnected = isConnected,
    balanceSats = balanceSats,
    activities = persistentListOf(),
)

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            wallets = listOf(
                previewWallet(),
                previewWallet(
                    id = "dev2",
                    name = "Ledger Nano X",
                    transportType = TransportType.USB,
                    isConnected = false,
                    balanceSats = 2_735_180uL,
                ),
            ).toImmutableList(),
            isPendingRemoval = null,
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewEmpty() {
    AppThemeSurface {
        Content(
            wallets = persistentListOf(),
            isPendingRemoval = null,
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewRemoveDialog() {
    AppThemeSurface {
        Content(
            wallets = persistentListOf(previewWallet()),
            isPendingRemoval = previewWallet(),
        )
    }
}

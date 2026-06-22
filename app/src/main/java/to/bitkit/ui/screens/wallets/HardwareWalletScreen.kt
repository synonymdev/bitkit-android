package to.bitkit.ui.screens.wallets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synonym.bitkitcore.Activity
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableSet
import to.bitkit.R
import to.bitkit.ext.rawId
import to.bitkit.models.HwWallet
import to.bitkit.models.TransportType
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.StatusBarSpacer
import to.bitkit.ui.components.TabBar
import to.bitkit.ui.components.TertiaryButton
import to.bitkit.ui.components.TopBarSpacer
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.screens.wallets.activity.components.activityListGroupedItems
import to.bitkit.ui.screens.wallets.activity.utils.previewOnchainActivityItems
import to.bitkit.ui.shared.util.blockPointerInputPassthrough
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.TopBarGradient

@Composable
fun HardwareWalletScreen(
    deviceId: String,
    onActivityItemClick: (String) -> Unit,
    onTransferToSpendingClick: () -> Unit,
    onBackClick: () -> Unit,
    viewModel: HwWalletViewModel = hiltViewModel(),
) {
    val wallets by viewModel.wallets.collectAsStateWithLifecycle()
    val walletsLoaded by viewModel.walletsLoaded.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val wallet = remember(wallets, deviceId) { wallets.find { deviceId in it.deviceIds } }

    // Leave the screen once the device is gone, whether removed here or forgotten elsewhere.
    LaunchedEffect(wallet, walletsLoaded) {
        if (walletsLoaded && wallet == null) onBackClick()
    }

    wallet?.let { device ->
        HardwareWalletContent(
            wallet = device,
            showRemoveDialog = uiState.isPendingRemoval != null,
            onActivityItemClick = onActivityItemClick,
            onTransferToSpendingClick = onTransferToSpendingClick,
            onRemoveClick = { viewModel.onRemoveClick(device) },
            onConfirmRemove = { viewModel.removeDevice(deviceId) },
            onDismissRemoveDialog = viewModel::onDismissRemoveDialog,
            onBackClick = onBackClick,
        )
    }
}

@Composable
private fun HardwareWalletContent(
    wallet: HwWallet,
    showRemoveDialog: Boolean,
    onActivityItemClick: (String) -> Unit,
    onTransferToSpendingClick: () -> Unit,
    onRemoveClick: () -> Unit,
    onConfirmRemove: () -> Unit,
    onDismissRemoveDialog: () -> Unit,
    onBackClick: () -> Unit,
) {
    val hasFunds = wallet.balanceSats > 0uL
    val hasActivity = wallet.activities.isNotEmpty()
    val showEmptyState = !hasFunds && !hasActivity

    // Every activity here belongs to the watch-only device, so render them all with the blue
    // hardware icon, matching the home list.
    val hardwareIds = remember(wallet.activities) { wallet.activities.map { it.rawId() }.toImmutableSet() }

    val hazeState = rememberHazeState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .blockPointerInputPassthrough()
            .testTag("HardwareWalletScreen")
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Colors.Black)
                .hazeSource(hazeState)
        ) {
            Image(
                painter = painterResource(id = R.drawable.trezor),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 119.dp, y = 92.dp)
                    .size(256.dp)
            )
        }

        AppTopBar(
            titleText = wallet.name,
            icon = R.drawable.ic_btc_circle_blue,
            onBackClick = onBackClick,
            actions = {
                DrawerNavIcon()
            },
            modifier = Modifier
                .hazeEffect(state = hazeState) {
                    mask = TopBarGradient
                }
                .background(TopBarGradient)
                .zIndex(1f)
                .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Vertical))
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            item { StatusBarSpacer() }
            item { TopBarSpacer() }
            item {
                BalanceHeaderView(
                    sats = wallet.balanceSats.toLong(),
                    testTag = "TotalBalance",
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (!showEmptyState) {
                item { VerticalSpacer(32.dp) }

                if (hasFunds) {
                    item {
                        SecondaryButton(
                            onClick = onTransferToSpendingClick,
                            text = stringResource(R.string.wallet__transfer_to_spending),
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_transfer),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            hazeState = hazeState,
                            modifier = Modifier.testTag("HardwareTransferToSpending")
                        )
                    }
                }

                activityListGroupedItems(
                    items = wallet.activities,
                    onActivityItemClick = onActivityItemClick,
                    onEmptyActivityRowClick = {},
                    showFooter = false,
                    hardwareIds = hardwareIds,
                    footerContent = {
                        RemoveHardwareWalletButton(
                            walletName = wallet.name,
                            onClick = onRemoveClick,
                        )
                    },
                )
            }
            if (showEmptyState) {
                item { VerticalSpacer(32.dp) }
                item {
                    RemoveHardwareWalletButton(
                        walletName = wallet.name,
                        onClick = onRemoveClick,
                    )
                }
                item { VerticalSpacer(120.dp) }
            }
        }

        if (showRemoveDialog) {
            AppAlertDialog(
                title = stringResource(R.string.hardware__remove_dialog_title, wallet.name),
                text = stringResource(R.string.hardware__remove_dialog_text),
                confirmText = stringResource(R.string.common__remove),
                dismissText = stringResource(R.string.common__cancel),
                onConfirm = onConfirmRemove,
                onDismiss = onDismissRemoveDialog,
            )
        }
    }
}

@Composable
private fun RemoveHardwareWalletButton(
    walletName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TertiaryButton(
        text = stringResource(R.string.hardware__remove_button, walletName),
        onClick = onClick,
        modifier = modifier
            .wrapContentWidth()
            .padding(top = 8.dp)
            .testTag("RemoveHardwareWallet")
    )
}

private fun previewWallet(
    balanceSats: ULong = 10_562_411uL,
    activities: ImmutableList<Activity> = previewOnchainActivityItems(),
) = HwWallet(
    id = "dev1",
    name = "Trezor Safe 3",
    model = "Safe 3",
    transportType = TransportType.USB,
    isConnected = true,
    balanceSats = balanceSats,
    activities = activities,
)

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Box {
            HardwareWalletContent(
                wallet = previewWallet(),
                showRemoveDialog = false,
                onActivityItemClick = {},
                onTransferToSpendingClick = {},
                onRemoveClick = {},
                onConfirmRemove = {},
                onDismissRemoveDialog = {},
                onBackClick = {},
            )
            TabBar()
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewNoActivity() {
    AppThemeSurface {
        Box {
            HardwareWalletContent(
                wallet = previewWallet(activities = persistentListOf()),
                showRemoveDialog = false,
                onActivityItemClick = {},
                onTransferToSpendingClick = {},
                onRemoveClick = {},
                onConfirmRemove = {},
                onDismissRemoveDialog = {},
                onBackClick = {},
            )
            TabBar()
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewEmpty() {
    AppThemeSurface {
        Box {
            HardwareWalletContent(
                wallet = previewWallet(balanceSats = 0uL, activities = persistentListOf()),
                showRemoveDialog = false,
                onActivityItemClick = {},
                onTransferToSpendingClick = {},
                onRemoveClick = {},
                onConfirmRemove = {},
                onDismissRemoveDialog = {},
                onBackClick = {},
            )
            TabBar()
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewRemoveDialog() {
    AppThemeSurface {
        Box {
            HardwareWalletContent(
                wallet = previewWallet(),
                showRemoveDialog = true,
                onActivityItemClick = {},
                onTransferToSpendingClick = {},
                onRemoveClick = {},
                onConfirmRemove = {},
                onDismissRemoveDialog = {},
                onBackClick = {},
            )
            TabBar()
        }
    }
}

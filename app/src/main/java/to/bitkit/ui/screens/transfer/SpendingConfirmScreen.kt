package to.bitkit.ui.screens.transfer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.safe
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.ChannelStatusUi
import to.bitkit.ui.components.ConnectionIssuesView
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FeeInfo
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.LightningChannel
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SwipeToConfirm
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SettingsSwitchRow
import to.bitkit.ui.openNotificationSettings
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppSwitchDefaults
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.RequestNotificationPermissions
import to.bitkit.ui.utils.rememberNotificationToggleClick
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.SettingsViewModel
import to.bitkit.viewmodels.TransferToSpendingUiState
import to.bitkit.viewmodels.TransferViewModel

@Composable
fun SpendingConfirmScreen(
    viewModel: TransferViewModel,
    isOffline: Boolean,
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
    onLearnMoreClick: () -> Unit = {},
    onAdvancedClick: () -> Unit = {},
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    val state by viewModel.spendingUiState.collectAsStateWithLifecycle()

    if (state.feeSat == 0uL) {
        onCloseClick()
        return
    }
    val isAdvanced = state.isAdvanced
    val miningFeeSats = state.miningFeeSats
    val isConfirmFeeReady = state.isConfirmFeeReady
    val isConfirmPaying = state.isBusy

    BackHandler(enabled = state.isBusy) {}

    LaunchedEffect(state.feeSat) {
        viewModel.prepareSpendingConfirmFunding()
    }

    val notificationsGranted by settingsViewModel.notificationsGranted.collectAsStateWithLifecycle()

    RequestNotificationPermissions(
        onPermissionChange = { granted ->
            settingsViewModel.setNotificationPreference(granted)
        },
        showPermissionDialog = false,
    )

    val onNotificationSwitchClick = rememberNotificationToggleClick(
        isGranted = notificationsGranted,
        onPermissionResult = { granted -> settingsViewModel.setNotificationPreference(granted) },
        onOpenSystemSettings = { context.openNotificationSettings() },
    )

    Box {
        Content(
            onBackClick = { if (!state.isBusy) onBackClick() },
            onLearnMoreClick = { if (!state.isBusy) onLearnMoreClick() },
            onAdvancedClick = { if (!state.isBusy) onAdvancedClick() },
            onUseDefaultLspBalanceClick = viewModel::onUseDefaultLspBalanceClick,
            onTransferToSpendingConfirm = viewModel::onTransferToSpendingConfirm,
            state = state,
            miningFeeSats = miningFeeSats,
            isConfirmFeeReady = isConfirmFeeReady,
            isConfirmPaying = isConfirmPaying,
            hasNotificationPermission = notificationsGranted,
            onSwitchClick = onNotificationSwitchClick,
            isAdvanced = isAdvanced,
        )
        AnimatedVisibility(
            visible = isOffline,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ConnectionIssuesView(
                titleText = stringResource(R.string.lightning__transfer__nav_title),
                modifier = Modifier.statusBarsPadding()
            )
        }
    }
}

@Suppress("MagicNumber", "LongMethod")
@Composable
private fun Content(
    onBackClick: () -> Unit,
    onLearnMoreClick: () -> Unit,
    onAdvancedClick: () -> Unit,
    onUseDefaultLspBalanceClick: () -> Unit,
    onSwitchClick: () -> Unit,
    hasNotificationPermission: Boolean,
    onTransferToSpendingConfirm: () -> Unit,
    state: TransferToSpendingUiState,
    miningFeeSats: ULong,
    isConfirmFeeReady: Boolean,
    isConfirmPaying: Boolean,
    isAdvanced: Boolean,
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__transfer__nav_title),
            onBackClick = onBackClick,
            actions = { if (!isConfirmPaying) DrawerNavIcon() },
        )
        Box(modifier = Modifier.fillMaxSize()) {
            if (!isAdvanced) {
                Image(
                    painter = painterResource(id = R.drawable.coin_stack_x),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 60.dp)
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 76.dp)
                )
            }

            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                val clientBalance = state.clientBalanceSat
                val lspFee = state.feeSat.safe() - clientBalance.safe()
                val total = state.feeSat.safe() + miningFeeSats.safe()
                val lspBalance = state.lspBalanceSat

                VerticalSpacer(32.dp)
                Display(stringResource(R.string.lightning__transfer__confirm).withAccent(accentColor = Colors.Purple))
                VerticalSpacer(8.dp)

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.height(IntrinsicSize.Min)
                ) {
                    FeeInfo(
                        label = stringResource(R.string.lightning__spending_confirm__network_fee),
                        amount = miningFeeSats.toLong(),
                    )
                    FeeInfo(
                        label = stringResource(R.string.lightning__spending_confirm__lsp_fee),
                        amount = lspFee.toLong(),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.height(IntrinsicSize.Min)
                ) {
                    FeeInfo(
                        label = stringResource(R.string.lightning__spending_confirm__amount),
                        amount = clientBalance.toLong(),
                    )
                    FeeInfo(
                        label = stringResource(R.string.lightning__spending_confirm__total),
                        amount = total.toLong(),
                    )
                }

                if (isAdvanced) {
                    VerticalSpacer(16.dp)
                    LightningChannel(
                        capacity = (clientBalance + lspBalance).toLong(),
                        localBalance = clientBalance.toLong(),
                        remoteBalance = lspBalance.toLong(),
                        status = ChannelStatusUi.OPEN,
                        showLabels = true,
                        modifier = Modifier.testTag("SpendingConfirmChannel")
                    )

                    VerticalSpacer(16.dp)
                }

                SettingsSwitchRow(
                    title = stringResource(R.string.settings__bg__setup),
                    isChecked = hasNotificationPermission,
                    colors = AppSwitchDefaults.colorsPurple,
                    onClick = onSwitchClick,
                    switchTestTag = "SpendingConfirmNotificationSwitch",
                    modifier = Modifier.fillMaxWidth()
                )

                VerticalSpacer(31.dp)

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PrimaryButton(
                        text = stringResource(R.string.common__learn_more),
                        size = ButtonSize.Small,
                        fullWidth = false,
                        onClick = onLearnMoreClick,
                        modifier = Modifier.testTag("SpendingConfirmMore")
                    )
                    PrimaryButton(
                        text = stringResource(
                            if (isAdvanced) R.string.lightning__spending_confirm__default else R.string.common__advanced
                        ),
                        size = ButtonSize.Small,
                        fullWidth = false,
                        onClick = {
                            if (isAdvanced) {
                                onUseDefaultLspBalanceClick()
                            } else {
                                onAdvancedClick()
                            }
                        },
                        modifier = Modifier.testTag(
                            if (isAdvanced) "SpendingConfirmDefault" else "SpendingConfirmAdvanced"
                        )
                    )
                }
                VerticalSpacer(16.dp)

                FillHeight()

                val canConfirm = isConfirmFeeReady && miningFeeSats > 0uL && !isConfirmPaying
                SwipeToConfirm(
                    text = stringResource(R.string.lightning__transfer__swipe),
                    loading = isConfirmPaying || !isConfirmFeeReady,
                    color = Colors.Purple,
                    onConfirm = {
                        if (!canConfirm) return@SwipeToConfirm
                        onTransferToSpendingConfirm()
                    },
                )
                VerticalSpacer(16.dp)
            }
        }
    }
}

@Preview(showSystemUi = true, showBackground = true, name = "Normal screen - Default")
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            onBackClick = {},
            onLearnMoreClick = {},
            onAdvancedClick = {},
            onUseDefaultLspBalanceClick = {},
            onTransferToSpendingConfirm = {},
            state = TransferToSpendingUiState(
                clientBalanceSat = 500_000uL,
                lspBalanceSat = 2_000_000uL,
                feeSat = 1_000uL,

            ),
            onSwitchClick = {},
            hasNotificationPermission = true,
            miningFeeSats = 250uL,
            isConfirmFeeReady = true,
            isConfirmPaying = false,
            isAdvanced = false
        )
    }
}

@Preview(showSystemUi = true, showBackground = true, name = "Normal screen - Advanced")
@Composable
private fun Preview2() {
    AppThemeSurface {
        Content(
            onBackClick = {},
            onLearnMoreClick = {},
            onAdvancedClick = {},
            onUseDefaultLspBalanceClick = {},
            onTransferToSpendingConfirm = {},
            state = TransferToSpendingUiState(
                clientBalanceSat = 500_000uL,
                lspBalanceSat = 2_000_000uL,
                feeSat = 1_000uL,

            ),
            onSwitchClick = {},
            hasNotificationPermission = true,
            miningFeeSats = 250uL,
            isConfirmFeeReady = true,
            isConfirmPaying = false,
            isAdvanced = true
        )
    }
}

@Preview(showSystemUi = true, showBackground = true, heightDp = 700, name = "Small screen - Normal")
@Composable
private fun Preview3() {
    AppThemeSurface {
        Content(
            onBackClick = {},
            onLearnMoreClick = {},
            onAdvancedClick = {},
            onUseDefaultLspBalanceClick = {},
            onTransferToSpendingConfirm = {},
            state = TransferToSpendingUiState(
                clientBalanceSat = 500_000uL,
                lspBalanceSat = 2_000_000uL,
                feeSat = 1_000uL,

            ),
            onSwitchClick = {},
            hasNotificationPermission = false,
            miningFeeSats = 250uL,
            isConfirmFeeReady = true,
            isConfirmPaying = false,
            isAdvanced = false
        )
    }
}

@Preview(showSystemUi = true, showBackground = true, heightDp = 700, name = "Small screen - Advanced")
@Composable
private fun Preview4() {
    AppThemeSurface {
        Content(
            onBackClick = {},
            onLearnMoreClick = {},
            onAdvancedClick = {},
            onUseDefaultLspBalanceClick = {},
            onTransferToSpendingConfirm = {},
            state = TransferToSpendingUiState(
                clientBalanceSat = 500_000uL,
                lspBalanceSat = 2_000_000uL,
                feeSat = 1_000uL,

            ),
            onSwitchClick = {},
            hasNotificationPermission = true,
            miningFeeSats = 250uL,
            isConfirmFeeReady = true,
            isConfirmPaying = false,
            isAdvanced = true
        )
    }
}

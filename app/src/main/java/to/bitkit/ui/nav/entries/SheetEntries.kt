package to.bitkit.ui.nav.entries

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.ui.LocalBalances
import to.bitkit.ui.nav.Navigator
import to.bitkit.ui.nav.Routes
import to.bitkit.ui.nav.SheetSceneStrategy
import to.bitkit.ui.screens.scanner.QrScanningScreen
import to.bitkit.ui.screens.wallets.activity.DateRangeSelectorContent
import to.bitkit.ui.screens.wallets.activity.TagSelectorContent
import to.bitkit.ui.screens.wallets.receive.EditInvoiceScreen
import to.bitkit.ui.screens.wallets.receive.LocationBlockScreen
import to.bitkit.ui.screens.wallets.receive.ReceiveAmountScreen
import to.bitkit.ui.screens.wallets.receive.ReceiveConfirmScreen
import to.bitkit.ui.screens.wallets.receive.ReceiveLiquidityScreen
import to.bitkit.ui.screens.wallets.receive.ReceiveQrScreen
import to.bitkit.ui.screens.wallets.send.AddTagScreen
import to.bitkit.ui.screens.wallets.send.SendAddressScreen
import to.bitkit.ui.screens.wallets.send.SendAmountScreen
import to.bitkit.ui.screens.wallets.send.SendCoinSelectionScreen
import to.bitkit.ui.screens.wallets.send.SendConfirmScreen
import to.bitkit.ui.screens.wallets.send.SendErrorScreen
import to.bitkit.ui.screens.wallets.send.SendFeeCustomScreen
import to.bitkit.ui.screens.wallets.send.SendFeeRateScreen
import to.bitkit.ui.screens.wallets.send.SendFeeViewModel
import to.bitkit.ui.screens.wallets.send.SendPinCheckScreen
import to.bitkit.ui.screens.wallets.send.SendQuickPayScreen
import to.bitkit.ui.screens.wallets.send.SendRecipientScreen
import to.bitkit.ui.screens.wallets.withdraw.WithdrawConfirmScreen
import to.bitkit.ui.screens.wallets.withdraw.WithdrawErrorScreen
import to.bitkit.ui.settings.backups.BackupIntroScreen
import to.bitkit.ui.settings.backups.BackupNavSheetViewModel
import to.bitkit.ui.settings.backups.ConfirmMnemonicScreen
import to.bitkit.ui.settings.backups.ConfirmPassphraseScreen
import to.bitkit.ui.settings.backups.MetadataScreen
import to.bitkit.ui.settings.backups.MultipleDevicesScreen
import to.bitkit.ui.settings.backups.ShowMnemonicScreen
import to.bitkit.ui.settings.backups.ShowPassphraseScreen
import to.bitkit.ui.settings.backups.SuccessScreen
import to.bitkit.ui.settings.backups.WarningScreen
import to.bitkit.ui.settings.pin.PinBiometricsScreen
import to.bitkit.ui.settings.pin.PinChooseScreen
import to.bitkit.ui.settings.pin.PinConfirmScreen
import to.bitkit.ui.settings.pin.PinPromptScreen
import to.bitkit.ui.settings.pin.PinResultScreen
import to.bitkit.ui.settings.support.SupportScreen
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.sheets.BackgroundPaymentsIntroSheet
import to.bitkit.ui.sheets.ForceTransferContent
import to.bitkit.ui.sheets.GiftErrorSheet
import to.bitkit.ui.sheets.GiftLoading
import to.bitkit.ui.sheets.GiftViewModel
import to.bitkit.ui.sheets.HighBalanceWarningSheet
import to.bitkit.ui.sheets.LnurlAuthContent
import to.bitkit.ui.sheets.NewTransactionSheetView
import to.bitkit.ui.sheets.QuickPayIntroSheet
import to.bitkit.ui.sheets.UpdateSheet
import to.bitkit.ui.utils.NotificationUtils
import to.bitkit.viewmodels.ActivityListViewModel
import to.bitkit.viewmodels.AmountInputViewModel
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.SendEvent
import to.bitkit.viewmodels.SettingsViewModel
import to.bitkit.viewmodels.TransferViewModel
import to.bitkit.viewmodels.WalletViewModel

/**
 * Sheet flow entry providers for Navigation 3.
 * These handle flows that were previously rendered as bottom sheets with internal navigation.
 */
@Suppress("LongMethod", "LongParameterList")
fun EntryProviderScope<NavKey>.sheetEntries(
    navigator: Navigator,
    appViewModel: AppViewModel,
    walletViewModel: WalletViewModel,
    activityListViewModel: ActivityListViewModel,
    transferViewModel: TransferViewModel,
) {
    // Simple sheet entries
    simpleSheetEntries(navigator, appViewModel, activityListViewModel, transferViewModel)

    // Pin flow entries
    pinFlowEntries(navigator)

    // Backup flow entries
    backupFlowEntries(navigator)

    // Send flow entries
    sendFlowEntries(navigator, appViewModel, walletViewModel)

    // Receive flow entries
    receiveFlowEntries(navigator, walletViewModel)

    // Gift flow entries
    giftFlowEntries(navigator, appViewModel)

    // Timed sheet entries
    timedSheetEntries(navigator, appViewModel)
}

/**
 * Simple sheets that don't have internal navigation.
 */
@Suppress("LongParameterList", "LongMethod")
private fun EntryProviderScope<NavKey>.simpleSheetEntries(
    navigator: Navigator,
    appViewModel: AppViewModel,
    activityListViewModel: ActivityListViewModel,
    transferViewModel: TransferViewModel,
) {
    entry<Routes.ActivityDateRangeSelectorSheet>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        val startDate by activityListViewModel.startDate.collectAsStateWithLifecycle()
        val endDate by activityListViewModel.endDate.collectAsStateWithLifecycle()

        DateRangeSelectorContent(
            initialStartDate = startDate,
            initialEndDate = endDate,
            onClearClick = { activityListViewModel.clearDateRange() },
            onApplyClick = { start, end ->
                activityListViewModel.setDateRange(startDate = start, endDate = end)
                navigator.goBack()
            },
        )
    }

    entry<Routes.ActivityTagSelectorSheet>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        val availableTags by activityListViewModel.availableTags.collectAsStateWithLifecycle()
        val selectedTags by activityListViewModel.selectedTags.collectAsStateWithLifecycle()

        TagSelectorContent(
            availableTags = availableTags,
            selectedTags = selectedTags,
            onTagClick = {
                activityListViewModel.toggleTag(it)
                navigator.goBack()
            },
        )
    }

    entry<Routes.LnurlAuthSheet>(
        metadata = SheetSceneStrategy.sheet()
    ) { route ->
        LnurlAuthContent(
            domain = route.domain,
            onContinue = {
                appViewModel.requestLnurlAuth(
                    callback = route.lnurl,
                    k1 = route.k1,
                    domain = route.domain,
                )
            },
            onCancel = { navigator.goBack() },
        )
    }

    entry<Routes.ForceTransferSheet>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        val isLoading by transferViewModel.isForceTransferLoading.collectAsStateWithLifecycle()

        ForceTransferContent(
            isLoading = isLoading,
            onForceTransfer = {
                transferViewModel.forceTransfer {
                    navigator.goBack()
                }
            },
            onCancel = { navigator.goBack() },
        )
    }
}

/**
 * Pin setup flow entries.
 */
private fun EntryProviderScope<NavKey>.pinFlowEntries(navigator: Navigator) {
    entry<Routes.PinPrompt>(
        metadata = SheetSceneStrategy.sheet()
    ) { route ->
        PinPromptScreen(
            showLaterButton = route.showLaterButton,
            onContinue = { navigator.navigate(Routes.PinChoose) },
            onLater = { navigator.goBack() },
        )
    }

    entry<Routes.PinChoose>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        PinChooseScreen(
            onPinChosen = { pin -> navigator.navigate(Routes.PinConfirm(pin)) },
            onBack = { navigator.goBack() },
        )
    }

    entry<Routes.PinConfirm>(
        metadata = SheetSceneStrategy.sheet()
    ) { route ->
        PinConfirmScreen(
            originalPin = route.pin,
            onPinConfirmed = { navigator.navigate(Routes.PinBiometrics) },
            onBack = { navigator.goBack() },
        )
    }

    entry<Routes.PinBiometrics>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        PinBiometricsScreen(
            onContinue = { isBioOn -> navigator.navigate(Routes.PinResult(isBioOn)) },
            onSkip = { navigator.navigate(Routes.PinResult(isBioOn = false)) },
            onBack = { navigator.goBack() },
        )
    }

    entry<Routes.PinResult>(
        metadata = SheetSceneStrategy.sheet()
    ) { route ->
        PinResultScreen(
            isBioOn = route.isBioOn,
            onDismiss = { navigator.navigateToHome() },
            onBack = { navigator.navigateToHome() },
        )
    }
}

/**
 * Backup flow entries.
 */
@Suppress("LongMethod")
private fun EntryProviderScope<NavKey>.backupFlowEntries(navigator: Navigator) {
    entry<Routes.BackupIntro>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        BackupIntroScreen(
            hasFunds = LocalBalances.current.totalSats > 0u,
            onClose = { navigator.goBack() },
            onConfirm = { navigator.navigate(Routes.BackupShowMnemonic) },
        )
    }

    entry<Routes.BackupShowMnemonic>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        val viewModel = hiltViewModel<BackupNavSheetViewModel>()
        ShowMnemonicScreen(
            uiState = viewModel.uiState.value,
            onRevealClick = viewModel::onRevealMnemonic,
            onContinueClick = { navigator.navigate(Routes.BackupShowPassphrase) },
        )
    }

    entry<Routes.BackupShowPassphrase>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        val viewModel = hiltViewModel<BackupNavSheetViewModel>()
        ShowPassphraseScreen(
            uiState = viewModel.uiState.value,
            onContinue = { navigator.navigate(Routes.BackupConfirmMnemonic) },
            onBack = { navigator.goBack() },
        )
    }

    entry<Routes.BackupConfirmMnemonic>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        val viewModel = hiltViewModel<BackupNavSheetViewModel>()
        ConfirmMnemonicScreen(
            uiState = viewModel.uiState.value,
            onContinue = { navigator.navigate(Routes.BackupConfirmPassphrase) },
            onBack = { navigator.goBack() },
        )
    }

    entry<Routes.BackupConfirmPassphrase>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        val viewModel = hiltViewModel<BackupNavSheetViewModel>()
        ConfirmPassphraseScreen(
            uiState = viewModel.uiState.value,
            onPassphraseChange = viewModel::onPassphraseInput,
            onContinue = { navigator.navigate(Routes.BackupWarning) },
            onBack = { navigator.goBack() },
        )
    }

    entry<Routes.BackupWarning>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        WarningScreen(
            onContinue = { navigator.navigate(Routes.BackupSuccess) },
            onBack = { navigator.goBack() },
        )
    }

    entry<Routes.BackupSuccess>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        SuccessScreen(
            onContinue = { navigator.navigate(Routes.BackupMultipleDevices) },
            onBack = { navigator.goBack() },
        )
    }

    entry<Routes.BackupMultipleDevices>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        MultipleDevicesScreen(
            onContinue = { navigator.navigate(Routes.BackupMetadata) },
            onBack = { navigator.goBack() },
        )
    }

    entry<Routes.BackupMetadata>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        val viewModel = hiltViewModel<BackupNavSheetViewModel>()
        MetadataScreen(
            uiState = viewModel.uiState.value,
            onDismiss = { navigator.navigateToHome() },
            onBack = { navigator.goBack() },
        )
    }
}

/**
 * Send flow entries (17 routes).
 */
@Suppress("LongMethod")
private fun EntryProviderScope<NavKey>.sendFlowEntries(
    navigator: Navigator,
    appViewModel: AppViewModel,
    walletViewModel: WalletViewModel,
) {
    entry<Routes.SendRecipient>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        LaunchedEffect(Unit) {
            appViewModel.resetSendState()
            appViewModel.resetQuickPayData()
        }
        SendRecipientScreen(
            onEvent = { appViewModel.setSendEvent(it) },
        )
    }

    entry<Routes.SendAddress>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        val uiState by appViewModel.sendUiState.collectAsStateWithLifecycle()
        SendAddressScreen(
            uiState = uiState,
            onBack = { navigator.goBack() },
            onEvent = { appViewModel.setSendEvent(it) },
        )
    }

    entry<Routes.SendAmount>(
        metadata = SheetSceneStrategy.sheet()
    ) { route ->
        val uiState by appViewModel.sendUiState.collectAsStateWithLifecycle()
        val walletUiState by walletViewModel.uiState.collectAsStateWithLifecycle()

        LaunchedEffect(route.prefill) {
            route.prefill?.let { prefill ->
                appViewModel.setSendEvent(SendEvent.AddressContinue(prefill))
            }
        }

        SendAmountScreen(
            uiState = uiState,
            walletUiState = walletUiState,
            canGoBack = true,
            onBack = { navigator.goBack() },
            onEvent = { appViewModel.setSendEvent(it) },
        )
    }

    entry<Routes.SendQrScanner>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        QrScanningScreen(
            navigator = navigator,
            onScanSuccess = { qrCode ->
                navigator.goBack()
                appViewModel.onScanResult(data = qrCode)
            },
        )
    }

    entry<Routes.SendCoinSelection>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        val sendUiState by appViewModel.sendUiState.collectAsStateWithLifecycle()
        SendCoinSelectionScreen(
            requiredAmount = sendUiState.amount,
            address = sendUiState.address,
            onBack = { navigator.goBack() },
            onContinue = { utxos -> appViewModel.setSendEvent(SendEvent.CoinSelectionContinue(utxos)) },
        )
    }

    entry<Routes.SendFeeRate>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        val sendUiState by appViewModel.sendUiState.collectAsStateWithLifecycle()
        val viewModel = hiltViewModel<SendFeeViewModel>()
        SendFeeRateScreen(
            sendUiState = sendUiState,
            viewModel = viewModel,
            onBack = { navigator.goBack() },
            onContinue = { navigator.goBack() },
            onSelect = { speed -> appViewModel.onSelectSpeed(speed) },
        )
    }

    entry<Routes.SendFeeCustom>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        val viewModel = hiltViewModel<SendFeeViewModel>()
        SendFeeCustomScreen(
            viewModel = viewModel,
            onBack = { navigator.goBack() },
            onContinue = { speed -> appViewModel.setTransactionSpeed(speed) },
        )
    }

    entry<Routes.SendConfirm>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        val uiState by appViewModel.sendUiState.collectAsStateWithLifecycle()
        val walletUiState by walletViewModel.uiState.collectAsStateWithLifecycle()

        SendConfirmScreen(
            savedStateHandle = remember { androidx.lifecycle.SavedStateHandle() },
            uiState = uiState,
            isNodeRunning = walletUiState.nodeLifecycleState.isRunning(),
            canGoBack = true,
            onBack = { navigator.goBack() },
            onEvent = { e -> appViewModel.setSendEvent(e) },
            onClickAddTag = { navigator.navigate(Routes.SendAddTag) },
            onClickTag = { tag -> appViewModel.removeTag(tag) },
            onNavigateToPin = { navigator.navigate(Routes.SendPinCheck) },
        )
    }

    entry<Routes.SendSuccess>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        val sendDetail by appViewModel.successSendUiState.collectAsStateWithLifecycle()
        NewTransactionSheetView(
            details = sendDetail,
            onCloseClick = { navigator.navigateToHome() },
            onDetailClick = { appViewModel.onClickSendDetail() },
            modifier = Modifier
                .fillMaxSize()
                .gradientBackground()
                .navigationBarsPadding()
                .testTag("SendSuccess"),
        )
    }

    entry<Routes.SendError>(
        metadata = SheetSceneStrategy.sheet()
    ) { route ->
        SendErrorScreen(
            errorMessage = route.message,
            onRetry = { navigator.navigate(Routes.SendRecipient) },
            onClose = { navigator.navigateToHome() },
        )
    }

    entry<Routes.SendWithdrawConfirm>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        val uiState by appViewModel.sendUiState.collectAsStateWithLifecycle()
        WithdrawConfirmScreen(
            uiState = uiState,
            onBack = { navigator.goBack() },
            onConfirm = { appViewModel.onConfirmWithdraw() },
        )
    }

    entry<Routes.SendWithdrawError>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        val uiState by appViewModel.sendUiState.collectAsStateWithLifecycle()
        WithdrawErrorScreen(
            uiState = uiState,
            onBack = { navigator.goBack() },
            onClickScan = { navigator.navigate(Routes.SendQrScanner) },
            onClickSupport = { navigator.navigate(Routes.SendSupport) },
        )
    }

    entry<Routes.SendSupport>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        SupportScreen(navigator)
    }

    entry<Routes.SendAddTag>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        AddTagScreen(
            onBack = { navigator.goBack() },
            onTagSelected = { tag ->
                appViewModel.addTagToSelected(tag)
                navigator.goBack()
            },
            tqgInputTestTag = "TagInputSend",
            addButtonTestTag = "SendTagsSubmit",
        )
    }

    entry<Routes.SendPinCheck>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        SendPinCheckScreen(
            onBack = { navigator.goBack() },
            onSuccess = {
                navigator.goBack()
                appViewModel.setSendEvent(SendEvent.PayConfirmed)
            },
        )
    }

    entry<Routes.SendQuickPay>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        val quickPayData by appViewModel.quickPayData.collectAsStateWithLifecycle()
        quickPayData?.let { data ->
            SendQuickPayScreen(
                quickPayData = data,
                onPaymentComplete = { paymentHash, amountWithFee ->
                    appViewModel.handlePaymentSuccess(
                        NewTransactionSheetDetails(
                            type = NewTransactionSheetType.LIGHTNING,
                            direction = NewTransactionSheetDirection.SENT,
                            paymentHashOrTxId = paymentHash,
                            sats = amountWithFee,
                        ),
                    )
                },
                onShowError = { errorMessage ->
                    navigator.navigate(Routes.SendError(errorMessage))
                },
            )
        }
    }
}

/**
 * Receive flow entries (9 routes).
 */
@Suppress("LongMethod")
private fun EntryProviderScope<NavKey>.receiveFlowEntries(
    navigator: Navigator,
    walletViewModel: WalletViewModel,
) {
    entry<Routes.ReceiveQr>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        val walletUiState by walletViewModel.uiState.collectAsStateWithLifecycle()
        val cjitInvoice by walletViewModel.pendingCjitInvoice.collectAsStateWithLifecycle()
        val lightningState by walletViewModel.lightningState.collectAsStateWithLifecycle()

        LaunchedEffect(Unit) {
            walletViewModel.resetPreActivityMetadataTagsForCurrentInvoice()
            walletViewModel.refreshReceiveState()
        }

        ReceiveQrScreen(
            cjitInvoice = cjitInvoice,
            walletState = walletUiState,
            onClickEditInvoice = { navigator.navigate(Routes.ReceiveEditInvoice) },
            onClickReceiveCjit = {
                if (lightningState.isGeoBlocked) {
                    navigator.navigate(Routes.ReceiveGeoBlock)
                } else {
                    navigator.navigate(Routes.ReceiveAmount)
                }
            },
        )
    }

    entry<Routes.ReceiveAmount>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        ReceiveAmountScreen(
            onCjitCreated = { entry ->
                walletViewModel.setPendingCjitEntry(entry)
                navigator.navigate(Routes.ReceiveConfirm)
            },
            onBack = { navigator.goBack() },
        )
    }

    entry<Routes.ReceiveGeoBlock>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        LocationBlockScreen(
            onBackPressed = { navigator.goBack() },
            navigateAdvancedSetup = { navigator.navigate(Routes.ExternalConnection()) },
        )
    }

    entry<Routes.ReceiveConfirm>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        val entry by walletViewModel.pendingCjitEntry.collectAsStateWithLifecycle()
        entry?.let { entryDetails ->
            ReceiveConfirmScreen(
                entry = entryDetails,
                onLearnMore = { navigator.navigate(Routes.ReceiveLiquidity) },
                onContinue = { invoice ->
                    walletViewModel.setPendingCjitInvoice(invoice)
                    navigator.popBackTo(Routes.ReceiveQr)
                },
                onBack = { navigator.goBack() },
            )
        }
    }

    entry<Routes.ReceiveConfirmInbound>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        val entry by walletViewModel.pendingCjitEntry.collectAsStateWithLifecycle()
        entry?.let { entryDetails ->
            ReceiveConfirmScreen(
                entry = entryDetails,
                isAdditional = true,
                onLearnMore = { navigator.navigate(Routes.ReceiveLiquidityAdditional) },
                onContinue = { invoice ->
                    walletViewModel.setPendingCjitInvoice(invoice)
                    navigator.popBackTo(Routes.ReceiveQr)
                },
                onBack = { navigator.goBack() },
            )
        }
    }

    entry<Routes.ReceiveLiquidity>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        val entry by walletViewModel.pendingCjitEntry.collectAsStateWithLifecycle()
        val settingsViewModel = hiltViewModel<SettingsViewModel>()
        val notificationsGranted by settingsViewModel.notificationsGranted.collectAsStateWithLifecycle()
        val context = LocalContext.current

        entry?.let { entryDetails ->
            ReceiveLiquidityScreen(
                entry = entryDetails,
                onContinue = { navigator.goBack() },
                onBack = { navigator.goBack() },
                hasNotificationPermission = notificationsGranted,
                onSwitchClick = { NotificationUtils.openNotificationSettings(context) },
            )
        }
    }

    entry<Routes.ReceiveLiquidityAdditional>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        val entry by walletViewModel.pendingCjitEntry.collectAsStateWithLifecycle()
        val settingsViewModel = hiltViewModel<SettingsViewModel>()
        val notificationsGranted by settingsViewModel.notificationsGranted.collectAsStateWithLifecycle()
        val context = LocalContext.current

        entry?.let { entryDetails ->
            ReceiveLiquidityScreen(
                entry = entryDetails,
                isAdditional = true,
                onContinue = { navigator.goBack() },
                onBack = { navigator.goBack() },
                hasNotificationPermission = notificationsGranted,
                onSwitchClick = { NotificationUtils.openNotificationSettings(context) },
            )
        }
    }

    entry<Routes.ReceiveEditInvoice>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        val walletState by walletViewModel.walletState.collectAsStateWithLifecycle()
        val editInvoiceAmountViewModel = hiltViewModel<AmountInputViewModel>()

        LaunchedEffect(Unit) { editInvoiceAmountViewModel.clearInput() }

        EditInvoiceScreen(
            amountInputViewModel = editInvoiceAmountViewModel,
            walletUiState = walletState,
            onBack = { navigator.goBack() },
            updateInvoice = walletViewModel::updateBip21Invoice,
            onClickAddTag = { navigator.navigate(Routes.ReceiveAddTag) },
            onClickTag = walletViewModel::removeTag,
            onDescriptionUpdate = walletViewModel::updateBip21Description,
            navigateReceiveConfirm = { entry ->
                walletViewModel.setPendingCjitEntry(entry)
                navigator.navigate(Routes.ReceiveConfirmInbound)
            },
        )
    }

    entry<Routes.ReceiveAddTag>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        AddTagScreen(
            onBack = { navigator.goBack() },
            onTagSelected = { tag ->
                walletViewModel.addTagToSelected(tag)
                navigator.goBack()
            },
            tqgInputTestTag = "TagInputReceive",
            addButtonTestTag = "ReceiveTagsSubmit",
        )
    }
}

/**
 * Gift flow entries (5 routes).
 */
@Suppress("LongMethod")
private fun EntryProviderScope<NavKey>.giftFlowEntries(
    navigator: Navigator,
    appViewModel: AppViewModel,
) {
    entry<Routes.GiftLoading>(
        metadata = SheetSceneStrategy.sheet()
    ) { route ->
        val viewModel = hiltViewModel<GiftViewModel>()

        LaunchedEffect(route.code, route.amount) {
            viewModel.initialize(route.code, route.amount)
        }

        LaunchedEffect(viewModel) {
            viewModel.successEvent.collect { details ->
                navigator.navigateToHome()
                appViewModel.showTransactionSheet(details)
            }
        }

        LaunchedEffect(viewModel) {
            viewModel.navigationEvent.collect { route ->
                when (route) {
                    is Routes.GiftUsed -> navigator.navigate(Routes.GiftUsed)
                    is Routes.GiftUsedUp -> navigator.navigate(Routes.GiftUsedUp)
                    is Routes.GiftError -> navigator.navigate(Routes.GiftError)
                    is Routes.GiftSuccess -> navigator.navigateToHome()
                    else -> { /* Ignore other routes */ }
                }
            }
        }

        GiftLoading(viewModel = viewModel)
    }

    entry<Routes.GiftUsed>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        GiftErrorSheet(
            titleRes = R.string.other__gift__used__title,
            textRes = R.string.other__gift__used__text,
            testTag = "GiftUsed",
            onDismiss = { navigator.navigateToHome() },
        )
    }

    entry<Routes.GiftUsedUp>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        GiftErrorSheet(
            titleRes = R.string.other__gift__used_up__title,
            textRes = R.string.other__gift__used_up__text,
            testTag = "GiftUsedUp",
            onDismiss = { navigator.navigateToHome() },
        )
    }

    entry<Routes.GiftError>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        GiftErrorSheet(
            titleRes = R.string.other__gift__error__title,
            textRes = R.string.other__gift__error__text,
            testTag = "GiftError",
            onDismiss = { navigator.navigateToHome() },
        )
    }

    entry<Routes.GiftSuccess>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        // This route is typically not navigated to directly,
        // as success triggers navigation to home and shows transaction sheet
        LaunchedEffect(Unit) {
            navigator.navigateToHome()
        }
    }
}

/**
 * Timed sheet entries - sheets that appear automatically based on conditions.
 */
@Suppress("LongMethod")
private fun EntryProviderScope<NavKey>.timedSheetEntries(
    navigator: Navigator,
    appViewModel: AppViewModel,
) {
    entry<Routes.TimedUpdateSheet>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        UpdateSheet(
            onCancel = {
                appViewModel.dismissTimedSheet()
                navigator.goBack()
            },
        )
    }

    entry<Routes.TimedBackupSheet>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        BackupIntroScreen(
            hasFunds = LocalBalances.current.totalSats > 0u,
            onClose = {
                appViewModel.dismissTimedSheet()
                navigator.goBack()
            },
            onConfirm = { navigator.navigate(Routes.BackupShowMnemonic) },
        )
    }

    entry<Routes.TimedNotificationsSheet>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        BackgroundPaymentsIntroSheet(
            onContinue = {
                appViewModel.dismissTimedSheet(skipQueue = true)
                navigator.navigate(Routes.BackgroundPaymentsSettings)
            },
        )
    }

    entry<Routes.TimedQuickPaySheet>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        QuickPayIntroSheet(
            onContinue = {
                appViewModel.dismissTimedSheet(skipQueue = true)
                navigator.navigate(Routes.QuickPaySettings)
            },
        )
    }

    entry<Routes.TimedHighBalanceSheet>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        val context = LocalContext.current
        HighBalanceWarningSheet(
            understoodClick = {
                appViewModel.dismissTimedSheet()
                navigator.goBack()
            },
            learnMoreClick = {
                val intent = Intent(Intent.ACTION_VIEW, Env.STORING_BITCOINS_URL.toUri())
                context.startActivity(intent)
                appViewModel.dismissTimedSheet(skipQueue = true)
                navigator.goBack()
            },
        )
    }
}

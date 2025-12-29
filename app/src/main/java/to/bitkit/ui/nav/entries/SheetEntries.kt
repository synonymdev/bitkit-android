package to.bitkit.ui.nav.entries

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.DisposableEffect
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
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.SendEvent
import to.bitkit.viewmodels.SettingsViewModel
import to.bitkit.viewmodels.TransferViewModel
import to.bitkit.viewmodels.WalletViewModel

@Suppress("LongMethod", "LongParameterList")
fun EntryProviderScope<NavKey>.sheetEntries(
    navigator: Navigator,
    appViewModel: AppViewModel,
    walletViewModel: WalletViewModel,
    activityListViewModel: ActivityListViewModel,
    transferViewModel: TransferViewModel,
) {
    simpleSheetEntries(navigator, appViewModel, activityListViewModel, transferViewModel)
    pinFlowEntries(navigator)
    backupFlowEntries(navigator)
    sendFlowEntries(navigator, appViewModel, walletViewModel)
    receiveFlowEntries(navigator, walletViewModel)
    giftFlowEntries(navigator, appViewModel)
    sheetFlowEntries(navigator, appViewModel)
}

@Suppress("LongParameterList", "LongMethod")
private fun EntryProviderScope<NavKey>.simpleSheetEntries(
    navigator: Navigator,
    appViewModel: AppViewModel,
    activityListViewModel: ActivityListViewModel,
    transferViewModel: TransferViewModel,
) {
    entry<Routes.Activity.DateRangeSelectorSheet>(
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

    entry<Routes.Activity.TagSelectorSheet>(
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

    entry<Routes.Sheet.LnurlAuth>(
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

    entry<Routes.Sheet.ForceTransfer>(
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

private fun EntryProviderScope<NavKey>.pinFlowEntries(navigator: Navigator) {
    entry<Routes.Pin.Prompt>(
        metadata = SheetSceneStrategy.sheet()
    ) { route ->
        PinPromptScreen(
            showLaterButton = route.showLaterButton,
            onContinue = { navigator.navigate(Routes.Pin.Choose) },
            onLater = { navigator.goBack() },
        )
    }

    entry<Routes.Pin.Choose> {
        PinChooseScreen(
            onPinChosen = { pin -> navigator.navigate(Routes.Pin.Confirm(pin)) },
            onBack = { navigator.goBack() },
        )
    }

    entry<Routes.Pin.Confirm> { route ->
        PinConfirmScreen(
            originalPin = route.pin,
            onPinConfirmed = { navigator.navigate(Routes.Pin.Biometrics) },
            onBack = { navigator.goBack() },
        )
    }

    entry<Routes.Pin.Biometrics> {
        PinBiometricsScreen(
            onContinue = { isBioOn -> navigator.navigate(Routes.Pin.Result(isBioOn)) },
            onSkip = { navigator.navigate(Routes.Pin.Result(isBioOn = false)) },
            onBack = { navigator.goBack() },
        )
    }

    entry<Routes.Pin.Result> { route ->
        PinResultScreen(
            isBioOn = route.isBioOn,
            onDismiss = { navigator.navigateToHome() },
            onBack = { navigator.navigateToHome() },
        )
    }
}

@Suppress("LongMethod")
private fun EntryProviderScope<NavKey>.backupFlowEntries(navigator: Navigator) {
    entry<Routes.Backup.Intro>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        BackupIntroScreen(
            hasFunds = LocalBalances.current.totalSats > 0u,
            onClose = { navigator.goBack() },
            onConfirm = { navigator.navigate(Routes.Backup.ShowMnemonic) },
        )
    }

    entry<Routes.Backup.ShowMnemonic> {
        val viewModel = hiltViewModel<BackupNavSheetViewModel>()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        ShowMnemonicScreen(
            uiState = uiState,
            onRevealClick = viewModel::onRevealMnemonic,
            onContinueClick = { navigator.navigate(Routes.Backup.ShowPassphrase) },
        )
    }

    entry<Routes.Backup.ShowPassphrase> {
        val viewModel = hiltViewModel<BackupNavSheetViewModel>()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        ShowPassphraseScreen(
            uiState = uiState,
            onContinue = { navigator.navigate(Routes.Backup.ConfirmMnemonic) },
            onBack = { navigator.goBack() },
        )
    }

    entry<Routes.Backup.ConfirmMnemonic> {
        val viewModel = hiltViewModel<BackupNavSheetViewModel>()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        ConfirmMnemonicScreen(
            uiState = uiState,
            onContinue = { navigator.navigate(Routes.Backup.ConfirmPassphrase) },
            onBack = { navigator.goBack() },
        )
    }

    entry<Routes.Backup.ConfirmPassphrase> {
        val viewModel = hiltViewModel<BackupNavSheetViewModel>()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        ConfirmPassphraseScreen(
            uiState = uiState,
            onPassphraseChange = viewModel::onPassphraseInput,
            onContinue = { navigator.navigate(Routes.Backup.Warning) },
            onBack = { navigator.goBack() },
        )
    }

    entry<Routes.Backup.Warning> {
        WarningScreen(
            onContinue = { navigator.navigate(Routes.Backup.Success) },
            onBack = { navigator.goBack() },
        )
    }

    entry<Routes.Backup.Success> {
        SuccessScreen(
            onContinue = { navigator.navigate(Routes.Backup.MultipleDevices) },
            onBack = { navigator.goBack() },
        )
    }

    entry<Routes.Backup.MultipleDevices> {
        MultipleDevicesScreen(
            onContinue = { navigator.navigate(Routes.Backup.Metadata) },
            onBack = { navigator.goBack() },
        )
    }

    entry<Routes.Backup.Metadata> {
        val viewModel = hiltViewModel<BackupNavSheetViewModel>()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        MetadataScreen(
            uiState = uiState,
            onDismiss = { navigator.navigateToHome() },
            onBack = { navigator.goBack() },
        )
    }
}

@Suppress("LongMethod")
private fun EntryProviderScope<NavKey>.sendFlowEntries(
    navigator: Navigator,
    appViewModel: AppViewModel,
    walletViewModel: WalletViewModel,
) {
    entry<Routes.Send.Recipient>(
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

    entry<Routes.Send.Address> {
        val uiState by appViewModel.sendUiState.collectAsStateWithLifecycle()
        SendAddressScreen(
            uiState = uiState,
            onBack = { navigator.goBack() },
            onEvent = { appViewModel.setSendEvent(it) },
        )
    }

    entry<Routes.Send.Amount> { route ->
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

    entry<Routes.Send.QrScanner> {
        QrScanningScreen(
            navigator = navigator,
            onScanSuccess = { qrCode ->
                navigator.goBack()
                appViewModel.onScanResult(data = qrCode)
            },
        )
    }

    entry<Routes.Send.CoinSelection> {
        val sendUiState by appViewModel.sendUiState.collectAsStateWithLifecycle()
        SendCoinSelectionScreen(
            requiredAmount = sendUiState.amount,
            address = sendUiState.address,
            onBack = { navigator.goBack() },
            onContinue = { utxos -> appViewModel.setSendEvent(SendEvent.CoinSelectionContinue(utxos)) },
        )
    }

    entry<Routes.Send.FeeRate> {
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

    entry<Routes.Send.FeeCustom> {
        val viewModel = hiltViewModel<SendFeeViewModel>()
        SendFeeCustomScreen(
            viewModel = viewModel,
            onBack = { navigator.goBack() },
            onContinue = { speed -> appViewModel.setTransactionSpeed(speed) },
        )
    }

    entry<Routes.Send.Confirm> {
        val uiState by appViewModel.sendUiState.collectAsStateWithLifecycle()
        val walletUiState by walletViewModel.uiState.collectAsStateWithLifecycle()

        SendConfirmScreen(
            savedStateHandle = remember { androidx.lifecycle.SavedStateHandle() },
            uiState = uiState,
            isNodeRunning = walletUiState.nodeLifecycleState.isRunning(),
            canGoBack = true,
            onBack = { navigator.goBack() },
            onEvent = { e -> appViewModel.setSendEvent(e) },
            onClickAddTag = { navigator.navigate(Routes.Send.AddTag) },
            onClickTag = { tag -> appViewModel.removeTag(tag) },
            onNavigateToPin = { navigator.navigate(Routes.Send.PinCheck) },
        )
    }

    entry<Routes.Send.Success> {
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

    entry<Routes.Send.Error> { route ->
        SendErrorScreen(
            errorMessage = route.message,
            onRetry = { navigator.navigate(Routes.Send.Recipient) },
            onClose = { navigator.navigateToHome() },
        )
    }

    entry<Routes.Send.WithdrawConfirm> {
        val uiState by appViewModel.sendUiState.collectAsStateWithLifecycle()
        WithdrawConfirmScreen(
            uiState = uiState,
            onBack = { navigator.goBack() },
            onConfirm = { appViewModel.onConfirmWithdraw() },
        )
    }

    entry<Routes.Send.WithdrawError> {
        val uiState by appViewModel.sendUiState.collectAsStateWithLifecycle()
        WithdrawErrorScreen(
            uiState = uiState,
            onBack = { navigator.goBack() },
            onClickScan = { navigator.navigate(Routes.Send.QrScanner) },
            onClickSupport = { navigator.navigate(Routes.Send.Support) },
        )
    }

    entry<Routes.Send.Support> {
        SupportScreen(navigator)
    }

    entry<Routes.Send.AddTag> {
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

    entry<Routes.Send.PinCheck> {
        SendPinCheckScreen(
            onBack = { navigator.goBack() },
            onSuccess = {
                navigator.goBack()
                appViewModel.setSendEvent(SendEvent.PayConfirmed)
            },
        )
    }

    entry<Routes.Send.QuickPay> {
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
                    navigator.navigate(Routes.Send.Error(errorMessage))
                },
            )
        }
    }
}

@Suppress("LongMethod")
private fun EntryProviderScope<NavKey>.receiveFlowEntries(
    navigator: Navigator,
    walletViewModel: WalletViewModel,
) {
    entry<Routes.Receive.Qr>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        val walletUiState by walletViewModel.uiState.collectAsStateWithLifecycle()
        val cjitInvoice by walletViewModel.pendingCjitInvoice.collectAsStateWithLifecycle()
        val lightningState by walletViewModel.lightningState.collectAsStateWithLifecycle()

        LaunchedEffect(Unit) {
            walletViewModel.refreshReceiveState()
        }

        ReceiveQrScreen(
            cjitInvoice = cjitInvoice,
            walletState = walletUiState,
            onClickEditInvoice = { navigator.navigate(Routes.Receive.EditInvoice) },
            onClickReceiveCjit = {
                if (lightningState.isGeoBlocked) {
                    navigator.navigate(Routes.Receive.GeoBlock)
                } else {
                    navigator.navigate(Routes.Receive.Amount)
                }
            },
        )
    }

    entry<Routes.Receive.Amount> {
        ReceiveAmountScreen(
            onCjitCreated = { entry ->
                walletViewModel.setPendingCjitEntry(entry)
                navigator.navigate(Routes.Receive.Confirm)
            },
            onBack = { navigator.goBack() },
        )
    }

    entry<Routes.Receive.GeoBlock> {
        LocationBlockScreen(
            onBackPressed = { navigator.goBack() },
            navigateAdvancedSetup = { navigator.navigate(Routes.External.Connection()) },
        )
    }

    entry<Routes.Receive.Confirm> {
        val entry by walletViewModel.pendingCjitEntry.collectAsStateWithLifecycle()
        entry?.let { entryDetails ->
            ReceiveConfirmScreen(
                entry = entryDetails,
                onLearnMore = { navigator.navigate(Routes.Receive.Liquidity) },
                onContinue = { invoice ->
                    walletViewModel.setPendingCjitInvoice(invoice)
                    navigator.popBackTo(Routes.Receive.Qr)
                },
                onBack = { navigator.goBack() },
            )
        }
    }

    entry<Routes.Receive.ConfirmInbound> {
        val entry by walletViewModel.pendingCjitEntry.collectAsStateWithLifecycle()
        entry?.let { entryDetails ->
            ReceiveConfirmScreen(
                entry = entryDetails,
                isAdditional = true,
                onLearnMore = { navigator.navigate(Routes.Receive.LiquidityAdditional) },
                onContinue = { invoice ->
                    walletViewModel.setPendingCjitInvoice(invoice)
                    navigator.popBackTo(Routes.Receive.Qr)
                },
                onBack = { navigator.goBack() },
            )
        }
    }

    entry<Routes.Receive.Liquidity> {
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

    entry<Routes.Receive.LiquidityAdditional> {
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

    entry<Routes.Receive.EditInvoice> {
        val walletState by walletViewModel.walletState.collectAsStateWithLifecycle()
        EditInvoiceScreen(
            walletUiState = walletState,
            onBack = { navigator.goBack() },
            updateInvoice = walletViewModel::updateBip21Invoice,
            onClickAddTag = { navigator.navigate(Routes.Receive.AddTag) },
            onClickTag = walletViewModel::removeTag,
            onDescriptionUpdate = walletViewModel::updateBip21Description,
            navigateReceiveConfirm = { entry ->
                walletViewModel.setPendingCjitEntry(entry)
                navigator.navigate(Routes.Receive.ConfirmInbound)
            },
        )
    }

    entry<Routes.Receive.AddTag> {
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

@Suppress("LongMethod")
private fun EntryProviderScope<NavKey>.giftFlowEntries(
    navigator: Navigator,
    appViewModel: AppViewModel,
) {
    entry<Routes.Gift.Loading>(
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
                    is Routes.Gift.Used -> navigator.navigate(Routes.Gift.Used)
                    is Routes.Gift.UsedUp -> navigator.navigate(Routes.Gift.UsedUp)
                    is Routes.Gift.Error -> navigator.navigate(Routes.Gift.Error)
                    is Routes.Gift.Success -> navigator.navigateToHome()
                    is Routes.Gift.Loading -> Unit
                }
            }
        }

        GiftLoading(viewModel = viewModel)
    }

    entry<Routes.Gift.Used> {
        GiftErrorSheet(
            titleRes = R.string.other__gift__used__title,
            textRes = R.string.other__gift__used__text,
            testTag = "GiftUsed",
            onDismiss = { navigator.navigateToHome() },
        )
    }

    entry<Routes.Gift.UsedUp> {
        GiftErrorSheet(
            titleRes = R.string.other__gift__used_up__title,
            textRes = R.string.other__gift__used_up__text,
            testTag = "GiftUsedUp",
            onDismiss = { navigator.navigateToHome() },
        )
    }

    entry<Routes.Gift.Error> {
        GiftErrorSheet(
            titleRes = R.string.other__gift__error__title,
            textRes = R.string.other__gift__error__text,
            testTag = "GiftError",
            onDismiss = { navigator.navigateToHome() },
        )
    }

    entry<Routes.Gift.Success> {
        // This route is not navigated directly, success triggers navigation to home and shows transaction sheet.
        LaunchedEffect(Unit) {
            navigator.navigateToHome()
        }
    }
}

@Suppress("LongMethod")
private fun EntryProviderScope<NavKey>.sheetFlowEntries(
    navigator: Navigator,
    appViewModel: AppViewModel,
) {
    entry<Routes.Sheet.Update>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        DisposableEffect(Unit) {
            onDispose { appViewModel.dismissTimedSheet() }
        }
        UpdateSheet(
            onCancel = { navigator.goBack() },
        )
    }

    entry<Routes.Sheet.Backup>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        DisposableEffect(Unit) {
            onDispose { appViewModel.dismissTimedSheet() }
        }
        BackupIntroScreen(
            hasFunds = LocalBalances.current.totalSats > 0u,
            onClose = { navigator.goBack() },
            onConfirm = { navigator.navigate(Routes.Backup.ShowMnemonic) },
        )
    }

    entry<Routes.Sheet.Notifications>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        DisposableEffect(Unit) {
            onDispose { appViewModel.dismissTimedSheet() }
        }
        BackgroundPaymentsIntroSheet(
            onContinue = {
                appViewModel.dismissTimedSheet(skipQueue = true)
                navigator.navigate(Routes.BackgroundPayments.Settings)
            },
        )
    }

    entry<Routes.Sheet.QuickPay>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        DisposableEffect(Unit) {
            onDispose { appViewModel.dismissTimedSheet() }
        }
        QuickPayIntroSheet(
            onContinue = {
                appViewModel.dismissTimedSheet(skipQueue = true)
                navigator.navigate(Routes.QuickPay.Settings)
            },
        )
    }

    entry<Routes.Sheet.HighBalance>(
        metadata = SheetSceneStrategy.sheet()
    ) {
        DisposableEffect(Unit) {
            onDispose { appViewModel.dismissTimedSheet() }
        }
        val context = LocalContext.current
        HighBalanceWarningSheet(
            understoodClick = { navigator.goBack() },
            learnMoreClick = {
                val intent = Intent(Intent.ACTION_VIEW, Env.STORING_BITCOINS_URL.toUri())
                context.startActivity(intent)
                appViewModel.dismissTimedSheet(skipQueue = true)
                navigator.goBack()
            },
        )
    }
}

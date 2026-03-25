package to.bitkit.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.transactionSpeedUiText
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.Routes
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.AuthCheckAction
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.components.settings.SettingsIcon
import to.bitkit.ui.components.settings.SettingsSwitchRow
import to.bitkit.ui.navigateTo
import to.bitkit.ui.navigateToAuthCheck
import to.bitkit.ui.navigateToDefaultUnitSettings
import to.bitkit.ui.navigateToDevSettings
import to.bitkit.ui.navigateToLanguageSettings
import to.bitkit.ui.navigateToLocalCurrencySettings
import to.bitkit.ui.navigateToPinManagement
import to.bitkit.ui.navigateToQuickPaySettings
import to.bitkit.ui.navigateToTagsSettings
import to.bitkit.ui.navigateToTransactionSpeedSettings
import to.bitkit.ui.navigateToWidgetsSettings
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.wallets.activity.components.CustomTabRowWithSpacing
import to.bitkit.ui.screens.wallets.activity.components.TabItem
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.rememberBiometricAuthSupported
import to.bitkit.viewmodels.LanguageViewModel

private enum class SettingsTab(@StringRes private val titleRes: Int) : TabItem {
    General(R.string.settings__general_title),
    Security(R.string.settings__security_title),
    Advanced(R.string.settings__advanced_title);

    override val uiText @Composable get() = stringResource(titleRes)
}

@Composable
fun SettingsScreen(
    navController: NavController,
    advancedViewModel: AdvancedSettingsViewModel = hiltViewModel(),
    languageViewModel: LanguageViewModel = hiltViewModel(),
) {
    val app = appViewModel ?: return
    val settings = settingsViewModel ?: return
    val currencies = LocalCurrencies.current

    // General tab state
    val defaultTransactionSpeed by settings.defaultTransactionSpeed.collectAsStateWithLifecycle()
    val lastUsedTags by settings.lastUsedTags.collectAsStateWithLifecycle()
    val showWidgets by settings.showWidgets.collectAsStateWithLifecycle()
    val isQuickPayEnabled by settings.isQuickpayEnabled.collectAsStateWithLifecycle()
    val quickPayIntroSeen by settings.quickPayIntroSeen.collectAsStateWithLifecycle()
    val bgPaymentsIntroSeen by settings.bgPaymentsIntroSeen.collectAsStateWithLifecycle()
    val notificationsGranted by settings.notificationsGranted.collectAsStateWithLifecycle()
    val languageUiState by languageViewModel.uiState.collectAsStateWithLifecycle()

    // Security tab state
    val isPinEnabled by settings.isPinEnabled.collectAsStateWithLifecycle()
    val isBiometricEnabled by settings.isBiometricEnabled.collectAsStateWithLifecycle()
    val isPinForPaymentsEnabled by settings.isPinForPaymentsEnabled.collectAsStateWithLifecycle()
    val enableSwipeToHideBalance by settings.enableSwipeToHideBalance.collectAsStateWithLifecycle()
    val hideBalanceOnOpen by settings.hideBalanceOnOpen.collectAsStateWithLifecycle()
    val enableAutoReadClipboard by settings.enableAutoReadClipboard.collectAsStateWithLifecycle()
    val enableSendAmountWarning by settings.enableSendAmountWarning.collectAsStateWithLifecycle()

    // Advanced tab state
    val isDevModeEnabled by settings.isDevModeEnabled.collectAsStateWithLifecycle()
    val selectedAddressTypeName by advancedViewModel.selectedAddressTypeName.collectAsStateWithLifecycle()
    val openChannelCount by advancedViewModel.openChannelCount.collectAsStateWithLifecycle()
    val truncatedNodeId by advancedViewModel.truncatedNodeId.collectAsStateWithLifecycle()
    val isCustomElectrum by advancedViewModel.isCustomElectrum.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { languageViewModel.fetchLanguageInfo() }

    SettingsContent(
        generalState = GeneralTabState(
            selectedCurrency = currencies.selectedCurrency,
            currencySymbol = currencies.currencySymbol,
            primaryDisplay = currencies.primaryDisplay,
            defaultTransactionSpeed = defaultTransactionSpeed,
            selectedLanguage = languageUiState.selectedLanguage.displayName,
            showWidgets = showWidgets,
            tagCount = lastUsedTags.size,
            isQuickPayEnabled = isQuickPayEnabled,
            notificationsGranted = notificationsGranted,
        ),
        securityState = SecurityTabState(
            isPinEnabled = isPinEnabled,
            isBiometricEnabled = isBiometricEnabled,
            isPinForPaymentsEnabled = isPinForPaymentsEnabled,
            enableSwipeToHideBalance = enableSwipeToHideBalance,
            hideBalanceOnOpen = hideBalanceOnOpen,
            enableAutoReadClipboard = enableAutoReadClipboard,
            enableSendAmountWarning = enableSendAmountWarning,
            isBiometrySupported = rememberBiometricAuthSupported(),
        ),
        advancedState = AdvancedTabState(
            isDevModeEnabled = isDevModeEnabled,
            selectedAddressTypeName = selectedAddressTypeName,
            openChannelCount = openChannelCount,
            truncatedNodeId = truncatedNodeId,
            isCustomElectrum = isCustomElectrum,
        ),
        onLanguageClick = { navController.navigateToLanguageSettings() },
        onLocalCurrencyClick = { navController.navigateToLocalCurrencySettings() },
        onDefaultUnitClick = { navController.navigateToDefaultUnitSettings() },
        onWidgetsClick = { navController.navigateToWidgetsSettings() },
        onTagsClick = { navController.navigateToTagsSettings() },
        onTransactionSpeedClick = { navController.navigateToTransactionSpeedSettings() },
        onQuickPayClick = { navController.navigateToQuickPaySettings(quickPayIntroSeen) },
        onBgPaymentsClick = {
            if (bgPaymentsIntroSeen || notificationsGranted) {
                navController.navigateTo(Routes.BackgroundPaymentsSettings)
            } else {
                navController.navigateTo(Routes.BackgroundPaymentsIntro)
            }
        },
        onBackupWalletClick = { app.showSheet(Sheet.Backup()) },
        onDataBackupsClick = { navController.navigateTo(Routes.BackupSettings) },
        onResetWalletClick = {
            if (isPinEnabled) {
                navController.navigateToAuthCheck(onSuccessActionId = AuthCheckAction.NAV_TO_RESET)
            } else {
                navController.navigateTo(Routes.ResetAndRestoreSettings)
            }
        },
        onPinClick = { navController.navigateToPinManagement() },
        onPinForPaymentsClick = {
            navController.navigateToAuthCheck(
                onSuccessActionId = AuthCheckAction.TOGGLE_PIN_FOR_PAYMENTS,
            )
        },
        onUseBiometricsClick = {
            navController.navigateToAuthCheck(
                requireBiometrics = true,
                onSuccessActionId = AuthCheckAction.TOGGLE_BIOMETRICS,
            )
        },
        onSwipeToHideBalanceClick = { settings.setEnableSwipeToHideBalance(!enableSwipeToHideBalance) },
        onHideBalanceOnOpenClick = { settings.setHideBalanceOnOpen(!hideBalanceOnOpen) },
        onAutoReadClipboardClick = { settings.setEnableAutoReadClipboard(!enableAutoReadClipboard) },
        onSendAmountWarningClick = { settings.setEnableSendAmountWarning(!enableSendAmountWarning) },
        onDevSettingsClick = { navController.navigateToDevSettings() },
        onAddressTypeClick = { navController.navigateTo(Routes.AddressTypePreference) },
        onCoinSelectionClick = { navController.navigateTo(Routes.CoinSelectPreference) },
        onAddressViewerClick = { navController.navigateTo(Routes.AddressViewer) },
        onLightningConnectionsClick = { navController.navigateTo(Routes.LightningConnections) },
        onLightningNodeClick = { navController.navigateTo(Routes.NodeInfo) },
        onElectrumServerClick = { navController.navigateTo(Routes.ElectrumConfig) },
        onRgsServerClick = { navController.navigateTo(Routes.RgsServer) },
        onBackClick = { navController.popBackStack() },
    )
}

@Suppress("LongParameterList")
@Composable
private fun SettingsContent(
    generalState: GeneralTabState = GeneralTabState(),
    securityState: SecurityTabState = SecurityTabState(),
    advancedState: AdvancedTabState = AdvancedTabState(),
    // General callbacks
    onLanguageClick: () -> Unit = {},
    onLocalCurrencyClick: () -> Unit = {},
    onDefaultUnitClick: () -> Unit = {},
    onWidgetsClick: () -> Unit = {},
    onTagsClick: () -> Unit = {},
    onTransactionSpeedClick: () -> Unit = {},
    onQuickPayClick: () -> Unit = {},
    onBgPaymentsClick: () -> Unit = {},
    // Security callbacks
    onBackupWalletClick: () -> Unit = {},
    onDataBackupsClick: () -> Unit = {},
    onResetWalletClick: () -> Unit = {},
    onPinClick: () -> Unit = {},
    onPinForPaymentsClick: () -> Unit = {},
    onUseBiometricsClick: () -> Unit = {},
    onSwipeToHideBalanceClick: () -> Unit = {},
    onHideBalanceOnOpenClick: () -> Unit = {},
    onAutoReadClipboardClick: () -> Unit = {},
    onSendAmountWarningClick: () -> Unit = {},
    // Advanced callbacks
    onDevSettingsClick: () -> Unit = {},
    onAddressTypeClick: () -> Unit = {},
    onCoinSelectionClick: () -> Unit = {},
    onAddressViewerClick: () -> Unit = {},
    onLightningConnectionsClick: () -> Unit = {},
    onLightningNodeClick: () -> Unit = {},
    onElectrumServerClick: () -> Unit = {},
    onRgsServerClick: () -> Unit = {},
    // Navigation
    onBackClick: () -> Unit = {},
    initialTab: SettingsTab = SettingsTab.General,
) {
    val tabs = remember { SettingsTab.entries.toImmutableList() }
    val pagerState = rememberPagerState(
        initialPage = tabs.indexOf(initialTab),
        pageCount = { tabs.size },
    )
    val scope = rememberCoroutineScope()

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__settings),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )

        CustomTabRowWithSpacing(
            tabs = tabs,
            currentTabIndex = pagerState.currentPage,
            selectedColor = Colors.White,
            onTabChange = { scope.launch { pagerState.animateScrollToPage(tabs.indexOf(it)) } },
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        HorizontalPager(state = pagerState) { page ->
            when (tabs[page]) {
                SettingsTab.General -> GeneralTabContent(
                    state = generalState,
                    onLanguageClick = onLanguageClick,
                    onLocalCurrencyClick = onLocalCurrencyClick,
                    onDefaultUnitClick = onDefaultUnitClick,
                    onWidgetsClick = onWidgetsClick,
                    onTagsClick = onTagsClick,
                    onTransactionSpeedClick = onTransactionSpeedClick,
                    onQuickPayClick = onQuickPayClick,
                    onBgPaymentsClick = onBgPaymentsClick,
                )

                SettingsTab.Security -> SecurityTabContent(
                    state = securityState,
                    onBackupWalletClick = onBackupWalletClick,
                    onDataBackupsClick = onDataBackupsClick,
                    onResetWalletClick = onResetWalletClick,
                    onPinClick = onPinClick,
                    onPinForPaymentsClick = onPinForPaymentsClick,
                    onUseBiometricsClick = onUseBiometricsClick,
                    onSwipeToHideBalanceClick = onSwipeToHideBalanceClick,
                    onHideBalanceOnOpenClick = onHideBalanceOnOpenClick,
                    onAutoReadClipboardClick = onAutoReadClipboardClick,
                    onSendAmountWarningClick = onSendAmountWarningClick,
                )

                SettingsTab.Advanced -> AdvancedTabContent(
                    state = advancedState,
                    onDevSettingsClick = onDevSettingsClick,
                    onAddressTypeClick = onAddressTypeClick,
                    onCoinSelectionClick = onCoinSelectionClick,
                    onAddressViewerClick = onAddressViewerClick,
                    onLightningConnectionsClick = onLightningConnectionsClick,
                    onLightningNodeClick = onLightningNodeClick,
                    onElectrumServerClick = onElectrumServerClick,
                    onRgsServerClick = onRgsServerClick,
                )
            }
        }
    }
}

@Composable
private fun GeneralTabContent(
    state: GeneralTabState,
    onLanguageClick: () -> Unit,
    onLocalCurrencyClick: () -> Unit,
    onDefaultUnitClick: () -> Unit,
    onWidgetsClick: () -> Unit,
    onTagsClick: () -> Unit,
    onTransactionSpeedClick: () -> Unit,
    onQuickPayClick: () -> Unit,
    onBgPaymentsClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Interface section
        SectionHeader(title = stringResource(R.string.settings__general__section_interface))

        SettingsButtonRow(
            title = stringResource(R.string.settings__language_title),
            icon = { SettingsIcon(R.drawable.ic_translate) },
            value = SettingsButtonValue.StringValue(state.selectedLanguage),
            onClick = onLanguageClick,
            modifier = Modifier.testTag("LanguageSettings"),
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__general__currency_local),
            icon = { SettingsIcon(R.drawable.ic_coins) },
            value = SettingsButtonValue.StringValue("${state.selectedCurrency} (${state.currencySymbol})"),
            onClick = onLocalCurrencyClick,
            modifier = Modifier.testTag("CurrenciesSettings"),
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__general__unit),
            icon = { SettingsIcon(R.drawable.ic_bitcoin_modern) },
            value = SettingsButtonValue.StringValue(
                when (state.primaryDisplay) {
                    PrimaryDisplay.BITCOIN -> stringResource(R.string.settings__general__unit_bitcoin)
                    PrimaryDisplay.FIAT -> state.selectedCurrency
                }
            ),
            onClick = onDefaultUnitClick,
            modifier = Modifier.testTag("UnitSettings"),
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__widgets__nav_title),
            icon = { SettingsIcon(R.drawable.ic_stack) },
            value = SettingsButtonValue.StringValue(
                stringResource(if (state.showWidgets) R.string.settings__bg__on else R.string.settings__bg__off)
            ),
            onClick = onWidgetsClick,
            modifier = Modifier.testTag("WidgetsSettings"),
        )
        if (state.tagCount > 0) {
            SettingsButtonRow(
                title = stringResource(R.string.settings__general__tags),
                icon = { SettingsIcon(R.drawable.ic_tag) },
                value = SettingsButtonValue.StringValue(state.tagCount.toString()),
                onClick = onTagsClick,
                modifier = Modifier.testTag("TagsSettings"),
            )
        }

        // Payments section
        SectionHeader(
            title = stringResource(R.string.settings__general__section_payments),
            padding = PaddingValues(top = 16.dp),
        )

        SettingsButtonRow(
            title = stringResource(R.string.settings__general__speed),
            icon = {
                SettingsIcon(
                    when (state.defaultTransactionSpeed) {
                        is TransactionSpeed.Fast -> R.drawable.ic_speed_fast
                        is TransactionSpeed.Slow -> R.drawable.ic_speed_slow
                        else -> R.drawable.ic_speed_normal
                    }
                )
            },
            value = SettingsButtonValue.StringValue(state.defaultTransactionSpeed.transactionSpeedUiText()),
            onClick = onTransactionSpeedClick,
            modifier = Modifier.testTag("TransactionSpeedSettings"),
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__quickpay__nav_title),
            icon = { SettingsIcon(R.drawable.ic_caret_double_right) },
            value = SettingsButtonValue.StringValue(
                stringResource(if (state.isQuickPayEnabled) R.string.settings__bg__on else R.string.settings__bg__off)
            ),
            onClick = onQuickPayClick,
            modifier = Modifier.testTag("QuickpaySettings"),
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__bg__title),
            icon = { SettingsIcon(R.drawable.ic_bell) },
            value = SettingsButtonValue.StringValue(
                stringResource(
                    if (state.notificationsGranted) R.string.settings__bg__on else R.string.settings__bg__off
                )
            ),
            onClick = onBgPaymentsClick,
            modifier = Modifier.testTag("BackgroundPaymentSettings"),
        )

        VerticalSpacer(32.dp)
    }
}

@Composable
private fun SecurityTabContent(
    state: SecurityTabState,
    onBackupWalletClick: () -> Unit,
    onDataBackupsClick: () -> Unit,
    onResetWalletClick: () -> Unit,
    onPinClick: () -> Unit,
    onPinForPaymentsClick: () -> Unit,
    onUseBiometricsClick: () -> Unit,
    onSwipeToHideBalanceClick: () -> Unit,
    onHideBalanceOnOpenClick: () -> Unit,
    onAutoReadClipboardClick: () -> Unit,
    onSendAmountWarningClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Back up or reset section
        SectionHeader(title = stringResource(R.string.settings__security__section_backup))

        SettingsButtonRow(
            title = stringResource(R.string.settings__backup__wallet),
            icon = { SettingsIcon(R.drawable.ic_lock_key) },
            onClick = onBackupWalletClick,
            modifier = Modifier.testTag("BackupWallet"),
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__backup__data),
            icon = { SettingsIcon(R.drawable.ic_database) },
            onClick = onDataBackupsClick,
            modifier = Modifier.testTag("BackupSettings"),
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__backup__reset),
            icon = { SettingsIcon(R.drawable.ic_arrow_counter_clockwise) },
            onClick = onResetWalletClick,
            modifier = Modifier.testTag("ResetAndRestore"),
        )

        // Safety section
        SectionHeader(
            title = stringResource(R.string.settings__security__section_safety),
            padding = PaddingValues(top = 16.dp),
        )

        SettingsButtonRow(
            title = stringResource(R.string.settings__security__pin),
            icon = { SettingsIcon(R.drawable.ic_shield) },
            value = SettingsButtonValue.StringValue(
                stringResource(
                    if (state.isPinEnabled) {
                        R.string.settings__security__pin_enabled
                    } else {
                        R.string.settings__security__pin_disabled
                    }
                )
            ),
            onClick = onPinClick,
            modifier = Modifier.testTag("PINCode"),
        )

        if (state.isPinEnabled) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings__security__pin_payments),
                icon = { SettingsIcon(R.drawable.ic_coins) },
                isChecked = state.isPinForPaymentsEnabled,
                onClick = onPinForPaymentsClick,
                modifier = Modifier.testTag("EnablePinForPayments"),
            )

            if (state.isBiometrySupported) {
                SettingsSwitchRow(
                    title = run {
                        val bioTypeName = stringResource(R.string.security__bio)
                        stringResource(R.string.settings__security__use_bio)
                            .replace("{biometryTypeName}", bioTypeName)
                    },
                    icon = { SettingsIcon(R.drawable.ic_smiley) },
                    isChecked = state.isBiometricEnabled,
                    onClick = onUseBiometricsClick,
                    modifier = Modifier.testTag("UseBiometryInstead"),
                )
            }
        }

        SettingsSwitchRow(
            title = stringResource(R.string.settings__security__warn_100),
            icon = { SettingsIcon(R.drawable.ic_warning) },
            isChecked = state.enableSendAmountWarning,
            onClick = onSendAmountWarningClick,
            modifier = Modifier.testTag("SendAmountWarning"),
        )

        // Privacy section
        SectionHeader(
            title = stringResource(R.string.settings__security__section_privacy),
            padding = PaddingValues(top = 16.dp),
        )

        SettingsSwitchRow(
            title = stringResource(R.string.settings__security__swipe_balance_to_hide),
            icon = { SettingsIcon(R.drawable.ic_hand_pointing) },
            isChecked = state.enableSwipeToHideBalance,
            onClick = onSwipeToHideBalanceClick,
            modifier = Modifier.testTag("SwipeBalanceToHide"),
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings__security__hide_balance_on_open),
            icon = { SettingsIcon(R.drawable.ic_eye_slash) },
            isChecked = state.hideBalanceOnOpen,
            onClick = onHideBalanceOnOpenClick,
            modifier = Modifier.testTag("HideBalanceOnOpen"),
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings__security__clipboard),
            icon = { SettingsIcon(R.drawable.ic_clipboard_text) },
            isChecked = state.enableAutoReadClipboard,
            onClick = onAutoReadClipboardClick,
            modifier = Modifier.testTag("AutoReadClipboard"),
        )

        VerticalSpacer(32.dp)
    }
}

@Composable
private fun AdvancedTabContent(
    state: AdvancedTabState,
    onDevSettingsClick: () -> Unit,
    onAddressTypeClick: () -> Unit,
    onCoinSelectionClick: () -> Unit,
    onAddressViewerClick: () -> Unit,
    onLightningConnectionsClick: () -> Unit,
    onLightningNodeClick: () -> Unit,
    onElectrumServerClick: () -> Unit,
    onRgsServerClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
            .testTag("advanced_settings_screen")
    ) {
        // Debug section (only if dev mode enabled)
        if (state.isDevModeEnabled) {
            SectionHeader(title = stringResource(R.string.settings__adv__section_debug))

            SettingsButtonRow(
                title = stringResource(R.string.settings__dev_title),
                icon = { SettingsIcon(R.drawable.ic_settings_dev) },
                onClick = onDevSettingsClick,
                modifier = Modifier.testTag("DevSettings"),
            )
        }

        // Payments section
        SectionHeader(title = stringResource(R.string.settings__adv__section_payments))

        SettingsButtonRow(
            title = stringResource(R.string.settings__addr_type__title),
            icon = { SettingsIcon(R.drawable.ic_list_dashes) },
            value = if (state.selectedAddressTypeName.isNotEmpty()) {
                SettingsButtonValue.StringValue(state.selectedAddressTypeName)
            } else {
                SettingsButtonValue.None
            },
            onClick = onAddressTypeClick,
            modifier = Modifier.testTag("AddressTypePreference"),
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__adv__coin_selection),
            icon = { SettingsIcon(R.drawable.ic_coins) },
            onClick = onCoinSelectionClick,
            modifier = Modifier.testTag("CoinSelectPreference"),
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__adv__address_viewer),
            icon = { SettingsIcon(R.drawable.ic_eye) },
            onClick = onAddressViewerClick,
            modifier = Modifier.testTag("AddressViewer"),
        )

        // Networks section
        SectionHeader(
            title = stringResource(R.string.settings__adv__section_networks),
            padding = PaddingValues(top = 16.dp),
        )

        SettingsButtonRow(
            title = stringResource(R.string.settings__adv__lightning_connections),
            icon = { SettingsIcon(R.drawable.ic_lightning) },
            value = if (state.openChannelCount > 0) {
                SettingsButtonValue.StringValue(state.openChannelCount.toString())
            } else {
                SettingsButtonValue.None
            },
            onClick = onLightningConnectionsClick,
            modifier = Modifier.testTag("Channels"),
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__adv__lightning_node),
            icon = { SettingsIcon(R.drawable.ic_git_branch) },
            value = if (state.truncatedNodeId.isNotEmpty()) {
                SettingsButtonValue.StringValue("${state.truncatedNodeId}...")
            } else {
                SettingsButtonValue.None
            },
            onClick = onLightningNodeClick,
            modifier = Modifier.testTag("LightningNodeInfo"),
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__adv__electrum_server),
            icon = { SettingsIcon(R.drawable.ic_hard_drives) },
            value = SettingsButtonValue.StringValue(
                stringResource(
                    if (state.isCustomElectrum) {
                        R.string.settings__adv__electrum_custom
                    } else {
                        R.string.settings__adv__electrum_auto
                    }
                )
            ),
            onClick = onElectrumServerClick,
            modifier = Modifier.testTag("ElectrumConfig"),
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__adv__rgs_server),
            icon = { SettingsIcon(R.drawable.ic_broadcast) },
            onClick = onRgsServerClick,
            modifier = Modifier.testTag("RGSServer"),
        )

        VerticalSpacer(32.dp)
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewGeneral() {
    AppThemeSurface {
        SettingsContent(
            generalState = GeneralTabState(
                selectedLanguage = "System Settings",
                tagCount = 8,
                isQuickPayEnabled = true,
                notificationsGranted = true,
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewSecurity() {
    AppThemeSurface {
        SettingsContent(
            securityState = SecurityTabState(
                isPinEnabled = true,
                isPinForPaymentsEnabled = true,
                enableSwipeToHideBalance = true,
                isBiometrySupported = true,
            ),
            initialTab = SettingsTab.Security,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewAdvanced() {
    AppThemeSurface {
        SettingsContent(
            advancedState = AdvancedTabState(
                isDevModeEnabled = true,
                selectedAddressTypeName = "Taproot",
                openChannelCount = 2,
                truncatedNodeId = "34sdx",
            ),
            initialTab = SettingsTab.Advanced,
        )
    }
}

@Immutable
data class GeneralTabState(
    val selectedCurrency: String = "USD",
    val currencySymbol: String = "$",
    val primaryDisplay: PrimaryDisplay = PrimaryDisplay.BITCOIN,
    val defaultTransactionSpeed: TransactionSpeed = TransactionSpeed.Medium,
    val selectedLanguage: String = "",
    val showWidgets: Boolean = true,
    val tagCount: Int = 0,
    val isQuickPayEnabled: Boolean = false,
    val notificationsGranted: Boolean = false,
)

@Immutable
data class SecurityTabState(
    val isPinEnabled: Boolean = false,
    val isBiometricEnabled: Boolean = false,
    val isPinForPaymentsEnabled: Boolean = false,
    val enableSwipeToHideBalance: Boolean = false,
    val hideBalanceOnOpen: Boolean = false,
    val enableAutoReadClipboard: Boolean = true,
    val enableSendAmountWarning: Boolean = true,
    val isBiometrySupported: Boolean = false,
)

@Immutable
data class AdvancedTabState(
    val isDevModeEnabled: Boolean = false,
    val selectedAddressTypeName: String = "",
    val openChannelCount: Int = 0,
    val truncatedNodeId: String = "",
    val isCustomElectrum: Boolean = false,
)

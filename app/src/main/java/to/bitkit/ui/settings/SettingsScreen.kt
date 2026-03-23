package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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

private enum class SettingsTab(private val titleRes: Int) : TabItem {
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

    LaunchedEffect(Unit) { languageViewModel.fetchLanguageInfo() }

    SettingsContent(
        // General
        selectedCurrency = currencies.selectedCurrency,
        primaryDisplay = currencies.primaryDisplay,
        defaultTransactionSpeed = defaultTransactionSpeed,
        selectedLanguage = languageUiState.selectedLanguage.displayName,
        showWidgets = showWidgets,
        tagCount = lastUsedTags.size,
        notificationsGranted = notificationsGranted,
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
        // Security
        isPinEnabled = isPinEnabled,
        isBiometricEnabled = isBiometricEnabled,
        isPinForPaymentsEnabled = isPinForPaymentsEnabled,
        enableSwipeToHideBalance = enableSwipeToHideBalance,
        hideBalanceOnOpen = hideBalanceOnOpen,
        enableAutoReadClipboard = enableAutoReadClipboard,
        enableSendAmountWarning = enableSendAmountWarning,
        isBiometrySupported = rememberBiometricAuthSupported(),
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
        // Advanced
        isDevModeEnabled = isDevModeEnabled,
        selectedAddressTypeName = selectedAddressTypeName,
        onDevSettingsClick = { navController.navigateToDevSettings() },
        onAddressTypeClick = { navController.navigateTo(Routes.AddressTypePreference) },
        onCoinSelectionClick = { navController.navigateTo(Routes.CoinSelectPreference) },
        onAddressViewerClick = { navController.navigateTo(Routes.AddressViewer) },
        onLightningConnectionsClick = { navController.navigateTo(Routes.LightningConnections) },
        onLightningNodeClick = { navController.navigateTo(Routes.NodeInfo) },
        onElectrumServerClick = { navController.navigateTo(Routes.ElectrumConfig) },
        onRgsServerClick = { navController.navigateTo(Routes.RgsServer) },
        // Navigation
        onBackClick = { navController.popBackStack() },
    )
}

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun SettingsContent(
    // General
    selectedCurrency: String = "USD",
    primaryDisplay: PrimaryDisplay = PrimaryDisplay.BITCOIN,
    defaultTransactionSpeed: TransactionSpeed = TransactionSpeed.Medium,
    selectedLanguage: String = "",
    showWidgets: Boolean = true,
    tagCount: Int = 0,
    notificationsGranted: Boolean = false,
    onLanguageClick: () -> Unit = {},
    onLocalCurrencyClick: () -> Unit = {},
    onDefaultUnitClick: () -> Unit = {},
    onWidgetsClick: () -> Unit = {},
    onTagsClick: () -> Unit = {},
    onTransactionSpeedClick: () -> Unit = {},
    onQuickPayClick: () -> Unit = {},
    onBgPaymentsClick: () -> Unit = {},
    // Security
    isPinEnabled: Boolean = false,
    isBiometricEnabled: Boolean = false,
    isPinForPaymentsEnabled: Boolean = false,
    enableSwipeToHideBalance: Boolean = false,
    hideBalanceOnOpen: Boolean = false,
    enableAutoReadClipboard: Boolean = true,
    enableSendAmountWarning: Boolean = true,
    isBiometrySupported: Boolean = false,
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
    // Advanced
    isDevModeEnabled: Boolean = false,
    selectedAddressTypeName: String = "",
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
    val tabs = remember { SettingsTab.entries }
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
                    selectedCurrency = selectedCurrency,
                    primaryDisplay = primaryDisplay,
                    defaultTransactionSpeed = defaultTransactionSpeed,
                    selectedLanguage = selectedLanguage,
                    showWidgets = showWidgets,
                    tagCount = tagCount,
                    notificationsGranted = notificationsGranted,
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
                    isPinEnabled = isPinEnabled,
                    isBiometricEnabled = isBiometricEnabled,
                    isPinForPaymentsEnabled = isPinForPaymentsEnabled,
                    enableSwipeToHideBalance = enableSwipeToHideBalance,
                    hideBalanceOnOpen = hideBalanceOnOpen,
                    enableAutoReadClipboard = enableAutoReadClipboard,
                    enableSendAmountWarning = enableSendAmountWarning,
                    isBiometrySupported = isBiometrySupported,
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
                    isDevModeEnabled = isDevModeEnabled,
                    selectedAddressTypeName = selectedAddressTypeName,
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
    selectedCurrency: String,
    primaryDisplay: PrimaryDisplay,
    defaultTransactionSpeed: TransactionSpeed,
    selectedLanguage: String,
    showWidgets: Boolean,
    tagCount: Int,
    notificationsGranted: Boolean,
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
            value = SettingsButtonValue.StringValue(selectedLanguage),
            onClick = onLanguageClick,
            modifier = Modifier.testTag("LanguageSettings"),
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__general__currency_local),
            icon = { SettingsIcon(R.drawable.ic_coins) },
            value = SettingsButtonValue.StringValue(selectedCurrency),
            onClick = onLocalCurrencyClick,
            modifier = Modifier.testTag("CurrenciesSettings"),
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__general__unit),
            icon = { SettingsIcon(R.drawable.ic_bitcoin_modern) },
            value = SettingsButtonValue.StringValue(
                when (primaryDisplay) {
                    PrimaryDisplay.BITCOIN -> stringResource(R.string.settings__general__unit_bitcoin)
                    PrimaryDisplay.FIAT -> selectedCurrency
                }
            ),
            onClick = onDefaultUnitClick,
            modifier = Modifier.testTag("UnitSettings"),
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__widgets__nav_title),
            icon = { SettingsIcon(R.drawable.ic_stack) },
            value = SettingsButtonValue.StringValue(
                stringResource(if (showWidgets) R.string.settings__bg__on else R.string.settings__bg__off)
            ),
            onClick = onWidgetsClick,
            modifier = Modifier.testTag("WidgetsSettings"),
        )
        if (tagCount > 0) {
            SettingsButtonRow(
                title = stringResource(R.string.settings__general__tags),
                icon = { SettingsIcon(R.drawable.ic_tag) },
                value = SettingsButtonValue.StringValue(tagCount.toString()),
                onClick = onTagsClick,
                modifier = Modifier.testTag("TagsSettings"),
            )
        }

        // Payments section
        SectionHeader(
            title = stringResource(R.string.settings__general__section_payments),
            padding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp),
        )

        SettingsButtonRow(
            title = stringResource(R.string.settings__general__speed),
            icon = { SettingsIcon(R.drawable.ic_speed_normal) },
            value = SettingsButtonValue.StringValue(defaultTransactionSpeed.transactionSpeedUiText()),
            onClick = onTransactionSpeedClick,
            modifier = Modifier.testTag("TransactionSpeedSettings"),
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__quickpay__nav_title),
            icon = { SettingsIcon(R.drawable.ic_caret_double_right) },
            onClick = onQuickPayClick,
            modifier = Modifier.testTag("QuickpaySettings"),
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__bg__title),
            icon = { SettingsIcon(R.drawable.ic_bell) },
            value = SettingsButtonValue.StringValue(
                stringResource(if (notificationsGranted) R.string.settings__bg__on else R.string.settings__bg__off)
            ),
            onClick = onBgPaymentsClick,
            modifier = Modifier.testTag("BackgroundPaymentSettings"),
        )

        VerticalSpacer(32.dp)
    }
}

@Suppress("LongParameterList")
@Composable
private fun SecurityTabContent(
    isPinEnabled: Boolean,
    isBiometricEnabled: Boolean,
    isPinForPaymentsEnabled: Boolean,
    enableSwipeToHideBalance: Boolean,
    hideBalanceOnOpen: Boolean,
    enableAutoReadClipboard: Boolean,
    enableSendAmountWarning: Boolean,
    isBiometrySupported: Boolean,
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
            padding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp),
        )

        SettingsButtonRow(
            title = stringResource(R.string.settings__security__pin),
            icon = { SettingsIcon(R.drawable.ic_shield) },
            value = SettingsButtonValue.StringValue(
                stringResource(
                    if (isPinEnabled) {
                        R.string.settings__security__pin_enabled
                    } else {
                        R.string.settings__security__pin_disabled
                    }
                )
            ),
            onClick = onPinClick,
            modifier = Modifier.testTag("PINCode"),
        )

        if (isPinEnabled) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings__security__pin_payments),
                icon = { SettingsIcon(R.drawable.ic_coins) },
                isChecked = isPinForPaymentsEnabled,
                onClick = onPinForPaymentsClick,
                modifier = Modifier.testTag("EnablePinForPayments"),
            )

            if (isBiometrySupported) {
                SettingsSwitchRow(
                    title = run {
                        val bioTypeName = stringResource(R.string.security__bio)
                        stringResource(R.string.settings__security__use_bio)
                            .replace("{biometryTypeName}", bioTypeName)
                    },
                    icon = { SettingsIcon(R.drawable.ic_smiley) },
                    isChecked = isBiometricEnabled,
                    onClick = onUseBiometricsClick,
                    modifier = Modifier.testTag("UseBiometryInstead"),
                )
            }
        }

        SettingsSwitchRow(
            title = stringResource(R.string.settings__security__warn_100),
            icon = { SettingsIcon(R.drawable.ic_warning) },
            isChecked = enableSendAmountWarning,
            onClick = onSendAmountWarningClick,
            modifier = Modifier.testTag("SendAmountWarning"),
        )

        // Privacy section
        SectionHeader(
            title = stringResource(R.string.settings__security__section_privacy),
            padding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp),
        )

        SettingsSwitchRow(
            title = stringResource(R.string.settings__security__swipe_balance_to_hide),
            icon = { SettingsIcon(R.drawable.ic_hand_pointing) },
            isChecked = enableSwipeToHideBalance,
            onClick = onSwipeToHideBalanceClick,
            modifier = Modifier.testTag("SwipeBalanceToHide"),
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings__security__hide_balance_on_open),
            icon = { SettingsIcon(R.drawable.ic_eye_slash) },
            isChecked = hideBalanceOnOpen,
            onClick = onHideBalanceOnOpenClick,
            modifier = Modifier.testTag("HideBalanceOnOpen"),
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings__security__clipboard),
            icon = { SettingsIcon(R.drawable.ic_clipboard_text) },
            isChecked = enableAutoReadClipboard,
            onClick = onAutoReadClipboardClick,
            modifier = Modifier.testTag("AutoReadClipboard"),
        )

        VerticalSpacer(32.dp)
    }
}

@Composable
private fun AdvancedTabContent(
    isDevModeEnabled: Boolean,
    selectedAddressTypeName: String,
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
        if (isDevModeEnabled) {
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
            value = if (selectedAddressTypeName.isNotEmpty()) {
                SettingsButtonValue.StringValue(selectedAddressTypeName)
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
            padding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp),
        )

        SettingsButtonRow(
            title = stringResource(R.string.settings__adv__lightning_connections),
            icon = { SettingsIcon(R.drawable.ic_lightning) },
            onClick = onLightningConnectionsClick,
            modifier = Modifier.testTag("Channels"),
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__adv__lightning_node),
            icon = { SettingsIcon(R.drawable.ic_git_branch) },
            onClick = onLightningNodeClick,
            modifier = Modifier.testTag("LightningNodeInfo"),
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__adv__electrum_server),
            icon = { SettingsIcon(R.drawable.ic_hard_drives) },
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
        SettingsContent(tagCount = 3)
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewSecurity() {
    AppThemeSurface {
        SettingsContent(
            isPinEnabled = true,
            isPinForPaymentsEnabled = true,
            enableSwipeToHideBalance = true,
            isBiometrySupported = true,
            initialTab = SettingsTab.Security,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewAdvanced() {
    AppThemeSurface {
        SettingsContent(
            isDevModeEnabled = true,
            selectedAddressTypeName = "Taproot",
            initialTab = SettingsTab.Advanced,
        )
    }
}

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
import androidx.compose.ui.unit.Dp
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
import to.bitkit.ui.navigateToPaymentPreferenceSettings
import to.bitkit.ui.navigateToPinManagement
import to.bitkit.ui.navigateToQuickPaySettings
import to.bitkit.ui.navigateToTagsSettings
import to.bitkit.ui.navigateToTransactionSpeedSettings
import to.bitkit.ui.navigateToWidgetsSettings
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.PinnedTabsScaffold
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

@Suppress("CyclomaticComplexMethod")
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
    val isPubkyAuthenticated by settings.isPubkyAuthenticated.collectAsStateWithLifecycle()
    val isPaykitEnabled by settings.isPaykitEnabled.collectAsStateWithLifecycle()
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
    val electrumHost by advancedViewModel.electrumHost.collectAsStateWithLifecycle()
    val coinSelectAuto by advancedViewModel.coinSelectAuto.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { languageViewModel.fetchLanguageInfo() }

    SettingsContent(
        generalState = GeneralTabState(
            selectedCurrency = currencies.selectedCurrency,
            currencySymbol = currencies.currencySymbol,
            primaryDisplay = currencies.primaryDisplay,
            defaultTransactionSpeed = defaultTransactionSpeed,
            selectedLanguage = languageUiState.selectedLanguage.let {
                if (it.displayNameResId != null) stringResource(it.displayNameResId) else it.nativeName.orEmpty()
            },
            showWidgets = showWidgets,
            tagCount = lastUsedTags.size,
            isQuickPayEnabled = isQuickPayEnabled,
            notificationsGranted = notificationsGranted,
            isPubkyAuthenticated = isPubkyAuthenticated,
            isPaykitEnabled = isPaykitEnabled,
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
            coinSelectAuto = coinSelectAuto,
            openChannelCount = openChannelCount,
            truncatedNodeId = truncatedNodeId,
            electrumHost = electrumHost,
        ),
        onEvent = { event ->
            when (event) {
                SettingsEvent.LanguageClick -> navController.navigateToLanguageSettings()
                SettingsEvent.LocalCurrencyClick -> navController.navigateToLocalCurrencySettings()
                SettingsEvent.DefaultUnitClick -> navController.navigateToDefaultUnitSettings()
                SettingsEvent.WidgetsClick -> navController.navigateToWidgetsSettings()
                SettingsEvent.TagsClick -> navController.navigateToTagsSettings()
                SettingsEvent.TransactionSpeedClick -> navController.navigateToTransactionSpeedSettings()
                SettingsEvent.PaymentPreferenceClick -> navController.navigateToPaymentPreferenceSettings()
                SettingsEvent.QuickPayClick -> navController.navigateToQuickPaySettings(quickPayIntroSeen)
                SettingsEvent.BgPaymentsClick -> {
                    if (bgPaymentsIntroSeen || notificationsGranted) {
                        navController.navigateTo(Routes.BackgroundPaymentsSettings)
                    } else {
                        navController.navigateTo(Routes.BackgroundPaymentsIntro)
                    }
                }
                SettingsEvent.BackupWalletClick -> app.showSheet(Sheet.Backup())
                SettingsEvent.DataBackupsClick -> navController.navigateTo(Routes.BackupSettings)
                SettingsEvent.ResetWalletClick ->
                    navController.navigateTo(Routes.ResetAndRestoreSettings)
                SettingsEvent.PinClick -> navController.navigateToPinManagement()
                SettingsEvent.PinForPaymentsClick -> {
                    navController.navigateToAuthCheck(
                        onSuccessActionId = AuthCheckAction.TOGGLE_PIN_FOR_PAYMENTS,
                    )
                }
                SettingsEvent.UseBiometricsClick -> {
                    navController.navigateToAuthCheck(
                        requireBiometrics = true,
                        onSuccessActionId = AuthCheckAction.TOGGLE_BIOMETRICS,
                    )
                }
                SettingsEvent.SwipeToHideBalanceClick -> settings.setEnableSwipeToHideBalance(!enableSwipeToHideBalance)
                SettingsEvent.HideBalanceOnOpenClick -> settings.setHideBalanceOnOpen(!hideBalanceOnOpen)
                SettingsEvent.AutoReadClipboardClick -> settings.setEnableAutoReadClipboard(!enableAutoReadClipboard)
                SettingsEvent.SendAmountWarningClick -> settings.setEnableSendAmountWarning(!enableSendAmountWarning)
                SettingsEvent.DevSettingsClick -> navController.navigateToDevSettings()
                SettingsEvent.AddressTypeClick -> navController.navigateTo(Routes.AddressTypePreference)
                SettingsEvent.CoinSelectionClick -> navController.navigateTo(Routes.CoinSelectPreference)
                SettingsEvent.AddressViewerClick -> navController.navigateTo(Routes.AddressViewer)
                SettingsEvent.LightningConnectionsClick -> navController.navigateTo(Routes.LightningConnections)
                SettingsEvent.LightningNodeClick -> navController.navigateTo(Routes.NodeInfo)
                SettingsEvent.ElectrumServerClick -> navController.navigateTo(Routes.ElectrumConfig)
                SettingsEvent.RgsServerClick -> navController.navigateTo(Routes.RgsServer)
                SettingsEvent.BackClick -> navController.popBackStack()
            }
        },
    )
}

@Composable
private fun SettingsContent(
    generalState: GeneralTabState = GeneralTabState(),
    securityState: SecurityTabState = SecurityTabState(),
    advancedState: AdvancedTabState = AdvancedTabState(),
    onEvent: OnSettingsEvent = {},
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
            onBackClick = { onEvent(SettingsEvent.BackClick) },
            actions = { DrawerNavIcon() },
        )

        PinnedTabsScaffold(
            header = {
                CustomTabRowWithSpacing(
                    tabs = tabs,
                    currentTabIndex = pagerState.currentPage,
                    selectedColor = Colors.White,
                    onTabChange = { scope.launch { pagerState.animateScrollToPage(tabs.indexOf(it)) } },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        ) { topPadding ->
            HorizontalPager(state = pagerState) { page ->
                when (tabs[page]) {
                    SettingsTab.General -> GeneralTabContent(
                        state = generalState,
                        onEvent = onEvent,
                        topPadding = topPadding,
                    )

                    SettingsTab.Security -> SecurityTabContent(
                        state = securityState,
                        onEvent = onEvent,
                        topPadding = topPadding,
                    )

                    SettingsTab.Advanced -> AdvancedTabContent(
                        state = advancedState,
                        onEvent = onEvent,
                        topPadding = topPadding,
                    )
                }
            }
        }
    }
}

@Composable
private fun GeneralTabContent(
    state: GeneralTabState,
    onEvent: OnSettingsEvent,
    topPadding: Dp = 0.dp,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = topPadding, start = 16.dp, end = 16.dp)
    ) {
        SectionHeader(title = stringResource(R.string.settings__general__section_interface))

        SettingsButtonRow(
            title = stringResource(R.string.settings__language_title),
            icon = { SettingsIcon(R.drawable.ic_translate) },
            value = SettingsButtonValue.StringValue(state.selectedLanguage),
            onClick = { onEvent(SettingsEvent.LanguageClick) },
            modifier = Modifier.testTag("LanguageSettings")
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__general__currency_local),
            icon = { SettingsIcon(R.drawable.ic_coins) },
            value = SettingsButtonValue.StringValue("${state.selectedCurrency} (${state.currencySymbol})"),
            onClick = { onEvent(SettingsEvent.LocalCurrencyClick) },
            modifier = Modifier.testTag("CurrenciesSettings")
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
            onClick = { onEvent(SettingsEvent.DefaultUnitClick) },
            modifier = Modifier.testTag("UnitSettings")
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__widgets__nav_title),
            icon = { SettingsIcon(R.drawable.ic_stack) },
            value = SettingsButtonValue.StringValue(
                stringResource(if (state.showWidgets) R.string.settings__bg__on else R.string.settings__bg__off)
            ),
            onClick = { onEvent(SettingsEvent.WidgetsClick) },
            modifier = Modifier.testTag("WidgetsSettings")
        )
        if (state.tagCount > 0) {
            SettingsButtonRow(
                title = stringResource(R.string.settings__general__tags),
                icon = { SettingsIcon(R.drawable.ic_tag) },
                value = SettingsButtonValue.StringValue(state.tagCount.toString()),
                onClick = { onEvent(SettingsEvent.TagsClick) },
                modifier = Modifier.testTag("TagsSettings")
            )
        }

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
            onClick = { onEvent(SettingsEvent.TransactionSpeedClick) },
            modifier = Modifier.testTag("TransactionSpeedSettings")
        )
        if (state.isPaykitEnabled && state.isPubkyAuthenticated) {
            SettingsButtonRow(
                title = stringResource(R.string.settings__payment_pref_title),
                icon = { SettingsIcon(R.drawable.ic_coins) },
                onClick = { onEvent(SettingsEvent.PaymentPreferenceClick) },
                modifier = Modifier.testTag("PaymentPreferenceSettings")
            )
        }
        SettingsButtonRow(
            title = stringResource(R.string.settings__quickpay__nav_title),
            icon = { SettingsIcon(R.drawable.ic_caret_double_right) },
            value = SettingsButtonValue.StringValue(
                stringResource(if (state.isQuickPayEnabled) R.string.settings__bg__on else R.string.settings__bg__off)
            ),
            onClick = { onEvent(SettingsEvent.QuickPayClick) },
            modifier = Modifier.testTag("QuickpaySettings")
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__bg__title),
            icon = { SettingsIcon(R.drawable.ic_bell) },
            value = SettingsButtonValue.StringValue(
                stringResource(
                    if (state.notificationsGranted) R.string.settings__bg__on else R.string.settings__bg__off
                )
            ),
            onClick = { onEvent(SettingsEvent.BgPaymentsClick) },
            modifier = Modifier.testTag("BackgroundPaymentSettings")
        )

        VerticalSpacer(32.dp)
    }
}

@Composable
private fun SecurityTabContent(
    state: SecurityTabState,
    onEvent: OnSettingsEvent,
    topPadding: Dp = 0.dp,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = topPadding, start = 16.dp, end = 16.dp)
    ) {
        SectionHeader(title = stringResource(R.string.settings__security__section_backup))

        SettingsButtonRow(
            title = stringResource(R.string.settings__backup__wallet),
            icon = { SettingsIcon(R.drawable.ic_lock_key) },
            onClick = { onEvent(SettingsEvent.BackupWalletClick) },
            modifier = Modifier.testTag("BackupWallet")
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__backup__data),
            icon = { SettingsIcon(R.drawable.ic_database) },
            onClick = { onEvent(SettingsEvent.DataBackupsClick) },
            modifier = Modifier.testTag("BackupSettings")
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__backup__reset),
            icon = { SettingsIcon(R.drawable.ic_arrow_counter_clockwise) },
            onClick = { onEvent(SettingsEvent.ResetWalletClick) },
            modifier = Modifier.testTag("ResetAndRestore")
        )

        SectionHeader(
            title = stringResource(R.string.settings__security__section_safety),
            padding = PaddingValues(top = 16.dp)
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
            onClick = { onEvent(SettingsEvent.PinClick) },
            modifier = Modifier.testTag("PINCode")
        )

        if (state.isPinEnabled) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings__security__pin_payments),
                icon = { SettingsIcon(R.drawable.ic_coins) },
                isChecked = state.isPinForPaymentsEnabled,
                onClick = { onEvent(SettingsEvent.PinForPaymentsClick) },
                modifier = Modifier.testTag("EnablePinForPayments")
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
                    onClick = { onEvent(SettingsEvent.UseBiometricsClick) },
                    modifier = Modifier.testTag("UseBiometryInstead")
                )
            }
        }

        SettingsSwitchRow(
            title = stringResource(R.string.settings__security__warn_100),
            icon = { SettingsIcon(R.drawable.ic_warning) },
            isChecked = state.enableSendAmountWarning,
            onClick = { onEvent(SettingsEvent.SendAmountWarningClick) },
            modifier = Modifier.testTag("SendAmountWarning")
        )

        SectionHeader(
            title = stringResource(R.string.settings__security__section_privacy),
            padding = PaddingValues(top = 16.dp)
        )

        SettingsSwitchRow(
            title = stringResource(R.string.settings__security__swipe_balance_to_hide),
            icon = { SettingsIcon(R.drawable.ic_hand_pointing) },
            isChecked = state.enableSwipeToHideBalance,
            onClick = { onEvent(SettingsEvent.SwipeToHideBalanceClick) },
            modifier = Modifier.testTag("SwipeBalanceToHide")
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings__security__hide_balance_on_open),
            icon = { SettingsIcon(R.drawable.ic_eye_slash) },
            isChecked = state.hideBalanceOnOpen,
            onClick = { onEvent(SettingsEvent.HideBalanceOnOpenClick) },
            modifier = Modifier.testTag("HideBalanceOnOpen")
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings__security__clipboard),
            icon = { SettingsIcon(R.drawable.ic_clipboard_text) },
            isChecked = state.enableAutoReadClipboard,
            onClick = { onEvent(SettingsEvent.AutoReadClipboardClick) },
            modifier = Modifier.testTag("AutoReadClipboard")
        )

        VerticalSpacer(32.dp)
    }
}

@Composable
private fun AdvancedTabContent(
    state: AdvancedTabState,
    onEvent: OnSettingsEvent,
    topPadding: Dp = 0.dp,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = topPadding, start = 16.dp, end = 16.dp)
            .testTag("advanced_settings_screen")
    ) {
        if (state.isDevModeEnabled) {
            SectionHeader(title = stringResource(R.string.settings__adv__section_debug))

            SettingsButtonRow(
                title = stringResource(R.string.settings__dev_title),
                icon = { SettingsIcon(R.drawable.ic_settings_dev) },
                onClick = { onEvent(SettingsEvent.DevSettingsClick) },
                modifier = Modifier.testTag("DevSettings")
            )
        }

        SectionHeader(title = stringResource(R.string.settings__adv__section_payments))

        SettingsButtonRow(
            title = stringResource(R.string.settings__addr_type__title),
            icon = { SettingsIcon(R.drawable.ic_list_dashes) },
            value = if (state.selectedAddressTypeName.isNotEmpty()) {
                SettingsButtonValue.StringValue(state.selectedAddressTypeName)
            } else {
                SettingsButtonValue.None
            },
            onClick = { onEvent(SettingsEvent.AddressTypeClick) },
            modifier = Modifier.testTag("AddressTypePreference")
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__adv__coin_selection),
            icon = { SettingsIcon(R.drawable.ic_coins) },
            value = SettingsButtonValue.StringValue(
                stringResource(
                    if (state.coinSelectAuto) R.string.settings__adv__cs_auto else R.string.settings__adv__cs_manual
                )
            ),
            onClick = { onEvent(SettingsEvent.CoinSelectionClick) },
            modifier = Modifier.testTag("CoinSelectPreference")
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__adv__address_viewer),
            icon = { SettingsIcon(R.drawable.ic_eye) },
            onClick = { onEvent(SettingsEvent.AddressViewerClick) },
            modifier = Modifier.testTag("AddressViewer")
        )

        SectionHeader(
            title = stringResource(R.string.settings__adv__section_networks),
            padding = PaddingValues(top = 16.dp)
        )

        SettingsButtonRow(
            title = stringResource(R.string.settings__adv__lightning_connections),
            icon = { SettingsIcon(R.drawable.ic_lightning) },
            value = if (state.openChannelCount > 0) {
                SettingsButtonValue.StringValue(state.openChannelCount.toString())
            } else {
                SettingsButtonValue.None
            },
            onClick = { onEvent(SettingsEvent.LightningConnectionsClick) },
            modifier = Modifier.testTag("Channels")
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__adv__lightning_node),
            icon = { SettingsIcon(R.drawable.ic_git_branch) },
            value = if (state.truncatedNodeId.isNotEmpty()) {
                SettingsButtonValue.StringValue("${state.truncatedNodeId}...")
            } else {
                SettingsButtonValue.None
            },
            onClick = { onEvent(SettingsEvent.LightningNodeClick) },
            modifier = Modifier.testTag("LightningNodeInfo")
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__adv__electrum_server),
            icon = { SettingsIcon(R.drawable.ic_hard_drives) },
            value = SettingsButtonValue.StringValue(
                state.electrumHost.ifEmpty { stringResource(R.string.settings__adv__electrum_auto) }
            ),
            onClick = { onEvent(SettingsEvent.ElectrumServerClick) },
            modifier = Modifier.testTag("ElectrumConfig")
        )
        SettingsButtonRow(
            title = stringResource(R.string.settings__adv__rgs_server),
            icon = { SettingsIcon(R.drawable.ic_broadcast) },
            onClick = { onEvent(SettingsEvent.RgsServerClick) },
            modifier = Modifier.testTag("RGSServer")
        )

        VerticalSpacer(32.dp)
    }
}

@Preview(showSystemUi = true)
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

@Preview(showSystemUi = true)
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

@Preview(showSystemUi = true)
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

sealed interface SettingsEvent {
    // General
    data object LanguageClick : SettingsEvent
    data object LocalCurrencyClick : SettingsEvent
    data object DefaultUnitClick : SettingsEvent
    data object WidgetsClick : SettingsEvent
    data object TagsClick : SettingsEvent
    data object TransactionSpeedClick : SettingsEvent
    data object PaymentPreferenceClick : SettingsEvent
    data object QuickPayClick : SettingsEvent
    data object BgPaymentsClick : SettingsEvent

    // Security
    data object BackupWalletClick : SettingsEvent
    data object DataBackupsClick : SettingsEvent
    data object ResetWalletClick : SettingsEvent
    data object PinClick : SettingsEvent
    data object PinForPaymentsClick : SettingsEvent
    data object UseBiometricsClick : SettingsEvent
    data object SwipeToHideBalanceClick : SettingsEvent
    data object HideBalanceOnOpenClick : SettingsEvent
    data object AutoReadClipboardClick : SettingsEvent
    data object SendAmountWarningClick : SettingsEvent

    // Advanced
    data object DevSettingsClick : SettingsEvent
    data object AddressTypeClick : SettingsEvent
    data object CoinSelectionClick : SettingsEvent
    data object AddressViewerClick : SettingsEvent
    data object LightningConnectionsClick : SettingsEvent
    data object LightningNodeClick : SettingsEvent
    data object ElectrumServerClick : SettingsEvent
    data object RgsServerClick : SettingsEvent

    // Navigation
    data object BackClick : SettingsEvent
}

typealias OnSettingsEvent = (SettingsEvent) -> Unit

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
    val isPubkyAuthenticated: Boolean = false,
    val isPaykitEnabled: Boolean = false,
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
    val coinSelectAuto: Boolean = true,
    val openChannelCount: Int = 0,
    val truncatedNodeId: String = "",
    val electrumHost: String = "",
)

package to.bitkit.ui.nav

import androidx.compose.runtime.Stable
import androidx.navigation3.runtime.NavKey
import com.synonym.bitkitcore.Activity
import kotlinx.serialization.Serializable

@Stable
sealed interface Routes : NavKey {
    // Core screens
    @Serializable
    data object Home : Routes

    @Serializable
    data object Savings : Routes

    @Serializable
    data object Spending : Routes

    @Serializable
    data object AllActivity : Routes

    // Onboarding
    @Serializable
    data object Terms : Routes

    @Serializable
    data object Intro : Routes

    @Serializable
    data class Slides(val tab: Int = 0) : Routes

    @Serializable
    data object Restore : Routes

    @Serializable
    data object Advanced : Routes

    @Serializable
    data object WarningMultipleDevices : Routes

    // Settings
    @Serializable
    data object Settings : Routes

    @Serializable
    data object NodeInfo : Routes

    @Serializable
    data object GeneralSettings : Routes

    @Serializable
    data object TransactionSpeedSettings : Routes

    @Serializable
    data object WidgetsSettings : Routes

    @Serializable
    data object TagsSettings : Routes

    @Serializable
    data object AdvancedSettings : Routes

    @Serializable
    data object CoinSelectPreference : Routes

    @Serializable
    data object ElectrumConfig : Routes

    @Serializable
    data object RgsServer : Routes

    @Serializable
    data object AddressViewer : Routes

    @Serializable
    data object AboutSettings : Routes

    @Serializable
    data object CustomFeeSettings : Routes

    @Serializable
    data object SecuritySettings : Routes

    @Serializable
    data object DisablePin : Routes

    @Serializable
    data object ChangePin : Routes

    @Serializable
    data object ChangePinNew : Routes

    @Serializable
    data class ChangePinConfirm(val newPin: String) : Routes

    @Serializable
    data object ChangePinResult : Routes

    @Serializable
    data class AuthCheck(
        val showLogoOnPin: Boolean = false,
        val requirePin: Boolean = false,
        val requireBiometrics: Boolean = false,
        val onSuccessActionId: String,
    ) : Routes

    @Serializable
    data object DefaultUnitSettings : Routes

    @Serializable
    data object LocalCurrencySettings : Routes

    @Serializable
    data object BackupSettings : Routes

    @Serializable
    data object ResetAndRestoreSettings : Routes

    @Serializable
    data object ChannelOrdersSettings : Routes

    @Serializable
    data object Logs : Routes

    @Serializable
    data class LogDetail(val fileName: String) : Routes

    // Activity detail - passes full serializable object
    @Serializable
    data class ActivityDetail(val activity: Activity) : Routes

    @Serializable
    data class ActivityExplore(val id: String) : Routes

    // Orders - passes ID to look up from ViewModel
    @Serializable
    data class OrderDetail(val orderId: String) : Routes

    @Serializable
    data class CjitDetail(val entryId: String) : Routes

    // Lightning connections
    @Serializable
    data object LightningConnections : Routes

    @Serializable
    data object ChannelDetail : Routes

    @Serializable
    data object CloseConnection : Routes

    // Dev settings
    @Serializable
    data object DevSettings : Routes

    @Serializable
    data object LdkDebug : Routes

    @Serializable
    data object FeeSettings : Routes

    @Serializable
    data object RegtestSettings : Routes

    // Transfer flow
    @Serializable
    data object TransferIntro : Routes

    @Serializable
    data object SpendingIntro : Routes

    @Serializable
    data object SpendingAmount : Routes

    @Serializable
    data object SpendingConfirm : Routes

    @Serializable
    data object SpendingAdvanced : Routes

    @Serializable
    data object TransferLiquidity : Routes

    @Serializable
    data object SettingUp : Routes

    @Serializable
    data object SavingsIntro : Routes

    @Serializable
    data object SavingsAvailability : Routes

    @Serializable
    data object SavingsConfirm : Routes

    @Serializable
    data object SavingsAdvanced : Routes

    @Serializable
    data object SavingsProgress : Routes

    @Serializable
    data object Funding : Routes

    @Serializable
    data object FundingAdvanced : Routes

    // External node
    @Serializable
    data class ExternalConnection(val scannedNodeUri: String? = null) : Routes

    @Serializable
    data object ExternalAmount : Routes

    @Serializable
    data object ExternalConfirm : Routes

    @Serializable
    data object ExternalSuccess : Routes

    @Serializable
    data object ExternalFeeCustom : Routes

    @Serializable
    data object ExternalNodeScanner : Routes

    @Serializable
    data class LnurlChannel(val uri: String, val callback: String, val k1: String) : Routes

    // Scanner
    @Serializable
    data object QrScanner : Routes

    // Buy
    @Serializable
    data object BuyIntro : Routes

    // Support
    @Serializable
    data object Support : Routes

    @Serializable
    data object ReportIssue : Routes

    @Serializable
    data object ReportIssueSuccess : Routes

    @Serializable
    data object ReportIssueFailure : Routes

    // Quick Pay
    @Serializable
    data object QuickPayIntro : Routes

    @Serializable
    data object QuickPaySettings : Routes

    // Language
    @Serializable
    data object LanguageSettings : Routes

    // Profile
    @Serializable
    data object ProfileIntro : Routes

    @Serializable
    data object CreateProfile : Routes

    // Shop
    @Serializable
    data object ShopIntro : Routes

    @Serializable
    data object ShopDiscover : Routes

    @Serializable
    data class ShopWebView(val page: String, val title: String) : Routes

    // Widgets
    @Serializable
    data object WidgetsIntro : Routes

    @Serializable
    data object AddWidget : Routes

    @Serializable
    data object Headlines : Routes

    @Serializable
    data object HeadlinesPreview : Routes

    @Serializable
    data object HeadlinesEdit : Routes

    @Serializable
    data object Facts : Routes

    @Serializable
    data object FactsPreview : Routes

    @Serializable
    data object FactsEdit : Routes

    @Serializable
    data object Blocks : Routes

    @Serializable
    data object BlocksPreview : Routes

    @Serializable
    data object BlocksEdit : Routes

    @Serializable
    data object Weather : Routes

    @Serializable
    data object WeatherPreview : Routes

    @Serializable
    data object WeatherEdit : Routes

    @Serializable
    data object Price : Routes

    @Serializable
    data object PricePreview : Routes

    @Serializable
    data object PriceEdit : Routes

    @Serializable
    data object CalculatorPreview : Routes

    // App status
    @Serializable
    data object AppStatus : Routes

    @Serializable
    data object CriticalUpdate : Routes

    // Recovery
    @Serializable
    data object RecoveryMode : Routes

    @Serializable
    data object RecoveryMnemonic : Routes

    // Background payments
    @Serializable
    data object BackgroundPaymentsIntro : Routes

    @Serializable
    data object BackgroundPaymentsSettings : Routes

    // region Send Flow (17 routes)
    @Serializable
    data object SendRecipient : Routes

    @Serializable
    data object SendAddress : Routes

    @Serializable
    data class SendAmount(val prefill: String? = null) : Routes

    @Serializable
    data object SendQrScanner : Routes

    @Serializable
    data object SendCoinSelection : Routes

    @Serializable
    data object SendFeeRate : Routes

    @Serializable
    data object SendFeeCustom : Routes

    @Serializable
    data object SendConfirm : Routes

    @Serializable
    data object SendSuccess : Routes

    @Serializable
    data class SendError(val message: String) : Routes

    @Serializable
    data object SendWithdrawConfirm : Routes

    @Serializable
    data object SendWithdrawError : Routes

    @Serializable
    data object SendSupport : Routes

    @Serializable
    data object SendAddTag : Routes

    @Serializable
    data object SendPinCheck : Routes

    @Serializable
    data object SendQuickPay : Routes
    // endregion

    // region Receive Flow (9 routes)
    @Serializable
    data object ReceiveQr : Routes

    @Serializable
    data object ReceiveAmount : Routes

    @Serializable
    data object ReceiveConfirm : Routes

    @Serializable
    data object ReceiveConfirmInbound : Routes

    @Serializable
    data object ReceiveLiquidity : Routes

    @Serializable
    data object ReceiveLiquidityAdditional : Routes

    @Serializable
    data object ReceiveEditInvoice : Routes

    @Serializable
    data object ReceiveAddTag : Routes

    @Serializable
    data object ReceiveGeoBlock : Routes
    // endregion

    // region Pin Flow (5 routes)
    @Serializable
    data class PinPrompt(val showLaterButton: Boolean = false) : Routes

    @Serializable
    data object PinChoose : Routes

    @Serializable
    data class PinConfirm(val pin: String) : Routes

    @Serializable
    data object PinBiometrics : Routes

    @Serializable
    data class PinResult(val isBioOn: Boolean) : Routes
    // endregion

    // region Backup Flow (9 routes)
    @Serializable
    data object BackupIntro : Routes

    @Serializable
    data object BackupShowMnemonic : Routes

    @Serializable
    data object BackupShowPassphrase : Routes

    @Serializable
    data object BackupConfirmMnemonic : Routes

    @Serializable
    data object BackupConfirmPassphrase : Routes

    @Serializable
    data object BackupWarning : Routes

    @Serializable
    data object BackupSuccess : Routes

    @Serializable
    data object BackupMultipleDevices : Routes

    @Serializable
    data object BackupMetadata : Routes
    // endregion

    // region Gift Flow (5 routes)
    @Serializable
    data class GiftLoading(val code: String, val amount: ULong) : Routes

    @Serializable
    data object GiftUsed : Routes

    @Serializable
    data object GiftUsedUp : Routes

    @Serializable
    data object GiftError : Routes

    @Serializable
    data object GiftSuccess : Routes
    // endregion

    // region Simple Sheets (4 routes)
    @Serializable
    data object ActivityDateRangeSelectorSheet : Routes

    @Serializable
    data object ActivityTagSelectorSheet : Routes

    @Serializable
    data class LnurlAuthSheet(val domain: String, val lnurl: String, val k1: String) : Routes

    @Serializable
    data object ForceTransferSheet : Routes

    // Timed Sheets
    @Serializable
    data object TimedUpdateSheet : Routes

    @Serializable
    data object TimedBackupSheet : Routes

    @Serializable
    data object TimedNotificationsSheet : Routes

    @Serializable
    data object TimedQuickPaySheet : Routes

    @Serializable
    data object TimedHighBalanceSheet : Routes
    // endregion
}

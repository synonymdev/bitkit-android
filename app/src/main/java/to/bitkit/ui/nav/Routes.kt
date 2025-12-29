package to.bitkit.ui.nav

import androidx.compose.runtime.Stable
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import com.synonym.bitkitcore.Activity as BitkitCoreActivity

@Stable
sealed interface Routes : NavKey {

    @Serializable
    data object Home : Routes

    @Serializable
    data object Savings : Routes

    @Serializable
    data object Spending : Routes

    @Serializable
    data object QrScanner : Routes

    @Serializable
    data object AppStatus : Routes

    @Serializable
    data object Support : Routes

    @Serializable
    data object BuyIntro : Routes

    @Serializable
    data object CriticalUpdate : Routes

    @Serializable
    data class AuthCheck(
        val showLogoOnPin: Boolean = false,
        val requirePin: Boolean = false,
        val requireBiometrics: Boolean = false,
        val onSuccessActionId: String,
    ) : Routes

    object Activity {
        @Serializable
        data object All : Routes

        @Serializable
        data class Detail(val activity: BitkitCoreActivity) : Routes

        @Serializable
        data class Explore(val id: String) : Routes

        @Serializable
        data object DateRangeSelectorSheet : Routes

        @Serializable
        data object TagSelectorSheet : Routes
    }

    object Onboarding {
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
    }

    object Settings {
        @Serializable
        data object Main : Routes

        @Serializable
        data object General : Routes

        @Serializable
        data object NodeInfo : Routes

        @Serializable
        data object TransactionSpeed : Routes

        @Serializable
        data object Widgets : Routes

        @Serializable
        data object Tags : Routes

        @Serializable
        data object Advanced : Routes

        @Serializable
        data object CoinSelectPreference : Routes

        @Serializable
        data object ElectrumConfig : Routes

        @Serializable
        data object RgsServer : Routes

        @Serializable
        data object AddressViewer : Routes

        @Serializable
        data object About : Routes

        @Serializable
        data object CustomFee : Routes

        @Serializable
        data object Security : Routes

        @Serializable
        data object DisablePin : Routes

        @Serializable
        data object DefaultUnit : Routes

        @Serializable
        data object LocalCurrency : Routes

        @Serializable
        data object BackupSettings : Routes

        @Serializable
        data object ResetAndRestore : Routes

        @Serializable
        data object LightningConnections : Routes

        @Serializable
        data object ChannelDetail : Routes

        @Serializable
        data object CloseConnection : Routes

        @Serializable
        data object Fee : Routes

        @Serializable
        data object Language : Routes

        object Dev {
            @Serializable
            data object Main : Routes

            @Serializable
            data object ChannelOrders : Routes

            @Serializable
            data object Regtest : Routes

            @Serializable
            data object LdkDebug : Routes

            @Serializable
            data class OrderDetail(val orderId: String) : Routes

            @Serializable
            data class CjitDetail(val entryId: String) : Routes

            object Log {
                @Serializable
                data object List : Routes

                @Serializable
                data class Detail(val fileName: String) : Routes
            }
        }
    }

    object Profile {
        @Serializable
        data object Intro : Routes

        @Serializable
        data object Create : Routes
    }

    object Widgets {
        @Serializable
        data object Intro : Routes

        @Serializable
        data object Add : Routes

        object Headlines {
            @Serializable
            data object Main : Routes

            @Serializable
            data object Preview : Routes

            @Serializable
            data object Edit : Routes
        }

        object Facts {
            @Serializable
            data object Main : Routes

            @Serializable
            data object Preview : Routes

            @Serializable
            data object Edit : Routes
        }

        object Blocks {
            @Serializable
            data object Main : Routes

            @Serializable
            data object Preview : Routes

            @Serializable
            data object Edit : Routes
        }

        object Weather {
            @Serializable
            data object Main : Routes

            @Serializable
            data object Preview : Routes

            @Serializable
            data object Edit : Routes
        }

        object Price {
            @Serializable
            data object Main : Routes

            @Serializable
            data object Preview : Routes

            @Serializable
            data object Edit : Routes
        }

        object Calculator {
            @Serializable
            data object Preview : Routes
        }
    }

    object Send {
        @Serializable
        data object Recipient : Routes

        @Serializable
        data object Address : Routes

        @Serializable
        data class Amount(val prefill: String? = null) : Routes

        @Serializable
        data object QrScanner : Routes

        @Serializable
        data object CoinSelection : Routes

        @Serializable
        data object FeeRate : Routes

        @Serializable
        data object FeeCustom : Routes

        @Serializable
        data object Confirm : Routes

        @Serializable
        data object Success : Routes

        @Serializable
        data class Error(val message: String) : Routes

        @Serializable
        data object WithdrawConfirm : Routes

        @Serializable
        data object WithdrawError : Routes

        @Serializable
        data object Support : Routes

        @Serializable
        data object AddTag : Routes

        @Serializable
        data object PinCheck : Routes

        @Serializable
        data object QuickPay : Routes
    }

    object Receive {
        @Serializable
        data object Qr : Routes

        @Serializable
        data object Amount : Routes

        @Serializable
        data object Confirm : Routes

        @Serializable
        data object ConfirmInbound : Routes

        @Serializable
        data object Liquidity : Routes

        @Serializable
        data object LiquidityAdditional : Routes

        @Serializable
        data object EditInvoice : Routes

        @Serializable
        data object AddTag : Routes

        @Serializable
        data object GeoBlock : Routes
    }

    object Pin {
        @Serializable
        data class Prompt(val showLaterButton: Boolean = false) : Routes

        @Serializable
        data object Choose : Routes

        @Serializable
        data class Confirm(val pin: String) : Routes

        @Serializable
        data object Biometrics : Routes

        @Serializable
        data class Result(val isBioOn: Boolean) : Routes

        object Change {
            @Serializable
            data object Start : Routes

            @Serializable
            data object New : Routes

            @Serializable
            data class Confirm(val newPin: String) : Routes

            @Serializable
            data object Result : Routes
        }
    }

    object Backup {
        @Serializable
        data object Intro : Routes

        @Serializable
        data object ShowMnemonic : Routes

        @Serializable
        data object ShowPassphrase : Routes

        @Serializable
        data object ConfirmMnemonic : Routes

        @Serializable
        data object ConfirmPassphrase : Routes

        @Serializable
        data object Warning : Routes

        @Serializable
        data object Success : Routes

        @Serializable
        data object MultipleDevices : Routes

        @Serializable
        data object Metadata : Routes
    }

    object Gift {
        @Serializable
        data class Loading(val code: String, val amount: ULong) : Routes

        @Serializable
        data object Used : Routes

        @Serializable
        data object UsedUp : Routes

        @Serializable
        data object Error : Routes

        @Serializable
        data object Success : Routes
    }

    object Sheet {
        @Serializable
        data object Update : Routes

        @Serializable
        data object Backup : Routes

        @Serializable
        data object Notifications : Routes

        @Serializable
        data object QuickPay : Routes

        @Serializable
        data object HighBalance : Routes

        @Serializable
        data object ForceTransfer : Routes

        @Serializable
        data class LnurlChannel(val uri: String, val callback: String, val k1: String) : Routes

        @Serializable
        data class LnurlAuth(val domain: String, val lnurl: String, val k1: String) : Routes
    }

    object External {
        @Serializable
        data class Connection(val scannedNodeUri: String? = null) : Routes

        @Serializable
        data object Amount : Routes

        @Serializable
        data object Confirm : Routes

        @Serializable
        data object Success : Routes

        @Serializable
        data object FeeCustom : Routes

        @Serializable
        data object NodeScanner : Routes
    }

    object Transfer {
        @Serializable
        data object Intro : Routes

        @Serializable
        data object Liquidity : Routes

        @Serializable
        data object SettingUp : Routes

        @Serializable
        data object Funding : Routes

        @Serializable
        data object FundingAdvanced : Routes

        object ToSavings {
            @Serializable
            data object Intro : Routes

            @Serializable
            data object Availability : Routes

            @Serializable
            data object Confirm : Routes

            @Serializable
            data object Advanced : Routes

            @Serializable
            data object Progress : Routes
        }

        object ToSpending {
            @Serializable
            data object Intro : Routes

            @Serializable
            data object Amount : Routes

            @Serializable
            data object Confirm : Routes

            @Serializable
            data object Advanced : Routes
        }
    }

    object QuickPay {
        @Serializable
        data object Intro : Routes

        @Serializable
        data object Settings : Routes
    }

    object BackgroundPayments {
        @Serializable
        data object Intro : Routes

        @Serializable
        data object Settings : Routes
    }

    object Shop {
        @Serializable
        data object Intro : Routes

        @Serializable
        data object Discover : Routes

        @Serializable
        data class WebView(val page: String, val title: String) : Routes
    }

    object ReportIssue {
        @Serializable
        data object Form : Routes

        @Serializable
        data object Success : Routes

        @Serializable
        data object Failure : Routes
    }

    object Recovery {
        @Serializable
        data object Mode : Routes

        @Serializable
        data object Mnemonic : Routes
    }
}

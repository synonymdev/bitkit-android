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

    sealed interface Activity : Routes {
        @Serializable
        data object All : Activity

        @Serializable
        data class Detail(val activity: BitkitCoreActivity) : Activity

        @Serializable
        data class Explore(val id: String) : Activity

        @Serializable
        data object DateRangeSelectorSheet : Activity

        @Serializable
        data object TagSelectorSheet : Activity
    }

    sealed interface Onboarding : Routes {
        @Serializable
        data object Terms : Onboarding

        @Serializable
        data object Intro : Onboarding

        @Serializable
        data class Slides(val tab: Int = 0) : Onboarding

        @Serializable
        data object Restore : Onboarding

        @Serializable
        data object Advanced : Onboarding

        @Serializable
        data object WarningMultipleDevices : Onboarding
    }

    sealed interface Settings : Routes {
        @Serializable
        data object Main : Settings

        @Serializable
        data object General : Settings

        @Serializable
        data object NodeInfo : Settings

        @Serializable
        data object TransactionSpeed : Settings

        @Serializable
        data object Widgets : Settings

        @Serializable
        data object Tags : Settings

        @Serializable
        data object Advanced : Settings

        @Serializable
        data object CoinSelectPreference : Settings

        @Serializable
        data object ElectrumConfig : Settings

        @Serializable
        data object RgsServer : Settings

        @Serializable
        data object AddressViewer : Settings

        @Serializable
        data object About : Settings

        @Serializable
        data object CustomFee : Settings

        @Serializable
        data object Security : Settings

        @Serializable
        data object DisablePin : Settings

        @Serializable
        data object DefaultUnit : Settings

        @Serializable
        data object LocalCurrency : Settings

        @Serializable
        data object BackupSettings : Settings

        @Serializable
        data object ResetAndRestore : Settings

        @Serializable
        data object LightningConnections : Settings

        @Serializable
        data object ChannelDetail : Settings

        @Serializable
        data object CloseConnection : Settings

        @Serializable
        data object Fee : Settings

        @Serializable
        data object Language : Settings

        sealed interface Dev : Settings {
            @Serializable
            data object Main : Dev

            @Serializable
            data object ChannelOrders : Dev

            @Serializable
            data object Regtest : Dev

            @Serializable
            data object LdkDebug : Dev

            @Serializable
            data class OrderDetail(val orderId: String) : Dev

            @Serializable
            data class CjitDetail(val entryId: String) : Dev

            sealed interface Log : Dev {
                @Serializable
                data object List : Log

                @Serializable
                data class Detail(val fileName: String) : Log
            }
        }
    }

    sealed interface Profile : Routes {
        @Serializable
        data object Intro : Profile

        @Serializable
        data object Create : Profile
    }

    sealed interface Widgets : Routes {
        @Serializable
        data object Intro : Widgets

        @Serializable
        data object Add : Widgets

        sealed interface Headlines : Widgets {
            @Serializable
            data object Main : Headlines

            @Serializable
            data object Preview : Headlines

            @Serializable
            data object Edit : Headlines
        }

        sealed interface Facts : Widgets {
            @Serializable
            data object Main : Facts

            @Serializable
            data object Preview : Facts

            @Serializable
            data object Edit : Facts
        }

        sealed interface Blocks : Widgets {
            @Serializable
            data object Main : Blocks

            @Serializable
            data object Preview : Blocks

            @Serializable
            data object Edit : Blocks
        }

        sealed interface Weather : Widgets {
            @Serializable
            data object Main : Weather

            @Serializable
            data object Preview : Weather

            @Serializable
            data object Edit : Weather
        }

        sealed interface Price : Widgets {
            @Serializable
            data object Main : Price

            @Serializable
            data object Preview : Price

            @Serializable
            data object Edit : Price
        }

        sealed interface Calculator : Widgets {
            @Serializable
            data object Preview : Calculator
        }
    }

    sealed interface Send : Routes {
        @Serializable
        data object Recipient : Send

        @Serializable
        data object Address : Send

        @Serializable
        data class Amount(val prefill: String? = null) : Send

        @Serializable
        data object QrScanner : Send

        @Serializable
        data object CoinSelection : Send

        @Serializable
        data object FeeRate : Send

        @Serializable
        data object FeeCustom : Send

        @Serializable
        data object Confirm : Send

        @Serializable
        data object Success : Send

        @Serializable
        data class Error(val message: String) : Send

        @Serializable
        data object WithdrawConfirm : Send

        @Serializable
        data object WithdrawError : Send

        @Serializable
        data object Support : Send

        @Serializable
        data object AddTag : Send

        @Serializable
        data object PinCheck : Send

        @Serializable
        data object QuickPay : Send
    }

    sealed interface Receive : Routes {
        @Serializable
        data object Qr : Receive

        @Serializable
        data object Amount : Receive

        @Serializable
        data object Confirm : Receive

        @Serializable
        data object ConfirmInbound : Receive

        @Serializable
        data object Liquidity : Receive

        @Serializable
        data object LiquidityAdditional : Receive

        @Serializable
        data object EditInvoice : Receive

        @Serializable
        data object AddTag : Receive

        @Serializable
        data object GeoBlock : Receive
    }

    sealed interface Pin : Routes {
        @Serializable
        data class Prompt(val showLaterButton: Boolean = false) : Pin

        @Serializable
        data object Choose : Pin

        @Serializable
        data class Confirm(val pin: String) : Pin

        @Serializable
        data object Biometrics : Pin

        @Serializable
        data class Result(val isBioOn: Boolean) : Pin

        sealed interface Change : Pin {
            @Serializable
            data object Start : Change

            @Serializable
            data object New : Change

            @Serializable
            data class Confirm(val newPin: String) : Change

            @Serializable
            data object Result : Change
        }
    }

    sealed interface Backup : Routes {
        @Serializable
        data object Intro : Backup

        @Serializable
        data object ShowMnemonic : Backup

        @Serializable
        data object ShowPassphrase : Backup

        @Serializable
        data object ConfirmMnemonic : Backup

        @Serializable
        data object ConfirmPassphrase : Backup

        @Serializable
        data object Warning : Backup

        @Serializable
        data object Success : Backup

        @Serializable
        data object MultipleDevices : Backup

        @Serializable
        data object Metadata : Backup
    }

    sealed interface Gift : Routes {
        @Serializable
        data class Loading(val code: String, val amount: ULong) : Gift

        @Serializable
        data object Used : Gift

        @Serializable
        data object UsedUp : Gift

        @Serializable
        data object Error : Gift

        @Serializable
        data object Success : Gift
    }

    sealed interface Sheet : Routes {
        @Serializable
        data object Update : Sheet

        @Serializable
        data object Backup : Sheet

        @Serializable
        data object Notifications : Sheet

        @Serializable
        data object QuickPay : Sheet

        @Serializable
        data object HighBalance : Sheet

        @Serializable
        data object ForceTransfer : Sheet

        @Serializable
        data class LnurlChannel(val uri: String, val callback: String, val k1: String) : Sheet

        @Serializable
        data class LnurlAuth(val domain: String, val lnurl: String, val k1: String) : Sheet
    }

    sealed interface External : Routes {
        @Serializable
        data class Connection(val scannedNodeUri: String? = null) : External

        @Serializable
        data object Amount : External

        @Serializable
        data object Confirm : External

        @Serializable
        data object Success : External

        @Serializable
        data object FeeCustom : External

        @Serializable
        data object NodeScanner : External
    }

    sealed interface Transfer : Routes {
        @Serializable
        data object Intro : Transfer

        @Serializable
        data object Liquidity : Transfer

        @Serializable
        data object SettingUp : Transfer

        @Serializable
        data object Funding : Transfer

        @Serializable
        data object FundingAdvanced : Transfer

        sealed interface ToSavings : Transfer {
            @Serializable
            data object Intro : ToSavings

            @Serializable
            data object Availability : ToSavings

            @Serializable
            data object Confirm : ToSavings

            @Serializable
            data object Advanced : ToSavings

            @Serializable
            data object Progress : ToSavings
        }

        sealed interface ToSpending : Transfer {
            @Serializable
            data object Intro : ToSpending

            @Serializable
            data object Amount : ToSpending

            @Serializable
            data object Confirm : ToSpending

            @Serializable
            data object Advanced : ToSpending
        }
    }

    sealed interface QuickPay : Routes {
        @Serializable
        data object Intro : QuickPay

        @Serializable
        data object Settings : QuickPay
    }

    sealed interface BackgroundPayments : Routes {
        @Serializable
        data object Intro : BackgroundPayments

        @Serializable
        data object Settings : BackgroundPayments
    }

    sealed interface Shop : Routes {
        @Serializable
        data object Intro : Shop

        @Serializable
        data object Discover : Shop

        @Serializable
        data class WebView(val page: String, val title: String) : Shop
    }

    sealed interface ReportIssue : Routes {
        @Serializable
        data object Form : ReportIssue

        @Serializable
        data object Success : ReportIssue

        @Serializable
        data object Failure : ReportIssue
    }

    sealed interface Recovery : Routes {
        @Serializable
        data object Mode : Recovery

        @Serializable
        data object Mnemonic : Recovery
    }
}

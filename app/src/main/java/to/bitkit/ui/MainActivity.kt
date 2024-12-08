package to.bitkit.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.lightningdevkit.ldknode.Event
import to.bitkit.env.Tag.LDK
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.Toast
import to.bitkit.ui.components.ToastOverlay
import to.bitkit.ui.screens.wallets.sheets.NewTransactionSheet
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.CurrencyViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val appViewModel by viewModels<AppViewModel>()
    private val walletViewModel by viewModels<WalletViewModel>()
    private val currencyViewModel by viewModels<CurrencyViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initNotificationChannel()

        walletViewModel.setOnEvent(::onLdkEvent)

        enableEdgeToEdge()
        setContent {
            AppThemeSurface {
                ContentView(appViewModel, walletViewModel, currencyViewModel) {
                    launchStartupActivity()
                }

                ToastOverlay(
                    toast = appViewModel.currentToast,
                    onDismiss = {
                        appViewModel.hideToast()
                    }
                )
            }

            if (appViewModel.showNewTransaction) {
                NewTransactionSheet(appViewModel)
            }
        }
    }

    private fun launchStartupActivity() {
        startActivity(Intent(this, StartupActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        })
        finish()
    }

    private fun onLdkEvent(event: Event) = runOnUiThread {
        try {
            when (event) {
                is Event.PaymentReceived -> {
                    appViewModel.showNewTransactionSheet(
                        NewTransactionSheetDetails(
                            type = NewTransactionSheetType.LIGHTNING,
                            direction = NewTransactionSheetDirection.RECEIVED,
                            sats = (event.amountMsat / 1000u).toLong(),
                        )
                    )
                }

                is Event.ChannelPending -> {
                    // Only relevant for channels to external nodes
                }

                is Event.ChannelReady -> {
                    // TODO: handle cjit as payment received
                    val channel = walletViewModel.findChannelById(event.channelId)
                    if (channel != null) {
                        appViewModel.showNewTransactionSheet(
                            NewTransactionSheetDetails(
                                type = NewTransactionSheetType.LIGHTNING,
                                direction = NewTransactionSheetDirection.RECEIVED,
                                sats = (channel.inboundCapacityMsat / 1000u).toLong(),
                            )
                        )
                    } else {
                        appViewModel.toast(
                            type = Toast.ToastType.ERROR,
                            title = "Channel opened",
                            description = "Ready to send"
                        )
                    }
                }

                is Event.ChannelClosed -> {
                    appViewModel.toast(
                        type = Toast.ToastType.LIGHTNING,
                        title = "Channel closed",
                        description = "Balance moved from spending to savings"
                    )
                }

                is Event.PaymentSuccessful -> {
                    appViewModel.showNewTransactionSheet(
                        NewTransactionSheetDetails(
                            type = NewTransactionSheetType.LIGHTNING,
                            direction = NewTransactionSheetDirection.SENT,
                            sats = ((event.feePaidMsat ?: 0u) / 1000u).toLong(),
                        )
                    )
                }

                is Event.PaymentClaimable -> Unit
                is Event.PaymentFailed -> {
                    appViewModel.toast(
                        type = Toast.ToastType.ERROR,
                        title = "Payment failed",
                        description = event.reason?.name ?: "Unknown error"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(LDK, "Ldk event handler error", e)
        }
    }

    override fun onStart() {
        super.onStart()

        walletViewModel.start()

        val pendingTransaction = NewTransactionSheetDetails.load(this)
        if (pendingTransaction != null) {
            appViewModel.showNewTransactionSheet(pendingTransaction)
            NewTransactionSheetDetails.clear(this)
        }
    }

    override fun onStop() {
        super.onStop()
        walletViewModel.stopIfNeeded()
    }
}

package to.bitkit.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.lightningdevkit.ldknode.Event
import to.bitkit.env.Tag.LDK
import to.bitkit.ext.toast
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.services.LightningService
import to.bitkit.ui.screens.wallet.HomeScreen
import to.bitkit.ui.screens.wallet.sheets.NewTransactionSheet
import to.bitkit.ui.theme.AppThemeSurface

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    val viewModel by viewModels<WalletViewModel>()
    val app by viewModels<AppViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initNotificationChannel()

        viewModel.setOnEvent(::onLdkEvent)

        setContent {
            enableEdgeToEdge()
            AppThemeSurface {
                AppNavHost(viewModel) { navController ->
                    val uiState = viewModel.uiState.collectAsStateWithLifecycle()
                    Crossfade(uiState, label = "ContentCrossfade") {
                        when (val state = it.value) {
                            is MainUiState.Loading -> LoadingScreen()
                            is MainUiState.NoWallet -> WelcomeScreen(viewModel)
                            is MainUiState.Content -> HomeScreen(viewModel, state, navController)
                            is MainUiState.Error -> ErrorScreen(state)
                        }
                    }
                }

                if (app.showNewTransaction) {
                    NewTransactionSheet(app)
                }
            }
        }
    }

    private fun onLdkEvent(event: Event) = runOnUiThread {
        try {
            when (event) {
                is Event.PaymentReceived -> {
                    app.showNewTransactionSheet(
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
                    val channel = LightningService.shared.channels?.firstOrNull { it.channelId == event.channelId }
                    if (channel != null) {
                        app.showNewTransactionSheet(
                            NewTransactionSheetDetails(
                                type = NewTransactionSheetType.LIGHTNING,
                                direction = NewTransactionSheetDirection.SENT,
                                sats = (channel.inboundCapacityMsat / 1000u).toLong(),
                            )
                        )
                    } else {
                        toast("Channel Opened")
                    }
                }

                is Event.ChannelClosed -> {
                    toast("Channel Closed")
                }

                is Event.PaymentSuccessful -> {
                    app.showNewTransactionSheet(
                        NewTransactionSheetDetails(
                            type = NewTransactionSheetType.LIGHTNING,
                            direction = NewTransactionSheetDirection.SENT,
                            sats = ((event.feePaidMsat ?: 0u) / 1000u).toLong(),
                        )
                    )
                }

                is Event.PaymentClaimable -> Unit
                is Event.PaymentFailed -> {
                    toast("Payment failed")
                }
            }
        } catch (e: Exception) {
            Log.e(LDK, "Ldk event handler error", e)
        }
    }

    override fun onStart() {
        super.onStart()

        viewModel.start()

        val pendingTransaction = NewTransactionSheetDetails.load(this)
        if (pendingTransaction != null) {
            app.showNewTransactionSheet(pendingTransaction)
            NewTransactionSheetDetails.clear(this)
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.stopIfNeeded()
    }
}

@Composable
fun LoadingScreen() {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize(),
    ) {
        CircularProgressIndicator()
    }
}

@Composable
fun ErrorScreen(uiState: MainUiState.Error) {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize(),
    ) {
        Text(
            text = uiState.title,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = uiState.message,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

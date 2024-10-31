package to.bitkit.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import org.lightningdevkit.ldknode.Event
import to.bitkit.R
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
                val navController = rememberNavController()
                MainScreen(viewModel, navController) {
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
        viewModel.stop()
    }
}

// region scaffold
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    viewModel: WalletViewModel = hiltViewModel(),
    navController: NavHostController,
    content: @Composable () -> Unit,
) {
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val isBackButtonVisible by remember(currentBackStackEntry) {
        derivedStateOf {
            navController.currentDestination?.route != Routes.Main.destination
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (isBackButtonVisible) {
                        IconButton(onClick = navController::popBackStack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Default.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                },
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                },
                actions = {
                    IconButton(viewModel::refreshState) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.sync),
                        )
                    }
                    IconButton(onClick = { navController.navigate(Routes.NodeState.destination) }) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = "Node State",
                        )
                    }
                    IconButton({ navController.navigate(Routes.Settings.destination) }) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.settings),
                        )
                    }
                }
            )
        },
        modifier = Modifier.imePadding()
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AppNavHost(
                navController = navController,
                viewModel = viewModel,
                content = content,
            )
        }
    }
}
// endregion

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

package to.bitkit.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import to.bitkit.R
import to.bitkit.ext.requiresPermission
import to.bitkit.ext.toast
import to.bitkit.ui.shared.Channels
import to.bitkit.ui.shared.FullWidthTextButton
import to.bitkit.ui.shared.Orders
import to.bitkit.ui.shared.Payments
import to.bitkit.ui.shared.Peers
import to.bitkit.ui.theme.AppThemeSurface

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    val viewModel by viewModels<WalletViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initNotificationChannel()
        logFcmToken()

        viewModel.start()

        setContent {
            enableEdgeToEdge()
            AppThemeSurface {
                MainScreen(viewModel) {
                    val uiState = viewModel.uiState.collectAsStateWithLifecycle()
                    Crossfade(uiState, label = "ContentCrossfade") {
                        when (val state = it.value) {
                            is MainUiState.Loading -> LoadingScreen()
                            is MainUiState.NoWallet -> WelcomeScreen(viewModel)
                            is MainUiState.Content -> WalletScreen(viewModel, state, debugUi(state))
                            is MainUiState.Error -> ErrorScreen(state)
                        }
                    }
                }
            }
        }
    }
}

// region scaffold
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    viewModel: WalletViewModel = hiltViewModel(),
    startContent: @Composable () -> Unit = {},
) {
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val isBackButtonVisible by remember(currentBackStackEntry) {
        derivedStateOf {
            navController.previousBackStackEntry?.destination?.route == Routes.Settings.destination
        }
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
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
                    Text(stringResource(R.string.app_name))
                },
                actions = {
                    IconButton(viewModel::debugSync) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.sync),
                        )
                    }
                })
        },
        bottomBar = {
            NavigationBar(tonalElevation = 5.dp) {
                var selected by remember { mutableIntStateOf(0) }
                navItems.forEachIndexed { i, it ->
                    NavigationBarItem(
                        icon = {
                            val icon = if (selected != i) it.icon.first else it.icon.second
                            Icon(
                                imageVector = icon,
                                contentDescription = stringResource(it.title),
                            )
                        },
                        label = { Text(stringResource(it.title)) },
                        selected = selected == i,
                        onClick = {
                            selected = i
                            navController.navigate(it.route.destination) {
                                navController.graph.startDestinationRoute?.let { popUpTo(it) }
                                launchSingleTop = true
                            }
                        },
                    )
                }
            }
        },
        modifier = Modifier
            .imePadding()
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AppNavHost(
                navController = navController,
                viewModel = viewModel,
                walletScreen = startContent,
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

// region debug
fun MainActivity.debugUi(uiState: MainUiState.Content) = @Composable {
    Peers(uiState.peers, viewModel::disconnectPeer)
    Channels(uiState.channels, uiState.peers.isNotEmpty(), viewModel::openChannel, viewModel::closeChannel)
    Payments(viewModel)
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Debug",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(12.dp)
        )
        FullWidthTextButton(viewModel::debugDb) { Text("Database") }
        FullWidthTextButton(viewModel::debugKeychain) { Text("Keychain") }
        FullWidthTextButton(viewModel::debugWipe) { Text("Wipe Wallet") }
        FullWidthTextButton(viewModel::debugBlocktankInfo) { Text("Blocktank Info API") }
        HorizontalDivider()
        NotificationButton()
        FullWidthTextButton(viewModel::registerForNotifications) { Text("1. Register Device for Notifications") }
        FullWidthTextButton(viewModel::debugLspNotifications) { Text("2. Test Remote Notification") }
    }
    Orders(uiState.orders, viewModel)
}

@Composable
private fun NotificationButton() {
    val context = LocalContext.current
    var canPush by remember {
        mutableStateOf(!context.requiresPermission(postNotificationsPermission))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        canPush = it
        toast("Permission ${if (it) "Granted" else "Denied"}")
    }

    val onClick = {
        if (context.requiresPermission(postNotificationsPermission)) {
            permissionLauncher.launch(postNotificationsPermission)
        } else {
            pushNotification(
                title = "Bitkit Notification",
                text = "Short custom notification description",
                bigText = "Much longer text that cannot fit one line " + "because the lightning channel has been updated " + "via a push notification bro…",
            )
        }
        Unit
    }
    val text by remember {
        derivedStateOf { if (canPush) "Test Local Notification" else "Enable Notification Permissions" }
    }
    FullWidthTextButton(onClick = onClick) { Text(text = text) }
}
// endregion

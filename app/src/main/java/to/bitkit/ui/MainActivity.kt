package to.bitkit.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.NotificationAdd
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import to.bitkit.R
import to.bitkit.ext.requiresPermission
import to.bitkit.ext.toast
import to.bitkit.ui.shared.Channels
import to.bitkit.ui.shared.Peers
import to.bitkit.ui.theme.AppThemeSurface

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
    private val sharedViewModel by viewModels<SharedViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedViewModel.logInstanceHashCode()
        setContent {
            enableEdgeToEdge()
            AppThemeSurface {
                MainScreen(viewModel) {
                    WalletScreen(viewModel) {

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Debug",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 24.dp),
                            )
                            Row(
                                horizontalArrangement = Arrangement.SpaceAround,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                TextButton(viewModel::debugDb) { Text("Debug DB") }
                                TextButton(viewModel::debugKeychain) { Text("Debug Keychain") }
                                TextButton(viewModel::debugWipeBdk) { Text("Wipe BDK") }
                            }
                        }

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Notifications",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 24.dp),
                            )
                            Row(
                                horizontalArrangement = Arrangement.SpaceAround,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                TextButton(sharedViewModel::registerForNotifications) { Text("Register Device") }
                                TextButton(viewModel::debugLspNotifications) { Text("LSP Notification") }
                            }
                        }

                        Peers(viewModel.peers, viewModel::togglePeerConnection)
                        Channels(viewModel.channels, viewModel::closeChannel)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
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
                    NotificationButton()
                    IconButton(viewModel::refresh) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.sync),
                            modifier = Modifier,
                        )
                    }
                }
            )
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
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            AppNavHost(
                navController = navController,
                viewModel = viewModel,
                walletScreen = startContent,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Composable
private fun NotificationButton() {
    val context = LocalContext.current
    var canPush by remember {
        mutableStateOf(!context.requiresPermission(notificationPermission))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        canPush = it
        toast("Permission ${if (it) "Granted" else "Denied"}")
    }

    val onClick = {
        if (context.requiresPermission(notificationPermission)) {
            permissionLauncher.launch(notificationPermission)
        } else {
            pushNotification(
                title = "Bitkit Notification",
                text = "Short custom notification description",
                bigText = "Much longer text that cannot fit one line " +
                    "because the lightning channel has been updated " +
                    "via a push notification bro…",
            )
        }
        Unit
    }
    val icon by remember {
        derivedStateOf { if (canPush) Icons.Default.NotificationAdd else Icons.Default.NotificationsNone }
    }
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier,
        )
    }
}

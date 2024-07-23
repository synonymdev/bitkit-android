package to.bitkit.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import to.bitkit.ui.theme.AppThemeSurface

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            enableEdgeToEdge()
            AppThemeSurface {
                MainScreen(viewModel) {
                    val context = LocalContext.current

                    WalletScreen(viewModel) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "Other",
                                style = MaterialTheme.typography.titleLarge,
                            )

                            var canPush by remember {
                                mutableStateOf(!context.requiresPermission(notificationPermission))
                            }

                            // Request Permissions
                            val permissionLauncher = rememberLauncherForActivityResult(
                                ActivityResultContracts.RequestPermission()
                            ) {
                                canPush = it
                                toast("Permission ${if (it) "Granted" else "Denied"}")
                            }

                            val onNotificationsClick = {
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
                            Button(onClick = onNotificationsClick) {
                                Text(text = if (canPush) "Notify" else "Request Permissions")
                            }
                        }
                    }
                }
            }
        }
    }
}

private val notificationPermission
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        android.Manifest.permission.POST_NOTIFICATIONS
    } else {
        TODO("Cant request 'POST_NOTIFICATIONS' permissions on SDK < 33")
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
                    IconButton(viewModel::sync) {
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
        Box(Modifier.padding(padding)) {
            AppNavHost(
                navController = navController,
                viewModel = viewModel,
                startDestination = Routes.Wallet.destination,
                startContent = startContent,
            )
        }
    }
}

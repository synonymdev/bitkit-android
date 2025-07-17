package to.bitkit.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.Toast
import to.bitkit.repositories.ConnectivityState
import to.bitkit.viewmodels.AppViewModel

@Composable
fun IsOnlineTracker(
    app: AppViewModel,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val connectivityState by app.isOnline.collectAsStateWithLifecycle(initialValue = ConnectivityState.CONNECTED)

    var isInitialized by remember { mutableStateOf(false) }

    LaunchedEffect(connectivityState) {
        // Skip the first emission to prevent toast on startup
        if (!isInitialized) {
            isInitialized = true
            return@LaunchedEffect
        }

        when (connectivityState) {
            ConnectivityState.CONNECTED -> {
                app.toast(
                    type = Toast.ToastType.SUCCESS,
                    title = context.getString(R.string.other__connection_back_title),
                    description = context.getString(R.string.other__connection_back_msg),
                )
            }

            ConnectivityState.DISCONNECTED -> {
                app.toast(
                    type = Toast.ToastType.WARNING,
                    title = context.getString(R.string.other__connection_issue),
                    description = context.getString(R.string.other__connection_issue_explain),
                )
            }

            else -> Unit
        }
    }

    content()
}

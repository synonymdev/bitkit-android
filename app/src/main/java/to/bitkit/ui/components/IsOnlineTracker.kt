package to.bitkit.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.Toast
import to.bitkit.repositories.ConnectivityState
import to.bitkit.viewmodels.AppViewModel

@Composable
fun IsOnlineTracker(
    app: AppViewModel,
) {
    val resources = LocalResources.current
    val connectivityState by app.isOnline.collectAsStateWithLifecycle(initialValue = ConnectivityState.CONNECTED)

    val (isFirstEmission, setIsFirstEmission) = remember { mutableStateOf(true) }

    LaunchedEffect(connectivityState) {
        // Skip the first emission to prevent toast on startup
        if (isFirstEmission) {
            setIsFirstEmission(false)
            return@LaunchedEffect
        }

        when (connectivityState) {
            ConnectivityState.CONNECTED -> {
                app.toast(
                    type = Toast.ToastType.SUCCESS,
                    title = resources.getString(R.string.other__connection_back_title),
                    description = resources.getString(R.string.other__connection_back_msg),
                )
            }

            ConnectivityState.DISCONNECTED -> {
                app.toast(
                    type = Toast.ToastType.WARNING,
                    title = resources.getString(R.string.other__connection_issue),
                    description = resources.getString(R.string.other__connection_issue_explain),
                )
            }

            else -> Unit
        }
    }
}

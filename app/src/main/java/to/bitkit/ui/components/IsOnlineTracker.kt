package to.bitkit.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.ToastText
import to.bitkit.repositories.ConnectivityState
import to.bitkit.ui.toaster
import to.bitkit.viewmodels.AppViewModel

@Composable
fun IsOnlineTracker(
    app: AppViewModel,
) {
    val toaster = toaster ?: return
    val connectivityState by app.isOnline.collectAsStateWithLifecycle(initialValue = ConnectivityState.CONNECTED)

    val (isFirstEmission, setIsFirstEmission) = remember { mutableStateOf(true) }

    LaunchedEffect(connectivityState) {
        // Skip the first emission to prevent toast on startup
        if (isFirstEmission) {
            setIsFirstEmission(true)
            return@LaunchedEffect
        }

        when (connectivityState) {
            ConnectivityState.CONNECTED -> {
                toaster.success(
                    title = ToastText(R.string.other__connection_back_title),
                    body = ToastText(R.string.other__connection_back_msg),
                )
            }

            ConnectivityState.DISCONNECTED -> {
                toaster.warn(
                    title = ToastText(R.string.other__connection_issue),
                    body = ToastText(R.string.other__connection_issue_explain),
                )
            }

            else -> Unit
        }
    }
}

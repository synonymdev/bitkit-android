package to.bitkit.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.ToastType
import to.bitkit.repositories.ConnectivityState
import to.bitkit.viewmodels.AppViewModel

@Composable
fun IsOnlineTracker(
    app: AppViewModel,
) {
    val context = LocalContext.current
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
                app.toast(
                    type = ToastType.SUCCESS,
                    title = context.getString(R.string.other__connection_back_title),
                    body = context.getString(R.string.other__connection_back_msg),
                )
            }

            ConnectivityState.DISCONNECTED -> {
                app.toast(
                    type = ToastType.WARNING,
                    title = context.getString(R.string.other__connection_issue),
                    body = context.getString(R.string.other__connection_issue_explain),
                )
            }

            else -> Unit
        }
    }
}

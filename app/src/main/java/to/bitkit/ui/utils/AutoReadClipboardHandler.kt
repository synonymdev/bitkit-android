package to.bitkit.ui.utils

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ext.getClipboardText
import to.bitkit.ui.appViewModel
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.settingsViewModel
import uniffi.bitkitcore.decode

@Composable
fun AutoReadClipboardHandler() {
    val appViewModel = appViewModel ?: return
    val settings = settingsViewModel ?: return

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val isAuthenticated by appViewModel.isAuthenticated.collectAsStateWithLifecycle()
    val isAutoReadClipboardEnabled by settings.enableAutoReadClipboard.collectAsState()

    var showClipboardDialog by remember { mutableStateOf(false) }
    var hasCheckedOnStartup by remember { mutableStateOf(false) }

    // Check clipboard on app startup - only after authentication
    LaunchedEffect(isAuthenticated, isAutoReadClipboardEnabled) {
        if (isAuthenticated && isAutoReadClipboardEnabled && !hasCheckedOnStartup) {
            showClipboardDialog = context.hasScanDataInClipboard()
            hasCheckedOnStartup = true
        }
    }

    LaunchedEffect(isAutoReadClipboardEnabled) {
        if (!isAutoReadClipboardEnabled) {
            showClipboardDialog = false
        }
    }

    // Check clipboard on app fg
    DisposableEffect(lifecycleOwner, isAuthenticated) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && isAuthenticated) {
                if (hasCheckedOnStartup && isAutoReadClipboardEnabled) {
                    scope.launch {
                        showClipboardDialog = context.hasScanDataInClipboard()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (showClipboardDialog && isAuthenticated) {
        AppAlertDialog(
            title = stringResource(R.string.other__clipboard_redirect_title),
            text = stringResource(R.string.other__clipboard_redirect_msg),
            confirmButtonText = stringResource(R.string.common__ok),
            dismissButtonText = stringResource(R.string.common__dialog_cancel),
            onConfirm = {
                context.getClipboardText()?.let { data -> appViewModel.onClipboardAutoRead(data) }
                showClipboardDialog = false
            },
            onDismiss = { showClipboardDialog = false }
        )
    }
}

private suspend fun Context.hasScanDataInClipboard(): Boolean {
    delay(1000) // delay needed for Android clipboard accessibility on start

    val clipText = this.getClipboardText()
    if (clipText.isNullOrBlank()) return false

    val scanResult = runCatching { decode(clipText) }
    return scanResult.isSuccess
}

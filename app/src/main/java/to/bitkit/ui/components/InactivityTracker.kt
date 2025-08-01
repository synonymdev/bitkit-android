package to.bitkit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import to.bitkit.utils.Logger
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.SettingsViewModel

private const val INACTIVITY_DELAY = 90_000L // 90 seconds

@Composable
fun InactivityTracker(
    app: AppViewModel,
    settings: SettingsViewModel,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val isPinEnabled by settings.isPinEnabled.collectAsStateWithLifecycle()
    val isPinOnIdleEnabled by settings.isPinOnIdleEnabled.collectAsStateWithLifecycle()
    val isAuthenticated by app.isAuthenticated.collectAsStateWithLifecycle()

    var inactivityJob by remember { mutableStateOf<Job?>(null) }

    fun resetInactivityTimeout() {
        inactivityJob?.cancel()?.also {
            inactivityJob = null
        }
        if (isPinEnabled && isPinOnIdleEnabled && isAuthenticated) {
            inactivityJob = scope.launch {
                delay(INACTIVITY_DELAY)
                Logger.debug("Inactivity timeout reached after ${INACTIVITY_DELAY / 1000}s, resetting isAuthenticated.")
                app.setIsAuthenticated(false)
                resetInactivityTimeout()
            }
        }
    }

    LaunchedEffect(isAuthenticated, isPinEnabled, isPinOnIdleEnabled) {
        if (isAuthenticated) {
            resetInactivityTimeout()
        } else {
            inactivityJob?.cancel()?.also {
                inactivityJob = null
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resetInactivityTimeout()
                Lifecycle.Event.ON_PAUSE -> inactivityJob?.cancel()?.also { inactivityJob = null }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            inactivityJob?.cancel()?.also {
                inactivityJob = null
            }
        }
    }

    Box(
        modifier = modifier.let { baseModifier ->
            if (isPinOnIdleEnabled) {
                baseModifier.pointerInput(Unit) {
                    while (true) {
                        awaitPointerEventScope {
                            awaitPointerEvent()
                            resetInactivityTimeout()
                        }
                    }
                }
            } else {
                baseModifier
            }
        }
    ) {
        content()
    }
}

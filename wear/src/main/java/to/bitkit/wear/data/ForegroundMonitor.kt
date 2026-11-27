package to.bitkit.wear.data

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import to.bitkit.wear.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

/** Whether the app is on screen: one of its activities is started. */
interface ForegroundMonitor {
    val isForeground: StateFlow<Boolean>
}

/**
 * Follows the process lifecycle, which starts with the first activity and stops a moment after the last one stopped,
 * so a configuration change is not a trip to the background.
 */
@Singleton
class ProcessForegroundMonitor @Inject constructor() : ForegroundMonitor {
    companion object {
        private const val TAG = "ForegroundMonitor"
    }

    private val _isForeground = MutableStateFlow(false)
    override val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    init {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = set(true)

            override fun onStop(owner: LifecycleOwner) = set(false)
        }
        val mainLooper = Looper.getMainLooper()
        if (Looper.myLooper() == mainLooper) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        } else {
            Handler(mainLooper).post { ProcessLifecycleOwner.get().lifecycle.addObserver(observer) }
        }
    }

    private fun set(isForeground: Boolean) {
        Logger.info("Changed app to '${if (isForeground) "foreground" else "background"}'", context = TAG)
        _isForeground.update { isForeground }
    }
}

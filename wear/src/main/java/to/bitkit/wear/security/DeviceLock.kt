package to.bitkit.wear.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.wear.di.IoDispatcher
import to.bitkit.wear.ext.keyguardManager
import to.bitkit.wear.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * The watch's screen lock: whether one is set, and whether the watch is locked now. A watch without a screen lock is
 * never locked.
 */
interface DeviceLock {
    /** Whether a PIN, pattern or password protects the watch. */
    val isSecure: Boolean

    val isLocked: StateFlow<Boolean>

    /** Reads the lock state again, off the main thread; the system does not announce every lock. */
    fun refresh()
}

/**
 * Reads the keyguard off the main thread. The lock state is false until the first read, a moment after the
 * process starts; a caller that must know calls [refresh] first, off the main thread.
 */
@Singleton
class KeyguardDeviceLock @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) : DeviceLock {
    companion object {
        private const val TAG = "DeviceLock"

        /** The system may lock a moment after the screen turns off, so the state is read again after this long. */
        private val LOCK_SETTLE_DELAY = 6.seconds
    }

    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())
    private val keyguard by lazy { context.keyguardManager }
    private val _isLocked = MutableStateFlow(false)
    private var settleJob: Job? = null

    override val isSecure: Boolean get() = keyguard.isDeviceSecure

    override val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    init {
        scope.launch {
            runCatching {
                refresh()
                register()
            }.onFailure { Logger.error("Failed to watch the screen lock", it, context = TAG) }
        }
    }

    override fun refresh() = _isLocked.update { keyguard.isDeviceLocked }

    private fun register() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                scope.launch { refresh() }
                if (intent.action == Intent.ACTION_SCREEN_OFF) refreshOnceSettled()
            }
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun refreshOnceSettled() {
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(LOCK_SETTLE_DELAY)
            refresh()
        }
    }
}

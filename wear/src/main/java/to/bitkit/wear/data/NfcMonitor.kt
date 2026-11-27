package to.bitkit.wear.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.wear.di.IoDispatcher
import to.bitkit.wear.ext.nfcAdapter
import to.bitkit.wear.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the watch can take an NFC tap: [Unknown] until it was read, [Unavailable] without NFC hardware, which no
 * store install lacks.
 */
enum class NfcState { Unknown, Unavailable, Off, On }

/** The watch's NFC state, updated as NFC is turned on or off. */
interface NfcMonitor {
    val state: StateFlow<NfcState>
}

/**
 * Reads the NFC adapter off the main thread, once at start and again on each state broadcast, through one receiver
 * registered for the life of the process.
 */
@Singleton
class AdapterNfcMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) : NfcMonitor {
    companion object {
        private const val TAG = "NfcMonitor"
    }

    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())
    private val adapter: NfcAdapter? by lazy { context.nfcAdapter }
    private val _state = MutableStateFlow(NfcState.Unknown)

    override val state: StateFlow<NfcState> = _state.asStateFlow()

    init {
        scope.launch {
            runCatching {
                refresh()
                if (adapter == null) return@runCatching
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        scope.launch { refresh() }
                    }
                }
                // The NFC service sends this protected broadcast under its own uid, which a not-exported receiver
                // could refuse; only the system may send it, so exporting the receiver lets no app spoof it.
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
                    ContextCompat.RECEIVER_EXPORTED,
                )
                refresh()
            }.onFailure { Logger.warn("Failed to watch the NFC state", it, context = TAG) }
        }
    }

    private fun refresh() = _state.update { read() }

    private fun read(): NfcState {
        val adapter = adapter ?: return NfcState.Unavailable
        return if (adapter.isEnabled) NfcState.On else NfcState.Off
    }
}

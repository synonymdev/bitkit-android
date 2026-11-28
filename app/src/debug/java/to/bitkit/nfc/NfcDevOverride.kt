package to.bitkit.nfc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import to.bitkit.ui.shared.effects.NfcState

/**
 * Debug-only NFC state overrides, set over adb through `DevToolsProvider`, so the scanner status can be checked on
 * an emulator, which has no NFC hardware. Null leaves the real value in place.
 */
internal object NfcDevOverride {
    private val _adapterState = MutableStateFlow<NfcState?>(null)
    val adapterState: StateFlow<NfcState?> = _adapterState.asStateFlow()

    private val _cameraStopsPolling = MutableStateFlow<Boolean?>(null)
    val cameraStopsPolling: StateFlow<Boolean?> = _cameraStopsPolling.asStateFlow()

    fun set(adapterState: NfcState?, cameraStopsPolling: Boolean?) {
        _adapterState.update { adapterState }
        _cameraStopsPolling.update { cameraStopsPolling }
    }
}

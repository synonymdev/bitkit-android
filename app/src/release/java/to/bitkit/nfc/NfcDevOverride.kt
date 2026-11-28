package to.bitkit.nfc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import to.bitkit.ui.shared.effects.NfcState

/** Release builds take no NFC overrides; see the debug source set. */
internal object NfcDevOverride {
    val adapterState: StateFlow<NfcState?> = MutableStateFlow(null)
    val cameraStopsPolling: StateFlow<Boolean?> = MutableStateFlow(null)
}

package to.bitkit.ui.screens.scanner

import android.util.Log
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import to.bitkit.env.Tag.APP
import javax.inject.Inject

@HiltViewModel
class QrScanViewModel @Inject constructor(
) : ViewModel() {
    private val _uiState: MutableStateFlow<QrScanUIState> = MutableStateFlow(QrScanUIState())
    val uiState = _uiState.asStateFlow()

    fun onQrCodeDetected(result: String) {
        Log.d(APP, "Scan result: $result")
        _uiState.update { it.copy(detectedQR = result) }
    }
}

data class QrScanUIState(
    val detectedQR: String = "",
)

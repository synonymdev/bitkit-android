package to.bitkit.wear.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the watch shows the app dimmed in ambient (always-on) mode, where nobody is taken to be looking. The
 * activity reports it, so what runs outside the screens can tell ambient mode from the app in use.
 */
@Singleton
class AmbientMonitor @Inject constructor() {
    private val _isAmbient = MutableStateFlow(false)
    val isAmbient: StateFlow<Boolean> = _isAmbient.asStateFlow()

    fun set(isAmbient: Boolean) = _isAmbient.update { isAmbient }
}

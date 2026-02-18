package to.bitkit.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TrezorDebugLog {
    private const val MAX_LINES = 300
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, msg: String) {
        val ts = fmt.format(Date())
        val line = "$ts [$tag] $msg"
        synchronized(this) {
            val current = _lines.value.toMutableList()
            current.add(line)
            if (current.size > MAX_LINES) {
                _lines.value = current.takeLast(MAX_LINES)
            } else {
                _lines.value = current
            }
        }
    }

    fun clear() {
        _lines.value = emptyList()
    }
}

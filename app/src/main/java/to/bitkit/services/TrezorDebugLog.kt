package to.bitkit.services

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TrezorDebugLog {
    private const val MAX_LINES = 300
    private val _lines = MutableStateFlow<ImmutableList<String>>(persistentListOf())
    val lines: StateFlow<ImmutableList<String>> = _lines.asStateFlow()

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, msg: String) {
        val ts = fmt.format(Date())
        val line = "$ts [$tag] $msg"
        _lines.update { current ->
            val updated = current + line
            if (updated.size > MAX_LINES) {
                updated.takeLast(MAX_LINES).toImmutableList()
            } else {
                updated.toImmutableList()
            }
        }
    }

    fun clear() {
        _lines.update { persistentListOf() }
    }
}

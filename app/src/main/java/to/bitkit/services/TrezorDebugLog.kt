package to.bitkit.services

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import to.bitkit.utils.Logger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TrezorDebugLog {
    private const val TAG = "TrezorDebugLog"
    private const val MAX_LINES = 300
    private val secretValuePattern = Regex(
        """(?i)\b(mnemonic|seed|passphrase|pin|pairing[ _-]?code|credential|xpub|extended[ _-]?key|psbt|""" +
            """raw[ _-]?tx|serialized[ _-]?tx)\s*[:=]\s*("[^"]*"|'[^']*'|[^\s,;]+)""",
    )
    private val _lines = MutableStateFlow<ImmutableList<String>>(persistentListOf())
    val lines: StateFlow<ImmutableList<String>> = _lines.asStateFlow()

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, msg: String) {
        val sanitizedMessage = secretValuePattern.replace(msg) { "${it.groupValues[1]}=<redacted>" }
        val ts = synchronized(fmt) { fmt.format(Date()) }
        val line = "$ts [$tag] $sanitizedMessage"
        _lines.update { current ->
            val updated = current + line
            if (updated.size > MAX_LINES) {
                updated.takeLast(MAX_LINES).toImmutableList()
            } else {
                updated.toImmutableList()
            }
        }
        Logger.debug("Recorded Trezor diagnostic '$tag': '$sanitizedMessage'", context = TAG)
    }

    fun clear() {
        _lines.update { persistentListOf() }
    }
}

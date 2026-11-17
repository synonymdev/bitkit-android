package to.bitkit.nfc

import to.bitkit.ext.nowMillis
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Hands NFC reads to a scanner screen. Call it on the main thread only. It drops a repeat of the same request within
 * [DUPLICATE_READ_WINDOW], and any read that lands after [close], since a tag read can finish after the screen left.
 */
internal class NfcReadDispatcher(
    private val onRead: (String) -> Unit,
    private val onUnreadable: () -> Unit,
    private val now: () -> Long = { nowMillis() },
) {
    private var lastRead: Pair<String, Long>? = null
    private var isClosed = false

    fun deliver(text: String?) {
        if (isClosed) return
        if (text == null) {
            onUnreadable()
            return
        }
        val time = now()
        val previous = lastRead
        val isRepeat = previous?.first == text && time - previous.second < DUPLICATE_READ_WINDOW.inWholeMilliseconds
        if (isRepeat) return
        lastRead = text to time
        onRead(text)
    }

    fun close() {
        isClosed = true
    }

    companion object {
        val DUPLICATE_READ_WINDOW: Duration = 3.seconds
    }
}

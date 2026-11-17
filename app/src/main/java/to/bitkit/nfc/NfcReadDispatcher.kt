package to.bitkit.nfc

import to.bitkit.ext.nowMillis
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Hands NFC reads to a scanner screen. Call [deliver] and [invalidate] on the main thread only. It drops a repeat of
 * the same request within [DUPLICATE_READ_WINDOW], and any read that started before the last [invalidate], since a tag
 * read can finish after the reader paused or the screen left.
 */
internal class NfcReadDispatcher(
    private val onRead: (String) -> Unit,
    private val onUnreadable: () -> Unit,
    private val now: () -> Long = { nowMillis() },
) {
    private var lastRead: Pair<String, Long>? = null

    /** The current reader activation. A read captures it when it starts, on the reader's binder thread. */
    @Volatile
    var activation = 0
        private set

    fun deliver(text: String?, readActivation: Int) {
        if (readActivation != activation) return
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

    fun invalidate() {
        activation++
    }

    companion object {
        val DUPLICATE_READ_WINDOW: Duration = 3.seconds
    }
}

package to.bitkit.nfc

import to.bitkit.ext.nowMillis
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Hands NFC reads to a scanner screen. Call it on the main thread only. Each time reader mode is enabled, [open]
 * starts a new activation, and the callback registered with it passes that number to [deliver]. A read is dropped
 * when its activation is not the open one: the reader paused, the screen left, or Android called an old callback
 * after reader mode was disabled. A repeat of the same request within [DUPLICATE_READ_WINDOW] is dropped too.
 */
internal class NfcReadDispatcher(
    private val onRead: (String) -> Unit,
    private val onUnreadable: () -> Unit,
    private val now: () -> Long = { nowMillis() },
) {
    private var lastRead: Pair<String, Long>? = null
    private var activation = 0
    private var isOpen = false

    /** Starts a new activation and returns its number, for the callback registered with it. */
    fun open(): Int {
        activation++
        isOpen = true
        return activation
    }

    /** Ends the open activation. Reads from it, or from an earlier one, are dropped. */
    fun close() {
        isOpen = false
    }

    /** Whether a read from [readActivation] would be delivered: the reader is open and still on that activation. */
    fun isCurrent(readActivation: Int): Boolean = isOpen && readActivation == activation

    fun deliver(text: String?, readActivation: Int) {
        if (!isCurrent(readActivation)) return
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

    companion object {
        val DUPLICATE_READ_WINDOW: Duration = 3.seconds
    }
}

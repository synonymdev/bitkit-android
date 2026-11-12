package to.bitkit.nfc

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The payment request this device currently offers over NFC.
 *
 * The Receive screen publishes its request while it is in the foreground and clears it when it
 * leaves, so [NdefTagService] answers a reader only while that request is on screen.
 * The service and the UI run in the same process, so a process-wide holder is enough.
 */
object NfcTagEmitter {
    private class Published(val token: Long, val message: ByteArray)

    private val tokens = AtomicLong(0)
    private val published = AtomicReference<Published?>(null)

    /** Publishes [uri] and returns the token that [clear] needs, so a later publisher is not cleared. */
    fun publish(uri: String): Long {
        val token = tokens.incrementAndGet()
        published.set(Published(token, NdefCodec.encodeUriMessage(uri)))
        return token
    }

    /** Clears the request published with [token]; returns false when another one replaced it. */
    fun clear(token: Long): Boolean {
        val current = published.get() ?: return false
        return current.token == token && published.compareAndSet(current, null)
    }

    fun currentMessage(): ByteArray? = published.get()?.message
}

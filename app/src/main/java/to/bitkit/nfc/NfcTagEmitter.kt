package to.bitkit.nfc

import java.util.concurrent.atomic.AtomicReference

/**
 * The payment request this device currently offers over NFC.
 *
 * The Receive screen publishes its request while it is in the foreground and clears it when it
 * leaves, so [NdefTagService] answers a reader only while that request is on screen.
 * The service and the UI run in the same process, so a process-wide holder is enough.
 */
object NfcTagEmitter {
    private val message = AtomicReference<ByteArray?>(null)

    fun publish(uri: String) {
        message.set(NdefCodec.encodeUriMessage(uri))
    }

    fun clear() {
        message.set(null)
    }

    fun currentMessage(): ByteArray? = message.get()
}

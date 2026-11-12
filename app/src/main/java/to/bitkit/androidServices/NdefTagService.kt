package to.bitkit.androidServices

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import to.bitkit.nfc.NfcTagEmitter
import to.bitkit.nfc.Type4TagEmulator

/**
 * Presents the Receive screen's payment request as an NFC Forum Type 4 tag, so another phone
 * or a point of sale reads it like any NDEF tag. It answers only while [NfcTagEmitter] holds a
 * request, which the Receive screen publishes while it is in the foreground.
 */
class NdefTagService : HostApduService() {
    private val emulator = Type4TagEmulator(NfcTagEmitter::currentMessage)

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val apdu = commandApdu ?: return Type4TagEmulator.SW_WRONG_LENGTH
        return emulator.process(apdu)
    }

    override fun onDeactivated(reason: Int) {
        emulator.reset()
    }
}

package to.bitkit.nfc

import java.nio.ByteBuffer

/**
 * Requests as point-of-sale terminals present them over NFC.
 *
 * A Square Register (2nd generation) serves `lightning:` and the uppercase BOLT11 invoice from its QR code as
 * one long-format NDEF URI record with identifier code 0x00. Numo, and Square in a later release, serve the
 * BIP 321 form without an address. [SQUARE_INVOICE] is a real signet invoice of the size Square serves:
 * its message is 465 bytes and starts like Square's.
 */
object PosNfcFixtures {
    const val SQUARE_INVOICE =
        "LNTBS79900N1P4TZWUQD962DCH2CTJV5S9YET8D9EHGETJYP8YVSEQWFJKWUN9WDEKJMMWYPNXJ7R5W4EX2GZNW96KZUN9YPFX2E" +
            "MFWD6X2U3QFERYXGRJV4NHYETNWD5K7M3QVE5HSAR4WFJJQ5M3W4SHYEFQ2FJKW6TNW3JHYGZWGEPJQUN9VAEX2UMND9HKUGRXD9" +
            "U8GATJV5PP5QURSWPC8QURSWPC8QURSWPC8QURSWPC8QURSWPC8QURSWPC8QURSSP5PYYSJZGFPYYSJZGFPYYSJZGFPYYSJZGFPY" +
            "YSJZGFPYYSJZGFPYYS9QRSGQXQ97ZVUQCQZYSKUAHP9D6GKX2H8ERFH5E9NZZ52SY0UFR2F6VGK43QEJ0ZJU7FTYQYJTQE8705R2" +
            "KM9QS5NYFM3ZPR330ML3S3HWJP5D0RERD49QGT5CPUXAWEL"
    const val SQUARE_URI = "lightning:$SQUARE_INVOICE"
    const val BIP321_URI = "bitcoin:?lightning=$SQUARE_INVOICE"

    /** The first bytes of Square's NDEF message: long URI record, 0x1CA-byte payload, code 0x00, `lightning:`. */
    const val SQUARE_MESSAGE_START_HEX = "C1010000 01CA 55 00 6C696768746E696E673A"

    /** Square's NDEF message, built by hand from the record layout rather than by [NdefCodec]. */
    fun squareMessage(uri: String = SQUARE_URI): ByteArray {
        val payload = byteArrayOf(0x00) + uri.toByteArray(Charsets.US_ASCII)
        val lengthBytes = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(payload.size).array()
        val header = byteArrayOf(0xC1.toByte(), 0x01) + lengthBytes + byteArrayOf(0x55)
        return header + payload
    }

    fun hex(value: String): ByteArray =
        value.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

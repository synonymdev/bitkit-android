package to.bitkit.nfc

/**
 * Read-only NFC Forum Type 4 tag, served over host card emulation.
 *
 * A reader selects the NDEF tag application, reads the capability container (file E103)
 * and then the NDEF file (E104), which holds a 2-byte length followed by the message.
 * [ndefMessage] is read when the NDEF file is selected, so one tap always sees one
 * consistent request. While it returns null the NDEF file is reported as not found,
 * so a reader sees no tag content.
 */
class Type4TagEmulator(
    private val ndefMessage: () -> ByteArray?,
) {
    private var isApplicationSelected = false
    private var selectedFile: ByteArray? = null

    fun process(apdu: ByteArray): ByteArray {
        if (apdu.size < HEADER_SIZE) return SW_WRONG_LENGTH
        if (apdu[0] != CLA) return SW_CLA_NOT_SUPPORTED
        return when (apdu[1]) {
            INS_SELECT -> select(apdu)
            INS_READ_BINARY -> readBinary(apdu)
            INS_UPDATE_BINARY -> SW_SECURITY_NOT_SATISFIED
            else -> SW_INS_NOT_SUPPORTED
        }
    }

    fun reset() {
        isApplicationSelected = false
        selectedFile = null
    }

    private fun select(apdu: ByteArray): ByteArray {
        val p1 = apdu[2]
        val data = commandData(apdu) ?: return SW_WRONG_LENGTH
        return when (p1) {
            P1_SELECT_BY_NAME -> if (data.contentEquals(NDEF_AID)) {
                isApplicationSelected = true
                selectedFile = null
                SW_OK
            } else {
                reset()
                SW_FILE_NOT_FOUND
            }

            P1_SELECT_BY_ID -> selectFile(data)
            else -> SW_FILE_NOT_FOUND
        }
    }

    private fun selectFile(fileId: ByteArray): ByteArray {
        if (!isApplicationSelected) return SW_FILE_NOT_FOUND
        selectedFile = when {
            fileId.contentEquals(CC_FILE_ID) -> CAPABILITY_CONTAINER
            fileId.contentEquals(NDEF_FILE_ID) -> ndefFile()
            else -> null
        }
        return if (selectedFile != null) SW_OK else SW_FILE_NOT_FOUND
    }

    private fun ndefFile(): ByteArray? {
        val message = ndefMessage() ?: return null
        if (message.size > MAX_NDEF_SIZE) return null
        val length = byteArrayOf((message.size shr 8).toByte(), message.size.toByte())
        return length + message
    }

    private fun readBinary(apdu: ByteArray): ByteArray {
        val file = selectedFile ?: return SW_FILE_NOT_FOUND
        val offset = ((apdu[2].toInt() and 0xFF) shl 8) or (apdu[3].toInt() and 0xFF)
        if (offset > file.size) return SW_WRONG_OFFSET
        val requested = if (apdu.size > HEADER_SIZE) apdu[HEADER_SIZE].toInt() and 0xFF else 0
        val length = if (requested == 0) SHORT_LE_MAX else requested
        val end = minOf(file.size, offset + length)
        return file.copyOfRange(offset, end) + SW_OK
    }

    private fun commandData(apdu: ByteArray): ByteArray? {
        if (apdu.size <= HEADER_SIZE) return null
        val lc = apdu[HEADER_SIZE].toInt() and 0xFF
        val start = HEADER_SIZE + 1
        if (apdu.size < start + lc) return null
        return apdu.copyOfRange(start, start + lc)
    }

    companion object {
        /** NDEF Tag Application, NFC Forum Type 4 Tag specification. */
        val NDEF_AID = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
        const val MAX_NDEF_SIZE = 0x0FFE

        private const val HEADER_SIZE = 4
        private const val SHORT_LE_MAX = 256
        private const val CLA: Byte = 0x00
        private const val INS_SELECT: Byte = 0xA4.toByte()
        private const val INS_READ_BINARY: Byte = 0xB0.toByte()
        private const val INS_UPDATE_BINARY: Byte = 0xD6.toByte()
        private const val P1_SELECT_BY_NAME: Byte = 0x04
        private const val P1_SELECT_BY_ID: Byte = 0x00

        private val CC_FILE_ID = byteArrayOf(0xE1.toByte(), 0x03)
        private val NDEF_FILE_ID = byteArrayOf(0xE1.toByte(), 0x04)

        /**
         * Mapping version 2.0, MLe 0x003B, MLc 0x0034, then the NDEF file control TLV:
         * file E104, maximum size [MAX_NDEF_SIZE] + 2, read access granted, write access denied.
         */
        private val CAPABILITY_CONTAINER = byteArrayOf(
            0x00, 0x0F, 0x20, 0x00, 0x3B, 0x00, 0x34,
            0x04, 0x06, 0xE1.toByte(), 0x04, 0x10, 0x00, 0x00, 0xFF.toByte(),
        )

        val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        val SW_FILE_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
        val SW_WRONG_LENGTH = byteArrayOf(0x67, 0x00)
        val SW_WRONG_OFFSET = byteArrayOf(0x6B, 0x00)
        val SW_SECURITY_NOT_SATISFIED = byteArrayOf(0x69, 0x82.toByte())
        val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
        val SW_CLA_NOT_SUPPORTED = byteArrayOf(0x6E, 0x00)
    }
}

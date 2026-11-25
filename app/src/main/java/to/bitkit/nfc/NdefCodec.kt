package to.bitkit.nfc

import java.nio.ByteBuffer

/**
 * Encodes and decodes the NDEF records used for NFC payments.
 *
 * Bitkit writes one NFC Forum well-known URI record ("U") holding the payment request.
 * When reading, it accepts URI records, Text records ("T", used by Numo and some POS apps)
 * and absolute-URI records, so a tag from any payment terminal or wallet lands in the same
 * scan flow as a QR code.
 */
object NdefCodec {
    private const val FLAG_MB = 0x80
    private const val FLAG_ME = 0x40
    private const val FLAG_CF = 0x20
    private const val FLAG_SR = 0x10
    private const val FLAG_IL = 0x08
    private const val TNF_MASK = 0x07
    private const val TNF_WELL_KNOWN = 0x01
    private const val TNF_ABSOLUTE_URI = 0x03
    private const val TEXT_UTF16 = 0x80
    private const val TEXT_LANG_LENGTH_MASK = 0x3F
    private const val SHORT_RECORD_MAX = 255
    private val TYPE_URI = byteArrayOf('U'.code.toByte())
    private val TYPE_TEXT = byteArrayOf('T'.code.toByte())

    /** URI identifier codes, NFC Forum URI Record Type Definition, table 3. */
    private val URI_PREFIXES = listOf(
        "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:",
        "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://", "sftp://", "smb://", "nfs://",
        "ftp://", "dav://", "news:", "telnet://", "imap:", "rtsp://", "urn:", "pop:", "sip:",
        "sips:", "tftp:", "btspp://", "btl2cap://", "btgoep://", "tcpobex://", "irdaobex://",
        "file://", "urn:epc:id:", "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:",
    )

    /** One NDEF message with a single URI record, written without an abbreviation prefix. */
    fun encodeUriMessage(uri: String): ByteArray {
        val payload = byteArrayOf(0x00) + uri.toByteArray(Charsets.UTF_8)
        val isShort = payload.size <= SHORT_RECORD_MAX
        val header = FLAG_MB or FLAG_ME or TNF_WELL_KNOWN or (if (isShort) FLAG_SR else 0)
        val lengthBytes = if (isShort) {
            byteArrayOf(payload.size.toByte())
        } else {
            ByteBuffer.allocate(Int.SIZE_BYTES).putInt(payload.size).array()
        }
        return byteArrayOf(header.toByte(), TYPE_URI.size.toByte()) + lengthBytes + TYPE_URI + payload
    }

    /**
     * Returns the text of every URI, Text or absolute-URI record in [message].
     * Other record types are skipped. A malformed or chunked message yields an empty list.
     */
    fun decodeTexts(message: ByteArray): List<String> = runCatching { parse(message) }.getOrDefault(emptyList())

    /**
     * Picks the payment request from the decoded record texts: the single distinct non-blank
     * value, or null when the tag holds nothing or several different requests.
     */
    fun payableText(texts: List<String>): String? {
        val distinct = texts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return distinct.singleOrNull()
    }

    private fun parse(message: ByteArray): List<String> {
        val texts = mutableListOf<String>()
        var offset = 0
        while (offset < message.size) {
            val header = message[offset].toInt() and 0xFF
            require(header and FLAG_CF == 0) { "Chunked records are not supported" }
            val typeLength = message[offset + 1].toInt() and 0xFF
            offset += 2
            val payloadLength: Int
            if (header and FLAG_SR != 0) {
                payloadLength = message[offset].toInt() and 0xFF
                offset += 1
            } else {
                payloadLength = ByteBuffer.wrap(message, offset, Int.SIZE_BYTES).int
                offset += Int.SIZE_BYTES
            }
            require(payloadLength >= 0) { "Invalid payload length" }
            val idLength = if (header and FLAG_IL != 0) (message[offset++].toInt() and 0xFF) else 0
            val type = message.copyOfRange(offset, offset + typeLength)
            offset += typeLength + idLength
            require(offset + payloadLength <= message.size) { "Truncated record" }
            val payload = message.copyOfRange(offset, offset + payloadLength)
            offset += payloadLength

            recordText(header and TNF_MASK, type, payload)?.let(texts::add)
            if (header and FLAG_ME != 0) break
        }
        return texts
    }

    private fun recordText(tnf: Int, type: ByteArray, payload: ByteArray): String? = when {
        tnf == TNF_WELL_KNOWN && type.contentEquals(TYPE_URI) -> decodeUri(payload)
        tnf == TNF_WELL_KNOWN && type.contentEquals(TYPE_TEXT) -> decodeText(payload)
        tnf == TNF_ABSOLUTE_URI -> type.toString(Charsets.UTF_8)
        else -> null
    }

    private fun decodeUri(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val prefix = URI_PREFIXES.getOrNull(payload[0].toInt() and 0xFF) ?: return null
        return prefix + payload.copyOfRange(1, payload.size).toString(Charsets.UTF_8)
    }

    private fun decodeText(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val status = payload[0].toInt() and 0xFF
        val textStart = 1 + (status and TEXT_LANG_LENGTH_MASK)
        if (textStart > payload.size) return null
        val charset = if (status and TEXT_UTF16 != 0) Charsets.UTF_16 else Charsets.UTF_8
        return payload.copyOfRange(textStart, payload.size).toString(charset)
    }
}

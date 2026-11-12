package to.bitkit.nfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NdefCodecTest {
    @Test
    fun `encodes a short URI record without a prefix code`() {
        val message = NdefCodec.encodeUriMessage("lightning:lnbc1")

        val payload = byteArrayOf(0x00) + "lightning:lnbc1".toByteArray()
        val expected = byteArrayOf(0xD1.toByte(), 0x01, payload.size.toByte(), 'U'.code.toByte()) + payload
        assertArrayEquals(expected, message)
    }

    @Test
    fun `encodes a long URI record with a 4-byte length`() {
        val uri = "bitcoin:bc1q?lightning=" + "x".repeat(400)

        val message = NdefCodec.encodeUriMessage(uri)

        assertEquals(0xC1.toByte(), message[0])
        assertEquals(listOf(uri), NdefCodec.decodeTexts(message))
    }

    @Test
    fun `round trips a URI record`() {
        val uri = "bitcoin:BC1QTEST?amount=0.001&lightning=LNBC10U1P"

        assertEquals(listOf(uri), NdefCodec.decodeTexts(NdefCodec.encodeUriMessage(uri)))
    }

    @Test
    fun `expands URI prefix codes`() {
        val payload = byteArrayOf(0x04) + "example.com/lnurlp/alice".toByteArray()
        val message = byteArrayOf(0xD1.toByte(), 0x01, payload.size.toByte(), 'U'.code.toByte()) + payload

        assertEquals(listOf("https://example.com/lnurlp/alice"), NdefCodec.decodeTexts(message))
    }

    @Test
    fun `decodes a UTF-8 text record`() {
        val text = "bitcoin:?lightning=lnbc1&creq=creqA1"
        val payload = byteArrayOf(0x02, 'e'.code.toByte(), 'n'.code.toByte()) + text.toByteArray()
        val message = byteArrayOf(0xD1.toByte(), 0x01, payload.size.toByte(), 'T'.code.toByte()) + payload

        assertEquals(listOf(text), NdefCodec.decodeTexts(message))
    }

    @Test
    fun `decodes a UTF-16 text record`() {
        val text = "lnbc1"
        val header = byteArrayOf(0x82.toByte(), 'e'.code.toByte(), 'n'.code.toByte())
        val payload = header + text.toByteArray(Charsets.UTF_16)
        val message = byteArrayOf(0xD1.toByte(), 0x01, payload.size.toByte(), 'T'.code.toByte()) + payload

        assertEquals(listOf(text), NdefCodec.decodeTexts(message))
    }

    @Test
    fun `decodes every record and skips unknown types`() {
        val uri = NdefCodec.encodeUriMessage("lightning:lnbc1")
        val mime = byteArrayOf(0x52, 0x0A, 0x01) + "text/plain".toByteArray() + byteArrayOf(0x41)
        val firstRecord = byteArrayOf((uri[0].toInt() and 0xBF).toByte()) + uri.copyOfRange(1, uri.size)

        assertEquals(listOf("lightning:lnbc1"), NdefCodec.decodeTexts(firstRecord + mime))
    }

    @Test
    fun `returns nothing for a truncated message`() {
        val message = NdefCodec.encodeUriMessage("lightning:lnbc1")

        assertTrue(NdefCodec.decodeTexts(message.copyOf(message.size - 3)).isEmpty())
    }

    @Test
    fun `payable text is the single distinct request`() {
        assertEquals("lightning:lnbc1", NdefCodec.payableText(listOf(" lightning:lnbc1 ", "lightning:lnbc1", "")))
    }

    @Test
    fun `payable text is null for no or conflicting requests`() {
        assertNull(NdefCodec.payableText(emptyList()))
        assertNull(NdefCodec.payableText(listOf("  ")))
        assertNull(NdefCodec.payableText(listOf("lightning:lnbc1", "lightning:lnbc2")))
    }
}

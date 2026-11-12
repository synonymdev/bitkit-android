package to.bitkit.nfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Type4TagEmulatorTest {
    private var message: ByteArray? = NdefCodec.encodeUriMessage("lightning:lnbc1")
    private val sut = Type4TagEmulator { message }

    private val selectApp = hex("00A4040007D276000085010100")
    private val selectCc = hex("00A4000C02E103")
    private val selectNdef = hex("00A4000C02E104")

    @Test
    fun `serves the NDEF message through the standard read sequence`() {
        assertArrayEquals(Type4TagEmulator.SW_OK, sut.process(selectApp))
        assertArrayEquals(Type4TagEmulator.SW_OK, sut.process(selectCc))
        assertArrayEquals(hex("000F20003B00340406E104100000FF9000"), sut.process(hex("00B000000F")))
        assertArrayEquals(Type4TagEmulator.SW_OK, sut.process(selectNdef))

        val expected = requireNotNull(message)
        val lengthResponse = sut.process(hex("00B0000002"))
        assertArrayEquals(byteArrayOf(0x00, expected.size.toByte()) + Type4TagEmulator.SW_OK, lengthResponse)

        val read = readNdef(offset = 2, total = expected.size)
        assertArrayEquals(expected, read)
    }

    @Test
    fun `reads a long message in chunks`() {
        message = NdefCodec.encodeUriMessage("bitcoin:bc1q?lightning=" + "x".repeat(700))
        sut.process(selectApp)
        sut.process(selectNdef)

        val expected = requireNotNull(message)
        assertArrayEquals(expected, readNdef(offset = 2, total = expected.size))
    }

    @Test
    fun `hides the NDEF file when nothing is published`() {
        message = null
        sut.process(selectApp)

        assertArrayEquals(Type4TagEmulator.SW_FILE_NOT_FOUND, sut.process(selectNdef))
        assertArrayEquals(Type4TagEmulator.SW_FILE_NOT_FOUND, sut.process(hex("00B0000002")))
    }

    @Test
    fun `refuses files before the application is selected`() {
        assertArrayEquals(Type4TagEmulator.SW_FILE_NOT_FOUND, sut.process(selectCc))
    }

    @Test
    fun `refuses other applications`() {
        assertArrayEquals(Type4TagEmulator.SW_FILE_NOT_FOUND, sut.process(hex("00A4040007A000000003101000")))
        assertArrayEquals(Type4TagEmulator.SW_FILE_NOT_FOUND, sut.process(selectCc))
    }

    @Test
    fun `keeps serving the message selected for this tap`() {
        sut.process(selectApp)
        sut.process(selectNdef)
        val first = requireNotNull(message)
        message = NdefCodec.encodeUriMessage("lightning:lnbc2")

        assertArrayEquals(first, readNdef(offset = 2, total = first.size))
    }

    @Test
    fun `is read-only`() {
        sut.process(selectApp)
        sut.process(selectNdef)

        assertArrayEquals(Type4TagEmulator.SW_SECURITY_NOT_SATISFIED, sut.process(hex("00D60000020000")))
    }

    @Test
    fun `rejects malformed and unsupported commands`() {
        assertArrayEquals(Type4TagEmulator.SW_WRONG_LENGTH, sut.process(hex("00A4")))
        assertArrayEquals(Type4TagEmulator.SW_CLA_NOT_SUPPORTED, sut.process(hex("80A4040000")))
        assertArrayEquals(Type4TagEmulator.SW_INS_NOT_SUPPORTED, sut.process(hex("00CA000000")))
    }

    @Test
    fun `reset forgets the selection`() {
        sut.process(selectApp)
        sut.reset()

        assertArrayEquals(Type4TagEmulator.SW_FILE_NOT_FOUND, sut.process(selectNdef))
    }

    private fun readNdef(offset: Int, total: Int): ByteArray {
        var result = byteArrayOf()
        var position = offset
        while (result.size < total) {
            val length = minOf(MAX_READ, total - result.size)
            val response = sut.process(
                byteArrayOf(0x00, 0xB0.toByte(), (position shr 8).toByte(), position.toByte(), length.toByte())
            )
            assertArrayEquals(Type4TagEmulator.SW_OK, response.copyOfRange(response.size - 2, response.size))
            val chunk = response.copyOfRange(0, response.size - 2)
            assertEquals(length, chunk.size)
            result += chunk
            position += chunk.size
        }
        return result
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val MAX_READ = 0x3B
    }
}

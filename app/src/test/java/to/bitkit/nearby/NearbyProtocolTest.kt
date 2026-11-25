package to.bitkit.nearby

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class NearbyProtocolTest {
    @Test
    fun `uses the documented UUIDs`() {
        assertEquals(UUID.fromString("CB0E128B-1CA5-4959-94DF-A4FE588E3E98"), NearbyProtocol.SERVICE_UUID)
        assertEquals(UUID.fromString("69A713E5-B0A7-42F1-8B7F-FA0BDA119C00"), NearbyProtocol.META_UUID)
        assertEquals(UUID.fromString("69A713E5-B0A7-42F1-8B7F-FA0BDA119C01"), NearbyProtocol.DATA_UUIDS.first())
        assertEquals(UUID.fromString("69A713E5-B0A7-42F1-8B7F-FA0BDA119C08"), NearbyProtocol.DATA_UUIDS.last())
        assertEquals(8, NearbyProtocol.DATA_UUIDS.size)
    }

    @Test
    fun `meta holds the version and big-endian length`() {
        val request = ByteArray(0x0234)

        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x34), NearbyProtocol.meta(request))
        assertEquals(0x0234, NearbyProtocol.requestLength(NearbyProtocol.meta(request)))
    }

    @Test
    fun `rejects unknown versions and bad lengths`() {
        assertNull(NearbyProtocol.requestLength(byteArrayOf(0x02, 0x00, 0x10)))
        assertNull(NearbyProtocol.requestLength(byteArrayOf(0x01, 0x00, 0x00)))
        assertNull(NearbyProtocol.requestLength(byteArrayOf(0x01, 0x10, 0x01)))
        assertNull(NearbyProtocol.requestLength(byteArrayOf(0x01)))
    }

    @Test
    fun `splits and reassembles a request across chunks`() {
        val request = "bitcoin:bc1qtest?amount=0.001&lightning=" + "lnbc".repeat(200)
        val bytes = requireNotNull(NearbyProtocol.encodeRequest(request))
        val length = requireNotNull(NearbyProtocol.requestLength(NearbyProtocol.meta(bytes)))
        val chunkCount = NearbyProtocol.chunksFor(length)

        val chunks = (0 until chunkCount).map { NearbyProtocol.chunk(bytes, it) }

        assertEquals(2, chunkCount)
        assertEquals(512, chunks[0].size)
        assertEquals(request, NearbyProtocol.decode(chunks, length))
        assertTrue(NearbyProtocol.chunk(bytes, 2).isEmpty())
        assertTrue(NearbyProtocol.chunk(bytes, 8).isEmpty())
    }

    @Test
    fun `refuses empty and oversized requests`() {
        assertNull(NearbyProtocol.encodeRequest(""))
        assertNull(NearbyProtocol.encodeRequest("x".repeat(NearbyProtocol.MAX_REQUEST_BYTES + 1)))
    }

    @Test
    fun `serves reads from an offset`() {
        val value = byteArrayOf(1, 2, 3, 4)

        assertArrayEquals(byteArrayOf(3, 4), NearbyProtocol.readAt(value, 2))
        assertArrayEquals(byteArrayOf(), NearbyProtocol.readAt(value, 4))
        assertNull(NearbyProtocol.readAt(value, 5))
    }

    @Test
    fun `decode fails when chunks do not match the length`() {
        assertNull(NearbyProtocol.decode(listOf("abc".toByteArray()), 4))
    }
}

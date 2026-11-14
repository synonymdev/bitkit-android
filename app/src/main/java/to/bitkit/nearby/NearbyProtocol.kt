package to.bitkit.nearby

import java.util.UUID

/**
 * Bitkit nearby payments over Bluetooth LE, version 1. See docs/nearby-payments.md.
 *
 * The receiving phone serves its payment request from a GATT service; a paying phone reads the
 * meta characteristic for the length and then as many 512-byte data chunks as it needs.
 */
object NearbyProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("CB0E128B-1CA5-4959-94DF-A4FE588E3E98")
    val META_UUID: UUID = UUID.fromString("69A713E5-B0A7-42F1-8B7F-FA0BDA119C00")
    val DATA_UUIDS: List<UUID> = (1..CHUNK_COUNT).map {
        UUID.fromString("69A713E5-B0A7-42F1-8B7F-FA0BDA119C%02X".format(it))
    }

    const val VERSION: Byte = 0x01
    const val CHUNK_SIZE = 512
    const val CHUNK_COUNT = 8
    const val MAX_REQUEST_BYTES = CHUNK_SIZE * CHUNK_COUNT
    const val MIN_RSSI = -60

    /** The request as served, or null when it is empty or too large to serve. */
    fun encodeRequest(request: String): ByteArray? {
        val bytes = request.toByteArray(Charsets.UTF_8)
        return bytes.takeIf { it.isNotEmpty() && it.size <= MAX_REQUEST_BYTES }
    }

    fun meta(request: ByteArray): ByteArray =
        byteArrayOf(VERSION, (request.size shr 8).toByte(), request.size.toByte())

    /** Chunk [index] of [request]; empty past the end of the request. */
    fun chunk(request: ByteArray, index: Int): ByteArray {
        val start = index * CHUNK_SIZE
        if (index !in 0 until CHUNK_COUNT || start >= request.size) return byteArrayOf()
        return request.copyOfRange(start, minOf(request.size, start + CHUNK_SIZE))
    }

    /** The request length announced by [meta], or null for an unknown version or bad length. */
    fun requestLength(meta: ByteArray): Int? {
        if (meta.size < 3 || meta[0] != VERSION) return null
        val length = ((meta[1].toInt() and 0xFF) shl 8) or (meta[2].toInt() and 0xFF)
        return length.takeIf { it in 1..MAX_REQUEST_BYTES }
    }

    fun chunksFor(length: Int): Int = (length + CHUNK_SIZE - 1) / CHUNK_SIZE

    /** Value served for a read at [offset] of a characteristic whose full value is [value]. */
    fun readAt(value: ByteArray, offset: Int): ByteArray? =
        if (offset in 0..value.size) value.copyOfRange(offset, value.size) else null

    fun decode(chunks: List<ByteArray>, length: Int): String? {
        val bytes = chunks.fold(byteArrayOf()) { acc, chunk -> acc + chunk }
        if (bytes.size != length) return null
        return bytes.toString(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    }
}

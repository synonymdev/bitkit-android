package to.bitkit.services

import java.nio.ByteBuffer
import java.nio.ByteOrder

class MmkvParser(private val data: ByteArray) {
    companion object {
        private const val HEADER_SIZE = 8
        private const val CONTENT_SIZE_BYTES = 4
        private const val VARINT_DATA_MASK = 0x7F
        private const val VARINT_CONTINUE_MASK = 0x80
        private const val VARINT_SHIFT_INCREMENT = 7
        private const val VARINT_MAX_SHIFT = 64
    }

    @Suppress("LoopWithTooManyJumpStatements")
    fun parse(): Map<String, String> {
        if (data.size < HEADER_SIZE) return emptyMap()

        val contentSize = ByteBuffer.wrap(data, 0, CONTENT_SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
        val endOffset = minOf(HEADER_SIZE + contentSize, data.size)

        val result = mutableMapOf<String, String>()
        var offset = HEADER_SIZE

        while (offset < endOffset) {
            val (keyLength, keyLengthBytes) = readVarint(offset, endOffset) ?: break
            offset += keyLengthBytes

            if (offset + keyLength > endOffset) break
            val keyData = data.sliceArray(offset until offset + keyLength)
            val key = String(keyData, Charsets.UTF_8)
            offset += keyLength

            val (valueLength, valueLengthBytes) = readVarint(offset, endOffset) ?: break
            offset += valueLengthBytes

            if (offset + valueLength > endOffset) break
            val valueData = data.sliceArray(offset until offset + valueLength)

            val value = runCatching { String(valueData, Charsets.UTF_8) }
                .getOrElse { String(valueData, Charsets.ISO_8859_1) }
            result[key] = value
            offset += valueLength
        }

        return result
    }

    private fun readVarint(offset: Int, endOffset: Int): Pair<Int, Int>? {
        var result = 0
        var shift = 0
        var bytesRead = 0
        var currentOffset = offset

        while (currentOffset < endOffset) {
            val byte = data[currentOffset].toInt()
            result = result or ((byte and VARINT_DATA_MASK) shl shift)

            bytesRead++
            currentOffset++

            if ((byte and VARINT_CONTINUE_MASK) == 0) {
                return Pair(result, bytesRead)
            }

            shift += VARINT_SHIFT_INCREMENT
            if (shift >= VARINT_MAX_SHIFT) return null
        }

        return null
    }
}

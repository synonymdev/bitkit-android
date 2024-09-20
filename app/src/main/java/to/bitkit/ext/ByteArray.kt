@file:Suppress("unused")

package to.bitkit.ext

import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// region hex
@OptIn(ExperimentalStdlibApi::class)
fun ByteArray.toHex(): String = this.toHexString()

@OptIn(ExperimentalStdlibApi::class)
fun String.fromHex(): ByteArray = this.hexToByteArray()

val ByteArray.hex: String get() = joinToString("") { "%02x".format(it) }

val String.hex: ByteArray
    get() {
        require(length % 2 == 0) { "Cannot convert hex string of uneven length to ByteArray: $this" }
        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }
// endregion

// region base64
@OptIn(ExperimentalEncodingApi::class)
fun ByteArray.toBase64(): String = Base64.encode(this)

@OptIn(ExperimentalEncodingApi::class)
fun String.fromBase64(): ByteArray = Base64.decode(this)
// endregion

fun Any.convertToByteArray(): ByteArray {
    val byteArrayOutputStream = ByteArrayOutputStream()
    ObjectOutputStream(byteArrayOutputStream).use { it.writeObject(this) }
    return byteArrayOutputStream.toByteArray()
}

val String.uByteList get() = this.toByteArray().map { it.toUByte() }

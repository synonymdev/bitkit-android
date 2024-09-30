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
// endregion

// region base64
@OptIn(ExperimentalEncodingApi::class)
fun ByteArray.toBase64(): String = Base64.encode(this)

@OptIn(ExperimentalEncodingApi::class)
fun String.fromBase64(): ByteArray = Base64.decode(this)
// endregion

fun Any.convertToByteArray(): ByteArray {
    val out = ByteArrayOutputStream()
    ObjectOutputStream(out).use { it.writeObject(this) }
    return out.toByteArray()
}

val String.uByteList get() = this.toByteArray().map { it.toUByte() }

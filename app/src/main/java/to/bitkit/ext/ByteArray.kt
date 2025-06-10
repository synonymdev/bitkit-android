package to.bitkit.ext

import java.security.MessageDigest
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

val String.uByteList get() = this.toByteArray().map { it.toUByte() }

fun ByteArray.toSha256(): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(this)
}

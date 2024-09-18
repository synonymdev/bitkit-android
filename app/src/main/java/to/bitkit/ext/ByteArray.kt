@file:Suppress("unused")

package to.bitkit.ext

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream

// region hex
val ByteArray.hex: String get() = joinToString("") { "%02x".format(it) }

val String.hex: ByteArray
    get() {
        require(length % 2 == 0) { "Cannot convert string of uneven length to hex ByteArray: $this" }
        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }
// endregion

// region base64
fun ByteArray.toBase64(flags: Int = Base64.DEFAULT): String = Base64.encodeToString(this, flags)

fun String.fromBase64(flags: Int = Base64.DEFAULT): ByteArray = Base64.decode(this, flags)
// endregion

fun Any.convertToByteArray(): ByteArray {
    val byteArrayOutputStream = ByteArrayOutputStream()
    ObjectOutputStream(byteArrayOutputStream).use { it.writeObject(this) }
    return byteArrayOutputStream.toByteArray()
}

val String.uByteList get() = this.toByteArray(Charsets.UTF_8).map { it.toUByte() }

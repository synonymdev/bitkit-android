@file:Suppress("unused")

package to.bitkit.ext

import android.util.Base64
import com.google.common.io.BaseEncoding
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream

fun ByteArray.toHex(): String {
    return BaseEncoding.base16().encode(this).lowercase()
}

// TODO check if this can be replaced with existing ByteArray.toHex()
val ByteArray.hex: String get() = joinToString("") { "%02x".format(it) }

val String.hex: ByteArray get() {
    require(length % 2 == 0) { "Cannot convert string of uneven length to hex ByteArray: $this" }
    return chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}

fun String.asByteArray(): ByteArray {
    return BaseEncoding.base16().decode(this.uppercase())
}

fun Any.convertToByteArray(): ByteArray {
    val bos = ByteArrayOutputStream()
    val oos = ObjectOutputStream(bos)
    oos.writeObject(this)
    oos.flush()
    return bos.toByteArray()
}

fun ByteArray.toBase64(flags: Int = Base64.DEFAULT): String = Base64.encodeToString(this, flags)

fun String.fromBase64(flags: Int = Base64.DEFAULT): ByteArray = Base64.decode(this, flags)

val String.uByteList get() = this.toByteArray(Charsets.UTF_8).map { it.toUByte() }

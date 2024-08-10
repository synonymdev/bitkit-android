package to.bitkit.ext

import com.google.common.io.BaseEncoding
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream

fun ByteArray.toHex(): String {
    return BaseEncoding.base16().encode(this).lowercase()
}

// TODO check if this can be replaced with existing ByteArray.toHex()
val ByteArray.hex: String get() = joinToString("") { "%02x".format(it) }

fun String.toByteArray(): ByteArray {
    return BaseEncoding.base16().decode(this.uppercase())
}

fun convertToByteArray(obj: Any): ByteArray {
    val bos = ByteArrayOutputStream()
    val oos = ObjectOutputStream(bos)
    oos.writeObject(obj)
    oos.flush()
    return bos.toByteArray()
}

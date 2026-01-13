package to.bitkit.ext

import kotlin.io.encoding.Base64

fun ByteArray.toHex(): String = this.toHexString()

fun String.fromHex(): ByteArray = this.hexToByteArray()

fun ByteArray.toBase64(): String = Base64.encode(this)

fun String.fromBase64(): ByteArray = Base64.decode(this)

val String.uByteList get() = this.toByteArray().map { it.toUByte() }

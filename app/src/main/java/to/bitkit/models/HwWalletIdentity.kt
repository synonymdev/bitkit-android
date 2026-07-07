package to.bitkit.models

import to.bitkit.utils.sha256d
import java.math.BigInteger

private const val EXTENDED_KEY_VERSION_SIZE = 4
private const val BASE58_CHECKSUM_SIZE = 4
private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
private const val BASE58_ZERO = '1'
private val BASE58_RADIX = BigInteger.valueOf(58L)
private val XPUB_VERSION = byteArrayOf(0x04.toByte(), 0x88.toByte(), 0xB2.toByte(), 0x1E.toByte())
private val TPUB_VERSION = byteArrayOf(0x04.toByte(), 0x35.toByte(), 0x87.toByte(), 0xCF.toByte())

val KnownDevice.identityKey: String
    get() = identityKey(xpubs, id)

fun identityKey(xpubs: Map<String, String>, fallback: String): String =
    xpubs.values.sorted().joinToString().ifEmpty { fallback }

fun List<KnownDevice>.findWalletId(
    deviceId: String,
    xpubs: Map<String, String>,
    deriveWalletId: (Collection<String>) -> String,
): String {
    val targetIdentityKey = identityKey(xpubs, deviceId)
    firstOrNull { it.identityKey == targetIdentityKey }?.walletId?.takeIf { it.isNotBlank() }?.let { return it }
    if (xpubs.values.any { it.isNotBlank() }) return deriveWalletId(xpubs.values)

    return firstOrNull { it.id == deviceId }?.walletId?.takeIf { it.isNotBlank() }.orEmpty()
}

fun List<KnownDevice>.withWalletIds(
    deriveWalletId: (Collection<String>) -> String,
): List<KnownDevice> {
    val existingByIdentity = filter { it.walletId.isNotBlank() }
        .associate { it.identityKey to it.walletId }
    val generatedByIdentity = mutableMapOf<String, String>()

    return map {
        val walletId = existingByIdentity[it.identityKey]
            ?: generatedByIdentity.getOrPut(it.identityKey) { deriveWalletId(it.xpubs.values) }
        if (it.walletId == walletId) it else it.copy(walletId = walletId)
    }
}

fun List<KnownDevice>.withStandardExtendedKeys(): List<KnownDevice> = map { device ->
    val normalized = device.xpubs.mapValues { it.value.toStandardExtendedKey() }
    if (normalized == device.xpubs) device else device.copy(xpubs = normalized)
}

fun String.toStandardExtendedKey(): String {
    val version = when (take(EXTENDED_KEY_VERSION_SIZE)) {
        "xpub", "tpub" -> return this
        "ypub", "zpub" -> XPUB_VERSION
        "upub", "vpub" -> TPUB_VERSION
        else -> return this
    }
    val payload = decodeBase58Check().getOrNull() ?: return this
    if (payload.size < EXTENDED_KEY_VERSION_SIZE) return this

    val normalized = payload.copyOf()
    version.copyInto(normalized, endIndex = EXTENDED_KEY_VERSION_SIZE)
    return normalized.encodeBase58Check()
}

private fun String.decodeBase58Check(): Result<ByteArray> = runCatching {
    val decoded = decodeBase58()
    check(decoded.size >= BASE58_CHECKSUM_SIZE)
    val payload = decoded.copyOfRange(0, decoded.size - BASE58_CHECKSUM_SIZE)
    val checksum = decoded.copyOfRange(decoded.size - BASE58_CHECKSUM_SIZE, decoded.size)
    check(sha256d(payload).copyOfRange(0, BASE58_CHECKSUM_SIZE).contentEquals(checksum))
    payload
}

private fun String.decodeBase58(): ByteArray {
    var value = BigInteger.ZERO
    forEach {
        val digit = BASE58_ALPHABET.indexOf(it)
        require(digit >= 0)
        value = value.multiply(BASE58_RADIX).add(BigInteger.valueOf(digit.toLong()))
    }

    val bytes = value.toByteArray().let {
        if (it.size > 1 && it.first() == 0.toByte()) it.copyOfRange(1, it.size) else it
    }.takeUnless { value == BigInteger.ZERO } ?: byteArrayOf()
    return ByteArray(takeWhile { it == BASE58_ZERO }.length) + bytes
}

private fun ByteArray.encodeBase58Check(): String {
    val checksum = sha256d(this).copyOfRange(0, BASE58_CHECKSUM_SIZE)
    return (this + checksum).encodeBase58()
}

private fun ByteArray.encodeBase58(): String {
    var value = BigInteger(1, this)
    val encoded = StringBuilder()
    while (value > BigInteger.ZERO) {
        val divideAndRemainder = value.divideAndRemainder(BASE58_RADIX)
        value = divideAndRemainder[0]
        encoded.append(BASE58_ALPHABET[divideAndRemainder[1].toInt()])
    }
    repeat(takeWhile { it == 0.toByte() }.size) { encoded.append(BASE58_ZERO) }
    return encoded.reverse().toString()
}

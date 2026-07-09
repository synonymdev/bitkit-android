package to.bitkit.models

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

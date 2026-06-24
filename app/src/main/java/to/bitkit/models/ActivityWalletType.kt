package to.bitkit.models

import java.util.Locale

enum class ActivityWalletType {
    BITKIT, TREZOR;

    fun id(): String = name.lowercase(Locale.US)

    fun idPrefixed(value: String): String = "${prefix()}$value"

    fun owns(walletId: String): Boolean = walletId == id() || walletId.startsWith(prefix())

    private fun prefix(): String = "${id()}:"
}

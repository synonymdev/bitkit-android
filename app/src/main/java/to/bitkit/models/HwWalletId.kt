package to.bitkit.models

import com.synonym.bitkitcore.deriveWalletId

object HwWalletId {
    fun derive(xpubs: Map<String, String>, deviceType: String = "trezor"): String {
        require(xpubs.isNotEmpty()) { "xpubs must not be empty" }
        return deriveWalletId(deviceType = deviceType, xpubs = xpubs.values.toList())
    }
}

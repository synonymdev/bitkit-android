package to.bitkit.utils

import org.lightningdevkit.ldknode.Network

/**
 * Helper for validating Bitcoin network compatibility of addresses and invoices
 */
object NetworkValidationHelper {
    /**
     * Check if an address/invoice network mismatches the current app network
     * @param addressNetwork The network detected from the address/invoice
     * @param currentNetwork The app's current network (typically Env.network)
     * @return true if there's a mismatch (address won't work on current network)
     */
    fun isNetworkMismatch(addressNetwork: Network?, currentNetwork: Network): Boolean {
        if (addressNetwork == null) return false

        // Special case: regtest uses testnet prefixes (m, n, 2, tb1)
        if (currentNetwork == Network.REGTEST && addressNetwork == Network.TESTNET) {
            return false
        }

        return addressNetwork != currentNetwork
    }
}

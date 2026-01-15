package to.bitkit.utils

import org.lightningdevkit.ldknode.Network

/**
 * Helper for validating Bitcoin network compatibility of addresses and invoices
 */
object NetworkValidationHelper {

    /**
     * Infer the Bitcoin network from an on-chain address prefix
     * @param address The Bitcoin address to check
     * @return The detected network, or null if the address format is unrecognized
     */
    fun getAddressNetwork(address: String): Network? {
        val lowercased = address.lowercase()

        // Bech32/Bech32m addresses (order matters: check bcrt1 before bc1)
        return when {
            lowercased.startsWith("bcrt1") -> Network.REGTEST
            lowercased.startsWith("bc1") -> Network.BITCOIN
            lowercased.startsWith("tb1") -> Network.TESTNET
            else -> {
                // Legacy addresses - check first character
                when (address.firstOrNull()) {
                    '1', '3' -> Network.BITCOIN
                    'm', 'n', '2' -> Network.TESTNET // testnet and regtest share these
                    else -> null
                }
            }
        }
    }

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

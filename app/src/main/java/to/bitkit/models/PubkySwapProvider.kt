package to.bitkit.models

import com.synonym.bitkitcore.BoltzNetwork
import org.lightningdevkit.ldknode.Network
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * A Pubky swap provider, addressed by its bare canonical Pubky public key, and the network it serves.
 * Wallets connect to one by scanning `pubkyswap://connect?pubky=<key>&network=<network>`.
 */
data class PubkySwapProvider(
    val publicKey: String,
    val network: PubkySwapNetwork,
) {
    companion object {
        const val SCHEME = "pubkyswap"
        private const val HOST = "connect"
        private const val PUBKY_QUERY_KEY = "pubky"
        private const val NETWORK_QUERY_KEY = "network"
        private const val NO_PORT = -1

        /** Longest connection link accepted before parsing. */
        private const val MAX_INPUT_LENGTH = 512

        private val QUERY_KEYS = setOf(PUBKY_QUERY_KEY, NETWORK_QUERY_KEY)
        private val ROOT_PATHS = setOf("", "/")

        /** Whether [raw] uses the connection link scheme, whether or not the rest of it is valid. */
        fun isProtocolUrl(raw: String): Boolean = raw.trim().startsWith("$SCHEME:", ignoreCase = true)

        /** Parses a connection link, or returns null for anything that is not exactly one. */
        @Suppress("ReturnCount", "CyclomaticComplexMethod")
        fun parse(raw: String): PubkySwapProvider? {
            val input = raw.trim()
            if (input.length > MAX_INPUT_LENGTH) return null
            val uri = runCatching { URI(input) }.getOrNull() ?: return null
            if (uri.isOpaque || !uri.scheme.equals(SCHEME, ignoreCase = true)) return null
            if (!uri.host.equals(HOST, ignoreCase = true)) return null
            if (uri.rawUserInfo != null || uri.port != NO_PORT || uri.rawFragment != null) return null
            if (uri.rawPath !in ROOT_PATHS) return null

            val query = uri.rawQuery?.let(::parseQuery) ?: return null
            if (query.size != QUERY_KEYS.size || query.map { it.first }.toSet() != QUERY_KEYS) return null
            val values = query.toMap()

            val publicKey = values[PUBKY_QUERY_KEY]?.takeIf { PubkyPublicKeyFormat.bareKey(it) == it } ?: return null
            val network = values[NETWORK_QUERY_KEY]?.let(PubkySwapNetwork::fromWireValue) ?: return null
            return PubkySwapProvider(publicKey, network)
        }

        private fun parseQuery(rawQuery: String): List<Pair<String, String>>? = rawQuery.split("&").map {
            val parts = it.split("=")
            if (parts.size != 2) return null
            val key = decode(parts[0]) ?: return null
            val value = decode(parts[1]) ?: return null
            key to value
        }

        private fun decode(value: String): String? =
            runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrNull()
    }
}

/** A network named by a Pubky swap connection link. */
enum class PubkySwapNetwork(val wireValue: String) {
    BITCOIN("bitcoin"),
    TESTNET("testnet"),
    SIGNET("signet"),
    REGTEST("regtest"),
    ;

    /** The bitkit-core swap network, or null where Bitkit does not support Pubky swaps. */
    val boltzNetwork: BoltzNetwork?
        get() = when (this) {
            BITCOIN -> BoltzNetwork.MAINNET
            REGTEST -> BoltzNetwork.REGTEST
            TESTNET, SIGNET -> null
        }

    fun matches(network: Network): Boolean = this == from(network)

    companion object {
        fun fromWireValue(value: String): PubkySwapNetwork? = entries.firstOrNull { it.wireValue == value }

        fun from(network: Network): PubkySwapNetwork = when (network) {
            Network.BITCOIN -> BITCOIN
            Network.TESTNET -> TESTNET
            Network.SIGNET -> SIGNET
            Network.REGTEST -> REGTEST
        }
    }
}

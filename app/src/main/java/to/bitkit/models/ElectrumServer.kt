package to.bitkit.models

import kotlinx.serialization.Serializable
import org.lightningdevkit.ldknode.Network
import to.bitkit.env.Env

const val MAX_VALID_PORT = 65535

@Serializable
data class ElectrumServer(
    val host: String,
    val tcp: Int,
    val ssl: Int,
    val protocol: ElectrumProtocol,
) {
    fun getPort(): Int {
        return when (protocol) {
            ElectrumProtocol.TCP -> tcp
            ElectrumProtocol.SSL -> ssl
        }
    }

    override fun toString(): String {
        val scheme = protocol.name.lowercase()
        return "$scheme://$host:${getPort()}"
    }

    companion object {
        fun parse(url: String): ElectrumServer {
            val url = url.trim()
            require(url.isNotBlank()) { "URL cannot be blank" }

            val parts = url.split("://", limit = 2)
            require(parts.size == 2) { "Invalid URL format, expected 'scheme://host:port'" }

            val scheme = parts[0].lowercase()
            val protocol = when (scheme) {
                "tcp" -> ElectrumProtocol.TCP
                "ssl" -> ElectrumProtocol.SSL
                else -> throw IllegalArgumentException("Invalid scheme: $scheme, expected 'tcp' or 'ssl'")
            }

            val hostPort = parts[1].split(":", limit = 2)
            require(hostPort.size == 2) { "Invalid URL format, expected 'scheme://host:port'" }

            val host = hostPort[0].trim()
            require(host.isNotBlank()) { "Host cannot be blank" }

            val port = hostPort[1].trim().toIntOrNull()
            require(port != null && port > 0 && port <= MAX_VALID_PORT) { "Invalid port: ${hostPort[1]}" }

            val defaultTcp = ElectrumProtocol.TCP.getDefaultPort()
            val defaultSsl = ElectrumProtocol.SSL.getDefaultPort()

            return ElectrumServer(
                host = host,
                tcp = if (protocol == ElectrumProtocol.TCP) port else defaultTcp,
                ssl = if (protocol == ElectrumProtocol.SSL) port else defaultSsl,
                protocol = protocol,
            )
        }

        fun fromUserInput(
            host: String,
            port: Int,
            protocol: ElectrumProtocol,
        ): ElectrumServer {
            val defaultTcp = ElectrumProtocol.TCP.getDefaultPort()
            val defaultSsl = ElectrumProtocol.SSL.getDefaultPort()

            return ElectrumServer(
                host = host.trim(),
                tcp = if (protocol == ElectrumProtocol.TCP) port else defaultTcp,
                ssl = if (protocol == ElectrumProtocol.SSL) port else defaultSsl,
                protocol = protocol,
            )
        }
    }
}

@Serializable
enum class ElectrumProtocol {
    TCP,
    SSL,
}

@Serializable
data class ElectrumServerPeer(
    val host: String,
    val port: String,
    val protocol: ElectrumProtocol,
)

@Suppress("MagicNumber")
fun ElectrumProtocol.getDefaultPort(): Int {
    val network = Env.network

    return when (this) {
        ElectrumProtocol.TCP -> when (network) {
            Network.BITCOIN -> 50001
            Network.TESTNET -> 60001
            Network.SIGNET -> 60001
            Network.REGTEST -> 60001
        }

        ElectrumProtocol.SSL -> when (network) {
            Network.BITCOIN -> 50002
            Network.TESTNET -> 60002
            Network.SIGNET -> 60002
            Network.REGTEST -> 60002
        }
    }
}

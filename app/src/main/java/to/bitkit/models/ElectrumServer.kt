package to.bitkit.models

import kotlinx.serialization.Serializable
import org.lightningdevkit.ldknode.Network

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
        fun fromUserInput(
            host: String,
            port: Int,
            protocol: ElectrumProtocol,
            network: Network,
        ): ElectrumServer {
            val defaultTcp = ElectrumProtocol.TCP.getDefaultPort(network)
            val defaultSsl = ElectrumProtocol.SSL.getDefaultPort(network)

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

val defaultElectrumPorts = listOf("51002", "50002", "51001", "50001")

fun ElectrumProtocol.getDefaultPort(network: Network): Int {
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

fun getProtocolForPort(port: String, network: Network? = null): ElectrumProtocol {
    if (port == "443") return ElectrumProtocol.SSL

    // Network-specific logic for testnet
    if (network == Network.TESTNET) {
        return if (port == "51002") ElectrumProtocol.SSL else ElectrumProtocol.TCP
    }

    // Default logic for mainnet and other networks
    return when (port) {
        "50002", "51002" -> ElectrumProtocol.SSL
        "50001", "51001" -> ElectrumProtocol.TCP
        else -> ElectrumProtocol.TCP // Default to TCP
    }
}

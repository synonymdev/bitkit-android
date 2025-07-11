package to.bitkit.models

import kotlinx.serialization.Serializable
import org.lightningdevkit.ldknode.Network
import to.bitkit.env.Env

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

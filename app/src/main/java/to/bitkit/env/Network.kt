package to.bitkit.env

import org.bitcoindevkit.Network as BdkNetwork
import org.lightningdevkit.ldknode.Network as LdkNetwork

object Network {

    data class Network(
        val id: String,
        val ldk: LdkNetwork,
        val bdk: BdkNetwork,
    )

    val Regtest = Network(
        id = "regtest",
        ldk = LdkNetwork.REGTEST,
        bdk = BdkNetwork.REGTEST,
    )
}

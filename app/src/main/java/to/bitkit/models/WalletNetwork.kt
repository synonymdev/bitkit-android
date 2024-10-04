package to.bitkit.models

import org.bitcoindevkit.Network as BdkNetwork
import org.lightningdevkit.ldknode.Network as LdkNetwork

enum class WalletNetwork(
    val id: String,
    val ldk: LdkNetwork,
    val bdk: BdkNetwork,
) {
    REGTEST(
        id = "regtest",
        ldk = LdkNetwork.REGTEST,
        bdk = BdkNetwork.REGTEST,
    ),
    SIGNET(
        id = "signet",
        ldk = LdkNetwork.SIGNET,
        bdk = BdkNetwork.SIGNET,
    ),
    TESTNET(
        id = "testnet",
        ldk = LdkNetwork.TESTNET,
        bdk = BdkNetwork.TESTNET,
    ),
    BITCOIN(
        id = "bitcoin",
        ldk = LdkNetwork.BITCOIN,
        bdk = BdkNetwork.BITCOIN,
    );
}

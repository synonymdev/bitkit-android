package to.bitkit.models

import org.lightningdevkit.ldknode.Network
import com.synonym.bitkitcore.Network as BitkitCoreNetwork

fun Network.networkUiText(): String = when (this) {
    Network.BITCOIN -> "Mainnet"
    Network.TESTNET -> "Testnet"
    Network.SIGNET -> "Signet"
    Network.REGTEST -> "Regtest"
}

fun Network.toCoreNetwork(): BitkitCoreNetwork = when (this) {
    Network.BITCOIN -> BitkitCoreNetwork.BITCOIN
    Network.TESTNET -> BitkitCoreNetwork.TESTNET
    Network.SIGNET -> BitkitCoreNetwork.SIGNET
    Network.REGTEST -> BitkitCoreNetwork.REGTEST
}

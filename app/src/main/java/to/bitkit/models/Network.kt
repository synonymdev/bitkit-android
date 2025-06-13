package to.bitkit.models

import org.lightningdevkit.ldknode.Network

fun Network.networkUiText(): String = when (this) {
    Network.BITCOIN -> "Mainnet"
    Network.TESTNET -> "Testnet"
    Network.SIGNET -> "Signet"
    Network.REGTEST -> "Regtest"
}

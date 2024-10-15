package to.bitkit.models.blocktank

import kotlinx.serialization.Serializable

@Suppress("EnumEntryName")
@Serializable
enum class BitcoinNetworkEnum {
    mainnet,
    testnet,
    signet,
    regtest,
}

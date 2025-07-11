package to.bitkit.models

import com.synonym.bitkitcore.AddressType
import org.lightningdevkit.ldknode.Network
import to.bitkit.env.Env

data class AddressTypeInfo(
    val path: String,
    val name: String,
    val shortName: String,
    val description: String,
    val example: String,
)

@Suppress("unused")
fun AddressType.addressTypeInfo(): AddressTypeInfo = when (this) {
    AddressType.P2TR -> AddressTypeInfo(
        path = "m/86'/0'/0'/0/0",
        name = "Taproot",
        shortName = "Taproot",
        description = "Taproot Address",
        example = "(bc1px...)",
    )

    AddressType.P2WPKH -> AddressTypeInfo(
        path = "m/84'/0'/0'/0/0",
        name = "Native Segwit Bech32",
        shortName = "Native Segwit",
        description = "Pay-to-witness-public-key-hash",
        example = "(bc1x...)",
    )

    AddressType.P2SH -> AddressTypeInfo(
        path = "m/49'/0'/0'/0/0",
        name = "Nested Segwit",
        shortName = "Segwit",
        description = "Pay-to-Script-Hash",
        example = "(3x...)",
    )

    AddressType.P2PKH -> AddressTypeInfo(
        path = "m/44'/0'/0'/0/0",
        name = "Legacy",
        shortName = "Legacy",
        description = "Pay-to-public-key-hash",
        example = "(1x...)",
    )

    else -> AddressTypeInfo(
        path = "",
        name = "Unknown",
        shortName = "Unknown",
        description = "Unknown",
        example = "",
    )
}

/**
 * Generate derivation path string for this address type and network
 * @param network The network to generate the path for
 * @param index The address index (default: 0)
 * @param isChange Whether this is a change address (default: false)
 * @return Complete derivation path string like "m/84'/0'/0'/0/0" or "m/84'/0'/0'/1/0" for change
 */
fun AddressType.toDerivationPath(
    index: Int = 0,
    network: Network = Env.network,
    isChange: Boolean = false,
): String {
    val coinType = if (network == Network.BITCOIN) 0 else 1
    val changeIndex = if (isChange) 1 else 0

    return when (this) {
        AddressType.P2TR -> "m/86'/$coinType'/0'/$changeIndex/$index"
        AddressType.P2WPKH -> "m/84'/$coinType'/0'/$changeIndex/$index"
        AddressType.P2SH -> "m/49'/$coinType'/0'/$changeIndex/$index"
        AddressType.P2PKH -> "m/44'/$coinType'/0'/$changeIndex/$index"
        else -> ""
    }
}

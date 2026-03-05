@file:Suppress("MatchingDeclarationName")

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
    val shortExample: String,
)

@Suppress("unused")
fun AddressType.addressTypeInfo(): AddressTypeInfo = when (this) {
    AddressType.P2TR -> AddressTypeInfo(
        path = "m/86'/0'/0'/0/0",
        name = "Taproot",
        shortName = "Taproot",
        description = "Pay-to-Taproot (bc1px...)",
        example = "(bc1px...)",
        shortExample = "bc1p...",
    )

    AddressType.P2WPKH -> AddressTypeInfo(
        path = "m/84'/0'/0'/0/0",
        name = "Native Segwit Bech32",
        shortName = "Native Segwit",
        description = "Pay-to-witness-public-key-hash (bc1q...)",
        example = "(bc1q...)",
        shortExample = "bc1q...",
    )

    AddressType.P2SH -> AddressTypeInfo(
        path = "m/49'/0'/0'/0/0",
        name = "Nested Segwit",
        shortName = "Nested Segwit",
        description = "Pay-to-Script-Hash (3x...)",
        example = "(3x...)",
        shortExample = "3x...",
    )

    AddressType.P2PKH -> AddressTypeInfo(
        path = "m/44'/0'/0'/0/0",
        name = "Legacy",
        shortName = "Legacy",
        description = "Pay-to-public-key-hash (1x...)",
        example = "(1x...)",
        shortExample = "1x...",
    )

    else -> AddressTypeInfo(
        path = "",
        name = "Unknown",
        shortName = "Unknown",
        description = "Unknown",
        example = "",
        shortExample = "",
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

fun AddressType.toSettingsString(): String = when (this) {
    AddressType.P2TR -> "taproot"
    AddressType.P2WPKH -> "nativeSegwit"
    AddressType.P2SH -> "nestedSegwit"
    AddressType.P2PKH -> "legacy"
    else -> "nativeSegwit"
}

fun String.toAddressType(): AddressType? = when (this) {
    "taproot" -> AddressType.P2TR
    "nativeSegwit" -> AddressType.P2WPKH
    "nestedSegwit" -> AddressType.P2SH
    "legacy" -> AddressType.P2PKH
    else -> null
}

val DEFAULT_ADDRESS_TYPE = AddressType.P2WPKH

val DEFAULT_ADDRESS_TYPE_STRING = DEFAULT_ADDRESS_TYPE.toSettingsString()

val ALL_ADDRESS_TYPES = listOf(AddressType.P2PKH, AddressType.P2SH, AddressType.P2WPKH, AddressType.P2TR)

val ALL_ADDRESS_TYPE_STRINGS = ALL_ADDRESS_TYPES.map { it.toSettingsString() }

val NATIVE_WITNESS_TYPES = setOf(AddressType.P2WPKH, AddressType.P2TR)

fun String.addressTypeFromAddress(): String? = when {
    startsWith("bc1p") || startsWith("tb1p") || startsWith("bcrt1p") -> "taproot"
    startsWith("bc1") || startsWith("tb1") || startsWith("bcrt1") -> "nativeSegwit"
    startsWith("3") || startsWith("2") -> "nestedSegwit"
    startsWith("1") || startsWith("m") || startsWith("n") -> "legacy"
    else -> null
}

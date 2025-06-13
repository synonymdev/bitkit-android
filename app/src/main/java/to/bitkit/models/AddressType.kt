package to.bitkit.models

import uniffi.bitkitcore.AddressType

data class AddressTypeInfo(
    val path: String,
    val name: String,
    val shortName: String,
    val description: String,
    val example: String,
)

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

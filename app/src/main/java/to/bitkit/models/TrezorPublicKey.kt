package to.bitkit.models

import com.synonym.bitkitcore.AddressType
import com.synonym.bitkitcore.TrezorPublicKeyResponse

/**
 * Account key to persist on a known device for [addressType].
 *
 * Since bitkit-core 0.7.0 [TrezorPublicKeyResponse.xpub] is always a normalized xpub/tpub, while known
 * devices paired on earlier versions stored the firmware's SLIP-132 form (ypub/zpub/upub/vpub) for
 * BIP-49/BIP-84. Those strings feed [walletKey], entry matching and `deriveWalletId`, so they must stay
 * byte-identical: SegWit types keep `xpubSegwit`, Legacy and Taproot keep `xpub` (Taproot `xpubSegwit`
 * can be a descriptor). See bitkit-core `src/modules/trezor/README.md`,
 * "Migrating to trezor-connect-rs 10.0.0 (Core 0.7.0)".
 */
fun TrezorPublicKeyResponse.storedAccountKey(addressType: AddressType): String = when (addressType) {
    AddressType.P2SH, AddressType.P2WPKH -> xpubSegwit ?: xpub
    else -> xpub
}

/** Address type for the BIP purpose of [path] (e.g. `m/84'/1'/0'` is [AddressType.P2WPKH]), or null. */
fun addressTypeForDerivationPath(path: String): AddressType? {
    val segments = path.split("/")
    if (segments.size < 2 || segments[0] != "m") return null
    return when (segments[1].trimEnd('\'', 'h', 'H')) {
        "44" -> AddressType.P2PKH
        "49" -> AddressType.P2SH
        "84" -> AddressType.P2WPKH
        "86" -> AddressType.P2TR
        else -> null
    }
}

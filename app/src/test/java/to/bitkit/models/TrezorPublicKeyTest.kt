package to.bitkit.models

import com.synonym.bitkitcore.AddressType
import com.synonym.bitkitcore.TrezorPublicKeyResponse
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Fixtures are the BIP-44/49/84/86 test vectors for the "abandon ... about" mnemonic. */
class TrezorPublicKeyTest {

    companion object Fixtures {
        private const val LEGACY_XPUB =
            "xpub6BosfCnifzxcFwrSzQiqu2DBVTshkCXacvNsWGYJVVhhawA7d4R" +
                "5WSWGFNbi8Aw6ZRc1brxMyWMzG3DSSSSoekkudhUd9yLb6qx39T9nMdj"
        private const val NESTED_YPUB =
            "ypub6Ww3ibxVfGzLrAH1PNcjyAWenMTbbAosGNB6VvmSEgytSER9azL" +
                "DWCxoJwW7Ke7icmizBMXrzBx9979FfaHxHcrArf3zbeJJJUZPf663zsP"
        private const val NESTED_XPUB =
            "xpub6C6nQwHaWbSrzs5tZ1q7m5R9cPK9eYpNMFesiXsYrgc1P8bvLLA" +
                "et9JfHjYXKjToD8cBRswJXXbbFpXgwsswVPAZzKMa1jUp2kVkGVUaJa7"
        private const val NATIVE_ZPUB =
            "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXN" +
                "fE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"
        private const val NATIVE_XPUB =
            "xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3" +
                "XyuvPEbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V"
        private const val TAPROOT_XPUB =
            "xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2a" +
                "fYWcLteoGVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ"
        private const val TAPROOT_DESCRIPTOR = "tr([73c5da0a/86'/0'/0']$TAPROOT_XPUB/<0;1>/*)"
    }

    @Test
    fun `segwit accounts keep the firmware slip132 key`() {
        val nested = response(xpub = NESTED_XPUB, xpubSegwit = NESTED_YPUB, path = "m/49'/0'/0'")
        val native = response(xpub = NATIVE_XPUB, xpubSegwit = NATIVE_ZPUB, path = "m/84'/0'/0'")

        assertEquals(NESTED_YPUB, nested.storedAccountKey(AddressType.P2SH))
        assertEquals(NATIVE_ZPUB, native.storedAccountKey(AddressType.P2WPKH))
    }

    @Test
    fun `legacy accounts keep the xpub`() {
        val legacy = response(xpub = LEGACY_XPUB, xpubSegwit = null, path = "m/44'/0'/0'")

        assertEquals(LEGACY_XPUB, legacy.storedAccountKey(AddressType.P2PKH))
    }

    @Test
    fun `taproot accounts never store the descriptor`() {
        val taproot = response(
            xpub = TAPROOT_XPUB,
            xpubSegwit = TAPROOT_DESCRIPTOR,
            descriptor = TAPROOT_DESCRIPTOR,
            path = "m/86'/0'/0'",
        )

        assertEquals(TAPROOT_XPUB, taproot.storedAccountKey(AddressType.P2TR))
    }

    @Test
    fun `segwit accounts fall back to the xpub without a segwit form`() {
        val native = response(xpub = NATIVE_XPUB, xpubSegwit = null, path = "m/84'/0'/0'")

        assertEquals(NATIVE_XPUB, native.storedAccountKey(AddressType.P2WPKH))
    }

    @Test
    fun `address type follows the derivation path purpose`() {
        assertEquals(AddressType.P2PKH, addressTypeForDerivationPath("m/44'/0'/0'"))
        assertEquals(AddressType.P2SH, addressTypeForDerivationPath("m/49'/1'/0'"))
        assertEquals(AddressType.P2WPKH, addressTypeForDerivationPath("m/84'/1'/0'"))
        assertEquals(AddressType.P2TR, addressTypeForDerivationPath("m/86h/0h/0h"))
        assertNull(addressTypeForDerivationPath("m/45'/0'/0'"))
        assertNull(addressTypeForDerivationPath("84'/0'/0'"))
    }

    private fun response(
        xpub: String,
        xpubSegwit: String?,
        path: String,
        descriptor: String? = null,
    ) = TrezorPublicKeyResponse(
        xpub = xpub,
        xpubSegwit = xpubSegwit,
        descriptor = descriptor,
        displayablePublicKey = xpubSegwit ?: xpub,
        path = path,
        publicKey = "02",
        chainCode = "00",
        fingerprint = 0u,
        depth = 3u,
        rootFingerprint = 0x73c5da0au,
    )
}

package to.bitkit.services

import org.junit.Test
import org.lightningdevkit.ldknode.Network
import to.bitkit.repositories.WatchOnlyAccountError
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WatchOnlyAccountXpubDeriverTest {
    @Test
    fun `reads the account xpub from a bitkit-core descriptor`() {
        val descriptor = "wpkh([73c5da0a/84'/0'/0']$BIP84_ACCOUNT_0_XPUB/0/*)"

        assertEquals(BIP84_ACCOUNT_0_XPUB, descriptorAccountXpub(descriptor))
    }

    @Test
    fun `rejects a descriptor without an xpub`() {
        assertFailsWith<WatchOnlyAccountError.InvalidExtendedPublicKey> {
            descriptorAccountXpub("wpkh([73c5da0a/84'/0'/0']tpubABC/0/*)")
        }
    }

    @Test
    fun `keeps mainnet version bytes on bitcoin`() {
        assertEquals(BIP84_ACCOUNT_0_XPUB, accountXpubForNetwork(BIP84_ACCOUNT_0_XPUB, Network.BITCOIN))
    }

    @Test
    fun `uses tpub version bytes on test networks like ldk-node`() {
        assertEquals(BIP84_ACCOUNT_0_TPUB, accountXpubForNetwork(BIP84_ACCOUNT_0_XPUB, Network.REGTEST))
        assertEquals(BIP84_ACCOUNT_0_TPUB, accountXpubForNetwork(BIP84_ACCOUNT_0_XPUB, Network.TESTNET))
    }

    @Test
    fun `rejects an xpub with a bad checksum`() {
        val corrupted = BIP84_ACCOUNT_0_XPUB.dropLast(1) + "W"

        assertFailsWith<WatchOnlyAccountError.InvalidExtendedPublicKey> {
            accountXpubForNetwork(corrupted, Network.REGTEST)
        }
    }

    private companion object {
        // BIP84 test vector ("abandon … about", m/84'/0'/0') with xpub and tpub version bytes.
        const val BIP84_ACCOUNT_0_XPUB =
            "xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3Xyuv" +
                "PEbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V"
        const val BIP84_ACCOUNT_0_TPUB =
            "tpubDCxX2sYFS5bDkSe5GKKYHjBW7tgyN1R3UchpLJvdbf54ohxeGRtd8Mb" +
                "DUe1cguVHe4vnK68DsuD5MXjxi9EXx16rb9EnNsaF5KT99CinaJz"
    }
}

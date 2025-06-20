package to.bitkit.services

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.synonym.bitkitcore.AddressType
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.lightningdevkit.ldknode.Network
import to.bitkit.models.toDerivationPath
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class OnchainServiceTests {
    private lateinit var onchainService: OnchainService

    private val mnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Before
    fun setUp() {
        onchainService = OnchainService()
    }

    @Test
    fun testGenerateMnemonic(): Unit = runBlocking {
        val result = onchainService.generateMnemonic()

        assertNotNull(result)
        val words = result.split(" ")
        assertEquals(12, words.size)
    }

    @Test
    fun testDeriveAddressNativeSegWit(): Unit = runBlocking {
            val derivationPath = AddressType.P2WPKH.toDerivationPath(Network.BITCOIN)

            val result = onchainService.deriveBitcoinAddress(
                mnemonicPhrase = mnemonic,
                derivationPathStr = derivationPath,
                network = Network.BITCOIN,
                bip39Passphrase = null,
            )

            assertNotNull(result)
            assertNotNull(result.address)
            assertEquals(derivationPath, result.path)
            assertTrue(result.address.startsWith("bc1"), "Native SegWit address should start with bc1")
            assertNotNull(result.publicKey)
    }

    @Test
    fun testDeriveAddressSegWit(): Unit = runBlocking {
        val derivationPath = AddressType.P2SH.toDerivationPath(Network.BITCOIN)

        val result = onchainService.deriveBitcoinAddress(
            mnemonicPhrase = mnemonic,
            derivationPathStr = derivationPath,
            network = Network.BITCOIN,
            bip39Passphrase = null,
        )

        assertNotNull(result)
        assertNotNull(result.address)
        assertEquals(derivationPath, result.path)
        assertTrue(result.address.startsWith("3"), "SegWit address should start with 3")
        assertNotNull(result.publicKey)
    }

    @Test
    fun testDeriveAddressTaproot(): Unit = runBlocking {
        val derivationPath = AddressType.P2TR.toDerivationPath(Network.BITCOIN)

        val result = onchainService.deriveBitcoinAddress(
            mnemonicPhrase = mnemonic,
            derivationPathStr = derivationPath,
            network = Network.BITCOIN,
            bip39Passphrase = null,
        )

        assertNotNull(result)
        assertNotNull(result.address)
        assertEquals(derivationPath, result.path)
        assertTrue(result.address.startsWith("bc1p"), "Taproot address should start with bc1p")
        assertNotNull(result.publicKey)
    }

    @Test
    fun testDeriveAddressLegacy(): Unit = runBlocking {
        val derivationPath = AddressType.P2PKH.toDerivationPath(Network.BITCOIN)

        val result = onchainService.deriveBitcoinAddress(
            mnemonicPhrase = mnemonic,
            derivationPathStr = derivationPath,
            network = Network.BITCOIN,
            bip39Passphrase = null,
        )

        assertNotNull(result)
        assertNotNull(result.address)
        assertEquals(derivationPath, result.path)
        assertTrue(result.address.startsWith("1"), "Legacy address should start with 1")
        assertNotNull(result.publicKey)
    }

    @Test
    fun testDeriveAddressTestnet(): Unit = runBlocking {
        val derivationPath = AddressType.P2WPKH.toDerivationPath(Network.TESTNET)

        val result = onchainService.deriveBitcoinAddress(
            mnemonicPhrase = mnemonic,
            derivationPathStr = derivationPath,
            network = Network.TESTNET,
            bip39Passphrase = null,
        )

        assertNotNull(result)
        assertNotNull(result.address)
        assertEquals(derivationPath, result.path)
        assertTrue(result.address.startsWith("tb1"), "Testnet address should start with tb1")
        assertNotNull(result.publicKey)
    }

    @Test
    fun testDeriveAddressRegtest(): Unit = runBlocking {
        val network = Network.REGTEST
        val derivationPath = AddressType.P2WPKH.toDerivationPath(network)

        val result = onchainService.deriveBitcoinAddress(
            mnemonicPhrase = mnemonic,
            derivationPathStr = derivationPath,
            network = network,
            bip39Passphrase = null,
        )

        assertNotNull(result)
        assertNotNull(result.address)
        assertEquals(derivationPath, result.path)
        assertTrue(result.address.startsWith("bcrt1"), "Regtest address should start with bcrt1")
        assertNotNull(result.publicKey)
    }

    @Test
    fun testDeriveAddresses(): Unit = runBlocking {
        val derivationPath = AddressType.P2WPKH.toDerivationPath(Network.BITCOIN)
        val network = Network.BITCOIN
        val isChange = false
        val startIndex = 0u
        val count = 5u

        val result = onchainService.deriveBitcoinAddresses(
            mnemonicPhrase = mnemonic,
            derivationPathStr = derivationPath,
            network = network,
            bip39Passphrase = null,
            isChange = isChange,
            startIndex = startIndex,
            count = count,
        )

        assertNotNull(result)
        assertEquals(5, result.addresses.size)

        result.addresses.forEachIndexed { index, address ->
            assertNotNull(address.address)
            assertTrue(address.address.startsWith("bc1"), "All addresses should start with bc1")
            assertTrue(address.path.contains("/0/$index"), "Path should contain correct change (0) and index")
            assertNotNull(address.publicKey)
        }
    }

    @Test
    fun testDeriveAddressesChange(): Unit = runBlocking {
        val network = Network.BITCOIN

        val result = onchainService.deriveBitcoinAddresses(
            mnemonicPhrase = mnemonic,
            derivationPathStr = AddressType.P2WPKH.toDerivationPath(network),
            network = network,
            bip39Passphrase = null,
            isChange = true,
            startIndex = 0u,
            count = 3u,
        )

        assertNotNull(result)
        assertEquals(3, result.addresses.size)

        result.addresses.forEach { address ->
            assertNotNull(address.address)
            assertTrue(address.address.startsWith("bc1"), "All addresses should start with bc1")
            assertTrue(address.path.contains("/1/"), "Change addresses should have /1/ in path")
            assertNotNull(address.publicKey)
        }
    }

    @Test
    fun testDerivePrivateKey(): Unit = runBlocking {
        val network = Network.BITCOIN

        val result = onchainService.derivePrivateKey(
            mnemonicPhrase = mnemonic,
            derivationPathStr = AddressType.P2WPKH.toDerivationPath(network),
            network = network,
            bip39Passphrase = null,
        )

        assertNotNull(result)
        assertTrue(result.isNotBlank(), "Private key should not be blank")
        assertTrue(result.length >= 51, "Private key should be at least 51 characters for WIF format")
    }

    @Test
    fun testDerivePrivateKeyRegtest(): Unit = runBlocking {
        val network = Network.REGTEST

        val result = onchainService.derivePrivateKey(
            mnemonicPhrase = mnemonic,
            derivationPathStr = AddressType.P2WPKH.toDerivationPath(network),
            network = network,
            bip39Passphrase = null,
        )

        assertNotNull(result)
        assertTrue(result.isNotBlank(), "Private key should not be blank")
        assertTrue(result.length >= 51, "Private key should be at least 51 characters for WIF format")
    }

}

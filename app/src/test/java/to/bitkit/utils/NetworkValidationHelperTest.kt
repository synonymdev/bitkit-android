package to.bitkit.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lightningdevkit.ldknode.Network

class NetworkValidationHelperTest {

    // MARK: - getAddressNetwork Tests

    // Mainnet addresses
    @Test
    fun `getAddressNetwork - mainnet bech32`() {
        val address = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
        assertEquals(Network.BITCOIN, NetworkValidationHelper.getAddressNetwork(address))
    }

    @Test
    fun `getAddressNetwork - mainnet bech32 uppercase`() {
        val address = "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4"
        assertEquals(Network.BITCOIN, NetworkValidationHelper.getAddressNetwork(address))
    }

    @Test
    fun `getAddressNetwork - mainnet P2PKH`() {
        val address = "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2"
        assertEquals(Network.BITCOIN, NetworkValidationHelper.getAddressNetwork(address))
    }

    @Test
    fun `getAddressNetwork - mainnet P2SH`() {
        val address = "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy"
        assertEquals(Network.BITCOIN, NetworkValidationHelper.getAddressNetwork(address))
    }

    // Testnet addresses
    @Test
    fun `getAddressNetwork - testnet bech32`() {
        val address = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"
        assertEquals(Network.TESTNET, NetworkValidationHelper.getAddressNetwork(address))
    }

    @Test
    fun `getAddressNetwork - testnet P2PKH m prefix`() {
        val address = "mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn"
        assertEquals(Network.TESTNET, NetworkValidationHelper.getAddressNetwork(address))
    }

    @Test
    fun `getAddressNetwork - testnet P2PKH n prefix`() {
        val address = "n3ZddxzLvAY9o7184TB4c6FJasAybsw4HZ"
        assertEquals(Network.TESTNET, NetworkValidationHelper.getAddressNetwork(address))
    }

    @Test
    fun `getAddressNetwork - testnet P2SH`() {
        val address = "2MzQwSSnBHWHqSAqtTVQ6v47XtaisrJa1Vc"
        assertEquals(Network.TESTNET, NetworkValidationHelper.getAddressNetwork(address))
    }

    // Regtest addresses
    @Test
    fun `getAddressNetwork - regtest bech32`() {
        val address = "bcrt1q6rhpng9evdsfnn833a4f4vej0asu6dk5srld6x"
        assertEquals(Network.REGTEST, NetworkValidationHelper.getAddressNetwork(address))
    }

    // Edge cases
    @Test
    fun `getAddressNetwork - empty string`() {
        assertNull(NetworkValidationHelper.getAddressNetwork(""))
    }

    @Test
    fun `getAddressNetwork - invalid address`() {
        assertNull(NetworkValidationHelper.getAddressNetwork("invalid"))
    }

    @Test
    fun `getAddressNetwork - random text`() {
        assertNull(NetworkValidationHelper.getAddressNetwork("test123"))
    }

    // MARK: - isNetworkMismatch Tests

    @Test
    fun `isNetworkMismatch - same network`() {
        assertFalse(NetworkValidationHelper.isNetworkMismatch(Network.BITCOIN, Network.BITCOIN))
        assertFalse(NetworkValidationHelper.isNetworkMismatch(Network.TESTNET, Network.TESTNET))
        assertFalse(NetworkValidationHelper.isNetworkMismatch(Network.REGTEST, Network.REGTEST))
    }

    @Test
    fun `isNetworkMismatch - different network`() {
        assertTrue(NetworkValidationHelper.isNetworkMismatch(Network.BITCOIN, Network.TESTNET))
        assertTrue(NetworkValidationHelper.isNetworkMismatch(Network.BITCOIN, Network.REGTEST))
        assertTrue(NetworkValidationHelper.isNetworkMismatch(Network.TESTNET, Network.BITCOIN))
    }

    @Test
    fun `isNetworkMismatch - regtest accepts testnet prefixes`() {
        // Regtest should accept testnet prefixes (m, n, 2, tb1)
        assertFalse(NetworkValidationHelper.isNetworkMismatch(Network.TESTNET, Network.REGTEST))
    }

    @Test
    fun `isNetworkMismatch - testnet rejects regtest addresses`() {
        // Testnet should NOT accept regtest-specific addresses (bcrt1)
        assertTrue(NetworkValidationHelper.isNetworkMismatch(Network.REGTEST, Network.TESTNET))
    }

    @Test
    fun `isNetworkMismatch - null address network`() {
        // When address network is nil (unrecognized format), no mismatch
        assertFalse(NetworkValidationHelper.isNetworkMismatch(null, Network.BITCOIN))
        assertFalse(NetworkValidationHelper.isNetworkMismatch(null, Network.REGTEST))
    }

    // MARK: - Integration Tests

    @Test
    fun `mainnet address on regtest should mismatch`() {
        val address = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
        val addressNetwork = NetworkValidationHelper.getAddressNetwork(address)
        assertTrue(NetworkValidationHelper.isNetworkMismatch(addressNetwork, Network.REGTEST))
    }

    @Test
    fun `testnet address on regtest should not mismatch`() {
        val address = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"
        val addressNetwork = NetworkValidationHelper.getAddressNetwork(address)
        assertFalse(NetworkValidationHelper.isNetworkMismatch(addressNetwork, Network.REGTEST))
    }

    @Test
    fun `regtest address on mainnet should mismatch`() {
        val address = "bcrt1q6rhpng9evdsfnn833a4f4vej0asu6dk5srld6x"
        val addressNetwork = NetworkValidationHelper.getAddressNetwork(address)
        assertTrue(NetworkValidationHelper.isNetworkMismatch(addressNetwork, Network.BITCOIN))
    }

    @Test
    fun `legacy testnet address on regtest should not mismatch`() {
        val address = "mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn" // m-prefix testnet
        val addressNetwork = NetworkValidationHelper.getAddressNetwork(address)
        assertFalse(NetworkValidationHelper.isNetworkMismatch(addressNetwork, Network.REGTEST))
    }
}

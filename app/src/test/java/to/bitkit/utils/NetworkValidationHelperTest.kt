package to.bitkit.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lightningdevkit.ldknode.Network

class NetworkValidationHelperTest {

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
}

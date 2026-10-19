package to.bitkit.models

import com.synonym.bitkitcore.BoltzNetwork
import org.lightningdevkit.ldknode.Network
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PubkySwapProviderTest {

    private val key = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xy"
    private val link = "pubkyswap://connect?pubky=$key&network=regtest"

    @Test
    fun `parse accepts a connection link`() {
        assertEquals(PubkySwapProvider(key, PubkySwapNetwork.REGTEST), PubkySwapProvider.parse(link))
    }

    @Test
    fun `parse accepts any parameter order, a root path and surrounding whitespace`() {
        val expected = PubkySwapProvider(key, PubkySwapNetwork.BITCOIN)

        assertEquals(expected, PubkySwapProvider.parse("  pubkyswap://connect/?network=bitcoin&pubky=$key \n"))
    }

    @Test
    fun `parse accepts scheme and host in any case`() {
        assertEquals(key, PubkySwapProvider.parse("PubkySwap://CONNECT?pubky=$key&network=regtest")?.publicKey)
    }

    @Test
    fun `parse decodes parameter values`() {
        val provider = PubkySwapProvider.parse("pubkyswap://connect?pubky=$key&network=%73ignet")

        assertEquals(PubkySwapNetwork.SIGNET, provider?.network)
    }

    @Test
    fun `parse rejects links over the length limit`() {
        val padded = "$link${" ".repeat(10)}${"&x".repeat(300)}"

        assertNull(PubkySwapProvider.parse(padded))
    }

    @Test
    fun `parse rejects other schemes, hosts, paths and authorities`() {
        assertNull(PubkySwapProvider.parse("pubky://connect?pubky=$key&network=regtest"))
        assertNull(PubkySwapProvider.parse("pubkyswap://swap?pubky=$key&network=regtest"))
        assertNull(PubkySwapProvider.parse("pubkyswap://connect/swap?pubky=$key&network=regtest"))
        assertNull(PubkySwapProvider.parse("pubkyswap://user@connect?pubky=$key&network=regtest"))
        assertNull(PubkySwapProvider.parse("pubkyswap://connect:80?pubky=$key&network=regtest"))
        assertNull(PubkySwapProvider.parse("$link#fragment"))
        assertNull(PubkySwapProvider.parse("pubkyswap:connect?pubky=$key&network=regtest"))
    }

    @Test
    fun `parse rejects missing, extra, duplicate and malformed parameters`() {
        assertNull(PubkySwapProvider.parse("pubkyswap://connect"))
        assertNull(PubkySwapProvider.parse("pubkyswap://connect?pubky=$key"))
        assertNull(PubkySwapProvider.parse("$link&extra=1"))
        assertNull(PubkySwapProvider.parse("pubkyswap://connect?pubky=$key&pubky=$key"))
        assertNull(PubkySwapProvider.parse("pubkyswap://connect?pubky=$key&network"))
        assertNull(PubkySwapProvider.parse("pubkyswap://connect?pubky=$key&network=reg=test"))
        assertNull(PubkySwapProvider.parse("pubkyswap://connect?pubky=$key&network=%zz"))
        assertNull(PubkySwapProvider.parse("pubkyswap://connect?PUBKY=$key&network=regtest"))
    }

    @Test
    fun `parse rejects keys that are prefixed, uppercase, non canonical or invalid`() {
        assertNull(PubkySwapProvider.parse("pubkyswap://connect?pubky=pubky$key&network=regtest"))
        assertNull(PubkySwapProvider.parse("pubkyswap://connect?pubky=${key.uppercase()}&network=regtest"))
        assertNull(PubkySwapProvider.parse("pubkyswap://connect?pubky=${key.dropLast(1)}g&network=regtest"))
        assertNull(PubkySwapProvider.parse("pubkyswap://connect?pubky=short&network=regtest"))
    }

    @Test
    fun `parse rejects unknown networks including mainnet`() {
        assertNull(PubkySwapProvider.parse("pubkyswap://connect?pubky=$key&network=mainnet"))
        assertNull(PubkySwapProvider.parse("pubkyswap://connect?pubky=$key&network=Regtest"))
    }

    @Test
    fun `isProtocolUrl matches the scheme even when the link is malformed`() {
        assertTrue(PubkySwapProvider.isProtocolUrl(" PUBKYSWAP://connect?bad"))
        assertFalse(PubkySwapProvider.isProtocolUrl("pubkyauth://signin"))
        assertFalse(PubkySwapProvider.isProtocolUrl("bitcoin:bcrt1q"))
    }

    @Test
    fun `network maps only bitcoin and regtest to swap networks`() {
        assertEquals(BoltzNetwork.MAINNET, PubkySwapNetwork.BITCOIN.boltzNetwork)
        assertEquals(BoltzNetwork.REGTEST, PubkySwapNetwork.REGTEST.boltzNetwork)
        assertNull(PubkySwapNetwork.TESTNET.boltzNetwork)
        assertNull(PubkySwapNetwork.SIGNET.boltzNetwork)
    }

    @Test
    fun `network matches the app network`() {
        assertTrue(PubkySwapNetwork.REGTEST.matches(Network.REGTEST))
        assertFalse(PubkySwapNetwork.BITCOIN.matches(Network.REGTEST))
    }
}

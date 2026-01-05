package to.bitkit.ext

import com.synonym.bitkitcore.ILspNode
import org.junit.Test
import org.lightningdevkit.ldknode.PeerDetails
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeerDetailsTest : BaseUnitTest() {

    @Test
    fun `host extension returns host part of address`() {
        val peer = PeerDetails(
            nodeId = "node123",
            address = "example.com:9735",
            isConnected = false,
            isPersisted = false,
        )

        assertEquals("example.com", peer.host)
    }

    @Test
    fun `port extension returns port part of address`() {
        val peer = PeerDetails(
            nodeId = "node123",
            address = "example.com:9735",
            isConnected = false,
            isPersisted = false,
        )

        assertEquals("9735", peer.port)
    }

    @Test
    fun `uri extension returns full connection string`() {
        val peer = PeerDetails(
            nodeId = "node123",
            address = "example.com:9735",
            isConnected = false,
            isPersisted = false,
        )

        assertEquals("node123@example.com:9735", peer.uri)
    }

    @Test
    fun `parse correctly parses full connection string`() {
        val uri = "028a8910b0048630d4eb17af25668cdd7ea6f2d8ae20956e7a06e2ae46ebcb69fc@34.65.86.104:9400"

        val peer = PeerDetails.of(uri)

        assertEquals("028a8910b0048630d4eb17af25668cdd7ea6f2d8ae20956e7a06e2ae46ebcb69fc", peer.nodeId)
        assertEquals("34.65.86.104:9400", peer.address)
        assertFalse(peer.isConnected)
        assertFalse(peer.isPersisted)
    }

    @Test
    fun `parse throws on invalid uri without at symbol`() {
        val invalidUri = "node123example.com:9735"

        val exception = assertFailsWith<IllegalArgumentException> {
            PeerDetails.of(invalidUri)
        }

        assertTrue(exception.message!!.contains("Invalid uri format"))
    }

    @Test
    fun `parse throws on invalid uri without port`() {
        val invalidUri = "node123@example.com"

        val exception = assertFailsWith<IllegalArgumentException> {
            PeerDetails.of(invalidUri)
        }

        assertTrue(exception.message!!.contains("Invalid uri format"))
    }

    @Test
    fun `from creates PeerDetails with correct values`() {
        val peer = PeerDetails.of(
            nodeId = "node123",
            host = "example.com",
            port = "9735",
        )

        assertEquals("node123", peer.nodeId)
        assertEquals("example.com:9735", peer.address)
        assertFalse(peer.isConnected)
        assertFalse(peer.isPersisted)
    }

    @Test
    fun `toPeerDetails handles connection string with host and port only`() {
        val lspNode = ILspNode(
            alias = "LSP1",
            pubkey = "node1pubkey",
            connectionStrings = listOf("node1.example.com:9735"),
            readonly = null,
        )

        val peer = lspNode.toPeerDetails()

        assertNotNull(peer)
        assertEquals("node1pubkey", peer.nodeId)
        assertEquals("node1.example.com:9735", peer.address)
        assertFalse(peer.isConnected)
        assertFalse(peer.isPersisted)
    }

    @Test
    fun `toPeerDetails handles connection string with full uri format`() {
        val lspNode = ILspNode(
            alias = "LSP1",
            pubkey = "028a8910b0048630d4eb17af25668cdd7ea6f2d8ae20956e7a06e2ae46ebcb69fc",
            connectionStrings = listOf(
                "028a8910b0048630d4eb17af25668cdd7ea6f2d8ae20956e7a06e2ae46ebcb69fc@34.65.86.104:9400",
            ),
            readonly = null,
        )

        val peer = lspNode.toPeerDetails()

        assertNotNull(peer)
        assertEquals("028a8910b0048630d4eb17af25668cdd7ea6f2d8ae20956e7a06e2ae46ebcb69fc", peer.nodeId)
        assertEquals("34.65.86.104:9400", peer.address)
        assertFalse(peer.isConnected)
        assertFalse(peer.isPersisted)
    }

    @Test
    fun `toPeerDetails returns null when connectionStrings is empty`() {
        val lspNode = ILspNode(
            alias = "LSP1",
            pubkey = "node1pubkey",
            connectionStrings = emptyList(),
            readonly = null,
        )

        val peer = lspNode.toPeerDetails()

        assertNull(peer)
    }

    @Test
    fun `toPeerDetails handles IP address in connection string`() {
        val lspNode = ILspNode(
            alias = "LSP1",
            pubkey = "node1pubkey",
            connectionStrings = listOf("127.0.0.1:9735"),
            readonly = null,
        )

        val peer = lspNode.toPeerDetails()

        assertNotNull(peer)
        assertEquals("node1pubkey", peer.nodeId)
        assertEquals("127.0.0.1:9735", peer.address)
    }

    @Test
    fun `toPeerDetails handles IPv6 address in connection string`() {
        val lspNode = ILspNode(
            alias = "LSP1",
            pubkey = "node1pubkey",
            connectionStrings = listOf("[2001:db8::1]:9735"),
            readonly = null,
        )

        val peer = lspNode.toPeerDetails()

        assertNotNull(peer)
        assertEquals("node1pubkey", peer.nodeId)
        assertEquals("[2001:db8::1]:9735", peer.address)
    }

    @Test
    fun `toPeerDetails takes first connection string when multiple provided`() {
        val lspNode = ILspNode(
            alias = "LSP1",
            pubkey = "node1pubkey",
            connectionStrings = listOf("primary.example.com:9735", "backup.example.com:9735"),
            readonly = null,
        )

        val peer = lspNode.toPeerDetails()

        assertNotNull(peer)
        assertEquals("primary.example.com:9735", peer.address)
    }

    @Test
    fun `toPeerDetailsList converts all valid nodes`() {
        val lspNodes = listOf(
            ILspNode(
                alias = "LSP1",
                pubkey = "node1pubkey",
                connectionStrings = listOf("node1.example.com:9735"),
                readonly = null,
            ),
            ILspNode(
                alias = "LSP2",
                pubkey = "node2pubkey",
                connectionStrings = listOf("node2pubkey@node2.example.com:9735"),
                readonly = null,
            ),
        )

        val peers = lspNodes.toPeerDetailsList()

        assertEquals(2, peers.size)
        assertEquals("node1pubkey", peers[0].nodeId)
        assertEquals("node1.example.com:9735", peers[0].address)
        assertEquals("node2pubkey", peers[1].nodeId)
        assertEquals("node2.example.com:9735", peers[1].address)
    }

    @Test
    fun `toPeerDetailsList filters out nodes with empty connectionStrings`() {
        val lspNodes = listOf(
            ILspNode(
                alias = "LSP1",
                pubkey = "node1pubkey",
                connectionStrings = listOf("node1.example.com:9735"),
                readonly = null,
            ),
            ILspNode(
                alias = "LSP2",
                pubkey = "node2pubkey",
                connectionStrings = emptyList(),
                readonly = null,
            ),
            ILspNode(
                alias = "LSP3",
                pubkey = "node3pubkey",
                connectionStrings = listOf("node3.example.com:9735"),
                readonly = null,
            ),
        )

        val peers = lspNodes.toPeerDetailsList()

        assertEquals(2, peers.size)
        assertEquals("node1pubkey", peers[0].nodeId)
        assertEquals("node3pubkey", peers[1].nodeId)
    }

    @Test
    fun `toPeerDetailsList returns empty list when all nodes have empty connectionStrings`() {
        val lspNodes = listOf(
            ILspNode(
                alias = "LSP1",
                pubkey = "node1pubkey",
                connectionStrings = emptyList(),
                readonly = null,
            ),
            ILspNode(
                alias = "LSP2",
                pubkey = "node2pubkey",
                connectionStrings = emptyList(),
                readonly = null,
            ),
        )

        val peers = lspNodes.toPeerDetailsList()

        assertTrue(peers.isEmpty())
    }
}

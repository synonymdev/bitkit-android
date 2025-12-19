package to.bitkit.ext

import com.synonym.bitkitcore.ILspNode
import org.junit.Test
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PeerDetailsExtTest : BaseUnitTest() {

    // Exact format from https://api.stag0.blocktank.to/blocktank/api/v2/info
    @Test
    fun `toPeerDetails extracts address from Blocktank API connection string`() {
        val node = ILspNode(
            alias = "Synonym-Own-Regtest-0",
            pubkey = "028a8910b0048630d4eb17af25668cdd7ea6f2d8ae20956e7a06e2ae46ebcb69fc",
            connectionStrings = listOf(
                "028a8910b0048630d4eb17af25668cdd7ea6f2d8ae20956e7a06e2ae46ebcb69fc@34.65.86.104:9400"
            ),
            readonly = false,
        )

        val peerDetails = node.toPeerDetails()

        assertEquals("028a8910b0048630d4eb17af25668cdd7ea6f2d8ae20956e7a06e2ae46ebcb69fc", peerDetails?.nodeId)
        assertEquals("34.65.86.104:9400", peerDetails?.address)
    }

    @Test
    fun `toPeerDetails returns null when connectionStrings is empty`() {
        val node = ILspNode(
            alias = "LSP",
            pubkey = "somepubkey",
            connectionStrings = emptyList(),
            readonly = null,
        )

        assertNull(node.toPeerDetails())
    }

    @Test
    fun `toPeerDetails returns null when connection string has no @ separator`() {
        val node = ILspNode(
            alias = "LSP",
            pubkey = "somePubkey",
            connectionStrings = listOf("invalid-connection-string"),
            readonly = null,
        )

        assertNull(node.toPeerDetails())
    }

    @Test
    fun `toPeerDetailsList maps list of nodes`() {
        val nodes = listOf(
            ILspNode(
                alias = "LSP1",
                pubkey = "pubkey1",
                connectionStrings = listOf("pubkey1@host1.com:9735"),
                readonly = null,
            ),
            ILspNode(
                alias = "LSP2",
                pubkey = "pubkey2",
                connectionStrings = listOf("pubkey2@host2.com:9735"),
                readonly = null,
            ),
        )

        val peerDetailsList = nodes.toPeerDetailsList()

        assertEquals(2, peerDetailsList.size)
        assertEquals("pubkey1", peerDetailsList[0].nodeId)
        assertEquals("host1.com:9735", peerDetailsList[0].address)
        assertEquals("pubkey2", peerDetailsList[1].nodeId)
        assertEquals("host2.com:9735", peerDetailsList[1].address)
    }
}

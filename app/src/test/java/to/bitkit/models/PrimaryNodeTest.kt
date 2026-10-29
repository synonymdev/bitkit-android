package to.bitkit.models

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PrimaryNodeTest {

    private val nodeId = "024a6cc632544189896cca8e06e375bdb1d1f34a71a4c25d09a39cf0ed920a299c"

    @Test
    fun `parses a node uri and normalizes it`() {
        val primary = PrimaryNode.parse("lightning:${nodeId.uppercase()}@Bore.pub:39101")

        assertEquals(PrimaryNode(nodeId, "bore.pub:39101"), primary)
        assertEquals("$nodeId@bore.pub:39101", primary.uri)
    }

    @Test
    fun `parses a bare public key without an address`() {
        assertEquals(PrimaryNode(nodeId), PrimaryNode.parse(nodeId))
    }

    @Test
    fun `rejects invalid keys and addresses`() {
        assertFailsWith<PrimaryNodeError.InvalidInput> { PrimaryNode.parse("02" + "0".repeat(64)) }
        assertFailsWith<PrimaryNodeError.InvalidInput> { PrimaryNode.parse("$nodeId@host:0") }
        assertFailsWith<PrimaryNodeError.InvalidInput> { PrimaryNode.parse("$nodeId@host:9735/path") }
        assertFailsWith<PrimaryNodeError.InvalidInput> { PrimaryNode.parse("$nodeId @host:9735") }
        assertNull(PrimaryNode.parseOrNull("not a node"))
    }
}

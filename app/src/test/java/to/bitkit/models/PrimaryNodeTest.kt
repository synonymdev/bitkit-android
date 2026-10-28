package to.bitkit.models

import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PrimaryNodeTest {
    companion object {
        private const val KEY = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    }

    @Test
    fun `accepts a bare compressed key for runtime address resolution`() {
        val node = PrimaryNode.parse(" $KEY ")

        assertEquals(KEY, node.nodeId)
        assertEquals(KEY, node.uri)
        assertNull(node.address)
    }

    @Test
    fun `normalizes wrapped node URIs and DNS names`() {
        val node = PrimaryNode.parse(" LIGHTNING://${KEY.uppercase(Locale.ROOT)}@Node.Example:9735 ")

        assertEquals("$KEY@node.example:9735", node.uri)
        assertEquals(node, PrimaryNode.parse("node://$KEY@node.example:9735"))
    }

    @Test
    fun `keeps bracketed IPv6 endpoints and local regtest addresses`() {
        assertEquals("[2001:db8::1]:9735", PrimaryNode.parse("$KEY@[2001:db8::1]:9735").address)
        assertEquals("10.0.2.2:19735", PrimaryNode.parse("$KEY@10.0.2.2:19735").address)
    }

    @Test
    fun `preserves the requested Iroh endpoint and relay trailing dot`() {
        val uri = "$KEY@iroh:${"8e".repeat(32)}?relay=https%3A%2F%2Fuse1-1.relay.n0.iroh.link.%2F"
        val parsed = PrimaryNode.parse(uri)

        assertEquals(uri, parsed.uri)
        assertEquals(
            "https://use1-1.relay.n0.iroh.link./",
            IrohPrimaryEndpoint.parse(requireNotNull(parsed.address)).relayUrl,
        )
    }

    @Test
    fun `rejects ambiguous unsafe or unsupported Iroh relay addresses`() {
        val prefix = "$KEY@iroh:${"ab".repeat(32)}?relay="
        listOf(
            "$KEY@iroh:${"ab".repeat(31)}?relay=https%3A%2F%2Frelay.example%2F",
            prefix + "http%3A%2F%2Frelay.example%2F",
            prefix + "https%3A%2F%2Fuser%40relay.example%2F",
            prefix + "https%3A%2F%2Frelay.example%2Fpath",
            prefix + "https%3A%2F%2Frelay.example%2F%3Ftoken%3Dx",
            prefix + "https%3A%2F%2Frelay.example%2F%23fragment",
            prefix + "https%3A%2F%2Frelay.example%3A0%2F",
            prefix + "https%3A%2F%2Frelay.example%2F&relay=https%3A%2F%2Fother.example%2F",
            prefix + "%zz",
        ).forEach { assertFailsWith<PrimaryNodeError.InvalidInput>(it) { PrimaryNode.parse(it) } }
    }

    @Test
    fun `rejects malformed keys including encoded points outside secp256k1`() {
        listOf("", "04${KEY.drop(2)}", "02${"ff".repeat(32)}", KEY.dropLast(1), "x".repeat(66)).forEach {
            assertFailsWith<PrimaryNodeError.InvalidInput>(it) { PrimaryNode.parse(it) }
        }
    }

    @Test
    fun `rejects missing ports and URI paths credentials queries or fragments`() {
        listOf(
            "$KEY@node.example",
            "$KEY@node.example:0",
            "$KEY@node.example:65536",
            "$KEY@node.example:abc",
            "$KEY@node.example:9735/",
            "$KEY@node.example:9735?token=secret",
            "$KEY@node.example:9735#node",
            "$KEY@user@node.example:9735",
            "$KEY@node example:9735",
            "$KEY@2001:db8::1:9735",
            "$KEY@iroh:${"ab".repeat(32)}",
            "$KEY@",
        ).forEach {
            assertFailsWith<PrimaryNodeError.InvalidInput>(it) { PrimaryNode.parse(it) }
        }
    }
}

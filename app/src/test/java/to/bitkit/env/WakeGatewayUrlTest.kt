package to.bitkit.env

import org.junit.Test
import org.lightningdevkit.ldknode.Network
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WakeGatewayUrlTest {
    @Test
    fun `each network uses its deployed gateway`() {
        assertEquals("https://wake.bitkit.to", Env.wakeGatewayUrl(Network.BITCOIN))
        assertEquals("https://wake.staging.bitkit.to", Env.wakeGatewayUrl(Network.REGTEST))
    }

    @Test
    fun `testnet and signet have no gateway`() {
        assertNull(Env.wakeGatewayUrl(Network.TESTNET))
        assertNull(Env.wakeGatewayUrl(Network.SIGNET))
    }
}

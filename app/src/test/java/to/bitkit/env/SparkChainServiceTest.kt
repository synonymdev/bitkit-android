package to.bitkit.env

import org.junit.Test
import org.lightningdevkit.ldknode.Network
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SparkChainServiceTest {
    @Test
    fun `spark reads the chain from bitkit electrs on mainnet`() {
        assertEquals("https://bitkit.to/electrs", Env.sparkChainServiceUrl(Network.BITCOIN))
    }

    @Test
    fun `spark keeps the sdk default chain service off mainnet`() {
        assertNull(Env.sparkChainServiceUrl(Network.REGTEST))
        assertNull(Env.sparkChainServiceUrl(Network.TESTNET))
        assertNull(Env.sparkChainServiceUrl(Network.SIGNET))
    }
}

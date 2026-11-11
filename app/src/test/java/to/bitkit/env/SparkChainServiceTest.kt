package to.bitkit.env

import org.junit.Test
import org.lightningdevkit.ldknode.Network
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `on-chain spark funding needs the spark regtest chain on regtest`() {
        assertFalse(Env.sparkSharesNodeChain(Network.REGTEST, regtestEsploraUrl = null))
        assertTrue(Env.sparkSharesNodeChain(Network.REGTEST, regtestEsploraUrl = "http://10.0.2.2:3999"))
        assertTrue(Env.sparkSharesNodeChain(Network.BITCOIN, regtestEsploraUrl = null))
    }
}

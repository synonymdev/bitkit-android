package to.bitkit.env

import org.junit.Test
import org.lightningdevkit.ldknode.Network
import to.bitkit.data.SparkPayoutChain
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SparkChainServiceTest {
    private val local = LocalSparkSettings("encoded", "http://127.0.0.1:23090/api")

    @Test
    fun `spark reads the chain from bitkit electrs on mainnet`() {
        assertEquals("https://bitkit.to/electrs", Env.sparkChainServiceUrl(Network.BITCOIN))
    }

    @Test
    fun `spark keeps the sdk default chain service off mainnet`() {
        assertNull(Env.sparkChainServiceUrl(Network.REGTEST, localSpark = null))
        assertNull(Env.sparkChainServiceUrl(Network.TESTNET))
        assertNull(Env.sparkChainServiceUrl(Network.SIGNET))
    }

    @Test
    fun `a complete fixture routes only regtest to its chain`() {
        assertEquals(local.chainUrl, Env.sparkChainServiceUrl(Network.REGTEST, local))
        assertEquals(local.chainUrl, SparkPayoutChain.esploraUrl(Network.REGTEST, Env.sparkRegtestEsploraUrl(local)))
        assertEquals(Env.bitkitEsploraUrl, Env.sparkChainServiceUrl(Network.BITCOIN, local))
        assertNull(Env.sparkChainServiceUrl(Network.SIGNET, local))
        assertNull(Env.sparkChainServiceUrl(Network.REGTEST, local.copy(configBase64 = "")))
        assertNull(Env.sparkRegtestEsploraUrl(local.copy(chainUrl = "")))
    }

    @Test
    fun `on-chain spark funding needs the spark regtest chain on regtest`() {
        assertFalse(Env.sparkSharesNodeChain(Network.REGTEST, regtestEsploraUrl = null, localSpark = null))
        assertTrue(Env.sparkSharesNodeChain(Network.REGTEST, "http://10.0.2.2:3999", localSpark = null))
        assertTrue(Env.sparkSharesNodeChain(Network.BITCOIN, regtestEsploraUrl = null, localSpark = null))
        assertFalse(Env.sparkSharesNodeChain(Network.REGTEST, regtestEsploraUrl = null, localSpark = local))
        assertFalse(Env.sparkSharesNodeChain(Network.REGTEST, local.chainUrl, local))
        assertFalse(Env.sparkSharesNodeChain(Network.REGTEST, "http://10.0.2.2:3999", local))
        assertFalse(Env.sparkSharesNodeChain(Network.REGTEST, null, local.copy(configBase64 = "")))
    }
}

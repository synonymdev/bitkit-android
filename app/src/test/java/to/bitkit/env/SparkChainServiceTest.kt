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
    fun `a supplied slash-only fixture URL cannot fall back to hosted regtest`() {
        assertNull(LocalSparkSettings.fromInputs("", " "))
        val incomplete = requireNotNull(LocalSparkSettings.fromInputs("", "/"))
        assertFalse(incomplete.isComplete)
        assertNull(Env.sparkRegtestEsploraUrl(incomplete))
        assertEquals(local, LocalSparkSettings.fromInputs(local.configBase64, local.chainUrl + "/"))
    }

    @Test
    fun `spark reads the chain from bitkit electrs on mainnet`() {
        assertEquals("https://api.bitkit.to/electrs", Env.sparkChainServiceUrl(Network.BITCOIN))
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
        assertFalse(Env.sparkSharesNodeChain(Network.REGTEST, regtestEsploraUrl = null, null, nodeElectrumUrl = null))
        assertTrue(Env.sparkSharesNodeChain(Network.REGTEST, "http://10.0.2.2:3999", null, nodeElectrumUrl = null))
        assertTrue(Env.sparkSharesNodeChain(Network.BITCOIN, regtestEsploraUrl = null, null, nodeElectrumUrl = null))
    }

    @Test
    fun `a local fixture shares the node chain only while the node runs on the fixture electrum`() {
        fun shares(
            spark: LocalSparkSettings? = local,
            node: String? = "tcp://10.0.2.2:60001",
            esplora: String? = null,
        ) = Env.sparkSharesNodeChain(Network.REGTEST, esplora, spark, node, localHost = "10.0.2.2")

        assertTrue(shares())
        assertTrue(shares(spark = local.copy(chainUrl = "http://10.0.2.2:23090/api")))
        assertTrue(
            Env.sparkSharesNodeChain(Network.REGTEST, null, local, "tcp://127.0.0.1:60001", localHost = "127.0.0.1")
        )
        assertFalse(shares(node = null))
        assertFalse(shares(node = "tcp://regtest.example:60001"))
        assertFalse(shares(node = "ssl://electrs.bitkit.stag0.blocktank.to:9999"))
        assertFalse(shares(node = "tcp://10.0.2.2:60002"))
        assertFalse(shares(esplora = "http://10.0.2.2:3999"))
        assertFalse(shares(esplora = local.chainUrl))
        assertFalse(shares(spark = local.copy(configBase64 = "")))
        assertFalse(shares(spark = local.copy(chainUrl = "http://127.0.0.1:23091/api")))
        assertFalse(shares(spark = local.copy(chainUrl = "http://regtest.example:23090/api")))
    }
}

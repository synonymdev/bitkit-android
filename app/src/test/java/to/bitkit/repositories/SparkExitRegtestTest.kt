package to.bitkit.repositories

import io.ktor.client.HttpClient
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Runs the exit's chain client against Spark's hosted regtest mempool. It needs the network, so it only runs with
 * `SPARK_EXIT_REGTEST=1` in the environment of `just test file SparkExitRegtestTest`.
 */
class SparkExitRegtestTest : BaseUnitTest() {
    private val chain = EsploraSparkExitChain(checkNotNull(EsploraSparkExitChain.defaultHosts("regtest")), HttpClient())

    @Before
    fun requireOptIn() {
        assumeTrue(
            "Set SPARK_EXIT_REGTEST=1 to run against Spark's regtest",
            System.getenv("SPARK_EXIT_REGTEST") == "1"
        )
    }

    @Test
    fun `reads the tip and an unused funding address`() = test {
        assertTrue(chain.tipHeight() > 0u)
        assertTrue(chain.utxos("bcrt1qzyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3lgth6c").isEmpty())
    }

    /**
     * A parent spending a missing input with its child, as a package: the live node reads the body and answers per
     * transaction, which the classifier reads as retry later rather than a rejection.
     */
    @Test
    fun `live node answers a package the classifier retries`() = test {
        val parentId = SparkExitFixtures.PARENT_TXID.chunked(2).reversed().joinToString("")
        val child = "0200000001${parentId}0000000000ffffffff01e8030000000000001600142222222222222222222222222222" +
            "22222222222200000000"
        val tx = SparkExitFixtures.tx(
            SparkExitFixtures.PARENT_TXID,
            cpfp = child
        ).copy(txHex = SparkExitFixtures.PARENT_HEX)

        val outcome = chain.broadcast(tx)

        assertIs<SparkExitBroadcastOutcome.InputsMissing>(outcome)
        assertTrue("missingorspent" in outcome.reason, outcome.reason)
    }
}

package to.bitkit.services

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import breez_sdk_spark.ConnectRequest
import breez_sdk_spark.CpfpFundingKind
import breez_sdk_spark.ExitLeafSelection
import breez_sdk_spark.PrepareRecoverFundsRequest
import breez_sdk_spark.Seed
import breez_sdk_spark.connect
import breez_sdk_spark.defaultConfig
import breez_sdk_spark.singleKeyCpfpSigner
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.lightningdevkit.ldknode.Network
import to.bitkit.repositories.EsploraSparkExitChain
import to.bitkit.repositories.SparkExitFundingKey
import to.bitkit.repositories.SparkExitQuote
import to.bitkit.test.annotations.DeviceIntegration
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import breez_sdk_spark.Network as SparkNetwork

/**
 * Runs the exit's SDK, key and chain calls on a device against Spark's hosted regtest, for a new wallet with nothing
 * to exit. It needs the network, so it only runs with the instrumentation argument `sparkExitRegtest true`.
 */
@RunWith(AndroidJUnit4::class)
@DeviceIntegration
class SparkExitRegtestTest {
    private val onchain = OnchainService()

    @Before
    fun requireOptIn() {
        val isOptedIn = InstrumentationRegistry.getArguments().getString("sparkExitRegtest") == "true"
        assumeTrue("Pass -e sparkExitRegtest true to run against Spark's regtest", isOptedIn)
    }

    @Test
    fun quotesAnEmptyWalletAndReadsItsFundingAddress(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = File(context.cacheDir, "spark-exit-regtest-${System.nanoTime()}").apply { mkdirs() }
        val mnemonic = onchain.generateMnemonic()
        val config = defaultConfig(SparkNetwork.REGTEST).apply {
            leafOptimizationConfig = leafOptimizationConfig.copy(autoEnabled = false)
        }
        val sdk = connect(ConnectRequest(config, Seed.Mnemonic(mnemonic, null), storage.path))
        try {
            val path = SparkExitFundingKey.path(0, Network.REGTEST)
            val address = onchain.deriveBitcoinAddress(mnemonic, path, Network.REGTEST, null)
            val again = onchain.deriveBitcoinAddress(mnemonic, path, Network.REGTEST, null)
            val secret = SparkExitFundingKey.secretKey(onchain.derivePrivateKey(mnemonic, path, Network.REGTEST, null))
            singleKeyCpfpSigner(secret)
            assertEquals("m/84'/1'/7797'/0/0", path)
            assertTrue(address.address.startsWith("bcrt1q"), address.address)
            assertEquals(66, address.publicKey.length)
            assertEquals(32, secret.size)
            assertEquals(address, again)

            val feeRate = maxOf(1uL, sdk.recommendedFees().fastestFee)
            val prepared = BreezSparkExitRecovering(sdk).prepare(
                PrepareRecoverFundsRequest(feeRate, CpfpFundingKind.P2wpkh, address.address, ExitLeafSelection.All),
            )
            assertTrue(prepared.leaves.isEmpty())
            assertFalse(SparkExitQuote(prepared).isWorthExiting)

            val chain = EsploraSparkExitChain(EsploraSparkExitChain.defaultHosts("regtest"), HttpClient())
            assertTrue(chain.tipHeight() > 0u)
            assertTrue(chain.utxos(address.address).isEmpty())
        } finally {
            sdk.disconnect()
            storage.deleteRecursively()
        }
    }
}

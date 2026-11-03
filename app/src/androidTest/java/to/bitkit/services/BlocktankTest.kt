package to.bitkit.services

import androidx.test.platform.app.InstrumentationRegistry
import com.synonym.bitkitcore.initDb
import com.synonym.bitkitcore.updateBlocktankUrl
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import to.bitkit.env.Env
import to.bitkit.test.annotations.CoreServiceIntegration
import to.bitkit.test.annotations.DeviceIntegration
import javax.inject.Inject
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@HiltAndroidTest
@DeviceIntegration
@CoreServiceIntegration
class BlocktankTest {
    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var service: CoreService

    private val targetContext by lazy { InstrumentationRegistry.getInstrumentation().targetContext }

    @Before
    fun init() {
        hiltRule.inject()
        val testDbPath = targetContext.cacheDir.absolutePath
        Env.initAppStoragePath(testDbPath)
        initDb(testDbPath)
        runBlocking {
            updateBlocktankUrl(Env.blocktankApiUrl)
        }
    }

    @Test
    fun testGetInfo() = runBlocking {
        val info = service.blocktank.info(refresh = true)
        assertNotNull(info, "Info should not be null")

        // Verify info structure
        // Test options
        assertTrue(info.options.minChannelSizeSat > 0u, "Minimum channel size should be greater than 0")
        assertTrue(
            info.options.maxChannelSizeSat > info.options.minChannelSizeSat,
            "Maximum channel size should be greater than minimum"
        )
        assertTrue(info.options.minExpiryWeeks > 0u, "Minimum expiry weeks should be greater than 0")
        assertTrue(
            info.options.maxExpiryWeeks > info.options.minExpiryWeeks,
            "Maximum expiry weeks should be greater than minimum"
        )

        // Test nodes
        assertTrue(info.nodes.isNotEmpty(), "LSP nodes list should not be empty")
        for (node in info.nodes) {
            assertTrue(node.pubkey.isNotEmpty(), "Node pubkey should not be empty")
            assertTrue(
                node.connectionStrings.isNotEmpty(),
                "Node connection strings should not be empty"
            )
        }

        // Test versions
        assertTrue(info.versions.http.isNotEmpty(), "HTTP version should not be empty")
        assertTrue(info.versions.btc.isNotEmpty(), "BTC version should not be empty")
        assertTrue(info.versions.ln2.isNotEmpty(), "LN2 version should not be empty")

        // Test onchain info
        assertTrue(info.onchain.feeRates.fast > 0u, "Fast fee rate should be greater than 0")
        assertTrue(info.onchain.feeRates.mid > 0u, "Mid fee rate should be greater than 0")
        assertTrue(info.onchain.feeRates.slow > 0u, "Slow fee rate should be greater than 0")
    }
}

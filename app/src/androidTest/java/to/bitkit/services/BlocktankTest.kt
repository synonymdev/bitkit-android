package to.bitkit.services

import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import to.bitkit.env.Env
import uniffi.bitkitcore.BtOrderState
import uniffi.bitkitcore.BtOrderState2
import uniffi.bitkitcore.CreateCjitOptions
import uniffi.bitkitcore.CreateOrderOptions
import uniffi.bitkitcore.initDb
import uniffi.bitkitcore.updateBlocktankUrl
import javax.inject.Inject
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@HiltAndroidTest
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
            updateBlocktankUrl(Env.blocktankClientServer)
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

    @Test
    fun testCreateCjitOrder() = runBlocking {
        // Test creating a CJIT order
        val channelSizeSat = 100_000uL // 100k sats
        val invoiceSat = 10_000uL // 10k sats for the invoice
        val invoiceDescription = "Test CJIT order"
        val nodeId = "03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad" // Example node ID
        val channelExpiryWeeks = 6u
        val options = CreateCjitOptions(source = "bitkit", discountCode = null)

        // Create CJIT entry
        val cjitEntry = service.blocktank.createCjit(
            channelSizeSat = channelSizeSat,
            invoiceSat = invoiceSat,
            invoiceDescription = invoiceDescription,
            nodeId = nodeId,
            channelExpiryWeeks = channelExpiryWeeks,
            options = options
        )

        // Verify CJIT entry
        assertNotNull(cjitEntry, "CJIT entry should not be null")
        assertTrue(cjitEntry.id.isNotEmpty(), "CJIT entry ID should not be empty")
        assertEquals(cjitEntry.channelSizeSat, channelSizeSat, "Channel size should match requested amount")
        assertTrue(
            cjitEntry.source == "bitkit",
            "Source should match expected value"
        )
        assertNotNull(cjitEntry.lspNode, "LSP node should not be null")
        assertTrue(
            cjitEntry.lspNode.pubkey.isNotEmpty(),
            "LSP node pubkey should not be empty"
        )
        assertEquals(cjitEntry.nodeId, nodeId, "Node ID should match requested ID")
        assertEquals(
            cjitEntry.channelExpiryWeeks,
            channelExpiryWeeks,
            "Channel expiry weeks should match the requested value"
        )

        // Test getting CJIT entries
        val entries = service.blocktank.cjitOrders(
            entryIds = listOf(cjitEntry.id),
            filter = null,
            refresh = true
        )
        assertTrue(entries.isNotEmpty(), "Should retrieve created CJIT entry")
        assertEquals(entries.first().id, cjitEntry.id, "Retrieved entry should match created entry")
    }

    @Test
    fun testOrders() = runBlocking {
        // Test creating an order
        val lspBalanceSat = 100_000uL
        val channelExpiryWeeks = 2u
        val options = CreateOrderOptions(
            clientBalanceSat = 0uL,
            lspNodeId = null,
            couponCode = "",
            source = "bitkit-android",
            discountCode = null,
            turboChannel = false,
            zeroConfPayment = true,
            zeroReserve = false,
            clientNodeId = null,
            signature = null,
            timestamp = null,
            refundOnchainAddress = null,
            announceChannel = true
        )

        // Create Order
        val order = service.blocktank.newOrder(
            lspBalanceSat = lspBalanceSat,
            channelExpiryWeeks = channelExpiryWeeks,
            options = options
        )

        // Verify Order
        assertNotNull(order, "Order should not be null")
        assertTrue(order.id.isNotEmpty(), "Order ID should not be empty")
        assertEquals(order.state, BtOrderState.CREATED, "Initial state should be created")
        assertEquals(order.state2, BtOrderState2.CREATED, "Initial state2 should be created")
        assertEquals(order.lspBalanceSat, lspBalanceSat, "LSP balance should match requested amount")
        assertEquals(order.clientBalanceSat, 0uL, "Client balance should be zero")
        assertEquals(order.channelExpiryWeeks, channelExpiryWeeks, "Channel expiry weeks should match")
        assertEquals(order.source, "bitkit-android", "Source should match the expected value")

        // Test getting orders
        val orders = service.blocktank.orders(
            orderIds = listOf(order.id),
            filter = null,
            refresh = true
        )
        assertTrue(orders.isNotEmpty(), "Orders list should not be empty")
        assertEquals(orders.first().id, order.id, "Retrieved order should match created order")
    }
}

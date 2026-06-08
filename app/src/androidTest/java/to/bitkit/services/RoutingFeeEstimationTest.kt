package to.bitkit.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.NodeException
import to.bitkit.data.CacheStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.repositories.WalletRepo
import to.bitkit.test.annotations.CoreServiceIntegration
import to.bitkit.test.annotations.DeviceIntegration
import to.bitkit.utils.LdkError
import javax.inject.Inject
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@DeviceIntegration
@CoreServiceIntegration
class RoutingFeeEstimationTest {

    companion object {
        private const val NODE_STARTUP_MAX_RETRIES = 10
        private const val NODE_STARTUP_RETRY_DELAY_MS = 1000L
        private const val DEFAULT_INVOICE_EXPIRY_SECS = 3600u
    }

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var keychain: Keychain

    @Inject
    lateinit var lightningService: LightningService

    @Inject
    lateinit var walletRepo: WalletRepo

    @Inject
    lateinit var cacheStore: CacheStore

    private val walletIndex = 0
    private val appContext = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        // Use unique storage path per test to avoid DataStore conflicts
        val testStoragePath = "${appContext.filesDir.absolutePath}/${System.currentTimeMillis()}"
        Env.initAppStoragePath(testStoragePath)
        hiltRule.inject()
        println("Starting RoutingFeeEstimation test setup with storage: $testStoragePath")

        runBlocking {
            println("Wiping keychain before test")
            keychain.wipe()
            println("Keychain wiped successfully")
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            println("Tearing down RoutingFeeEstimation test")

            if (lightningService.status?.isRunning == true) {
                try {
                    lightningService.stop()
                } catch (e: Exception) {
                    println("Error stopping lightning service: ${e.message}")
                }
            }
            try {
                lightningService.wipeStorage(walletIndex = walletIndex)
            } catch (e: Exception) {
                println("Error wiping lightning storage: ${e.message}")
            }

            println("Resetting cache store to clear DataStore")
            try {
                cacheStore.reset()
                println("Cache store reset successfully")
            } catch (e: Exception) {
                println("Error resetting cache store: ${e.message}")
            }

            println("Wiping keychain after test")
            keychain.wipe()
            println("Keychain wiped successfully")
        }
    }


    @Test
    fun testNewRoutingFeeMethodsExist() = runBlocking {
        runNode()

        val paymentAmountSats = 1000uL
        val invoice = createInvoiceWithAmount(
            amountSats = paymentAmountSats,
            description = "Method existence test"
        )

        // Just test that the methods exist and can be called - don't worry about results
        val bolt11Payment = lightningService.node!!.bolt11Payment()

        println("Testing estimateRoutingFees method...")
        runCatching {
            bolt11Payment.estimateRoutingFees(invoice)
        }.fold(
            onSuccess = { fees ->
                println("estimateRoutingFees returned: $fees msat")
                assertTrue(true, "Method exists and returned a value")
            },
            onFailure = { error ->
                println("estimateRoutingFees threw: ${error.message}")
                // Method exists if it throws a specific error rather than NoSuchMethodError
                assertTrue(
                    !(error.message?.contains("NoSuchMethodError") == true),
                    "Method should exist (got: ${error.message})"
                )
            }
        )

        val zeroInvoice = createZeroAmountInvoice("Zero amount method test")
        println("Testing estimateRoutingFeesUsingAmount method...")
        runCatching {
            bolt11Payment.estimateRoutingFeesUsingAmount(zeroInvoice, 1_000_000uL)
        }.fold(
            onSuccess = { fees ->
                println("estimateRoutingFeesUsingAmount returned: $fees msat")
                assertTrue(true, "Method exists and returned a value")
            },
            onFailure = { error ->
                println("estimateRoutingFeesUsingAmount threw: ${error.message}")
                // Method exists if it throws a specific error rather than NoSuchMethodError
                assertTrue(
                    !(error.message?.contains("NoSuchMethodError") == true),
                    "Method should exist (got: ${error.message})"
                )
            }
        )
    }

    @Test
    fun estimateRoutingFeesForVariableAmountInvoice() = runBlocking {
        runNode()

        val invoice = createZeroAmountInvoice("Variable amount fee estimation test")
        val paymentAmountMsat = 5_000_000uL

        runCatching {
            lightningService.node!!.bolt11Payment()
                .estimateRoutingFeesUsingAmount(invoice, paymentAmountMsat)
        }.fold(
            onSuccess = { estimatedFeesMsat ->
                assertFeesAreReasonable(estimatedFeesMsat, paymentAmountMsat)
            },
            onFailure = { error ->
                handleExpectedRoutingError(error as NodeException)
            }
        )
    }

    @Test
    fun routeNotFoundErrorIsHandledProperly() = runBlocking {
        runNode()

        val largeAmountSats = 1_000_000uL
        val invoiceToSelf = createInvoiceWithAmount(
            amountSats = largeAmountSats,
            description = "Route error handling test"
        )

        runCatching {
            lightningService.node!!.bolt11Payment().estimateRoutingFees(invoiceToSelf)
        }.fold(
            onSuccess = { estimatedFeesMsat ->
                assertTrue(
                    estimatedFeesMsat >= 0u,
                    "If routing unexpectedly succeeds, fees should be non-negative"
                )
            },
            onFailure = { error ->
                assertRoutingErrorOccurred(error as NodeException)
            }
        )
    }

    @Test
    fun zeroAmountPaymentIsHandledGracefully() = runBlocking {
        runNode()

        val invoice = createZeroAmountInvoice("Zero amount validation test")
        val zeroAmountMsat = 0uL

        runCatching {
            lightningService.node!!.bolt11Payment()
                .estimateRoutingFeesUsingAmount(invoice, zeroAmountMsat)
        }.fold(
            onSuccess = { estimatedFeesMsat ->
                assertEquals(
                    0uL,
                    estimatedFeesMsat,
                    "Zero amount should result in zero fees"
                )
            },
            onFailure = {
                assertTrue(
                    true,
                    "Zero amount payment throwing an error is acceptable"
                )
            }
        )
    }

    @Test
    fun routingFeesScaleWithPaymentAmount() = runBlocking {
        runNode()

        val invoice = createZeroAmountInvoice("Fee scaling test")
        val smallAmountMsat = 1_000_000uL
        val largeAmountMsat = 10_000_000uL

        runCatching {
            val smallAmountFeesMsat = lightningService.node!!.bolt11Payment()
                .estimateRoutingFeesUsingAmount(invoice, smallAmountMsat)

            val largeAmountFeesMsat = lightningService.node!!.bolt11Payment()
                .estimateRoutingFeesUsingAmount(invoice, largeAmountMsat)

            Pair(smallAmountFeesMsat, largeAmountFeesMsat)
        }.fold(
            onSuccess = { (smallAmountFeesMsat, largeAmountFeesMsat) ->
                assertFeesScaleProperly(
                    smallAmountFeesMsat,
                    largeAmountFeesMsat,
                    smallAmountMsat,
                    largeAmountMsat
                )
            },
            onFailure = { error ->
                handleExpectedRoutingError(error as NodeException)
            }
        )
    }

    // region utils

    private suspend fun runNode() {
        println("Creating new wallet")
        walletRepo.createWallet(bip39Passphrase = null)

        println("Setting up lightning service")
        lightningService.setup(walletIndex = walletIndex)

        println("Starting lightning node")
        lightningService.start()
        println("Lightning node started successfully")

        waitForNodeInitialization()

        println("Syncing wallet")
        lightningService.sync()
        println("Wallet sync complete")
    }

    private suspend fun waitForNodeInitialization() {
        repeat(NODE_STARTUP_MAX_RETRIES) {
            if (lightningService.node != null) return
            delay(NODE_STARTUP_RETRY_DELAY_MS)
        }
        assertNotNull(lightningService.node, "Node should be initialized within timeout")
    }

    private suspend fun createInvoiceWithAmount(amountSats: ULong, description: String): Bolt11Invoice {
        val invoiceString = lightningService.receive(
            sat = amountSats,
            description = description,
            expirySecs = DEFAULT_INVOICE_EXPIRY_SECS
        )
        return Bolt11Invoice.fromStr(invoiceString)
    }

    private suspend fun createZeroAmountInvoice(description: String): Bolt11Invoice {
        val invoiceString = lightningService.receive(
            sat = null,
            description = description,
            expirySecs = DEFAULT_INVOICE_EXPIRY_SECS
        )
        return Bolt11Invoice.fromStr(invoiceString)
    }

    private fun assertFeesAreReasonable(estimatedFeesMsat: ULong, paymentAmountMsat: ULong) {
        assertTrue(
            estimatedFeesMsat >= 0u,
            "Estimated fees should be non-negative, got: $estimatedFeesMsat"
        )
        assertTrue(
            estimatedFeesMsat < paymentAmountMsat,
            "Estimated fees should be less than payment amount. Fees: $estimatedFeesMsat, Amount: $paymentAmountMsat msat"
        )
    }

    private fun handleExpectedRoutingError(error: NodeException) {
        when (error) {
            is NodeException.RouteNotFound -> {
                assertTrue(true, "RouteNotFound error is acceptable in test environment")
            }
            else -> throw LdkError(error)
        }
    }

    private fun assertRoutingErrorOccurred(error: NodeException) {
        assertIs<NodeException.RouteNotFound>(error)
    }

    private fun assertFeesScaleProperly(
        smallFees: ULong,
        largeFees: ULong,
        smallAmount: ULong,
        largeAmount: ULong,
    ) {
        assertTrue(
            largeFees >= smallFees,
            "Fees for larger amounts should be >= fees for smaller amounts. Small: $smallFees, Large: $largeFees"
        )
        assertFeesAreReasonable(smallFees, smallAmount)
        assertFeesAreReasonable(largeFees, largeAmount)
    }

    // endregion
}

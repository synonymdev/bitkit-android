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
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.repositories.WalletRepo
import javax.inject.Inject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TxBumpingTests {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var lightningService: LightningService

    @Inject
    lateinit var coreService: CoreService

    @Inject
    lateinit var keychain: Keychain

    @Inject
    lateinit var walletRepo: WalletRepo

    private val walletIndex = 0

    @Before
    fun setUp() {
        Env.initAppStoragePath(ApplicationProvider.getApplicationContext<Context>().filesDir.absolutePath)
        hiltRule.inject()
        println("Starting TX bumping test setup")

        // Wipe the keychain before starting tests
        println("Wiping keychain before test")
        runBlocking {
            keychain.wipe()
        }
        println("Keychain wiped successfully")
    }

    @After
    fun tearDown() {
        runBlocking {
            println("Tearing down TX bumping test")

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

            // Wipe the keychain after test completion
            println("Wiping keychain after test")
            keychain.wipe()
            println("Keychain wiped successfully")
        }
    }

    @Test
    fun testBumpFeeByRbf() = runBlocking {
        println("Starting bump fee by RBF test")

        // Create a new wallet using WalletRepo
        println("Creating new wallet")
        walletRepo.createWallet(bip39Passphrase = null)
        lightningService.setup(walletIndex = walletIndex)

        println("Starting lightning node")
        lightningService.start()
        println("Lightning node started successfully")

        // Test wallet sync
        println("Syncing wallet")
        lightningService.sync()
        println("Wallet sync complete")

        // Generate an address to receive funds
        println("Generating deposit address")
        val depositAddress = lightningService.newAddress()
        println("Deposit address: $depositAddress")

        // Fund the wallet with a single transaction
        val depositAmount = 100_000uL // 100,000 sats
        println("Depositing $depositAmount sats to wallet")
        coreService.blocktank.regtestMine(1u)
        val fundingTxId = coreService.blocktank.regtestDeposit(
            address = depositAddress,
            amountSat = depositAmount
        )
        assertFalse(fundingTxId.isEmpty(), "Funding transaction ID should not be empty")
        println("Funding transaction ID: $fundingTxId")

        // Mine blocks to confirm the funding transaction
        println("Mining 6 blocks to confirm funding transaction")
        coreService.blocktank.regtestMine(6u)
        println("Blocks mined successfully")

        // Wait for blocks to be processed
        println("Waiting 15 seconds for blocks to be processed")
        delay(15_000)
        println("Wait completed")

        // Sync the wallet to see the new balance
        println("Syncing wallet to update balances")
        lightningService.sync()
        println("Wallet sync complete")

        // Verify we have the expected balance
        val balances = lightningService.balances
        assertNotNull(balances, "Balances should not be null")
        val totalBalance = balances.totalOnchainBalanceSats
        println("Current balance: $totalBalance sats")
        assertEquals(depositAmount, totalBalance, "Balance should equal deposit amount")

        // Send a transaction with a low fee rate
        val destinationAddress = "bcrt1qs04g2ka4pr9s3mv73nu32tvfy7r3cxd27wkyu8"
        val sendAmount = 10_000uL // Send 10,000 sats
        val lowFeeRate = 1u // 1 sat/vbyte (very low)

        println("Sending $sendAmount sats to $destinationAddress with low fee rate of $lowFeeRate sat/vbyte")
        val originalTxId = lightningService.send(
            address = destinationAddress,
            sats = sendAmount,
            satsPerVByte = lowFeeRate,
        )

        lightningService.sync()

        assertFalse(originalTxId.isEmpty(), "Original transaction ID should not be empty")
        println("Original transaction sent with txid: $originalTxId")

        // Wait a moment before attempting to bump the fee
        println("Waiting 2 seconds before bumping fee")
        delay(2_000)
        println("Wait completed")

        // Bump the fee using RBF with a higher fee rate
        val highFeeRate = 10u // 10 sat/vbyte (much higher)
        println("Bumping fee for transaction $originalTxId to $highFeeRate sat/vbyte using RBF")

        val replacementTxId = lightningService.bumpFeeByRbf(
            txid = originalTxId,
            satsPerVByte = highFeeRate,
        )

        assertFalse(replacementTxId.isEmpty(), "Replacement transaction ID should not be empty")
        assertTrue(
            replacementTxId != originalTxId,
            "Replacement transaction ID should be different from original"
        )
        println("Fee bumped successfully! Replacement transaction ID: $replacementTxId")

        // Mine a block to confirm the replacement transaction
        println("Mining 1 block to confirm the replacement transaction")
        coreService.blocktank.regtestMine(1u)
        println("Block mined successfully")

        // Wait for the block to be processed
        println("Waiting 10 seconds for block to be processed")
        delay(10_000)
        println("Wait completed")

        // Sync the wallet to update balances
        println("Syncing wallet to update balances after fee bump")
        lightningService.sync()
        println("Wallet sync complete")

        // Verify the balance has been updated (should be less due to the higher fee)
        val updatedBalances = lightningService.balances
        assertNotNull(updatedBalances, "Updated balances should not be null")
        val finalBalance = updatedBalances.totalOnchainBalanceSats
        println("Final balance after fee bump: $finalBalance sats")

        // The final balance should be less than the initial balance due to the sent amount and fees
        assertTrue(finalBalance < totalBalance, "Final balance should be less than initial balance")
        println("✓ RBF fee bump test completed successfully")
    }

    @Test
    fun testAccelerateByCpfp() = runBlocking {
        println("Starting accelerate by CPFP test")

        // Create a new wallet using WalletRepo
        println("Creating new wallet")
        walletRepo.createWallet(bip39Passphrase = null)
        lightningService.setup(walletIndex = walletIndex)

        println("Starting lightning node")
        lightningService.start()
        println("Lightning node started successfully")

        // Test wallet sync
        println("Syncing wallet")
        lightningService.sync()
        println("Wallet sync complete")

        // Generate an address to receive funds
        println("Generating deposit address")
        val depositAddress = lightningService.newAddress()
        println("Deposit address: $depositAddress")

        // Simulate receiving a transaction with low fees (this represents someone sending us funds with insufficient fees)
        val incomingAmount = 100_000uL // 100,000 sats incoming
        println("Simulating incoming transaction with low fees: $incomingAmount sats")

        // Use blocktank to send us funds with very low fees (simulating a stuck incoming transaction)
        // In a real scenario, this would be someone else sending us funds with insufficient fees
        val stuckIncomingTxId = coreService.blocktank.regtestDeposit(
            address = depositAddress,
            amountSat = incomingAmount
        )
        assertFalse(
            stuckIncomingTxId.isEmpty(),
            "Stuck incoming transaction ID should not be empty"
        )
        println("Stuck incoming transaction ID: $stuckIncomingTxId")

        println("Waiting 20 seconds")
        delay(20_000)
        println("Wait completed")

        // Sync to see the incoming transaction
        println("Syncing wallet to detect incoming transaction")
        lightningService.sync()
        println("Wallet sync complete")

        // Check that we can see the balance from the incoming transaction
        val balances = lightningService.balances
        assertNotNull(balances, "Balances should not be null")
        val currentBalance = balances.totalOnchainBalanceSats
        println("Current balance: $currentBalance sats")

        // The balance should reflect the incoming amount
        assertTrue(currentBalance > 0uL, "Should have balance from incoming transaction")
        assertEquals(incomingAmount, currentBalance, "Balance should equal incoming amount")

        // Now use CPFP to spend from the incoming transaction with high fees
        // This demonstrates using CPFP to quickly move received funds
        val highFeeRate = 20u // 20 sat/vbyte (very high for fast confirmation)
        println("Using CPFP to quickly spend from incoming transaction $stuckIncomingTxId with $highFeeRate sat/vbyte")

        // Generate a destination address for the CPFP transaction (where we'll send the funds)
        println("Generating destination address for CPFP child transaction")
        val cpfpDestinationAddress = lightningService.newAddress()
        println("CPFP destination address: $cpfpDestinationAddress")

        val childTxId = lightningService.accelerateByCpfp(
            txid = stuckIncomingTxId,
            satsPerVByte = highFeeRate,
            destinationAddress = cpfpDestinationAddress,
        )

        assertFalse(childTxId.isEmpty(), "CPFP child transaction ID should not be empty")
        assertTrue(
            childTxId != stuckIncomingTxId,
            "Child transaction ID should be different from parent"
        )
        println("CPFP child transaction created successfully! Child transaction ID: $childTxId")
        println("This child transaction spends from the parent and pays high fees for fast confirmation")

        // Mine blocks to confirm the CPFP child transaction
        println("Mining 2 blocks to confirm the CPFP child transaction")
        coreService.blocktank.regtestMine(2u)
        println("Blocks mined successfully - both transactions should now be confirmed")

        // Wait for the blocks to be processed
        println("Waiting 10 seconds for blocks to be processed")
        delay(10_000)
        println("Wait completed")

        // Sync the wallet to update balances
        println("Syncing wallet to update balances after CPFP confirmation")
        lightningService.sync()
        println("Wallet sync complete")

        // Verify the final balance
        val finalBalances = lightningService.balances
        assertNotNull(finalBalances, "Final balances should not be null")
        val finalBalance = finalBalances.totalOnchainBalanceSats
        println("Final confirmed balance after CPFP: $finalBalance sats")

        // We should have received the incoming amount minus the CPFP fees
        // The exact amount depends on the fee calculation, but it should be positive and less than the incoming amount
        assertTrue(finalBalance > 0uL, "Should have positive balance after CPFP")
        assertTrue(
            finalBalance < incomingAmount,
            "Final balance should be less than incoming amount due to CPFP fees"
        )

        println("✓ CPFP test completed successfully")
        println("Successfully used CPFP to quickly spend from an incoming transaction")
    }
}

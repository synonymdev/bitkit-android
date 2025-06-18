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
import org.lightningdevkit.ldknode.CoinSelectionAlgorithm
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.repositories.WalletRepo
import javax.inject.Inject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class UtxoSelectionTests {

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
        println("Starting UTXO selection test setup")

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
            println("Tearing down UTXO selection test")

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
    fun testUtxoSelection() = runBlocking {
        println("Starting UTXO selection test")

        // Create a new wallet using walletRepo
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

        // Define different deposit amounts for 5 transactions
        val depositAmounts = listOf(15_000uL, 25_000uL, 35_000uL, 45_000uL, 50_000uL)
        var totalExpectedAmount = 0uL
        val transactionIds = mutableListOf<String>()

        // Fund the wallet with multiple transactions
        for ((index, depositAmount) in depositAmounts.withIndex()) {
            println("Depositing $depositAmount sats to wallet (transaction ${index + 1}/${depositAmounts.size})")
            val txId = coreService.blocktank.regtestDeposit(address = depositAddress, amountSat = depositAmount)
            assertTrue(txId.isNotEmpty(), "Transaction ID should not be empty")
            transactionIds.add(txId)
            totalExpectedAmount += depositAmount
            println("Deposit transaction ${index + 1} ID: $txId, Amount: $depositAmount sats")
        }

        println("Total expected amount from all deposits: $totalExpectedAmount sats")
        println("All transaction IDs: $transactionIds")

        // Mine some blocks to confirm all transactions
        println("Mining 6 blocks to confirm all transactions")
        coreService.blocktank.regtestMine(6u)
        println("Blocks mined successfully")

        // Sleep 15 seconds to ensure all blocks are processed
        println("Waiting 15 seconds for blocks to be processed")
        delay(15_000)
        println("Wait completed")

        // Sync the wallet to see the new balance
        println("Syncing wallet to update balances")
        lightningService.sync()
        println("Wallet sync complete")

        // Verify updated balances after funding
        val updatedBalances = lightningService.balances
        assertNotNull(updatedBalances, "Updated balances should not be null")
        val finalTotal = updatedBalances.totalOnchainBalanceSats

        println("Final balance: $finalTotal sats")
        println("Expected total: $totalExpectedAmount sats")

        // Verify the final balance matches the total expected amount
        assertEquals(totalExpectedAmount, finalTotal, "Final balance should equal the sum of all deposits")
        assertTrue(finalTotal > 0uL, "Final balance should be greater than 0")

        // List utxos and make sure we have the right amount
        println("Listing UTXOs to verify amounts")
        val outputs = lightningService.listSpendableOutputs().getOrThrow()
        println("Found ${outputs.size} spendable outputs")
        assertEquals(depositAmounts.size, outputs.size, "Number of UTXOs should match number of deposits")

        // Validate each spendable output matches one of the deposit amounts
        val remainingDepositAmounts = depositAmounts.toMutableList()
        for (output in outputs) {
            println("UTXO: ${output.outpoint.txid} with amount ${output.valueSats} sats")

            // Check if this output amount matches one of our expected deposit amounts
            val index = remainingDepositAmounts.indexOf(output.valueSats)
            if (index != -1) {
                println("✓ UTXO amount ${output.valueSats} sats matches expected deposit amount")
                remainingDepositAmounts.removeAt(index)
            } else {
                fail("UTXO amount ${output.valueSats} sats does not match any expected deposit amount. Expected amounts: $depositAmounts")
            }
        }

        // Ensure all deposit amounts were matched
        assertTrue(
            remainingDepositAmounts.isEmpty(),
            "Not all deposit amounts were matched. Remaining unmatched amounts: $remainingDepositAmounts"
        )
        println("✓ All spendable outputs successfully matched with deposit amounts")

        // Test a transaction spending specific utxos
        println("Testing transaction spending specific UTXOs")

        // Select the first 2 UTXOs to spend
        val utxosToSpend = outputs.take(2)
        val selectedUtxoIds = utxosToSpend.map { "${it.outpoint.txid}:${it.outpoint.vout}" }
        val totalSelectedAmount = utxosToSpend.sumOf { it.valueSats }

        println("Selected ${utxosToSpend.size} UTXOs to spend:")
        for ((index, utxo) in utxosToSpend.withIndex()) {
            println("  UTXO ${index + 1}: ${utxo.outpoint.txid}:${utxo.outpoint.vout} - ${utxo.valueSats} sats")
        }
        println("Total amount from selected UTXOs: $totalSelectedAmount sats")

        // Send transaction spending only the selected UTXOs
        val destinationAddress = "bcrt1qs04g2ka4pr9s3mv73nu32tvfy7r3cxd27wkyu8"
        val sendAmount = 10_000uL // Send 10,000 sats
        val feeRate = 1u // 1 sat/vbyte

        println("Sending $sendAmount sats to $destinationAddress using specific UTXOs")
        val txId = lightningService.send(
            address = destinationAddress,
            sats = sendAmount,
            satsPerVByte = feeRate,
            utxosToSpend = utxosToSpend
        )

        assertTrue(txId.isNotEmpty(), "Transaction ID should not be empty")
        println("Transaction sent successfully with txid: $txId")

        // Mine a block to confirm the transaction
        println("Mining 1 block to confirm the transaction")
        coreService.blocktank.regtestMine(1u)
        println("Block mined successfully")

        // Wait for the block to be processed
        println("Waiting 10 seconds for block to be processed")
        delay(10_000)
        println("Wait completed")

        // Sync the wallet to update UTXOs
        println("Syncing wallet to update UTXO set")
        lightningService.sync()
        println("Wallet sync complete")

        // List UTXOs again and verify the spent ones are missing
        println("Listing UTXOs after spending to verify the spent ones are missing")
        val remainingOutputs = lightningService.listSpendableOutputs().getOrThrow()
        println("Found ${remainingOutputs.size} remaining spendable outputs")

        // Verify the specific UTXOs we spent are no longer in the list
        val remainingUtxoIds = remainingOutputs.map { "${it.outpoint.txid}:${it.outpoint.vout}" }
        for (spentUtxoId in selectedUtxoIds) {
            assertFalse(
                remainingUtxoIds.contains(spentUtxoId),
                "Spent UTXO $spentUtxoId should not be in remaining outputs"
            )
            println("✓ Confirmed UTXO $spentUtxoId is no longer spendable")
        }

        // Verify the remaining UTXOs are the ones we didn't spend
        val originalUtxoIds = outputs.map { "${it.outpoint.txid}:${it.outpoint.vout}" }
        val expectedRemainingIds = originalUtxoIds.filter { !selectedUtxoIds.contains(it) }

        for (expectedId in expectedRemainingIds) {
            assertTrue(
                remainingUtxoIds.contains(expectedId),
                "Expected remaining UTXO $expectedId should still be spendable"
            )
        }

        println("✓ Successfully verified that the 2 selected UTXOs were spent and are no longer available")

        // Clean up by stopping the lightning node
        println("Stopping lightning node")
        lightningService.stop()
        println("Lightning node stopped successfully")
        println("UTXO selection test completed successfully with ${depositAmounts.size} transactions totaling $totalExpectedAmount sats")
    }

    @Test
    fun testUtxoSelectionAlgorithms() = runBlocking {
        println("Starting UTXO selection algorithms test")

        // Create a new wallet using walletRepo
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

        // Define different deposit amounts for testing coin selection algorithms
        val depositAmounts = listOf(5_000uL, 10_000uL, 20_000uL, 30_000uL, 50_000uL)
        var totalExpectedAmount = 0uL

        // Fund the wallet with multiple transactions
        for ((index, depositAmount) in depositAmounts.withIndex()) {
            println("Depositing $depositAmount sats to wallet (transaction ${index + 1}/${depositAmounts.size})")
            val txId = coreService.blocktank.regtestDeposit(address = depositAddress, amountSat = depositAmount)
            assertTrue(txId.isNotEmpty(), "Transaction ID should not be empty")
            totalExpectedAmount += depositAmount
            println("Deposit transaction ${index + 1} ID: $txId, Amount: $depositAmount sats")
        }

        println("Total expected amount from all deposits: $totalExpectedAmount sats")

        // Mine blocks to confirm all transactions
        println("Mining 6 blocks to confirm all transactions")
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

        // Get all available UTXOs
        println("Listing all available UTXOs")
        val allUtxos = lightningService.listSpendableOutputs().getOrThrow()
        println("Found ${allUtxos.size} spendable outputs")
        assertEquals(depositAmounts.size, allUtxos.size, "Number of UTXOs should match number of deposits")

        // Test parameters
        val targetAmountSats = 25_000uL // Target amount for selection
        val feeRate = 1u // 1 sat/vbyte

        // Test each coin selection algorithm
        val algorithms: List<CoinSelectionAlgorithm> = CoinSelectionAlgorithm.entries

        for (algorithm in algorithms) {
            println("Testing coin selection algorithm: $algorithm")

            val selectedUtxos = lightningService.selectUtxosWithAlgorithm(
                targetAmountSats = targetAmountSats,
                satsPerVByte = feeRate,
                algorithm = algorithm,
                utxos = allUtxos
            ).getOrThrow()

            assertTrue(selectedUtxos.isNotEmpty(), "Selected UTXOs should not be empty for algorithm $algorithm")

            val selectedAmount = selectedUtxos.sumOf { it.valueSats }
            println("Algorithm $algorithm selected ${selectedUtxos.size} UTXOs with total amount: $selectedAmount sats")

            // Verify that the selected amount is sufficient for the target amount
            assertTrue(
                selectedAmount >= targetAmountSats,
                "Selected amount should be at least the target amount for algorithm $algorithm"
            )

            // Log details of selected UTXOs
            for ((index, utxo) in selectedUtxos.withIndex()) {
                println("  UTXO ${index + 1}: ${utxo.outpoint.txid}:${utxo.outpoint.vout} - ${utxo.valueSats} sats")
            }

            println("✓ Algorithm $algorithm successfully selected UTXOs")
        }

        // Test algorithm-specific behavior with different target amounts
        println("Testing algorithm-specific behaviors with different target amounts")

        // Test with small amount (should prefer smaller UTXOs)
        val smallTargetAmount = 7_000uL
        val smallAmountUtxos = lightningService.selectUtxosWithAlgorithm(
            targetAmountSats = smallTargetAmount,
            satsPerVByte = feeRate,
            algorithm = CoinSelectionAlgorithm.LARGEST_FIRST,
            utxos = allUtxos
        ).getOrThrow()
        println("Largest first for $smallTargetAmount sats selected ${smallAmountUtxos.size} UTXOs")

        // Test with large amount (might need multiple UTXOs)
        val largeTargetAmount = 80_000uL
        val largeAmountUtxos = lightningService.selectUtxosWithAlgorithm(
            targetAmountSats = largeTargetAmount,
            satsPerVByte = feeRate,
            algorithm = CoinSelectionAlgorithm.LARGEST_FIRST,
            utxos = allUtxos
        ).getOrThrow()
        println("Largest first for $largeTargetAmount sats selected ${largeAmountUtxos.size} UTXOs")

        val largeSelectedAmount = largeAmountUtxos.sumOf { it.valueSats }
        assertTrue(largeSelectedAmount >= largeTargetAmount, "Should select enough UTXOs for large amount")

        // Test passing a subset of UTXOs
        println("Testing coin selection with subset of UTXOs")
        val subsetUtxos = allUtxos.take(3) // Use only first 3 UTXOs
        val subsetSelectedUtxos = lightningService.selectUtxosWithAlgorithm(
            targetAmountSats = 15_000uL,
            satsPerVByte = feeRate,
            algorithm = CoinSelectionAlgorithm.BRANCH_AND_BOUND,
            utxos = subsetUtxos
        ).getOrThrow()
        println("Branch and bound with subset selected ${subsetSelectedUtxos.size} UTXOs from ${subsetUtxos.size} available")

        // Clean up by stopping the lightning node
        println("Stopping lightning node")
        lightningService.stop()
        println("Lightning node stopped successfully")
        println("UTXO selection algorithms test completed successfully")
    }
}

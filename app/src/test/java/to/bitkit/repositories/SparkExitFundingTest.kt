package to.bitkit.repositories

import breez_sdk_spark.ExitTransactionStatus
import breez_sdk_spark.RecoveryTxKind
import breez_sdk_spark.SkippedLeaf
import breez_sdk_spark.SkippedLeafReason
import org.junit.Test
import org.lightningdevkit.ldknode.Network
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SparkExitFundingTest {
    @Test
    fun `quote splits what arrives from what it costs`() {
        val quote = SparkExitQuote(SparkExitFixtures.prepared(funding = 20_000uL))

        assertEquals(100_000uL, quote.exitSats)
        assertEquals(9_000uL, quote.networkFeeSats)
        assertEquals(91_000uL, quote.receiveSats)
        assertEquals(20_000uL, quote.fundingSats)
        assertEquals(111_000uL, quote.incomingSats)
        assertEquals(300uL, quote.leftBehindSats)
        assertEquals(listOf("leaf-1"), quote.leafIds)
        assertTrue(quote.isWorthExiting)
    }

    @Test
    fun `quote is not worth exiting when fees reach the value or nothing is left`() {
        val expensive = SparkExitQuote(SparkExitFixtures.prepared(totalFeeSats = 100_000uL))

        assertFalse(expensive.isWorthExiting)
        assertEquals(0uL, expensive.receiveSats)
        assertFalse(SparkExitQuote(SparkExitFixtures.prepared(leaves = emptyList())).isWorthExiting)
    }

    @Test
    fun `cooperative only quote needs no funding`() {
        assertEquals(0uL, SparkExitQuote(SparkExitFixtures.prepared(funding = null)).fundingSats)
    }

    @Test
    fun `shortfall counts what the funding address already holds`() {
        val record = SparkExitFixtures.record(SparkExitRecord.Phase.NeedsFunding(23_000uL)).copy(
            funding = listOf(
                SparkExitRecord.Funding(0u, SparkExitFixtures.FUNDING_ADDRESS, "02ab", "f0", 1u, 20_000uL)
            ),
        )

        assertEquals(20_000uL, record.fundedSats)
        assertEquals(3_000uL, record.fundingShortfallSats)
        assertNull(record.copy(phase = SparkExitRecord.Phase.NeedsFunding(20_000uL)).fundingShortfallSats)
        assertNull(record.copy(phase = SparkExitRecord.Phase.Running).fundingShortfallSats)
    }

    @Test
    fun `needed funding counts what the funding address still holds`() {
        // The headless regtest run: 9,035 held, the SDK wants 9,085 in all.
        assertEquals(9_085uL, SparkExitMath.neededFunding(9_085uL, fundedSats = 9_035uL, held = 9_035uL))
        // A Redo: a 20,000 input lives on as 13,000 of change, and the rebuild needs 15,000.
        assertEquals(22_000uL, SparkExitMath.neededFunding(15_000uL, fundedSats = 20_000uL, held = 13_000uL))
        assertEquals(20_001uL, SparkExitMath.neededFunding(12_000uL, fundedSats = 20_000uL, held = 13_000uL))
        assertEquals(20_000uL, SparkExitMath.neededFunding(15_000uL, fundedSats = 20_000uL, held = null))
    }

    @Test
    fun `a top up adds the input fee of each coin beyond the ones the sdk sized it on`() {
        val inputFee = SparkExitMath.inputFeeSats(2uL)
        assertEquals(136uL, inputFee)
        // The qa-03 run at 2 sat/vB: 9,035 held, the SDK wants 9,085, and the coin that pays the 50 adds its own input.
        assertEquals(9_221uL, SparkExitMath.neededFunding(9_085uL, fundedSats = 9_035uL, held = 9_035uL, inputFee))
        assertEquals(15_136uL, SparkExitMath.neededFunding(15_000uL, fundedSats = 0uL, held = null, inputFee))
        val record = SparkExitFixtures.record(SparkExitRecord.Phase.NeedsFunding(9_221uL)).copy(
            funding = listOf(
                SparkExitRecord.Funding(0u, SparkExitFixtures.FUNDING_ADDRESS, "02ab", "f0", 1u, 9_035uL)
            ),
        )
        assertEquals(SparkExitFundingState.Ask(294uL, 0uL, 186uL), SparkExitMath.fundingState(record, emptyList()))
        // Two coins of 1,000 cover a 1,999 shortfall only with the second coin's input fee on top.
        val coins = listOf(SparkExitFixtures.utxo("a", value = 1_000uL), SparkExitFixtures.utxo("b", value = 1_000uL))
        assertNull(SparkExitMath.fundingTopUp(coins, requiredSats = 1_999uL, inputFeeSats = inputFee))
        assertEquals(2, SparkExitMath.fundingTopUp(coins, requiredSats = 1_864uL, inputFeeSats = inputFee)?.size)
    }

    @Test
    fun `the countdown follows the longest path of timelocks and blocks left`() {
        val transactions = listOf(
            SparkExitFixtures.tx("n1", status = ExitTransactionStatus.Confirmed(990u)),
            SparkExitFixtures.tx("n2", dependsOn = listOf("n1")),
            SparkExitFixtures.tx(
                "r1",
                kind = RecoveryTxKind.REFUND,
                dependsOn = listOf("n2"),
                status = ExitTransactionStatus.WaitingForDependencies,
                csv = 100u,
            ),
            SparkExitFixtures.tx(
                "r2",
                kind = RecoveryTxKind.REFUND,
                dependsOn = listOf("n1"),
                status = ExitTransactionStatus.WaitingForTimelock(1_050u),
            ),
            SparkExitFixtures.tx("s", kind = RecoveryTxKind.SWEEP, dependsOn = listOf("r1", "r2")),
        )

        // n2 (1) then r1 (100 + 1) then the sweep (1); r2's path is shorter.
        assertEquals(103u, SparkExitMath.blocksToFinish(transactions, tip = 1_000u))
        assertEquals(1_103u, SparkExitMath.claimableAtHeight(SparkExitFixtures.recovery(transactions), tip = 1_000u))
        // n2 confirmed: one block less, not a jump.
        val moved = transactions.map {
            if (it.txid == "n2") it.copy(status = ExitTransactionStatus.Confirmed(1_001u)) else it
        }
        assertEquals(1_103u, SparkExitMath.claimableAtHeight(SparkExitFixtures.recovery(moved), tip = 1_001u))
        assertNull(SparkExitMath.blocksToFinish(listOf(transactions.first()), tip = 1_000u))
    }

    @Test
    fun `what reached savings is what the sweep and cooperative steps of every generation pay the destination`() {
        val address = "bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080"
        val destination = AddressScript.of(address)
        val other = AddressScript.of("bcrt1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqc8gma6")
        fun paying(vararg outputs: Pair<Long, ByteArray>): String {
            fun le(
                value: Long,
                size: Int
            ) = (0 until size).joinToString("") { "%02x".format((value shr (8 * it)) and 0xff) }
            val body = outputs.joinToString("") { (value, script) ->
                le(value, 8) + "%02x".format(script.size) + script.toHexString()
            }
            return "02000000" + "01" + "11".repeat(32) + le(0, 4) + "00" + "ffffffff" +
                "%02x".format(outputs.size) + body + le(0, 4)
        }
        val cooperative = SparkExitFixtures.tx(
            "c",
            kind = RecoveryTxKind.COOPERATIVE,
            status = ExitTransactionStatus.Confirmed(900u),
        ).copy(txHex = paying(5_000L to destination, 700L to other))
        val first = SparkExitFixtures.recovery(
            listOf(
                cooperative,
                SparkExitFixtures.tx("n", kind = RecoveryTxKind.NODE).copy(txHex = paying(1_000L to destination)),
            ),
        )
        // A Redo after the cooperative leaf landed: the new generation no longer lists it.
        val rebuilt = SparkExitFixtures.recovery(
            listOf(SparkExitFixtures.tx("s", kind = RecoveryTxKind.SWEEP).copy(txHex = paying(90_000L to destination))),
        )
        val record = SparkExitFixtures.record(SparkExitRecord.Phase.Running).copy(destination = address)
            .adopt(first, 1L)
            .adopt(rebuilt, 2L)

        assertEquals(mapOf("c" to 5_000uL), record.earlierReceivedSats)
        assertEquals(95_000uL, SparkExitMath.receivedSats(record))
        // Listed again, it counts once.
        val relisted = SparkExitFixtures.recovery(listOf(cooperative) + rebuilt.transactions)
        assertEquals(95_000uL, SparkExitMath.receivedSats(record.copy(recovery = relisted)))
    }

    @Test
    fun `a wait for a leaf that may still move tries again at doubling gaps up to a day and ends after a week`() {
        assertFalse(SparkExitMath.isRetryDue(since = 1_000u, retriedAt = 1_000u, tip = 1_005u))
        assertTrue(SparkExitMath.isRetryDue(since = 1_000u, retriedAt = 1_000u, tip = 1_006u))
        assertFalse(SparkExitMath.isRetryDue(since = 1_000u, retriedAt = 1_024u, tip = 1_047u))
        assertTrue(SparkExitMath.isRetryDue(since = 1_000u, retriedAt = 1_024u, tip = 1_048u))
        assertFalse(SparkExitMath.isRetryDue(since = 1_000u, retriedAt = 1_500u, tip = 1_643u))
        assertTrue(SparkExitMath.isRetryDue(since = 1_000u, retriedAt = 1_500u, tip = 1_644u))
        assertFalse(SparkExitMath.isRetryReleased(since = 1_000u, tip = 2_007u))
        assertTrue(SparkExitMath.isRetryReleased(since = 1_000u, tip = 2_008u))
    }

    @Test
    fun `spark's balance beyond the exit's leaves is outside the exit`() {
        assertEquals(17_097uL, SparkExitMath.outsideExitSats(leafTotalSats = 32_768uL, balanceSats = 49_865uL))
        assertNull(SparkExitMath.outsideExitSats(leafTotalSats = 90_112uL, balanceSats = 9_789uL))
        assertNull(SparkExitMath.outsideExitSats(leafTotalSats = null, balanceSats = 9_789uL))
    }

    @Test
    fun `claimable height is the latest timelock`() {
        val recovery = SparkExitFixtures.recovery(
            listOf(
                SparkExitFixtures.tx("a", status = ExitTransactionStatus.WaitingForTimelock(2_000u)),
                SparkExitFixtures.tx("b", status = ExitTransactionStatus.WaitingForTimelock(2_400u)),
                SparkExitFixtures.tx("c", status = ExitTransactionStatus.Confirmed(5u)),
            ),
        )

        assertEquals(2_400u, SparkExitMath.claimableAtHeight(recovery))
        assertEquals(1, SparkExitMath.confirmedCount(recovery))
        assertNull(SparkExitMath.claimableAtHeight(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("d")))))
    }

    @Test
    fun `timelocks are matured only once no refund waits`() {
        val waiting = SparkExitFixtures.tx(
            "r1",
            kind = RecoveryTxKind.REFUND,
            status = ExitTransactionStatus.WaitingForDependencies,
        )
        val ready = waiting.copy(status = ExitTransactionStatus.Ready)
        val timelocked = waiting.copy(status = ExitTransactionStatus.WaitingForTimelock(2_000u))

        assertFalse(SparkExitMath.areTimelocksMatured(listOf(SparkExitFixtures.tx("n1"), waiting)))
        assertFalse(SparkExitMath.areTimelocksMatured(listOf(timelocked)))
        assertFalse(SparkExitMath.areTimelocksMatured(listOf(SparkExitFixtures.tx("n1"))))
        assertTrue(SparkExitMath.areTimelocksMatured(listOf(ready)))
    }

    @Test
    fun `reminder targets the next timelock to mature`() {
        val recovery = SparkExitFixtures.recovery(
            listOf(
                SparkExitFixtures.tx("a", status = ExitTransactionStatus.WaitingForTimelock(900u)),
                SparkExitFixtures.tx("b", status = ExitTransactionStatus.WaitingForTimelock(2_400u)),
                SparkExitFixtures.tx("c", status = ExitTransactionStatus.WaitingForTimelock(2_000u)),
            ),
        )

        assertEquals(2_000u, SparkExitMath.nextMaturityHeight(recovery, tip = 1_000u))
        assertNull(SparkExitMath.nextMaturityHeight(recovery, tip = 2_400u))
    }

    @Test
    fun `funding utxos are the confirmed ones once together they cover the amount`() {
        val utxos = listOf(
            SparkExitFixtures.utxo("small", value = 999uL),
            SparkExitFixtures.utxo("pending", value = 50_000uL, confirmed = false),
            SparkExitFixtures.utxo("big", value = 2_000uL),
        )

        assertEquals(listOf("big", "small"), SparkExitMath.fundingTopUp(utxos, requiredSats = 2_999uL)?.map { it.txid })
        assertNull(SparkExitMath.fundingTopUp(utxos, requiredSats = 3_000uL))
        assertNull(SparkExitMath.fundingTopUp(emptyList(), requiredSats = 0uL))
    }

    @Test
    fun `savings must cover the funding and its fee`() {
        assertTrue(SparkExitMath.canFundFromSavings(10_500uL, 10_000uL, 500uL))
        assertFalse(SparkExitMath.canFundFromSavings(10_499uL, 10_000uL, 500uL))
        assertFalse(SparkExitMath.canFundFromSavings(10_000uL, 0uL, 0uL))
    }

    @Test
    fun `eta uses the network's block time`() {
        assertEquals(100.minutes, SparkExitMath.timeUntil(110u, 100u, Network.BITCOIN))
        assertEquals(5.minutes, SparkExitMath.timeUntil(110u, 100u, Network.REGTEST))
        assertEquals(Duration.ZERO, SparkExitMath.timeUntil(90u, 100u, Network.BITCOIN))
    }

    @Test
    fun `deadline warns 12 blocks into the operators' window of the next step`() {
        val mainnet = SparkExitMath.deadline(2.hours, Network.BITCOIN)
        assertEquals(SparkExitDeadline(warnIn = 4.hours, deadlineIn = 10.hours + 20.minutes), mainnet)
        val regtest = SparkExitMath.deadline(Duration.ZERO, Network.REGTEST)
        assertEquals(SparkExitDeadline(warnIn = 6.minutes, deadlineIn = 25.minutes), regtest)
    }

    @Test
    fun `deadline warns under half of the smaller of the 50- and 100-block windows on every network`() {
        listOf(Network.BITCOIN to 10.minutes, Network.REGTEST to 30.seconds).forEach { (network, block) ->
            val maturity = 3.hours
            val deadline = SparkExitMath.deadline(maturity, network)
            assertTrue(deadline.warnIn - maturity < block * minOf(50, 100) / 2, "$network warns too late")
            assertEquals(block * 50, deadline.deadlineIn - maturity)
        }
    }

    @Test
    fun `a step that can be published now puts the deadline in the current window`() {
        val far = SparkExitFixtures.tx("far", status = ExitTransactionStatus.WaitingForTimelock(1_100u))
        val waiting = SparkExitFixtures.recovery(listOf(far))
        assertEquals(100.minutes * 10, SparkExitMath.untilNextMaturity(waiting, tip = 1_000u, Network.BITCOIN))

        val ready = SparkExitFixtures.recovery(listOf(far, SparkExitFixtures.tx("n1")))
        assertEquals(Duration.ZERO, SparkExitMath.untilNextMaturity(ready, tip = 1_000u, Network.BITCOIN))

        val matured = SparkExitFixtures.tx("r1", status = ExitTransactionStatus.WaitingForTimelock(990u))
        val behindTip = SparkExitFixtures.recovery(listOf(far, matured))
        assertEquals(Duration.ZERO, SparkExitMath.untilNextMaturity(behindTip, tip = 1_000u, Network.BITCOIN))

        val blocked = SparkExitFixtures.tx("n2", dependsOn = listOf("n1"))
        val waitingForParent = SparkExitFixtures.recovery(listOf(far, blocked))
        assertEquals(100.minutes * 10, SparkExitMath.untilNextMaturity(waitingForParent, tip = 1_000u, Network.BITCOIN))
    }

    @Test
    fun `funding key derives on its own account`() {
        assertEquals("m/84'/1'/7797'/0/0", SparkExitFundingKey.path(0, Network.REGTEST))
        assertEquals("m/84'/0'/7797'/0/3", SparkExitFundingKey.path(3, Network.BITCOIN))
    }

    @Test
    fun `decodes a compressed wif`() {
        val key = SparkExitFundingKey.secretKey("KwDiBf89QgGbjEhKnhXJuH7LrciVrZi3qYjgd9M7rFU73sVHnoWn")

        assertContentEquals(ByteArray(31) + byteArrayOf(1), key)
    }

    @Test
    fun `rejects a wif with a bad checksum or an uncompressed key`() {
        assertFailsWith<SparkExitFundingKey.InvalidWif> {
            SparkExitFundingKey.secretKey("KwDiBf89QgGbjEhKnhXJuH7LrciVrZi3qYjgd9M7rFU73sVHnoWo")
        }
        assertFailsWith<SparkExitFundingKey.InvalidWif> {
            SparkExitFundingKey.secretKey("5HpHagT65TZzG1PH3CSu63k8DbpvD8s5ip4nEB3kEsreAnchuDf")
        }
        assertFailsWith<SparkExitFundingKey.InvalidWif> { SparkExitFundingKey.secretKey("0OIl") }
    }

    @Test
    fun `cpfp child txids ignore witness data`() {
        val segwit = "0200000000010111111111111111111111111111111111111111111111111111111111111111110000000000" +
            "ffffffff01e80300000000000016001422222222222222222222222222222222222222220202abcd0301020300000000"
        val recovery = SparkExitFixtures.recovery(
            listOf(
                SparkExitFixtures.tx("a", cpfp = SparkExitFixtures.PARENT_HEX),
                SparkExitFixtures.tx("b", cpfp = segwit),
                SparkExitFixtures.tx("c", cpfp = "02zz"),
                SparkExitFixtures.tx("d"),
            ),
        )

        assertEquals(
            listOf(SparkExitFixtures.PARENT_TXID, SparkExitFixtures.PARENT_TXID),
            SparkExitTxids.cpfpChildTxids(recovery),
        )
    }

    @Test
    fun `progress waits for a payment from savings and asks only for what is still missing`() {
        val record = SparkExitFixtures.record().copy(fundingPaymentTxid = "paid")
        val paidPending = listOf(SparkExitFixtures.utxo("paid", value = 20_000uL, confirmed = false))
        assertEquals(SparkExitFundingState.Paid("paid"), SparkExitMath.fundingState(record, paidPending))
        assertEquals(SparkExitFundingState.Paid("paid"), SparkExitMath.fundingState(record, utxos = null))
        // Confirmed but short: only the rest is asked for, with the input fee of the coin that pays it.
        val paidShort = listOf(SparkExitFixtures.utxo("paid", value = 15_000uL))
        assertEquals(
            SparkExitFundingState.Ask(5_136uL, 15_000uL, 20_136uL),
            SparkExitMath.fundingState(record, paidShort)
        )
        // Once adopted, a later shortfall no longer reads as paid.
        val topUp = record.copy(
            phase = SparkExitRecord.Phase.NeedsFunding(30_000uL),
            funding = listOf(record.funding.single().copy(txid = "paid", vout = 0u, valueSats = 20_000uL)),
        )
        assertEquals(SparkExitFundingState.Ask(10_000uL), SparkExitMath.fundingState(topUp, emptyList()))
    }

    @Test
    fun `progress waits for an external payment seen in the mempool`() {
        val record = SparkExitFixtures.record()
        val seen = listOf(SparkExitFixtures.utxo("ext", value = 20_000uL, confirmed = false))
        assertEquals(SparkExitFundingState.Seen("ext"), SparkExitMath.fundingState(record, seen))
        val partial = listOf(SparkExitFixtures.utxo("ext", value = 8_000uL, confirmed = false))
        assertEquals(
            SparkExitFundingState.Ask(12_136uL, 8_000uL, 20_136uL),
            SparkExitMath.fundingState(record, partial)
        )
        assertNull(SparkExitMath.fundingState(record.copy(phase = SparkExitRecord.Phase.Running), seen))
    }

    /**
     * The qa-03 rerun: right after an exit lost a leaf, Spark's balance still counted it for about 10 minutes, and a
     * record from before the lost-leaf list also held a leaf the balance had dropped already.
     */
    @Test
    fun `lost leaves stay out of what stays on spark whether the balance counts them or not`() {
        val failed = SparkExitFixtures.record(SparkExitRecord.Phase.Failed("Claimed by an older state"))
        val record = failed.copy(
            leafTotalSats = 65_536uL,
            lostLeaves = listOf(SparkExitRecord.LostLeaf("lost", 65_536uL)),
        )
        assertEquals(3_392uL, SparkExitMath.staysOnSparkSats(68_928uL, record), "balance still counts it")
        assertEquals(3_392uL, SparkExitMath.staysOnSparkSats(3_392uL, record), "balance synced")

        val older = failed.copy(
            leafTotalSats = 131_072uL,
            lostLeaves = listOf(
                SparkExitRecord.LostLeaf("lost-before", 65_536uL),
                SparkExitRecord.LostLeaf("lost", 65_536uL),
            ),
        )
        assertEquals(3_392uL, SparkExitMath.staysOnSparkSats(68_928uL, older))
        assertEquals(3_392uL, SparkExitMath.staysOnSparkSats(3_392uL, older))

        val small = SkippedLeaf("small", 3_392uL, SkippedLeafReason.FeeExceedsValue)
        val prepared = SparkExitFixtures.prepared(leaves = emptyList(), skipped = listOf(small))
        val quote = SparkExitQuote(prepared, lostLeafSats = listOf(65_536uL, 65_536uL))
        assertEquals(3_392uL, quote.copy(sparkBalanceSats = 68_928uL).unexitableSats)
        assertEquals(3_392uL, quote.copy(sparkBalanceSats = 3_392uL).unexitableSats)
    }

    @Test
    fun `quote names what it skips and spark's balance beyond every leaf it lists`() {
        val prepared = SparkExitFixtures.prepared()
        val beyond = SparkExitQuote(prepared, sparkBalanceSats = 150_000uL)
        assertEquals(50_000uL, beyond.unexitableSats)
        assertEquals(49_700uL, beyond.beyondLeavesSats)
        assertEquals(300uL, SparkExitQuote(prepared, sparkBalanceSats = 100_300uL).unexitableSats)
        assertEquals(300uL, SparkExitQuote(prepared).unexitableSats)
        assertNull(SparkExitQuote(SparkExitFixtures.prepared(skipped = emptyList())).unexitableSats)
    }

    @Test
    fun `each exit takes a funding key index no earlier exit used`() {
        assertEquals(0u, SparkExitMath.nextFundingIndex(kept = null, stored = null))
        val old = SparkExitFixtures.record(SparkExitRecord.Phase.Done)
        assertEquals(1u, SparkExitMath.nextFundingIndex(kept = null, stored = old))
        assertEquals(4u, SparkExitMath.nextFundingIndex(kept = 4, stored = old))
        val later = old.copy(funding = old.funding.map { it.copy(index = 6u) })
        assertEquals(7u, SparkExitMath.nextFundingIndex(kept = 4, stored = later))
    }

    @Test
    fun `a partial payment shows what arrived and what is still due`() {
        val partial = SparkExitPartialFunding(8_000uL, 20_000uL, 12_000uL)
        assertEquals(partial, SparkExitMath.partialFunding(8_000uL, 20_000uL))
        assertNull(SparkExitMath.partialFunding(0uL, 20_000uL))
        assertNull(SparkExitMath.partialFunding(20_000uL, 20_000uL))

        // On Progress the top-up counts only new funding, unconfirmed included, against the shortfall and the input
        // fee of the coin still to send.
        val record = SparkExitFixtures.record()
        val seen = listOf(SparkExitFixtures.utxo("ext", value = 8_000uL, confirmed = false))
        val ask = assertIs<SparkExitFundingState.Ask>(SparkExitMath.fundingState(record, seen))
        assertEquals(SparkExitPartialFunding(8_000uL, 20_136uL, 12_136uL), ask.partial)
        assertNull(SparkExitFundingState.Ask(20_000uL).partial)
    }

    @Test
    fun `a top up under dust is asked as the funding output's dust limit`() {
        val record = SparkExitFixtures.record()
        val almost = listOf(SparkExitFixtures.utxo("ext", value = 19_932uL))
        val ask = SparkExitFundingState.Ask(294uL, 19_932uL, 20_136uL)
        assertEquals(ask, SparkExitMath.fundingState(record, almost))
        // The hint names the shortfall as it is and asks for dust at least.
        assertEquals(SparkExitPartialFunding(19_932uL, 20_136uL, 294uL), ask.partial)
        assertEquals(
            SparkExitPartialFunding(19_932uL, 20_000uL, 294uL),
            SparkExitMath.partialFunding(19_932uL, 20_000uL)
        )
    }
}

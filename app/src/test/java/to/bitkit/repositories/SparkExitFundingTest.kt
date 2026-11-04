package to.bitkit.repositories

import breez_sdk_spark.ExitTransactionStatus
import breez_sdk_spark.RecoveryTxKind
import org.junit.Test
import org.lightningdevkit.ldknode.Network
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

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
    fun `deadline warns halfway through the operators' window of the next step`() {
        val mainnet = SparkExitMath.deadline(2.hours, Network.BITCOIN, period = 15.minutes)
        assertEquals(SparkExitDeadline(warnIn = 6.hours + 10.minutes, deadlineIn = 10.hours + 20.minutes), mainnet)
        // Never before two background runs could have happened, even where the window is shorter.
        val regtest = SparkExitMath.deadline(Duration.ZERO, Network.REGTEST, period = 15.minutes)
        assertEquals(SparkExitDeadline(warnIn = 30.minutes, deadlineIn = 25.minutes), regtest)
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
}

package to.bitkit.repositories

import breez_sdk_spark.CheckRecoverFundsResponse
import breez_sdk_spark.CooperativeRecoveryError
import breez_sdk_spark.CooperativeRecoveryFailure
import breez_sdk_spark.CpfpFundingStrategy
import breez_sdk_spark.CpfpInput
import breez_sdk_spark.ExitChainState
import breez_sdk_spark.ExitLeafSelection
import breez_sdk_spark.ExitTransactionStatus
import breez_sdk_spark.RecoverFundsLeaf
import breez_sdk_spark.RecoverFundsResponse
import breez_sdk_spark.RecoveryMethod
import breez_sdk_spark.RecoveryRedoReason
import breez_sdk_spark.RecoveryTxKind
import breez_sdk_spark.RecoveryVerdict
import breez_sdk_spark.SdkException
import breez_sdk_spark.SkippedLeaf
import breez_sdk_spark.SkippedLeafReason
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import to.bitkit.repositories.SparkExitRecord.Phase
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.settings.lightning.SparkExitQuoteBlock
import to.bitkit.ui.settings.lightning.SparkExitUiState
import to.bitkit.utils.SignedTransactionId
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SparkExitDriverTest : BaseUnitTest() {
    private companion object {
        const val SWEEP_DESTINATION = "bcrt1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqc8gma6"
        const val SWEEP_FUNDING = "bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080"
        val BUMP = "be".repeat(32)
        val UNPAID = SparkExitBroadcastOutcome.Rejected("min relay fee not met, 0 < 17")
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val chain = FakeSparkExitChain()
    private val sdk = FakeSparkExitRecovering()
    private val saved = mutableListOf<SparkExitRecord>()
    private var now = 1_800_000_000_000L

    private fun driver(
        withSdk: Boolean = true,
        tip: UInt? = null,
        save: (SparkExitRecord) -> Unit = { saved += it },
        trustedFeeRate: ULong? = 10uL,
    ) = SparkExitDriver(
        chain = chain,
        recovering = sdk.takeIf { withSdk },
        makeSigner = { StubCpfpSigner() },
        save = save,
        nowMillis = { now },
        tip = tip,
        trustedFeeRate = { trustedFeeRate },
    )

    private val interval = SparkExitDriver.REBUILD_INTERVAL.inWholeMilliseconds

    private fun running(recovery: RecoverFundsResponse, builtAt: Long = now) =
        SparkExitFixtures.record(Phase.Running).adopt(recovery, builtAt)

    private fun check(recovery: RecoverFundsResponse, verdict: RecoveryVerdict) =
        CheckRecoverFundsResponse(recovery, verdict)

    private val redo = RecoveryVerdict.Redo(RecoveryRedoReason.ON_CHAIN_STATE_DIVERGED)

    @Test
    fun `waits for a confirmed funding utxo`() = test {
        chain.utxosByAddress[SparkExitFixtures.FUNDING_ADDRESS] =
            listOf(SparkExitFixtures.utxo("f0", value = 20_000uL, confirmed = false))

        val record = driver().tick(SparkExitFixtures.record())

        assertEquals(Phase.AwaitingFunding(20_000uL), record.phase)
        assertTrue(sdk.prepareRequests.isEmpty())
    }

    @Test
    fun `builds once funded and saves before broadcasting`() = test {
        chain.utxosByAddress[SparkExitFixtures.FUNDING_ADDRESS] = listOf(SparkExitFixtures.utxo("f0", 1u, 25_000uL))
        sdk.builds += SparkExitFixtures.recovery(
            listOf(
                SparkExitFixtures.tx("n1", cpfp = "03c1"),
                SparkExitFixtures.tx(
                    "n2",
                    dependsOn = listOf("n1"),
                    status = ExitTransactionStatus.WaitingForDependencies,
                ),
            ),
        )
        var savesAtFirstBroadcast: Int? = null
        chain.onBroadcast = { savesAtFirstBroadcast = savesAtFirstBroadcast ?: saved.size }

        val record = driver().tick(SparkExitFixtures.record())

        assertEquals(Phase.Running, record.phase)
        assertEquals(1, record.generation)
        assertEquals(1, savesAtFirstBroadcast)
        assertEquals(record.recovery, saved.first().recovery)
        assertEquals(
            listOf<CpfpInput>(CpfpInput.P2wpkh("f0", 1u, 25_000uL, "02ab")),
            sdk.recoverRequests.first().fundingInputs
        )
        assertEquals(listOf("leaf-1"), (sdk.prepareRequests.first().selection as ExitLeafSelection.Specific).leafIds)
        assertEquals(listOf("n1"), chain.broadcasts.map { it.txid })
        assertEquals(listOf("n1"), record.broadcastLog.map { it.txid })
        assertEquals(record, saved.last())
    }

    @Test
    fun `valid broadcasts ready steps once until they are due again`() = test {
        var record = running(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1"))))
        val checked = SparkExitFixtures.recovery(
            listOf(
                SparkExitFixtures.tx("n1", status = ExitTransactionStatus.Confirmed(10u)),
                SparkExitFixtures.tx("n2", cpfp = "03c2", dependsOn = listOf("n1")),
                SparkExitFixtures.tx(
                    "r1",
                    kind = RecoveryTxKind.REFUND,
                    dependsOn = listOf("n2"),
                    status = ExitTransactionStatus.WaitingForTimelock(3_000u),
                ),
            ),
        )
        repeat(3) { sdk.checks += check(checked, RecoveryVerdict.Valid) }

        record = driver().tick(record)
        record = driver().tick(record)
        now += SparkExitDriver.REBROADCAST_INTERVAL.inWholeMilliseconds
        record = driver(tip = 2_000u).tick(record)

        assertEquals(listOf("n2", "n2"), chain.broadcasts.map { it.txid })
        assertEquals(2, record.broadcastLog.single().attempts)
        // The longest path left: n2's block, then r1's 1,000 blocks of timelock and its own block.
        assertEquals(3_002u, record.claimableAtHeight)
        assertEquals(checked, record.recovery)
    }

    @Test
    fun `marks steps the chain host shows in a block before the sdk check`() = test {
        val built = SparkExitFixtures.recovery(
            listOf(
                SparkExitFixtures.tx("fan", kind = RecoveryTxKind.FAN_OUT),
                SparkExitFixtures.tx(
                    "n1",
                    cpfp = "03c",
                    dependsOn = listOf("fan"),
                    status = ExitTransactionStatus.WaitingForDependencies,
                ),
                SparkExitFixtures.tx(
                    "n2",
                    cpfp = "03d",
                    dependsOn = listOf("n1"),
                    status = ExitTransactionStatus.WaitingForDependencies,
                ),
            ),
        )
        chain.confirmedTxids = setOf("fan", "n1")
        sdk.checks += check(built, RecoveryVerdict.Valid)

        driver().tick(running(built))

        val sent = sdk.checkedRecoveries.single().transactions.map { it.status }
        assertEquals(
            listOf<ExitTransactionStatus>(
                ExitTransactionStatus.Confirmed(900u),
                ExitTransactionStatus.Confirmed(900u),
                ExitTransactionStatus.WaitingForDependencies,
            ),
            sent,
        )
    }

    @Test
    fun `a refused step rebuilds until three rebuilds did not move the exit, then says it is not moving`() = test {
        val recovery = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1", cpfp = "03c")))
        var record = running(recovery, builtAt = now - interval)
        chain.outcome = SparkExitBroadcastOutcome.Rejected("insufficient fee, rejecting replacement")
        repeat(20) { sdk.checks += check(recovery, RecoveryVerdict.Valid) }
        repeat(SparkExitDriver.STALL_REBUILDS_TO_SURFACE) { sdk.builds += recovery }

        var ticks = 0
        while (record.stallRebuilds < SparkExitDriver.STALL_REBUILDS_TO_SURFACE && ticks++ < 12) {
            assertNull(record.surfacedRejection)
            record = driver().tick(record)
            now += interval
        }
        assertEquals(4, record.generation)
        // Stalled: the step is still sent, but nothing rebuilds over it again.
        repeat(3) {
            record = driver().tick(record)
            now += interval
        }

        assertEquals(4, record.generation)
        assertEquals(SparkExitDriver.STALL_REBUILDS_TO_SURFACE, sdk.recoverRequests.size)
        assertEquals("rejected: insufficient fee, rejecting replacement", record.surfacedRejection)
    }

    @Test
    fun `a step confirming after a rebuild over a broken chain starts the count over`() = test {
        val record = running(unpaidRecovery).copy(stallRebuilds = 3, stallConfirmedCount = 1)
        assertTrue(record.isStalled)
        val moved = SparkExitFixtures.recovery(
            listOf(
                SparkExitFixtures.tx("n1", status = ExitTransactionStatus.Confirmed(11_600u)),
                SparkExitFixtures.tx(
                    "n2",
                    cpfp = "03c2",
                    dependsOn = listOf("n1"),
                    status = ExitTransactionStatus.Confirmed(11_601u)
                ),
            ),
        )
        sdk.checks += check(moved, RecoveryVerdict.Valid)

        val ticked = driver().tick(record)

        assertEquals(0, ticked.stallRebuilds)
        assertNull(ticked.stallConfirmedCount)
        assertNull(ticked.surfacedRejection)
    }

    @Test
    fun `a step accepted but not confirming for more than six blocks reads as underpriced and does not rebuild`() =
        test {
            val recovery = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1", cpfp = "03c")))
            var record = running(recovery)
            repeat(2) { sdk.checks += check(recovery, RecoveryVerdict.Valid) }

            record = driver(tip = 1_000u).tick(record)
            assertEquals(1_000u, record.broadcastLog.single().acceptedAtHeight)
            assertNull(record.underpricedStep(1_006u))
            now += SparkExitDriver.REBROADCAST_INTERVAL.inWholeMilliseconds
            record = driver(tip = 1_007u).tick(record)

            assertEquals(2, chain.broadcasts.size)
            assertEquals(1_000u, record.broadcastLog.single().acceptedAtHeight)
            assertEquals("n1", record.underpricedStep(1_007u)?.txid)
            assertEquals(1, record.generation)
            assertTrue(sdk.prepareRequests.isEmpty())
            assertNull(record.surfacedRejection)
        }

    @Test
    fun `a step refused for a fee under the network's floor reads as underpriced at once and never rebuilds`() = test {
        val recovery = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1", cpfp = "03c")))
        var record = running(recovery, builtAt = now - interval)
        chain.outcome = SparkExitBroadcastOutcome.Rejected("min relay fee not met, 5 < 17")
        repeat(4) { sdk.checks += check(recovery, RecoveryVerdict.Valid) }

        record = driver(tip = 1_000u).tick(record)
        assertEquals("n1", record.underpricedStep(null)?.txid)
        repeat(3) {
            now += interval
            record = driver(tip = 1_001u).tick(record)
        }

        assertEquals(0, record.broadcastLog.single().rejections)
        assertNull(record.stuckStep)
        assertEquals(1, record.generation)
        assertTrue(sdk.prepareRequests.isEmpty())
        assertNull(record.surfacedRejection)
        assertEquals("n1", record.underpricedStep(1_001u)?.txid)
        assertEquals(
            2uL,
            SparkExitUiState(record = record, tipHeight = 1_001u).lowFeeRate,
        )
    }

    @Test
    fun `a mempool floor refusal reads as underpriced and a lost replacement race does not`() = test {
        val recovery = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1", cpfp = "03c")))
        sdk.checks += check(recovery, RecoveryVerdict.Valid)
        sdk.checks += check(recovery, RecoveryVerdict.Valid)
        chain.outcome = SparkExitBroadcastOutcome.Rejected("mempool min fee not met, 300 < 400")
        assertNotNull(driver(tip = 1_000u).tick(running(recovery)).underpricedStep(1_000u))

        chain.outcome = SparkExitBroadcastOutcome.Rejected("insufficient fee, rejecting replacement")
        val raced = driver(tip = 1_000u).tick(running(recovery))
        assertNull(raced.underpricedStep(1_000u))
        assertEquals(1, raced.broadcastLog.single().rejections)
    }

    @Test
    fun `a unilateral step the sdk could not read is not sent and rebuilds once a block`() = test {
        val unverified = SparkExitFixtures.recovery(
            listOf(
                SparkExitFixtures.tx("n1", status = ExitTransactionStatus.Confirmed(900u)),
                SparkExitFixtures.tx(
                    "n2",
                    cpfp = "03c2",
                    dependsOn = listOf("n1"),
                    status = ExitTransactionStatus.Unverified,
                ),
                SparkExitFixtures.tx(
                    "co",
                    kind = RecoveryTxKind.COOPERATIVE,
                    status = ExitTransactionStatus.Unverified
                ),
            ),
        )
        var record = running(unverified)
        repeat(2) { sdk.checks += check(unverified, RecoveryVerdict.Valid) }
        sdk.builds += unverified

        record = driver(tip = 1_000u).tick(record)

        assertTrue(chain.broadcasts.none { it.txid == "n2" })
        assertTrue(chain.broadcasts.any { it.txid == "co" })
        assertEquals(2, record.generation)
        assertEquals(1_000u, record.unverifiedRebuiltAtHeight)
        assertEquals(0, record.stallRebuilds)
        assertEquals(record.fundingInputs, sdk.recoverRequests.single().fundingInputs)
        assertEquals(2uL, sdk.prepareRequests.single().feeRateSatPerVbyte)

        record = driver(tip = 1_000u).tick(record)

        assertEquals(2, record.generation)
        assertEquals(1, sdk.recoverRequests.size)
    }

    @Test
    fun `an unverified rebuild that adopts nothing keeps checking and sending the valid generation`() = test {
        val unverified = SparkExitFixtures.recovery(
            listOf(
                SparkExitFixtures.tx("n1", status = ExitTransactionStatus.Confirmed(900u)),
                SparkExitFixtures.tx("n2", dependsOn = listOf("n1"), status = ExitTransactionStatus.Unverified),
            ),
        )
        val readable = SparkExitFixtures.recovery(
            listOf(
                SparkExitFixtures.tx("n1", status = ExitTransactionStatus.Confirmed(900u)),
                SparkExitFixtures.tx("n2", dependsOn = listOf("n1")),
            ),
        )
        sdk.checks += check(unverified, RecoveryVerdict.Valid)
        sdk.checks += check(readable, RecoveryVerdict.Valid)
        sdk.preparedResponse = SparkExitFixtures.prepared(
            leaves = emptyList(),
            skipped = listOf(SkippedLeaf("leaf-1", 100_000uL, SkippedLeafReason.Unverified)),
        )

        var record = driver(tip = 1_000u).tick(running(unverified))
        // A run killed during the rebuild resumes the valid generation, not a rebuild only the SDK moves on from.
        assertFalse(saved.first().isRebuilding)
        assertFalse(record.isRebuilding)
        assertFalse(record.isWaitingForLeaves)
        assertEquals(1, record.generation)

        record = driver(tip = 1_001u).tick(record)

        assertEquals(2, sdk.checkedRecoveries.size)
        assertEquals(listOf("n2"), chain.broadcasts.map { it.txid })
        assertEquals(1, record.generation)
    }

    @Test
    fun `an unverified rebuild that needs more fee funding keeps sending the valid generation`() = test {
        val unverified = SparkExitFixtures.recovery(
            listOf(
                SparkExitFixtures.tx("n1", status = ExitTransactionStatus.Confirmed(900u)),
                SparkExitFixtures.tx("n2", dependsOn = listOf("n1"), status = ExitTransactionStatus.Unverified),
            ),
        )
        val readable = SparkExitFixtures.recovery(
            listOf(
                SparkExitFixtures.tx("n1", status = ExitTransactionStatus.Confirmed(900u)),
                SparkExitFixtures.tx("n2", dependsOn = listOf("n1")),
            ),
        )
        sdk.checks += check(unverified, RecoveryVerdict.Valid)
        sdk.checks += check(readable, RecoveryVerdict.Valid)
        sdk.recoverError = SdkException.InsufficientCpfpFunds(30_000uL, "")

        var record = driver(tip = 1_000u).tick(running(unverified))

        assertIs<Phase.NeedsFunding>(record.phase)
        assertFalse(record.isRebuilding)
        assertTrue(record.isDrivingWhileFunding, "the valid generation stops going while the top-up waits")
        assertEquals(1, record.generation)

        record = driver(tip = 1_001u).tick(record)

        assertEquals(listOf("n2"), chain.broadcasts.map { it.txid })
        assertEquals(1, record.generation)
        assertEquals(Phase.Running, record.phase, "the fee ask stayed once every step read again")
        assertFalse(record.isUnverifiedAsk)
    }

    @Test
    fun `an unverified rebuild's fee ask stays while a step still reads unverified`() = test {
        val unverified = SparkExitFixtures.recovery(
            listOf(
                SparkExitFixtures.tx("n1", status = ExitTransactionStatus.Confirmed(900u)),
                SparkExitFixtures.tx("n2", dependsOn = listOf("n1"), status = ExitTransactionStatus.Unverified),
            ),
        )
        repeat(2) { sdk.checks += check(unverified, RecoveryVerdict.Valid) }
        sdk.recoverError = SdkException.InsufficientCpfpFunds(30_000uL, "")

        var record = driver(tip = 1_000u).tick(running(unverified))
        record = driver(tip = 1_000u).tick(record)

        assertIs<Phase.NeedsFunding>(record.phase)
        assertTrue(record.isUnverifiedAsk)
    }

    @Test
    fun `an unverified rebuild of only failed cooperative leaves keeps the valid generation`() = test {
        val failed = CooperativeRecoveryFailure(
            "coop-1",
            "o",
            0u,
            CooperativeRecoveryError.OperatorsUnavailable("down"),
        )
        val unverified = SparkExitFixtures.recovery(
            listOf(
                SparkExitFixtures.tx("n1", status = ExitTransactionStatus.Confirmed(900u)),
                SparkExitFixtures.tx("n2", dependsOn = listOf("n1"), status = ExitTransactionStatus.Unverified),
            ),
            failed = listOf(failed),
        )
        val readable = SparkExitFixtures.recovery(
            listOf(
                SparkExitFixtures.tx("n1", status = ExitTransactionStatus.Confirmed(900u)),
                SparkExitFixtures.tx("n2", dependsOn = listOf("n1")),
            ),
            failed = listOf(failed),
        )
        sdk.checks += check(unverified, RecoveryVerdict.Valid)
        sdk.checks += check(readable, RecoveryVerdict.Valid)
        sdk.preparedResponse = coopLeaf(16_384uL).copy(
            skipped = listOf(SkippedLeaf("leaf-1", 100_000uL, SkippedLeafReason.Unverified)),
        )
        repeat(2) { sdk.builds += SparkExitFixtures.recovery(emptyList(), failed = listOf(failed)) }
        val start = running(unverified).copy(leafIds = listOf("leaf-1", "coop-1"))

        var record = driver(tip = 1_000u).tick(start)
        assertEquals(1, record.generation, "rebuild adopted a recovery without leaf-1's signed steps")
        assertTrue(saved.none { it.generation != 1 }, "a killed run would resume the recovery without leaf-1")

        record = driver(tip = 1_001u).tick(record)
        assertEquals(listOf("n2"), chain.broadcasts.map { it.txid }, "leaf-1's matured step n2 was not sent")
    }

    @Test
    fun `funding an unverified rebuild's ask keeps the valid generation over only failed cooperative leaves`() = test {
        val failed = CooperativeRecoveryFailure(
            "coop-1",
            "o",
            0u,
            CooperativeRecoveryError.OperatorsUnavailable("down"),
        )
        val unverified = SparkExitFixtures.recovery(
            listOf(
                SparkExitFixtures.tx("n1", status = ExitTransactionStatus.Confirmed(900u)),
                SparkExitFixtures.tx("n2", dependsOn = listOf("n1"), status = ExitTransactionStatus.Unverified),
            ),
            failed = listOf(failed),
        )
        repeat(3) { sdk.checks += check(unverified, RecoveryVerdict.Valid) }
        sdk.recoverErrors += SdkException.InsufficientCpfpFunds(30_000uL, "")
        sdk.preparedResponses += SparkExitFixtures.prepared()
        sdk.preparedResponse = coopLeaf(16_384uL).copy(
            skipped = listOf(SkippedLeaf("leaf-1", 100_000uL, SkippedLeafReason.Unverified)),
        )
        sdk.builds += SparkExitFixtures.recovery(emptyList(), failed = listOf(failed))
        val start = running(unverified).copy(leafIds = listOf("leaf-1", "coop-1"))

        var record = driver(tip = 1_000u).tick(start)
        assertIs<Phase.NeedsFunding>(record.phase)

        chain.utxosByAddress[SparkExitFixtures.FUNDING_ADDRESS] =
            listOf(SparkExitFixtures.utxo("f0", value = 100_000uL))
        record = driver(tip = 1_001u).tick(record)

        assertEquals(1, record.generation, "the funded rebuild adopted a recovery without leaf-1's signed steps")
        assertTrue(saved.none { it.generation != 1 }, "a killed run would resume the recovery without leaf-1")
        assertEquals(Phase.Running, record.phase)
        assertEquals(unverified.transactions.map { it.txid }, record.recovery?.transactions?.map { it.txid })
    }

    @Test
    fun `an unverified rebuild's ask that diverged never sends the diverged generation again`() = test {
        val unverified = SparkExitFixtures.recovery(
            listOf(
                SparkExitFixtures.tx("n1", status = ExitTransactionStatus.Confirmed(900u)),
                SparkExitFixtures.tx("n2", dependsOn = listOf("n1"), status = ExitTransactionStatus.Unverified),
            ),
        )
        sdk.checks += check(unverified, RecoveryVerdict.Valid)
        sdk.checks += check(unverified, redo)
        // The diverged generation's rebuild keeps needing more than the funding holds.
        repeat(3) { sdk.recoverErrors += SdkException.InsufficientCpfpFunds(30_000uL, "") }

        var record = driver(tip = 1_000u).tick(running(unverified))
        assertTrue(record.isUnverifiedAsk)
        record = driver(tip = 1_001u).tick(record)
        assertTrue(record.isRebuilding)
        assertFalse(record.isUnverifiedAsk, "the ask now waits for the diverged generation's rebuild")

        chain.utxosByAddress[SparkExitFixtures.FUNDING_ADDRESS] =
            listOf(SparkExitFixtures.utxo("f0", value = 100_000uL))
        now += interval
        record = driver(tip = 1_002u).tick(record)

        assertIs<Phase.NeedsFunding>(record.phase)
        assertTrue(record.isRebuilding, "the diverged generation would be driven again")
        record = driver(tip = 1_003u).tick(record)
        assertTrue(chain.broadcasts.none { it.txid == "n1" || it.txid == "n2" }, "a diverged step was sent again")
    }

    /**
     * An unverified rebuild that skips the leaf it could not read but builds another unilateral leaf keeps the valid
     * generation: the unread leaf's signed steps are still sent once the chain reads again.
     */
    @Test
    fun `an unverified rebuild beside another unilateral leaf keeps the valid generation`() = test {
        val unread = SparkExitFixtures.recovery(
            listOf(
                SparkExitFixtures.tx("n1", status = ExitTransactionStatus.Confirmed(900u)),
                SparkExitFixtures.tx("n2", dependsOn = listOf("n1"), status = ExitTransactionStatus.Unverified),
                SparkExitFixtures.tx("m1", status = ExitTransactionStatus.Confirmed(900u)),
                SparkExitFixtures.tx(
                    "m2",
                    dependsOn = listOf("m1"),
                    status = ExitTransactionStatus.WaitingForTimelock(2_000u),
                ),
            ),
        )
        sdk.checks += check(unread, RecoveryVerdict.Valid)
        sdk.preparedResponse = SparkExitFixtures.prepared(funding = null, leaves = listOf("leaf-2")).copy(
            skipped = listOf(SkippedLeaf("leaf-1", 100_000uL, SkippedLeafReason.Unverified)),
        )
        repeat(2) {
            sdk.builds += SparkExitFixtures.recovery(
                listOf(
                    SparkExitFixtures.tx("m1", status = ExitTransactionStatus.Confirmed(900u)),
                    SparkExitFixtures.tx(
                        "m2b",
                        dependsOn = listOf("m1"),
                        status = ExitTransactionStatus.WaitingForTimelock(2_000u),
                    ),
                ),
            )
        }
        val start = running(unread).copy(leafIds = listOf("leaf-1", "leaf-2"))

        var record = driver(tip = 1_000u).tick(start)
        assertEquals(1, record.generation, "rebuild adopted a recovery without leaf-1's signed steps")

        // The chain reads again: whatever the record holds, its unverified steps now read as ready.
        val held = assertNotNull(record.recovery)
        val reread = held.copy(
            transactions = held.transactions.map {
                if (it.status == ExitTransactionStatus.Unverified) it.copy(status = ExitTransactionStatus.Ready) else it
            },
        )
        sdk.checks += check(reread, RecoveryVerdict.Valid)
        record = driver(tip = 1_001u).tick(record)
        assertEquals(listOf("n2"), chain.broadcasts.map { it.txid }, "leaf-1's matured step n2 was not sent")
    }

    @Test
    fun `an unverified unilateral step waits while another unilateral step is ready`() = test {
        val recovery = SparkExitFixtures.recovery(
            listOf(
                SparkExitFixtures.tx("n1", status = ExitTransactionStatus.Unverified),
                SparkExitFixtures.tx("n2"),
            ),
        )
        sdk.checks += check(recovery, RecoveryVerdict.Valid)

        val record = driver(tip = 1_000u).tick(running(recovery))

        assertEquals(listOf("n2"), chain.broadcasts.map { it.txid })
        assertEquals(1, record.generation)
        assertTrue(sdk.prepareRequests.isEmpty())
    }

    @Test
    fun `without the sdk an unverified step whose parents confirmed is still sent`() = test {
        val recovery = SparkExitFixtures.recovery(
            listOf(
                SparkExitFixtures.tx("n1", status = ExitTransactionStatus.Confirmed(900u)),
                SparkExitFixtures.tx("n2", dependsOn = listOf("n1"), status = ExitTransactionStatus.Unverified),
            ),
        )
        chain.confirmedTxids = setOf("n1")

        driver(withSdk = false).tick(running(recovery))

        // Only the SDK can rebuild over it; without it the step would never go out.
        assertEquals(listOf("n2"), chain.broadcasts.map { it.txid })
    }

    @Test
    fun `done finishes and reads leftover funding`() = test {
        var record = running(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("s", kind = RecoveryTxKind.SWEEP))))
        val done = SparkExitFixtures.recovery(
            listOf(
                SparkExitFixtures.tx("s", kind = RecoveryTxKind.SWEEP, status = ExitTransactionStatus.Confirmed(50u))
            ),
        )
        sdk.checks += check(done, RecoveryVerdict.Done)
        chain.utxosByAddress[SparkExitFixtures.FUNDING_ADDRESS] =
            listOf(SparkExitFixtures.utxo("change", value = 700uL))

        record = driver().tick(record)

        assertEquals(Phase.Done, record.phase)
        assertFalse(record.isActive)
        assertEquals(listOf(SparkExitRecord.Leftover("change", 0u, 700uL)), record.leftover)
        assertTrue(chain.broadcasts.isEmpty())
        assertEquals(record, saved.last())
    }

    @Test
    fun `redo rebuilds as a new generation and never sends the old one`() = test {
        val old = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("old")))
        var record = running(old, builtAt = now - SparkExitDriver.REBUILD_INTERVAL.inWholeMilliseconds).copy(
            funding = listOf(
                SparkExitRecord.Funding(0u, SparkExitFixtures.FUNDING_ADDRESS, "02ab", "f0", 1u, 20_000uL)
            ),
            broadcastLog = listOf(SparkExitRecord.BroadcastAttempt(1, "old", 1, 0, now, "accepted", true)),
        )
        sdk.checks += check(old, redo)
        sdk.builds += SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("new", cpfp = "03n")))

        record = driver().tick(record)

        assertEquals(2, record.generation)
        assertFalse(record.isRebuilding)
        assertEquals(listOf("new"), record.recovery?.transactions?.map { it.txid })
        assertEquals(listOf("new"), record.broadcastLog.map { it.txid })
        assertEquals(listOf("new"), chain.broadcasts.map { it.txid })
        assertEquals(
            listOf<CpfpInput>(CpfpInput.P2wpkh("f0", 1u, 20_000uL, "02ab")),
            sdk.recoverRequests.first().fundingInputs
        )
        assertTrue(saved.first().isRebuilding)
    }

    @Test
    fun `redo is saved before rebuilding so the old generation is never sent without the sdk`() = test {
        val old = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("old")))
        var record = running(old)
        sdk.checks += check(old, redo)

        // Built a minute ago: the rebuild waits, but the diverged state is already saved.
        now += 60.seconds.inWholeMilliseconds
        record = driver().tick(record)
        assertTrue(record.isRebuilding)
        assertTrue(saved.first().isRebuilding)
        assertTrue(sdk.recoverRequests.isEmpty())

        record = driver(withSdk = false).tick(record)
        assertTrue(chain.broadcasts.isEmpty())

        now += SparkExitDriver.REBUILD_INTERVAL.inWholeMilliseconds
        sdk.builds += SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("new")))
        record = driver().tick(record)
        assertFalse(record.isRebuilding)
        assertEquals(2, record.generation)
        assertEquals(listOf("new"), chain.broadcasts.map { it.txid })
    }

    @Test
    fun `rebuild that needs more fees asks for funding`() = test {
        val old = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("old")))
        var record = running(old, builtAt = 0L)
        sdk.checks += check(SparkExitFixtures.recovery(emptyList()), redo)
        sdk.recoverError = SdkException.InsufficientCpfpFunds(3_000uL, "")

        record = driver().tick(record)

        // The coin that pays it adds its own input fee, 68 vB at 2 sat/vB.
        assertEquals(Phase.NeedsFunding(3_136uL), record.phase)
        assertTrue(record.isActive)
        assertEquals(3_136uL, record.fundingShortfallSats)
    }

    @Test
    fun `asks only for what the sdk's total lacks and adopts small top ups together`() = test {
        // A headless regtest run: the quote asked 8,541, five UTXOs held 9,035, and the SDK wanted 9,085 in total.
        val funded = listOf(1_807uL, 1_807uL, 1_807uL, 1_807uL, 1_807uL).mapIndexed { i, value ->
            SparkExitRecord.Funding(0u, SparkExitFixtures.FUNDING_ADDRESS, "02ab", "f$i", 0u, value)
        }
        var record = running(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("old"))), builtAt = 0L)
            .copy(funding = funded)
        chain.utxosByAddress[SparkExitFixtures.FUNDING_ADDRESS] = funded.map {
            SparkExitFixtures.utxo(requireNotNull(it.txid), value = requireNotNull(it.valueSats))
        }
        sdk.checks += check(SparkExitFixtures.recovery(emptyList()), redo)
        sdk.recoverError = SdkException.InsufficientCpfpFunds(9_085uL, "")

        record = driver().tick(record)

        // 50 short, plus the input fee of the coin that pays it.
        assertEquals(Phase.NeedsFunding(9_221uL), record.phase)
        assertEquals(186uL, record.fundingShortfallSats)

        // Two coins: the second adds its own input fee too.
        chain.utxosByAddress[SparkExitFixtures.FUNDING_ADDRESS] = funded.map {
            SparkExitFixtures.utxo(requireNotNull(it.txid), value = requireNotNull(it.valueSats))
        } + listOf(SparkExitFixtures.utxo("t1", value = 150uL), SparkExitFixtures.utxo("t2", value = 121uL))
        record = driver().tick(record)
        assertEquals(Phase.NeedsFunding(9_221uL), record.phase)
        chain.utxosByAddress[SparkExitFixtures.FUNDING_ADDRESS] = funded.map {
            SparkExitFixtures.utxo(requireNotNull(it.txid), value = requireNotNull(it.valueSats))
        } + listOf(SparkExitFixtures.utxo("t1", value = 150uL), SparkExitFixtures.utxo("t2", value = 172uL))
        sdk.recoverError = null
        sdk.builds += SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n2")))
        record = driver().tick(record)

        assertEquals(Phase.Running, record.phase)
        assertEquals(7, sdk.recoverRequests.last().fundingInputs.size)
    }

    @Test
    fun `a redo whose spent input lives on as change asks only for what that change lacks`() = test {
        val built = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1", cpfp = SparkExitFixtures.PARENT_HEX)))
        var record = running(built, builtAt = 0L).copy(
            funding = listOf(
                SparkExitRecord.Funding(0u, SparkExitFixtures.FUNDING_ADDRESS, "02ab", "f0", 1u, 20_000uL),
            ),
        )
        // The input went into a CPFP child, whose 13,000 of change sits at the funding address.
        chain.utxosByAddress[SparkExitFixtures.FUNDING_ADDRESS] =
            listOf(SparkExitFixtures.utxo(SparkExitFixtures.PARENT_TXID, value = 13_000uL))
        sdk.checks += check(built, redo)
        sdk.recoverError = SdkException.InsufficientCpfpFunds(15_000uL, "")

        record = driver().tick(record)

        assertEquals(2_136uL, record.fundingShortfallSats)
    }

    @Test
    fun `the tick that ends the exit sweeps its leftover to savings until a node accepts it`() = test {
        val funding = "f0".repeat(32)
        chain.utxosByAddress[SWEEP_FUNDING] = listOf(SparkExitFixtures.utxo(funding, 1u, 25_000uL))
        sdk.preparedResponse = SparkExitFixtures.prepared(leaves = emptyList(), skipped = emptyList())
        chain.outcome = SparkExitBroadcastOutcome.RetryLater("unreachable")
        val start = SparkExitFixtures.record().copy(
            destination = SWEEP_DESTINATION,
            funding = listOf(SparkExitRecord.Funding(0u, SWEEP_FUNDING, "02ab")),
        )

        var record = driver().tick(start)

        assertIs<Phase.Failed>(record.phase)
        assertEquals(listOf(SparkExitRecord.Leftover(funding, 1u, 25_000uL)), record.leftover)
        val sweep = assertNotNull(record.leftoverSweep)
        assertEquals(1, chain.rawBroadcasts.size)
        assertTrue(record.isLeftoverSweepPending)
        assertEquals(sweep, saved.first { it.leftoverSweep != null }.leftoverSweep)

        chain.outcome = SparkExitBroadcastOutcome.Accepted
        record = driver().tick(record)

        assertEquals(listOf(sweep.txHex, sweep.txHex), chain.rawBroadcasts)
        assertTrue(assertNotNull(record.leftoverSweep).isAccepted)
        // Sent again until it confirms, and the result stays reachable until then.
        assertTrue(record.needsDriving)
        assertTrue(record.showsResult)
        record = driver().tick(record)
        assertEquals(3, chain.rawBroadcasts.size)

        chain.confirmedTxids = setOf(requireNotNull(sweep.txid))
        record = driver().tick(record)

        assertTrue(assertNotNull(record.leftoverSweep).isConfirmed)
        assertFalse(record.needsDriving)
        // A failed exit stays reachable until its result was seen.
        assertTrue(record.showsResult)
        assertFalse(record.copy(isResultSeen = true).showsResult)
        record = driver().tick(record)
        assertEquals(3, chain.rawBroadcasts.size)
    }

    @Test
    fun `a leftover worth less than its fees waits for cheaper fees, then is kept as dust after four weeks`() = test {
        chain.utxosByAddress[SWEEP_FUNDING] = listOf(SparkExitFixtures.utxo("f0", 1u, 1_000uL))
        chain.economyRate = 9uL
        val done = SparkExitFixtures.record(Phase.Done).copy(
            destination = SWEEP_DESTINATION,
            funding = listOf(SparkExitRecord.Funding(0u, SWEEP_FUNDING, "02ab")),
            leftover = listOf(SparkExitRecord.Leftover("f0", 1u, 1_000uL)),
            feeRateSatPerVbyte = 9uL,
            isResultSeen = true,
        )

        // At 9 sat/vB the sweep's fee leaves less than dust: it waits, and the exit is still driven.
        var record = driver(tip = 1_000u).tick(done)
        assertTrue(record.isLeftoverWaitingForFees)
        assertTrue(record.needsDriving)
        assertEquals(1_000u, assertNotNull(record.leftoverSweep).waitingSince)

        // The rate is read again about once a day.
        chain.utxosByAddress[SWEEP_FUNDING] = emptyList()
        record = driver(tip = 1_143u).tick(record)
        assertTrue(record.isLeftoverWaitingForFees)
        chain.utxosByAddress[SWEEP_FUNDING] = listOf(SparkExitFixtures.utxo("f0", 1u, 1_000uL))
        record = driver(tip = 1_144u).tick(record)
        assertEquals(1_144u, assertNotNull(record.leftoverSweep).checkedAtHeight)
        assertEquals(1_000u, assertNotNull(record.leftoverSweep).waitingSince)

        record = driver(tip = 5_032u).tick(record)
        assertTrue(assertNotNull(record.leftoverSweep).isDustKept)
        assertFalse(record.isLeftoverWaitingForFees)
        assertEquals(record, SparkExitJson.decode(SparkExitJson.encode(record)))
        assertTrue(chain.rawBroadcasts.isEmpty())
        assertFalse(record.needsDriving)
        // Shown once with the path that spends it; the earlier exit addresses keep it after that.
        assertTrue(record.showsResult, "the kept change loses its recovery screen")
        assertFalse(record.copy(isResultSeen = true).showsResult, "kept change blocks every later exit")
    }

    @Test
    fun `a leftover not even the lowest rate can send is kept as dust at once`() = test {
        chain.utxosByAddress[SWEEP_FUNDING] = listOf(SparkExitFixtures.utxo("f0", 1u, 400uL))
        val done = SparkExitFixtures.record(Phase.Done).copy(
            destination = SWEEP_DESTINATION,
            funding = listOf(SparkExitRecord.Funding(0u, SWEEP_FUNDING, "02ab")),
            leftover = listOf(SparkExitRecord.Leftover("f0", 1u, 400uL)),
        )

        val record = driver(tip = 1_000u).tick(done)

        assertTrue(assertNotNull(record.leftoverSweep).isDustKept)
        assertFalse(record.needsDriving)
    }

    @Test
    fun `a leftover sweep whose inputs went elsewhere reads the address again`() = test {
        var record = driver(tip = 1_000u).tick(sweepDone())
        assertNotNull(assertNotNull(record.leftoverSweep).txHex)
        val first = assertNotNull(record.leftoverSweep.txid)
        chain.outcome = SparkExitBroadcastOutcome.InputsMissing("missing-inputs")
        record = driver(tip = 1_001u).tick(record)
        // The accepted sweep may still confirm: it is looked for while the address is read again.
        assertEquals(listOf(first), assertNotNull(record.leftoverSweep).replacedTxids)
        assertNull(record.leftoverSweep.txid)

        chain.utxosByAddress[SWEEP_FUNDING] = emptyList()
        record = driver(tip = 1_002u).tick(record)
        assertTrue(record.leftover.isEmpty())
        assertFalse(record.needsDriving)
    }

    @Test
    fun `a higher fee waiting on its payment still sends the steps due and checks with the sdk`() = test {
        val recovery = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")))
        val bumped = running(recovery).copy(
            funding = listOf(
                SparkExitFixtures.record().funding.single().copy(txid = "f0", vout = 0u, valueSats = 20_000uL)
            ),
        ).applyFeeBump(rate = 9uL, extraSats = 6_000uL, paymentTxid = "bump")
        sdk.checks += check(recovery, RecoveryVerdict.Valid)

        var record = driver(tip = 1_001u).tick(bumped)

        assertEquals(listOf("n1"), chain.broadcasts.map { it.txid })
        assertIs<Phase.NeedsFunding>(record.phase)
        assertTrue(record.isAwaitingOwnPayment)
        assertEquals(1_001u, record.fundingPaymentSeenAtHeight)
        // No host knows the payment for three blocks: the exit forgets it and asks again.
        chain.unknownTxids = setOf("bump")
        sdk.checks += check(recovery, RecoveryVerdict.Valid)
        record = driver(tip = 1_002u).tick(record)
        assertEquals(1_002u, record.fundingPaymentMissingSince)
        sdk.checks += check(recovery, RecoveryVerdict.Valid)
        record = driver(tip = 1_004u).tick(record)
        assertTrue(record.isAwaitingOwnPayment)
        sdk.checks += check(recovery, RecoveryVerdict.Valid)
        record = driver(tip = 1_005u).tick(record)
        assertFalse(record.isAwaitingOwnPayment)
        assertNull(record.fundingPaymentTxid)
    }

    @Test
    fun `a savings payment that confirms short of the funding asks for the rest at once`() = test {
        val paid = "d0".repeat(32)
        val waiting = SparkExitFixtures.record(Phase.AwaitingFunding(35_000uL)).copy(fundingPaymentTxid = paid)
        val address = SparkExitFixtures.FUNDING_ADDRESS
        chain.utxosByAddress[address] = listOf(SparkExitFixtures.utxo(paid, 0u, 20_000uL, confirmed = false))

        val sent = driver(tip = 1_000u).tick(waiting)
        assertTrue(sent.isAwaitingOwnPayment, "still confirming")
        assertFalse(SparkExitAlerts.isFundingAskBack(sent, waiting))

        chain.utxosByAddress[address] = listOf(SparkExitFixtures.utxo(paid, 0u, 20_000uL))
        val short = driver(tip = 1_001u).tick(sent)

        assertFalse(short.isAwaitingOwnPayment)
        assertTrue(SparkExitAlerts.isFundingAskBack(short, sent))
        val ask = assertIs<SparkExitFundingState.Ask>(SparkExitMath.fundingState(short, chain.utxosByAddress[address]))
        assertEquals(20_000uL, ask.receivedSats)
    }

    @Test
    fun `a payment from savings one host misses for a block or a single read is not forgotten`() {
        val waiting = SparkExitFixtures.record(Phase.NeedsFunding(30_000uL)).copy(fundingPaymentTxid = "bump")
        // Missed just before a block and just after it, seconds apart: the host may still be indexing it.
        val missed = waiting.withOwnPaymentLooked(1_000u, isKnown = false).withOwnPaymentLooked(1_001u, isKnown = false)
        assertTrue(missed.isAwaitingOwnPayment, "a one-block miss asked for the funding again")

        // A read that finds it starts the count over; only a miss that lasts three blocks forgets it.
        val found = missed.withOwnPaymentLooked(1_002u, isKnown = true).withOwnPaymentLooked(1_003u, isKnown = false)
        assertTrue(found.withOwnPaymentLooked(1_005u, isKnown = false).isAwaitingOwnPayment)
        assertFalse(found.withOwnPaymentLooked(1_006u, isKnown = false).isAwaitingOwnPayment)
    }

    @Test
    fun `a payment from savings never adopted is forgotten 144 blocks after the first look`() {
        val waiting = SparkExitFixtures.record(Phase.NeedsFunding(30_000uL)).copy(fundingPaymentTxid = "bump")
        val looked = waiting.withOwnPaymentLooked(1_000u, isKnown = true)
        assertTrue(looked.withOwnPaymentLooked(1_143u, isKnown = true).isAwaitingOwnPayment)
        assertFalse(looked.withOwnPaymentLooked(1_144u, isKnown = true).isAwaitingOwnPayment)
    }

    @Test
    fun `a waiting leftover is swept once fees drop`() = test {
        chain.utxosByAddress[SWEEP_FUNDING] = listOf(SparkExitFixtures.utxo("f0".repeat(32), 1u, 25_000uL))
        chain.outcome = SparkExitBroadcastOutcome.Accepted
        val waiting = SparkExitFixtures.record(Phase.Done).copy(
            destination = SWEEP_DESTINATION,
            funding = listOf(SparkExitRecord.Funding(0u, SWEEP_FUNDING, "02ab")),
            leftover = listOf(SparkExitRecord.Leftover("f0".repeat(32), 1u, 25_000uL)),
            leftoverSweep = SparkExitRecord.LeftoverSweep(
                null,
                null,
                25_000uL,
                waitingSince = 1_000u,
                checkedAtHeight = 1_000u
            ),
        )

        val record = driver(tip = 1_144u).tick(waiting)

        assertTrue(assertNotNull(record.leftoverSweep).isAccepted)
        assertFalse(record.isLeftoverWaitingForFees)
    }

    @Test
    fun `the leftover sweeps at the cheapest rate up to the exit's, leaving coins not worth their fee`() =
        test {
            val coin = "f0".repeat(32)
            chain.utxosByAddress[SWEEP_FUNDING] = listOf(
                SparkExitFixtures.utxo(coin, 1u, 25_000uL),
                SparkExitFixtures.utxo("f1".repeat(32), 0u, 60uL),
            )
            chain.outcome = SparkExitBroadcastOutcome.Accepted
            val done = SparkExitFixtures.record(Phase.Done).copy(
                destination = SWEEP_DESTINATION,
                funding = listOf(SparkExitRecord.Funding(0u, SWEEP_FUNDING, "02ab")),
                feeRateSatPerVbyte = 5uL,
                leftover = listOf(SparkExitRecord.Leftover(coin, 1u, 25_000uL)),
            )

            val cheap = assertNotNull(driver(tip = 1_000u).tick(done).leftoverSweep)
            assertEquals(1uL, cheap.feeRateSatPerVbyte)
            // The 60-sat coin costs 68 sats to add: only the other one is swept.
            assertEquals(listOf(coin), SparkExitTxInputs.decode(requireNotNull(cheap.txHex))?.map { it.prevTxid })

            chain.economyRate = 9uL
            assertNull(assertNotNull(driver(tip = 1_000u).tick(done).leftoverSweep).feeRateSatPerVbyte)
        }

    @Test
    fun `top up ignores cpfp change chained to the funding address`() = test {
        var record = SparkExitFixtures.record(Phase.NeedsFunding(23_000uL)).copy(
            funding = listOf(
                SparkExitRecord.Funding(0u, SparkExitFixtures.FUNDING_ADDRESS, "02ab", "f0", 1u, 20_000uL)
            ),
            recovery = SparkExitFixtures.recovery(
                listOf(SparkExitFixtures.tx("n1", cpfp = SparkExitFixtures.PARENT_HEX))
            ),
        )
        chain.utxosByAddress[SparkExitFixtures.FUNDING_ADDRESS] = listOf(
            SparkExitFixtures.utxo("f0", 1u, 20_000uL),
            SparkExitFixtures.utxo(SparkExitFixtures.PARENT_TXID, value = 9_000uL),
        )

        record = driver().tick(record)
        assertEquals(Phase.NeedsFunding(23_000uL), record.phase)
        assertEquals(3_000uL, record.fundingShortfallSats)
        assertTrue(sdk.prepareRequests.isEmpty())

        chain.utxosByAddress[SparkExitFixtures.FUNDING_ADDRESS] =
            chain.utxosByAddress.getValue(SparkExitFixtures.FUNDING_ADDRESS) +
            SparkExitFixtures.utxo("topup", value = 3_000uL)
        sdk.builds += SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n2")))
        record = driver().tick(record)

        assertEquals(Phase.Running, record.phase)
        assertEquals(2, sdk.recoverRequests.first().fundingInputs.size)
    }

    @Test
    fun `a first build of only cooperative leaves the operators could not sign is kept and retried`() = test {
        sdk.preparedResponse = SparkExitFixtures.prepared(funding = null)
        val failed = listOf(
            CooperativeRecoveryFailure("leaf-1", "o", 0u, CooperativeRecoveryError.OperatorsUnavailable("down")),
        )
        sdk.builds += SparkExitFixtures.recovery(emptyList(), failed = failed)

        var record = driver(tip = 1_000u).tick(SparkExitFixtures.record(Phase.AwaitingFunding(0uL)))

        assertEquals(Phase.Running, record.phase)
        assertEquals(1, record.failedLeafCount)
        assertEquals(failed, assertNotNull(record.recovery).failed)
        assertEquals(record, saved.last())

        // No check of a recovery with nothing in it: the failed leaves are retried at once, then let go.
        sdk.builds += SparkExitFixtures.recovery(emptyList(), failed = failed)
        record = driver(tip = 1_001u).tick(record)
        assertTrue(sdk.checkedRecoveries.isEmpty())
        assertEquals(listOf("leaf-1"), (sdk.prepareRequests.last().selection as ExitLeafSelection.Specific).leafIds)
        assertEquals(listOf("leaf-1"), record.failedLeaves.map { it.leafId })
        assertEquals(Phase.Running, record.phase)

        record = driver(tip = 2_009u).tick(record)
        assertEquals(Phase.Done, record.phase)
        assertTrue(record.isFailedLeavesReleased)
    }

    @Test
    fun `a redo that rebuilds only cooperative leaves the operators can't sign retries them, then lets go`() = test {
        var record = running(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("old"))), builtAt = 0L)
        sdk.checks += check(assertNotNull(record.recovery), redo)
        sdk.preparedResponse = SparkExitFixtures.prepared(funding = null)
        val failed = listOf(
            CooperativeRecoveryFailure("leaf-1", "o", 0u, CooperativeRecoveryError.OperatorsUnavailable("down")),
        )
        repeat(1_100) { sdk.builds += SparkExitFixtures.recovery(emptyList(), failed = failed) }

        for (tip in 1_000u..2_010u) {
            record = driver(tip = tip).tick(record)
            now += interval
        }

        assertNotNull(record.failedLeafRetrySince)
        assertEquals(Phase.Done, record.phase)
        assertTrue(record.isFailedLeavesReleased)
    }

    private val operatorsDown = CooperativeRecoveryFailure(
        "coop-1",
        "o",
        0u,
        CooperativeRecoveryError.OperatorsUnavailable("down"),
    )

    private fun coopLeaf(valueSats: ULong) = SparkExitFixtures.prepared(funding = null, leaves = emptyList()).copy(
        leaves = listOf(RecoverFundsLeaf("coop-1", valueSats, RecoveryMethod.COOPERATIVE)),
    )

    @Test
    fun `leaves the operators moved on-chain are retried at doubling gaps, then let go after a week`() = test {
        val done = SparkExitFixtures.recovery(
            listOf(
                SparkExitFixtures.tx("s", kind = RecoveryTxKind.SWEEP, status = ExitTransactionStatus.Confirmed(9u))
            ),
            failed = listOf(operatorsDown),
        )
        var record = running(done)
        repeat(6) { sdk.checks += check(done, RecoveryVerdict.Done) }
        sdk.preparedResponse = coopLeaf(16_384uL)
        // The operators still cannot co-sign: the retry builds nothing.
        repeat(3) { sdk.builds += SparkExitFixtures.recovery(emptyList(), failed = listOf(operatorsDown)) }

        record = driver(tip = 1_000u).tick(record)
        assertEquals(Phase.Running, record.phase)
        assertTrue(record.isWaitingForOperators)
        assertEquals(listOf(SparkExitRecord.FailedLeaf("coop-1", 16_384uL)), record.failedLeaves)
        assertEquals(16_384uL, record.failedLeafSats)
        assertEquals(listOf("coop-1"), (sdk.prepareRequests.single().selection as ExitLeafSelection.Specific).leafIds)

        record = driver(tip = 1_005u).tick(record)
        assertEquals(1, sdk.prepareRequests.size)
        record = driver(tip = 1_006u).tick(record)
        assertEquals(2, sdk.prepareRequests.size)
        assertEquals(1_006u, record.failedLeafRetriedAt)

        record = driver(tip = 2_007u).tick(record)
        assertEquals(Phase.Running, record.phase)
        record = driver(tip = 2_008u).tick(record)
        assertEquals(Phase.Done, record.phase)
        assertTrue(record.isFailedLeavesReleased)
        assertEquals(16_384uL, record.failedLeafSats)
        assertFalse(record.isWaitingForOperators)
    }

    @Test
    fun `a retry the operators co-sign becomes the next generation and the exit ends once it confirms`() = test {
        val done = SparkExitFixtures.recovery(emptyList(), failed = listOf(operatorsDown))
        var record = running(done)
        sdk.preparedResponse = coopLeaf(16_384uL)
        val coop = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("coop", kind = RecoveryTxKind.COOPERATIVE)))
        sdk.builds += coop

        record = driver(tip = 1_000u).tick(record)
        assertEquals(2, record.generation)
        assertEquals(listOf("coop"), chain.broadcasts.map { it.txid })
        assertFalse(record.isWaitingForOperators)
        assertTrue(record.isRetryingFailedLeaves)

        sdk.checks += check(coop, RecoveryVerdict.Done)
        record = driver(tip = 1_001u).tick(record)
        assertEquals(Phase.Done, record.phase)
        assertTrue(record.failedLeaves.isEmpty())
        assertFalse(record.isFailedLeavesReleased)
    }

    private fun funded(record: SparkExitRecord) = record.copy(
        funding = listOf(record.funding.single().copy(txid = "f0", vout = 0u, valueSats = 20_000uL)),
    )

    @Test
    fun `a higher fee the funding covers needs nothing more`() = test {
        val record = funded(running(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")))))
        sdk.builds += SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1b")))

        assertEquals(0uL, driver().feeBumpShortfall(record, 9uL))

        val request = sdk.prepareRequests.single()
        assertEquals(9uL, request.feeRateSatPerVbyte)
        assertEquals(listOf("leaf-1"), (request.selection as ExitLeafSelection.Specific).leafIds)
        assertEquals(record.fundingInputs, sdk.recoverRequests.single().fundingInputs)
        // Priced, not built: nothing is sent or saved.
        assertTrue(chain.broadcasts.isEmpty())
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a higher fee asks for the sdk's shortfall less what the funding address holds, with one input's fee`() = test {
        val record = funded(running(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")))))
        sdk.recoverError = SdkException.InsufficientCpfpFunds(26_000uL, "")
        // Still at the address: the adopted input, unspent.
        chain.utxosByAddress[SparkExitFixtures.FUNDING_ADDRESS] = listOf(SparkExitFixtures.utxo("f0", value = 20_000uL))

        // 26,000 needed, 20,000 held: 6,000, plus 68 vB at 9 sat/vB for the coin that pays it.
        assertEquals(6_612uL, driver().feeBumpShortfall(record, 9uL))

        sdk.recoverError = SdkException.InsufficientCpfpFunds(20_000uL, "")
        assertEquals(SparkExitMath.FUNDING_DUST_SATS, driver().feeBumpShortfall(record, 9uL))
    }

    @Test
    fun `a higher fee at which the prepare skips an exiting leaf for its fee is refused`() = test {
        val record = funded(running(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")))))
            .copy(leafIds = listOf("leaf-1", "leaf-2"))
        sdk.preparedResponse = SparkExitFixtures.prepared(
            funding = null,
            skipped = listOf(SkippedLeaf("leaf-2", 2_000uL, SkippedLeafReason.FeeExceedsValue)),
        )

        val refused = assertFailsWith<SparkExitFeeBumpLeavesBehind> { driver().feeBumpShortfall(record, 9uL) }

        assertEquals(2_000uL, refused.sats)
    }

    @Test
    fun `a higher fee at which a leaf can't pay its own exit is refused, the live exit unchanged`() = test {
        val record = funded(running(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")))))
            .copy(leafIds = listOf("leaf-1", "leaf-2"))
        sdk.preparedResponse = SparkExitFixtures.prepared(leaves = listOf("leaf-1", "leaf-2"))
        // Refused by the build at this rate.
        sdk.recoverError = ladderRefusal("leaf-1")
        chain.utxosByAddress[SparkExitFixtures.FUNDING_ADDRESS] = listOf(SparkExitFixtures.utxo("f0", value = 20_000uL))

        val refused = assertFailsWith<SparkExitFeeBumpLeavesBehind> { driver().feeBumpShortfall(record, 9uL) }
        assertEquals(100_000uL, refused.sats)

        // Refused by the prepare at this rate.
        sdk.recoverError = null
        sdk.prepareErrors += ladderRefusal("leaf-2")
        assertFailsWith<SparkExitFeeBumpLeavesBehind> { driver().feeBumpShortfall(record, 9uL) }

        assertEquals(listOf("leaf-1", "leaf-2"), record.exitLeafIds)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a generation that diverges while a higher fee's payment confirms is never sent again`() = test {
        val diverged = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")))
        var record = funded(running(diverged)).applyFeeBump(rate = 9uL, extraSats = 6_612uL, paymentTxid = "bump")
        assertTrue(record.isDrivingWhileFunding)
        sdk.checks += check(diverged, redo)

        record = driver().tick(record)

        assertIs<Phase.NeedsFunding>(record.phase)
        assertTrue(record.isRebuilding)
        assertTrue(record.isRedoRebuild)
        assertFalse(record.isDrivingWhileFunding, "the diverged generation keeps being sent")
        record = driver(withSdk = false).tick(record)
        assertTrue(chain.broadcasts.isEmpty())
    }

    @Test
    fun `a generation that diverges while a dropped higher fee's payment waits rebuilds from the funding it has`() =
        test {
            val diverged = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")))
            var record = funded(running(diverged, builtAt = now - interval))
                .applyFeeBump(rate = 9uL, extraSats = 6_612uL, paymentTxid = "bump")
            sdk.checks += check(diverged, redo)
            // The higher fee's payment never reached the address: only the confirmed funding is there.
            chain.utxosByAddress[SparkExitFixtures.FUNDING_ADDRESS] =
                listOf(SparkExitFixtures.utxo("f0", value = 20_000uL))
            sdk.builds += SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1c")))

            record = driver(tip = 1_000u).tick(record)

            assertEquals(Phase.Running, record.phase, "the rebuild waited for a payment that was dropped")
            assertEquals(2, record.generation)
            assertEquals(listOf("f0"), sdk.recoverRequests.single().fundingInputs.map { (it as CpfpInput.P2wpkh).txid })
        }

    @Test
    fun `a diverged generation's rebuild the funding cannot pay keeps waiting for the top-up`() = test {
        val diverged = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")))
        var record = funded(running(diverged, builtAt = now - interval))
            .applyFeeBump(rate = 9uL, extraSats = 6_612uL, paymentTxid = "bump")
        sdk.checks += check(diverged, redo)
        chain.utxosByAddress[SparkExitFixtures.FUNDING_ADDRESS] = listOf(SparkExitFixtures.utxo("f0", value = 20_000uL))
        sdk.recoverErrors += SdkException.InsufficientCpfpFunds(26_000uL, "")

        record = driver(tip = 1_000u).tick(record)
        assertIs<Phase.NeedsFunding>(record.phase)
        assertTrue(record.isRebuilding)
        // Not built again on every tick while the top-up is out.
        record = driver(tip = 1_000u).tick(record)
        assertEquals(1, sdk.recoverRequests.size)
    }

    @Test
    fun `a paid higher fee rebuilds at the new rate once its payment confirms`() = test {
        var record = funded(running(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")))))
            .applyFeeBump(rate = 9uL, extraSats = 6_612uL, paymentTxid = "bump")
        assertEquals(Phase.NeedsFunding(26_612uL), record.phase)
        assertEquals(SparkExitFundingState.Paid("bump"), SparkExitMath.fundingState(record, utxos = null))
        // Savings' payment is on its way: nothing is asked for while it confirms.
        assertTrue(record.isAwaitingOwnPayment)

        chain.utxosByAddress[SparkExitFixtures.FUNDING_ADDRESS] = listOf(
            SparkExitFixtures.utxo("f0", value = 20_000uL),
            SparkExitFixtures.utxo("bump", value = 6_612uL),
        )
        sdk.builds += SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1b")))
        record = driver().tick(record)

        assertEquals(Phase.Running, record.phase)
        assertEquals(9uL, sdk.prepareRequests.single().feeRateSatPerVbyte)
        assertFalse(record.isAwaitingOwnPayment)
        assertEquals(
            listOf("f0", "bump"),
            sdk.recoverRequests.single().fundingInputs.map { (it as CpfpInput.P2wpkh).txid }
        )
        // The generation out there kept going while the payment confirmed.
        assertEquals(listOf("n1", "n1b"), chain.broadcasts.map { it.txid })
    }

    @Test
    fun `a higher fee with nothing to pay rebuilds on the next tick`() = test {
        val record = funded(running(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")))))
            .applyFeeBump(rate = 9uL, extraSats = 0uL, paymentTxid = null)
        assertTrue(record.isRebuilding)
        assertEquals(Phase.Running, record.phase)
        sdk.builds += SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1b")))

        val rebuilt = driver().tick(record)

        assertEquals(9uL, sdk.prepareRequests.single().feeRateSatPerVbyte)
        assertEquals(record.generation + 1, rebuilt.generation)
        assertEquals(listOf("n1b"), chain.broadcasts.map { it.txid })
    }

    @Test
    fun `a rebuild from the chain keeps its note until one of its steps confirms`() = test {
        val built = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")))
        val record = SparkExitFixtures.record(Phase.Running)
            .copy(isRebuilding = true, isRebuiltFromChain = true)
            .adopt(built, 0L)
        sdk.checks += check(built, RecoveryVerdict.Valid)
        assertTrue(driver(tip = 1_000u).tick(record).isRebuiltFromChain)

        val landed = SparkExitFixtures.recovery(
            listOf(SparkExitFixtures.tx("n1", status = ExitTransactionStatus.Confirmed(1_000u)))
        )
        sdk.checks += check(landed, RecoveryVerdict.Valid)
        val ticked = driver(tip = 1_001u).tick(record)
        assertFalse(ticked.isRebuiltFromChain)
        assertNull(ticked.rebuiltFromChainConfirmedCount)
    }

    @Test
    fun `rebuild with nothing left ends the exit`() = test {
        val record = running(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("old"))), builtAt = 0L)
            .copy(isRebuilding = true)
        sdk.preparedResponse = SparkExitFixtures.prepared(leaves = emptyList())

        assertEquals(Phase.Done, driver().tick(record).phase)
    }

    private fun waiting(reason: SkippedLeafReason, stopped: List<String> = emptyList()) =
        SparkExitFixtures.prepared(
            leaves = emptyList(),
            skipped = listOf(SkippedLeaf("leaf-1", 100_000uL, reason)),
        ).copy(exitChainState = ExitChainState(emptyList(), emptyList(), stopped, emptyList(), emptyList()))

    @Test
    fun `an empty rebuild waits for a leaf whose funds were not found yet, at doubling gaps`() = test {
        var record = running(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("old"))), builtAt = 0L)
            .copy(isRebuilding = true)
        sdk.preparedResponse = waiting(SkippedLeafReason.FundsNotFound)
        val tries = mutableListOf<UInt>()
        for (tip in 1_000u..1_030u) {
            val before = sdk.prepareRequests.size
            record = driver(tip = tip).tick(record)
            if (sdk.prepareRequests.size > before) tries += tip
        }

        assertEquals(listOf(1_000u, 1_006u, 1_012u, 1_024u), tries)
        assertEquals(Phase.Running, record.phase)
        assertTrue(record.isWaitingForLeaves)
        assertEquals(1_000u, record.retrySince)
        assertEquals(1_024u, record.retriedAt)
    }

    @Test
    fun `a leaf still waited for after about a week is let go and the exit ends`() = test {
        val record = running(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("old"))), builtAt = 0L)
            .copy(isRebuilding = true, retrySince = 1_000u, retriedAt = 2_000u)
        sdk.preparedResponse = waiting(SkippedLeafReason.Unverified)

        assertEquals(Phase.Running, driver(tip = 2_007u).tick(record).phase)
        assertEquals(Phase.Done, driver(tip = 2_008u).tick(record).phase)
    }

    @Test
    fun `a leaf the operators' watchtower stopped is waited for six blocks, then let go`() = test {
        var record = running(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("old"))), builtAt = 0L)
            .copy(isRebuilding = true)
        sdk.preparedResponse = waiting(SkippedLeafReason.NotRecoverable("stopped"), stopped = listOf("leaf-1"))

        record = driver(tip = 1_000u).tick(record)
        assertEquals(mapOf("leaf-1" to 1_000u), record.stoppedLeafSince)
        assertTrue(record.isWaitingForLeaves)
        record = driver(tip = 1_006u).tick(record)

        assertEquals(Phase.Done, record.phase)
    }

    @Test
    fun `an unrecoverable leaf the watchtower did not stop ends the exit at once`() = test {
        val record = running(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("old"))), builtAt = 0L)
            .copy(isRebuilding = true)
        sdk.preparedResponse = waiting(SkippedLeafReason.NotRecoverable("gone"))

        assertEquals(Phase.Done, driver(tip = 1_000u).tick(record).phase)
    }

    @Test
    fun `rebuild waits while a leaf is unverified`() = test {
        val record = running(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("old"))), builtAt = 0L)
            .copy(isRebuilding = true)
        sdk.preparedResponse = SparkExitFixtures.prepared(
            leaves = emptyList(),
            skipped = listOf(SkippedLeaf("leaf-1", 100_000uL, SkippedLeafReason.Unverified)),
        )

        val ticked = driver().tick(record)

        assertEquals(Phase.Running, ticked.phase)
        assertTrue(ticked.isRebuilding)
    }

    @Test
    fun `every prepare resumes with the strategy the exit was quoted with`() = test {
        val perBranch = running(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("old"))), builtAt = 0L)
            .copy(isRebuilding = true)
        sdk.builds += SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")))
        sdk.builds += SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n2")))

        driver().tick(perBranch)
        driver().tick(perBranch.copy(fundingStrategy = SparkExitRecord.PER_NODE))

        assertEquals(listOf(null, CpfpFundingStrategy.PER_NODE), sdk.prepareRequests.map { it.fundingStrategy })
    }

    @Test
    fun `rebuild waits while the operators could not be reached for a leaf`() = test {
        val record = running(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("old"))), builtAt = 0L)
            .copy(isRebuilding = true)
        sdk.preparedResponse = waiting(SkippedLeafReason.OperatorsUnavailable)

        val ticked = driver(tip = 1_000u).tick(record)

        assertEquals(Phase.Running, ticked.phase)
        assertTrue(ticked.isWaitingForLeaves)
    }

    private fun partialBuild(reason: SkippedLeafReason): SparkExitRecord {
        val record = running(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("old"))), builtAt = 0L)
            .copy(isRebuilding = true, leafIds = listOf("leaf-1", "leaf-2"))
        sdk.preparedResponse = SparkExitFixtures.prepared(skipped = listOf(SkippedLeaf("leaf-2", 50_000uL, reason)))
        sdk.builds += SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")))
        val landed = SparkExitFixtures.recovery(
            listOf(SparkExitFixtures.tx("n1", status = ExitTransactionStatus.Confirmed(990u))),
        )
        repeat(4) { sdk.checks += check(landed, RecoveryVerdict.Done) }
        return record
    }

    @Test
    fun `a leaf skipped while another builds is tried again before the exit ends`() = test {
        var record = driver(tip = 1_000u).tick(partialBuild(SkippedLeafReason.Unverified))
        assertEquals(2, record.generation)
        assertEquals(listOf("leaf-2"), record.waitingLeafIds)
        assertEquals(record.waitingLeafIds, SparkExitJson.decode(SparkExitJson.encode(record)).waitingLeafIds)

        sdk.preparedResponse = sdk.preparedResponse.copy(leaves = emptyList())
        record = driver(tip = 1_000u).tick(record)
        assertEquals(Phase.Running, record.phase, "leaf-2 may still move")
        assertTrue(record.isWaitingForLeaves)
        assertTrue(record.hasLeavesOutstanding, "leaf-2 is still in transfer")
        assertEquals(listOf("leaf-2"), (sdk.prepareRequests.last().selection as ExitLeafSelection.Specific).leafIds)

        chain.confirmedTxids = setOf("n1")
        record = driver(withSdk = false, tip = 1_001u).tick(record)
        assertEquals(Phase.Running, record.phase, "the exit waits for the sdk to retry leaf-2")

        sdk.preparedResponse = SparkExitFixtures.prepared(leaves = listOf("leaf-2"))
        sdk.builds += SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n2")))
        record = driver(tip = 1_006u).tick(record)
        assertNull(record.retrySince)
        assertEquals(listOf("n1", "n2"), chain.broadcasts.map { it.txid })

        record = driver(tip = 1_007u).tick(record)
        assertEquals(Phase.Done, record.phase)
    }

    @Test
    fun `a skipped leaf whose retry needs more fees asks for them and retries once they come`() = test {
        // Unfunded: the record's funding has no coin yet, so the shortfall is the SDK's total plus one input's fee.
        var record = driver(tip = 1_000u).tick(partialBuild(SkippedLeafReason.Unverified))
        sdk.preparedResponse = SparkExitFixtures.prepared(leaves = listOf("leaf-2"))
        sdk.recoverErrors += SdkException.InsufficientCpfpFunds(3_000uL, "")

        record = driver(tip = 1_000u).tick(record)
        assertEquals(Phase.NeedsFunding(3_136uL), record.phase)
        assertEquals(listOf("leaf-2"), record.waitingLeafIds)

        chain.utxosByAddress[SparkExitFixtures.FUNDING_ADDRESS] = listOf(SparkExitFixtures.utxo("f9", value = 3_136uL))
        sdk.builds += SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n2")))
        record = driver(tip = 1_001u).tick(record)

        assertEquals(Phase.Running, record.phase)
        assertEquals(listOf("leaf-2"), (sdk.prepareRequests.last().selection as ExitLeafSelection.Specific).leafIds)
        assertEquals(listOf("n1", "n2"), chain.broadcasts.map { it.txid })
        assertTrue(record.fundingInputs.isNotEmpty())
    }

    @Test
    fun `a skipped leaf's fee ask nobody pays is let go after about a week`() = test {
        var record = driver(tip = 1_000u).tick(partialBuild(SkippedLeafReason.Unverified))
        sdk.preparedResponse = SparkExitFixtures.prepared(leaves = listOf("leaf-2"))
        sdk.recoverErrors += SdkException.InsufficientCpfpFunds(3_000uL, "")
        record = driver(tip = 1_000u).tick(record)
        assertEquals(Phase.NeedsFunding(3_136uL), record.phase)

        record = driver(tip = 2_007u).tick(record)
        assertEquals(Phase.NeedsFunding(3_136uL), record.phase)
        record = driver(tip = 2_008u).tick(record)
        assertEquals(Phase.Done, record.phase)
        assertTrue(record.waitingLeafIds.isEmpty())
    }

    @Test
    fun `a skipped leaf's fee ask waits only a tick when the funding address can't be read`() = test {
        val funded = partialBuild(SkippedLeafReason.Unverified).let {
            it.copy(funding = listOf(it.funding.single().copy(txid = "f0", vout = 0u, valueSats = 20_000uL)))
        }
        var record = driver(tip = 1_000u).tick(funded)
        sdk.preparedResponse = SparkExitFixtures.prepared(leaves = listOf("leaf-2"))
        repeat(2) { sdk.recoverErrors += SdkException.InsufficientCpfpFunds(30_000uL, "") }
        chain.utxosError = SparkExitChainError("timeout")
        record = driver(tip = 1_000u).tick(record)
        assertEquals(Phase.Running, record.phase)

        chain.utxosError = null
        record = driver(tip = 1_001u).tick(record)
        assertIs<Phase.NeedsFunding>(record.phase, "the next tick asks")
    }

    @Test
    fun `a first build of only failed cooperative leaves keeps the leaf it skipped`() = test {
        sdk.preparedResponse = SparkExitFixtures.prepared(
            funding = null,
            skipped = listOf(SkippedLeaf("leaf-2", 50_000uL, SkippedLeafReason.Unverified)),
        )
        val failed = listOf(
            CooperativeRecoveryFailure("leaf-1", "o", 0u, CooperativeRecoveryError.OperatorsUnavailable("down")),
        )
        sdk.builds += SparkExitFixtures.recovery(emptyList(), failed = failed)
        val start = SparkExitFixtures.record(Phase.AwaitingFunding(0uL)).copy(leafIds = listOf("leaf-1", "leaf-2"))

        val record = driver(tip = 1_000u).tick(start)

        assertEquals(listOf("leaf-2"), record.waitingLeafIds)
    }

    @Test
    fun `a failed-leaf retry that builds one leaf and skips another keeps the skipped one`() = test {
        val coop2 = CooperativeRecoveryFailure("coop-2", "o", 1u, CooperativeRecoveryError.OperatorsUnavailable("down"))
        val done = SparkExitFixtures.recovery(emptyList(), failed = listOf(operatorsDown, coop2))
        var record = running(done).copy(leafIds = listOf("coop-1", "coop-2"))
        sdk.preparedResponse = coopLeaf(16_384uL).copy(
            skipped = listOf(SkippedLeaf("coop-2", 8_192uL, SkippedLeafReason.Unverified)),
        )
        val coop = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("coop", kind = RecoveryTxKind.COOPERATIVE)))
        sdk.builds += coop

        record = driver(tip = 1_000u).tick(record)
        assertEquals(listOf("coop-2"), record.waitingLeafIds)

        val landed = coop.copy(
            transactions = coop.transactions.map { it.copy(status = ExitTransactionStatus.Confirmed(1_001u)) },
        )
        sdk.checks += check(landed, RecoveryVerdict.Done)
        sdk.preparedResponse = coopLeaf(0uL).copy(
            leaves = emptyList(),
            skipped = listOf(SkippedLeaf("coop-2", 8_192uL, SkippedLeafReason.Unverified)),
        )
        record = driver(tip = 1_001u).tick(record)
        assertEquals(Phase.Running, record.phase, "coop-2 may still move")
        assertEquals(listOf("coop-2"), (sdk.prepareRequests.last().selection as ExitLeafSelection.Specific).leafIds)
    }

    @Test
    fun `a waiting leaf let go after a week adds to the leaves that stayed on spark`() = test {
        var record = driver(tip = 1_000u).tick(
            partialBuild(SkippedLeafReason.FundsNotFound).copy(skippedLeafCount = 13),
        )
        sdk.preparedResponse = sdk.preparedResponse.copy(leaves = emptyList())
        record = driver(tip = 1_000u).tick(record)
        record = driver(tip = 2_008u).tick(record)

        assertEquals(Phase.Done, record.phase)
        assertEquals(14, record.skippedLeafCount)
    }

    @Test
    fun `a leaf skipped while another builds is let go after about a week`() = test {
        var record = driver(tip = 1_000u).tick(partialBuild(SkippedLeafReason.FundsNotFound))
        sdk.preparedResponse = sdk.preparedResponse.copy(leaves = emptyList())
        record = driver(tip = 1_000u).tick(record)
        assertEquals(Phase.Running, record.phase)

        record = driver(tip = 2_008u).tick(record)
        assertEquals(Phase.Done, record.phase)
        assertNull(record.retrySince)
    }

    @Test
    fun `resumes after a restart mid-exit without repeating broadcasts`() = test {
        val store = SparkExitStore(File(tempFolder.root, "spark-exit.json"))
        chain.utxosByAddress[SparkExitFixtures.FUNDING_ADDRESS] = listOf(SparkExitFixtures.utxo("f0", value = 20_000uL))
        val built = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1", cpfp = "03c")))
        sdk.builds += built
        driver(save = store::save).tick(SparkExitFixtures.record())

        // A new process: only the file is left, and a fresh driver picks up from it.
        val reloaded = requireNotNull(store.load())
        sdk.checks += check(built, RecoveryVerdict.Valid)
        driver(save = store::save).tick(reloaded)

        assertEquals(Phase.Running, reloaded.phase)
        assertEquals(1, reloaded.generation)
        assertEquals(listOf("n1"), chain.broadcasts.map { it.txid })
        assertEquals(1, sdk.recoverRequests.size)
        assertEquals(1, requireNotNull(store.load()).broadcastLog.single().attempts)
    }

    @Test
    fun `without the sdk an exit whose every step confirmed ends done`() = test {
        val recovery = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")))
        chain.confirmedTxids = setOf("n1")

        val record = driver(withSdk = false, tip = 1_000u).tick(running(recovery))

        assertEquals(Phase.Done, record.phase)
        assertTrue(chain.broadcasts.isEmpty())
    }

    @Test
    fun `without the sdk an exit whose steps confirmed with failed leaves keeps running for their retry`() = test {
        val recovery = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")), failed = listOf(operatorsDown))
        chain.confirmedTxids = setOf("n1")

        val record = driver(withSdk = false, tip = 1_000u).tick(running(recovery))

        assertEquals(Phase.Running, record.phase)
    }

    @Test
    fun `an exit that ended with a savings payment unresolved watches it and sweeps it once it arrives`() = test {
        chain.utxosByAddress[SWEEP_FUNDING] = emptyList()
        val ended = SparkExitFixtures.record(Phase.Done).copy(
            destination = SWEEP_DESTINATION,
            funding = listOf(SparkExitRecord.Funding(0u, SWEEP_FUNDING, "02ab")),
            fundingPaymentTxid = BUMP,
        )
        assertTrue(ended.needsDriving)

        var record = driver(tip = 1_000u).tick(ended)
        assertEquals(1_000u, record.fundingPaymentSeenAtHeight)
        assertTrue(record.needsDriving)

        val payment = SparkExitFixtures.utxo(BUMP, 0u, 6_000uL)
        chain.utxosByAddress[SWEEP_FUNDING] = listOf(payment)
        record = driver(tip = 1_001u).tick(record)

        assertEquals(listOf(SparkExitRecord.Leftover(BUMP, 0u, 6_000uL)), record.leftover)
        assertNull(record.fundingPaymentTxid)
        assertNotNull(assertNotNull(record.leftoverSweep).txHex)

        // One no host sees for three blocks is forgotten, and the exit stops.
        chain.unknownTxids = setOf("gone")
        var forgotten = driver(tip = 1_000u).tick(ended.copy(fundingPaymentTxid = "gone"))
        forgotten = driver(tip = 1_003u).tick(forgotten)
        assertNull(forgotten.fundingPaymentTxid)
        assertFalse(forgotten.needsDriving)
    }

    @Test
    fun `a sweep past the day's wait for unconfirmed funding spends only the confirmed outputs`() = test {
        val confirmed = SparkExitFixtures.utxo("c1".repeat(32), 0u, 25_000uL)
        val unconfirmed = SparkExitFixtures.utxo("d1".repeat(32), 0u, 10_000uL, confirmed = false)
        chain.utxosByAddress[SWEEP_FUNDING] = listOf(confirmed, unconfirmed)
        val done = SparkExitFixtures.record(Phase.Done).copy(
            destination = SWEEP_DESTINATION,
            funding = listOf(SparkExitRecord.Funding(0u, SWEEP_FUNDING, "02ab")),
            leftover = listOf(SparkExitRecord.Leftover(confirmed.txid, 0u, 25_000uL)),
        )

        var record = driver(tip = 1_000u).tick(done)
        assertTrue(record.isLeftoverWaitingForFees)
        record = driver(tip = 1_144u).tick(record)

        val sweep = assertNotNull(record.leftoverSweep)
        assertEquals(listOf(confirmed.txid), SparkExitTxInputs.decode(assertNotNull(sweep.txHex))?.map { it.prevTxid })
    }

    @Test
    fun `without the sdk broadcasts steps whose parents confirmed and timelocks matured`() = test {
        var record = running(
            SparkExitFixtures.recovery(
                listOf(
                    SparkExitFixtures.tx("n1"),
                    SparkExitFixtures.tx(
                        "n2",
                        cpfp = "03c",
                        dependsOn = listOf("n1"),
                        status = ExitTransactionStatus.WaitingForDependencies,
                    ),
                    SparkExitFixtures.tx(
                        "r1",
                        kind = RecoveryTxKind.REFUND,
                        dependsOn = listOf("n2"),
                        status = ExitTransactionStatus.WaitingForTimelock(990u),
                    ),
                    SparkExitFixtures.tx(
                        "r2",
                        kind = RecoveryTxKind.REFUND,
                        dependsOn = listOf("n1"),
                        status = ExitTransactionStatus.WaitingForTimelock(1_500u),
                    ),
                ),
            ),
        )
        chain.tip = 1_000u
        chain.confirmedTxids = setOf("n1")

        record = driver(withSdk = false).tick(record)

        assertEquals(listOf("n2"), chain.broadcasts.map { it.txid })
        assertEquals(ExitTransactionStatus.Confirmed(900u), record.recovery?.transactions?.first()?.status)
        assertEquals(Phase.Running, record.phase)
    }

    @Test
    fun `does nothing once finished`() = test {
        val record = driver().tick(SparkExitFixtures.record(Phase.Done))

        assertIs<Phase.Done>(record.phase)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a run killed after a broadcast does not send it again on restart`() = test {
        val store = SparkExitStore(File(tempFolder.root, "spark-exit.json"))
        store.save(running(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1"), SparkExitFixtures.tx("n2")))))
        // The process dies while it sends the second transaction, after the first went out.
        chain.onBroadcast = { if (chain.broadcasts.size == 1) error("killed") }

        runCatching { driver(withSdk = false, save = store::save).tick(requireNotNull(store.load())) }
        chain.onBroadcast = {}
        val record = driver(withSdk = false, save = store::save).tick(requireNotNull(store.load()))

        assertEquals(listOf("n1", "n2"), chain.broadcasts.map { it.txid })
        assertEquals(listOf("n1", "n2"), record.broadcastLog.map { it.txid })
        assertEquals(record, store.load())
    }

    @Test
    fun `done after a redo that needed no funding sweeps the earlier generation's change in one transaction`() = test {
        val gen1 = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1", cpfp = "03c1")))
        var record = running(gen1).copy(
            destination = SWEEP_DESTINATION,
            funding = listOf(SparkExitRecord.Funding(0u, SWEEP_FUNDING, "02ab", "f0".repeat(32), 0u, 20_000uL)),
        )
        sdk.checks += check(gen1, redo)
        sdk.preparedResponse = SparkExitFixtures.prepared(funding = null)
        val gen2 = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n2")))
        sdk.builds += gen2
        now += SparkExitDriver.REBUILD_INTERVAL.inWholeMilliseconds

        record = driver().tick(record)

        assertEquals(2, record.generation)
        assertTrue(sdk.recoverRequests.single().fundingInputs.isEmpty())

        val change = (1..5).map { SparkExitFixtures.utxo("%064x".format(it), 1u, 700uL + it.toULong()) }
        chain.utxosByAddress[SWEEP_FUNDING] = change
        sdk.checks += check(gen2, RecoveryVerdict.Done)
        record = driver().tick(record)

        assertEquals(Phase.Done, record.phase)
        assertEquals(5, record.leftover.size)
        val sent = chain.rawBroadcasts.single()
        assertEquals(change.map { it.txid }, SparkExitTxInputs.decode(sent)?.map { it.prevTxid })
        assertTrue(assertNotNull(record.leftoverSweep).isAccepted)
        assertTrue(record.showsResult)
    }

    @Test
    fun `an exit that ended while its funding address could not be read ends again once it can`() = test {
        val recovery = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")))
        var record = running(recovery)
        chain.utxosError = IllegalStateException("unreachable")
        sdk.checks += check(recovery, RecoveryVerdict.Done)

        record = driver().tick(record)

        assertEquals(Phase.Running, record.phase)
        assertTrue(record.needsDriving)

        chain.utxosError = null
        chain.utxosByAddress[SparkExitFixtures.FUNDING_ADDRESS] = listOf(SparkExitFixtures.utxo("c1", value = 900uL))
        sdk.checks += check(recovery, RecoveryVerdict.Done)
        record = driver().tick(record)

        assertEquals(Phase.Done, record.phase)
        assertEquals(listOf(SparkExitRecord.Leftover("c1", 0u, 900uL)), record.leftover)
    }

    @Test
    fun `adopted funding keeps the exit's own funding key index`() = test {
        chain.utxosByAddress[SparkExitFixtures.FUNDING_ADDRESS] = listOf(SparkExitFixtures.utxo("f0", 1u, 25_000uL))
        sdk.builds += SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")))
        val start = SparkExitFixtures.record().let { it.copy(funding = it.funding.map { f -> f.copy(index = 3u) }) }

        val record = driver().tick(start)

        assertEquals(listOf(3u), record.funding.map { it.index })
        assertEquals(3u, record.fundingIndex)
    }

    @Test
    fun `without the sdk a refund waits for its csv to mature from its parent's confirmation`() = test {
        val node = "aa".repeat(32)
        val refund = SparkExitFixtures.tx(
            "r1",
            kind = RecoveryTxKind.REFUND,
            dependsOn = listOf(node),
            status = ExitTransactionStatus.WaitingForDependencies,
            csv = 100u,
        ).copy(txHex = SparkExitFixtures.csvTxHex(node, csv = 100))
        var record = running(SparkExitFixtures.recovery(listOf(refund)))
        chain.confirmedTxids = setOf(node)
        chain.heights[node] = 950u
        chain.tip = 1_000u

        record = driver(withSdk = false).tick(record)

        assertTrue(chain.broadcasts.isEmpty())
        val waiting = record.recovery?.transactions?.single()?.status
        assertEquals(ExitTransactionStatus.WaitingForTimelock(1_050u), waiting)

        // Nodes take it once it would be valid in the next block.
        chain.tip = 1_049u
        record = driver(withSdk = false).tick(record)

        assertEquals(listOf("r1"), chain.broadcasts.map { it.txid })
        assertEquals(0, record.broadcastLog.single().rejections)
    }

    @Test
    fun `without the sdk a step whose input someone else spent is not sent again`() = test {
        val recovery = SparkExitFixtures.recovery(
            listOf(
                SparkExitFixtures.tx("n1"),
                SparkExitFixtures.tx(
                    "r1",
                    kind = RecoveryTxKind.REFUND,
                    dependsOn = listOf("n1"),
                    status = ExitTransactionStatus.WaitingForDependencies,
                ),
            ),
        )
        var record = running(recovery)
        chain.confirmedTxids = setOf("n1")
        chain.outcome = SparkExitBroadcastOutcome.InputsMissing("bad-txns-inputs-missingorspent")

        record = driver(withSdk = false).tick(record)
        now += SparkExitDriver.REBROADCAST_INTERVAL.inWholeMilliseconds
        record = driver(withSdk = false).tick(record)

        assertEquals(listOf("r1"), chain.broadcasts.map { it.txid })
        val attempt = record.broadcastLog.single()
        assertTrue(attempt.isInputSpent)
        assertEquals(0, attempt.rejections)
        assertNull(record.surfacedRejection)
    }

    private fun reorgedParentRecovery() = SparkExitFixtures.recovery(
        listOf(
            SparkExitFixtures.tx("n1", status = ExitTransactionStatus.Confirmed(900u)),
            SparkExitFixtures.tx(
                "r1",
                kind = RecoveryTxKind.REFUND,
                dependsOn = listOf("n1"),
                status = ExitTransactionStatus.WaitingForDependencies,
            ),
        ),
    )

    @Test
    fun `without the sdk a saved confirmation a reorg undid is asked again before its child goes out`() = test {
        var record = running(reorgedParentRecovery())
        chain.outcome = SparkExitBroadcastOutcome.InputsMissing("bad-txns-inputs-missingorspent")

        record = driver(withSdk = false).tick(record)

        assertTrue(chain.broadcasts.none { it.txid == "r1" })
        val parent = record.recovery?.transactions?.first { it.txid == "n1" }
        assertFalse(parent?.status is ExitTransactionStatus.Confirmed)
        assertTrue(record.broadcastLog.none { it.txid == "r1" && it.isInputSpent })

        // Confirmed again: its child goes out.
        chain.confirmedTxids = setOf("n1")
        chain.outcome = SparkExitBroadcastOutcome.Accepted
        now += SparkExitDriver.REBROADCAST_INTERVAL.inWholeMilliseconds
        record = driver(withSdk = false).tick(record)

        assertEquals(listOf("r1"), chain.broadcasts.map { it.txid }.filter { it == "r1" })
    }

    @Test
    fun `without the sdk a reorg clears the spent input its child was refused for`() = test {
        val spent = SparkExitRecord.BroadcastAttempt(1, "r1", 1, 0, now, "inputs missing", false, isInputSpent = true)
        var record = running(reorgedParentRecovery()).copy(broadcastLog = listOf(spent))

        record = driver(withSdk = false).tick(record)
        assertFalse(record.broadcastLog.single { it.txid == "r1" }.isInputSpent)

        chain.confirmedTxids = setOf("n1")
        record = driver(withSdk = false).tick(record)

        assertEquals(listOf("r1"), chain.broadcasts.map { it.txid }.filter { it == "r1" })
    }

    @Test
    fun `without the sdk a saved confirmation stays while no host answers`() = test {
        chain.statusErrorTxids = setOf("n1")

        val record = driver(withSdk = false).tick(running(reorgedParentRecovery()))

        assertEquals(ExitTransactionStatus.Confirmed(900u), record.recovery?.transactions?.first()?.status)
        assertEquals(listOf("r1"), chain.broadcasts.map { it.txid })
    }

    /** The qa-03 run: an older state's refund took the leaf, and every Redo rebuilt the same spent refund. */
    private fun lostLeafRecovery(leafId: String = "leaf-1") = SparkExitFixtures.recovery(
        listOf(
            SparkExitFixtures.tx("node", status = ExitTransactionStatus.Confirmed(11_522u)),
            SparkExitFixtures.tx("refund", kind = RecoveryTxKind.REFUND, cpfp = "03c", dependsOn = listOf("node"))
                .copy(nodeId = leafId),
            SparkExitFixtures.tx(
                "sweep",
                kind = RecoveryTxKind.SWEEP,
                dependsOn = listOf("refund"),
                status = ExitTransactionStatus.WaitingForDependencies,
            ),
        ),
    )

    @Test
    fun `a refund an older state already spent ends the exit after two rebuilds reproduce it`() = test {
        val recovery = lostLeafRecovery()
        var record = running(recovery)
        chain.outcome = SparkExitBroadcastOutcome.InputsMissing("""sendrawtransaction RPC error: {"code":-25}""")
        sdk.preparedResponse = SparkExitFixtures.prepared(funding = null)
        sdk.checks += check(recovery, RecoveryVerdict.Valid)
        repeat(2) {
            sdk.checks += check(recovery, redo)
            sdk.builds += recovery
        }

        // Sent, refused as spent; then each Redo rebuilds the same refund.
        record = driver().tick(record)
        assertTrue(record.broadcastLog.single().isInputSpent)
        now += SparkExitDriver.REBUILD_INTERVAL.inWholeMilliseconds
        record = driver().tick(record)
        assertEquals(2, record.generation)
        assertEquals(mapOf("refund" to 1), record.spentStepStrikes)
        now += SparkExitDriver.REBUILD_INTERVAL.inWholeMilliseconds
        record = driver().tick(record)

        assertEquals(2, record.generation)
        assertEquals(Phase.Failed(SparkExitDriver.LOST_REASON), record.phase)
        assertEquals(listOf(SparkExitRecord.LostLeaf("leaf-1", 100_000uL)), record.lostLeaves)
        assertEquals(100_000uL, record.lostSats)
        assertFalse(record.isActive)
    }

    @Test
    fun `a refund reproduced spent is not struck while the watchtower stopped its leaf a short while ago`() = test {
        val recovery = lostLeafRecovery()
        var record = running(recovery)
        chain.outcome = SparkExitBroadcastOutcome.InputsMissing("""sendrawtransaction RPC error: {"code":-25}""")
        sdk.preparedResponse = SparkExitFixtures.prepared(funding = null)
            .copy(exitChainState = ExitChainState(emptyList(), emptyList(), listOf("leaf-1"), emptyList(), emptyList()))
        sdk.checks += check(recovery, RecoveryVerdict.Valid)
        repeat(2) {
            sdk.checks += check(recovery, redo)
            sdk.builds += recovery
        }

        record = driver(tip = 1_000u).tick(record)
        now += interval
        record = driver(tip = 1_001u).tick(record)
        now += interval
        record = driver(tip = 1_002u).tick(record)

        assertEquals(3, record.generation)
        assertEquals(mapOf("refund" to 2), record.spentStepStrikes)
        assertTrue(record.lostLeaves.isEmpty())
        assertEquals(Phase.Running, record.phase)
    }

    @Test
    fun `the first build keeps the sdk's exit data and every rebuild imports it first`() = test {
        chain.utxosByAddress[SparkExitFixtures.FUNDING_ADDRESS] = listOf(SparkExitFixtures.utxo("f0", 1u, 25_000uL))
        val built = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")))
        sdk.builds += built
        sdk.builds += built

        var record = driver().tick(SparkExitFixtures.record())

        assertEquals("exit-state-1", record.exitStateSnapshot)
        assertEquals(1, sdk.exports)
        assertTrue(sdk.imports.isEmpty())

        sdk.exitState = "exit-state-2"
        sdk.checks += check(built, redo)
        now += interval
        record = driver().tick(record)

        assertEquals(2, record.generation)
        assertEquals(1, sdk.exports)
        assertEquals(listOf("exit-state-1"), sdk.imports)
        assertEquals("exit-state-1", record.exitStateSnapshot)
    }

    private fun sweepDone(valueSats: ULong = 25_000uL): SparkExitRecord {
        chain.utxosByAddress[SWEEP_FUNDING] = listOf(SparkExitFixtures.utxo("f0".repeat(32), 1u, valueSats))
        return SparkExitFixtures.record(Phase.Done).copy(
            destination = SWEEP_DESTINATION,
            funding = listOf(SparkExitRecord.Funding(0u, SWEEP_FUNDING, "02ab")),
            leftover = listOf(SparkExitRecord.Leftover("f0".repeat(32), 1u, valueSats)),
        )
    }

    /**
     * The 0.28 rerun's case 6 (Run E): six 294-sat coins of per-node change swept at 1 sat/vB, which the mempool
     * refused with "min relay fee not met, 450 < 674".
     */
    @Test
    fun `a fee change sweep refused for its fee waits a block and tries again at a rate nodes take`() = test {
        val coins = List(6) { SparkExitFixtures.utxo("%064x".format(it), 0u, 294uL) }
        chain.utxosByAddress[SWEEP_FUNDING] = coins
        chain.outcome = SparkExitBroadcastOutcome.Rejected("min relay fee not met, 450 < 674")
        val done = SparkExitFixtures.record(Phase.Done).copy(
            destination = SWEEP_DESTINATION,
            funding = listOf(SparkExitRecord.Funding(0u, SWEEP_FUNDING, "02ab")),
            feeRateSatPerVbyte = 1uL,
            leftover = coins.map { SparkExitRecord.Leftover(it.txid, it.vout, it.value) },
        )

        var record = driver(tip = 1_000u).tick(done)
        // Refused, as a stored record from before this rule would be: it already reads as waiting for fees.
        assertTrue(assertNotNull(record.leftoverSweep).isFeeRejection)
        assertTrue(record.isLeftoverWaitingForFees)
        record = driver(tip = 1_000u).tick(record)

        // Back to waiting, with the rate the refusal asks for, still driven, and Done says the change waits.
        val waiting = assertNotNull(record.leftoverSweep)
        assertTrue(record.isLeftoverWaitingForFees)
        assertEquals(2uL, waiting.refusedFeeRate)
        assertEquals(1_001u, waiting.retryAtHeight)
        assertEquals(1_000u, waiting.waitingSince)
        assertTrue(record.needsDriving)
        assertEquals(1, chain.rawBroadcasts.size)
        assertEquals(record, SparkExitJson.decode(SparkExitJson.encode(record)))

        // Not again in the same block; the next one builds at max(economy, 2).
        record = driver(tip = 1_000u).tick(record)
        assertEquals(1, chain.rawBroadcasts.size)
        chain.outcome = SparkExitBroadcastOutcome.Accepted
        record = driver(tip = 1_001u).tick(record)
        val sent = assertNotNull(record.leftoverSweep)
        assertEquals(2uL, sent.feeRateSatPerVbyte)
        assertTrue(sent.isAccepted)
        assertEquals(6, SparkExitTxInputs.decode(requireNotNull(sent.txHex))?.size)
        assertFalse(record.isLeftoverWaitingForFees)
    }

    @Test
    fun `a refused minimum is the refused rate scaled to the fee asked for, and at least one more`() {
        assertEquals(2uL, SparkExitSweep.refusedFeeRate("min relay fee not met, 450 < 674", 1uL))
        assertEquals(
            4uL,
            SparkExitSweep.refusedFeeRate("mempool min fee not met, 450 < 1_400".replace("_", ""), 1uL)
        )
        assertEquals(3uL, SparkExitSweep.refusedFeeRate("insufficient fee", 2uL))
    }

    @Test
    fun `a refused minimum too big to scale asks for more than any cap`() {
        val refusal = "mempool min fee not met, 450 < 10000000000000000000"
        assertEquals(
            Long.MAX_VALUE.toULong(),
            SparkExitSweep.refusedFeeRate(refusal, 2uL),
            "the scaled rate wrapped around, or cannot be saved",
        )
        assertEquals(
            Long.MAX_VALUE.toULong(),
            SparkExitSweep.refusedFeeRate("fee not met, 1 < 10000000000000000000", 1uL),
        )
        // The host's text from the review, at 2 sat/vB: the rate it gives saves and loads.
        val sweep = SparkExitRecord.LeftoverSweep(
            txid = null,
            txHex = null,
            valueSats = 1_764uL,
            refusedFeeRate = SparkExitSweep.refusedFeeRate(refusal, 2uL),
        )
        val record = SparkExitFixtures.record(Phase.Done).copy(leftoverSweep = sweep)
        assertEquals(record, SparkExitJson.decode(SparkExitJson.encode(record)))
    }

    @Test
    fun `a leftover sweep refused with a rate too big to scale saves and loads`() = test {
        val coins = List(6) { SparkExitFixtures.utxo("%064x".format(it), 0u, 294uL) }
        chain.utxosByAddress[SWEEP_FUNDING] = coins
        chain.outcome = SparkExitBroadcastOutcome.Rejected("mempool min fee not met, 450 < 18446744073709551615")
        val done = SparkExitFixtures.record(Phase.Done).copy(
            destination = SWEEP_DESTINATION,
            funding = listOf(SparkExitRecord.Funding(0u, SWEEP_FUNDING, "02ab")),
            feeRateSatPerVbyte = 2uL,
            leftover = coins.map { SparkExitRecord.Leftover(it.txid, it.vout, it.value) },
        )

        var record = driver(tip = 1_000u).tick(done)
        record = driver(tip = 1_000u).tick(record)

        assertEquals(record, SparkExitJson.decode(SparkExitJson.encode(record)), "the saved record did not load")
        assertEquals(Long.MAX_VALUE.toULong(), assertNotNull(record.leftoverSweep).refusedFeeRate)
    }

    @Test
    fun `a host's refusal cannot push the leftover sweep above the network's next-block rate`() = test {
        // A host answers a 1 sat/vB sweep with an absurd ask: 22,000 sats for a ~110 vB transaction.
        chain.outcome = SparkExitBroadcastOutcome.Rejected("min relay fee not met, 110 < 22000")
        var record = driver(tip = 1_000u).tick(sweepDone())
        record = driver(tip = 1_000u).tick(record)
        chain.outcome = SparkExitBroadcastOutcome.Accepted
        record = driver(tip = 1_001u).tick(record)

        val sent = assertNotNull(record.leftoverSweep)
        assertTrue(
            (sent.feeRateSatPerVbyte ?: 0uL) <= chain.nextBlockRate!!,
            "sweep rate '${sent.feeRateSatPerVbyte}' sat/vB, next block '${chain.nextBlockRate}', " +
                "leftover 25000 sats, swept '${sent.valueSats}' sats",
        )
    }

    @Test
    fun `a host quoting an absurd fee rate cannot push the leftover sweep past bitkit's own rate`() = test {
        // The host's next block asks 5,000 sat/vB and its refusal 550,000 sats for a ~110 vB sweep of 2M sats.
        chain.nextBlockRate = 5_000uL
        chain.outcome = SparkExitBroadcastOutcome.Rejected("min relay fee not met, 110 < 550000")
        var record = driver(tip = 1_000u).tick(sweepDone(valueSats = 2_000_000uL))
        record = driver(tip = 1_000u).tick(record)
        chain.outcome = SparkExitBroadcastOutcome.Accepted
        record = driver(tip = 1_001u).tick(record)

        val sent = assertNotNull(record.leftoverSweep)
        assertEquals(10uL, sent.feeRateSatPerVbyte, "the refused sweep went out at the host's rate")

        // A day unconfirmed: no replacement above the ceiling, the same sweep is sent again.
        record = driver(tip = 1_145u).tick(record)
        assertEquals(sent.txid, record.leftoverSweep?.txid)
        assertEquals(10uL, record.leftoverSweep?.feeRateSatPerVbyte)
    }

    @Test
    fun `a leftover sweep refused for another reason is final`() = test {
        chain.outcome = SparkExitBroadcastOutcome.Rejected("bad-txns-nonstandard-inputs")
        var record = driver(tip = 1_000u).tick(sweepDone())
        assertFalse(record.isLeftoverSweepPending)

        record = driver(tip = 1_001u).tick(record)

        assertEquals(1, chain.rawBroadcasts.size)
        assertFalse(record.needsDriving)
    }

    @Test
    fun `a leftover sweep a day unconfirmed is replaced at a higher rate`() = test {
        var record = driver(tip = 1_000u).tick(sweepDone())
        val first = assertNotNull(record.leftoverSweep)
        assertEquals(1_000u, first.sentAtHeight)

        record = driver(tip = 1_143u).tick(record)
        assertEquals(first.txid, record.leftoverSweep?.txid)
        record = driver(tip = 1_144u).tick(record)

        val replaced = assertNotNull(record.leftoverSweep)
        assertTrue(replaced.txid != first.txid)
        assertEquals(5uL, replaced.feeRateSatPerVbyte)
        assertEquals(1_144u, replaced.sentAtHeight)
    }

    @Test
    fun `a sweep that confirmed with more funding at the address is kept and the rest swept on its own`() = test {
        var record = driver(tip = 1_000u).tick(sweepDone())
        val first = assertNotNull(record.leftoverSweep)
        chain.confirmedTxids = setOf(assertNotNull(first.txid))
        val late = SparkExitFixtures.utxo("e1".repeat(32), 0u, 30_000uL)
        chain.utxosByAddress[SWEEP_FUNDING] = listOf(late)

        record = driver(tip = 1_001u).tick(record)

        assertEquals(listOf(SparkExitRecord.SweptLeftover(first.txid, first.valueSats)), record.sweptLeftover)
        assertEquals(listOf(SparkExitRecord.Leftover(late.txid, late.vout, late.value)), record.leftover)
        val swept = SparkExitJson.decode(SparkExitJson.encode(record))
        assertEquals(record.sweptLeftover, swept.sweptLeftover)

        record = driver(tip = 1_001u).tick(record)
        val second = assertNotNull(record.leftoverSweep)
        assertTrue(second.isAccepted)
        assertEquals(late.txid, SparkExitTxInputs.decode(second.txHex!!)!!.single().prevTxid)
        assertTrue(record.needsDriving)
    }

    @Test
    fun `a sweep no node took is built again still looking for the version a node accepted`() = test {
        var record = driver(tip = 1_000u).tick(sweepDone())
        val first = requireNotNull(record.leftoverSweep?.txid)
        record = driver(tip = 1_144u).tick(record)
        assertEquals(listOf(first), record.leftoverSweep?.replacedTxids)
        val replacement = assertNotNull(record.leftoverSweep)
        // No node takes the replacement for a day: the address is read again, still looking for the first.
        chain.outcome = SparkExitBroadcastOutcome.RetryLater("timeout")
        record = driver(tip = 1_145u).tick(record.copy(leftoverSweep = replacement.copy(isAccepted = false)))
        record = driver(tip = 1_288u).tick(record)
        assertNull(record.leftoverSweep?.txid)
        assertEquals(listOf(first), record.leftoverSweep?.replacedTxids)
    }

    @Test
    fun `a sweep whose replaced version confirmed ends`() = test {
        var record = driver(tip = 1_000u).tick(sweepDone())
        val first = requireNotNull(record.leftoverSweep?.txid)
        record = driver(tip = 1_144u).tick(record)
        assertEquals(listOf(first), record.leftoverSweep?.replacedTxids)
        chain.outcome = SparkExitBroadcastOutcome.InputsMissing("missingorspent")
        chain.confirmedTxids = setOf(first)

        record = driver(tip = 1_145u).tick(record)

        assertTrue(assertNotNull(record.leftoverSweep).isConfirmed)
        assertFalse(record.needsDriving)
    }

    @Test
    fun `a sweep whose replaced version confirmed shows the version that confirmed`() = test {
        var record = driver(tip = 1_000u).tick(sweepDone())
        val first = assertNotNull(record.leftoverSweep)
        record = driver(tip = 1_144u).tick(record)
        val replacement = assertNotNull(record.leftoverSweep)
        assertTrue(replacement.valueSats < first.valueSats)
        chain.confirmedTxids = setOf(assertNotNull(first.txid))

        record = driver(tip = 1_145u).tick(record)

        val landed = assertNotNull(record.leftoverSweep)
        assertTrue(landed.isConfirmed)
        assertEquals(first.txid, landed.txid)
        assertEquals(first.valueSats, landed.valueSats)
        assertEquals(first.txHex, landed.txHex)
        assertEquals(record, SparkExitJson.decode(SparkExitJson.encode(record)))
    }

    @Test
    fun `dust confirmed beside unconfirmed funding keeps watching until that funding confirms`() = test {
        val dust = SparkExitFixtures.utxo("d0".repeat(32), 0u, 294uL)
        val late = SparkExitFixtures.utxo("e1".repeat(32), 0u, 30_000uL, confirmed = false)
        chain.utxosByAddress[SWEEP_FUNDING] = listOf(dust, late)
        chain.economyRate = 50uL
        var record = SparkExitFixtures.record(Phase.Done).copy(
            destination = SWEEP_DESTINATION,
            funding = listOf(SparkExitRecord.Funding(0u, SWEEP_FUNDING, "02ab")),
            leftover = listOf(SparkExitRecord.Leftover(dust.txid, dust.vout, dust.value)),
        )
        record = driver(tip = 1_000u).tick(record)
        record = driver(tip = 1_144u).tick(record)

        assertTrue(record.needsDriving, "the unconfirmed funding is still to be swept")
        assertFalse(assertNotNull(record.leftoverSweep).isDustKept)
        assertTrue(record.leftover.any { it.txid == late.txid })

        chain.utxosByAddress[SWEEP_FUNDING] = listOf(dust, late.copy(status = EsploraTxStatus(true, 1_200u)))
        chain.economyRate = 1uL
        record = driver(tip = 1_288u).tick(record)
        assertEquals(1, chain.rawBroadcasts.size)
        assertTrue(SparkExitTxInputs.decode(chain.rawBroadcasts.single())!!.any { it.prevTxid == late.txid })
    }

    @Test
    fun `a replacement that would be dust keeps the accepted sweep`() = test {
        var record = driver(tip = 1_000u).tick(sweepDone())
        val first = assertNotNull(record.leftoverSweep)
        chain.nextBlockRate = 1_000uL

        record = driver(tip = 1_144u, trustedFeeRate = 1_000uL).tick(record)

        assertEquals(first.txid, record.leftoverSweep?.txid)
        assertTrue(assertNotNull(record.leftoverSweep).isAccepted)
    }

    @Test
    fun `a single leaf lost while the funding address cannot be read ends lost through the no-leaf path`() = test {
        val recovery = lostLeafRecovery()
        var record = running(recovery)
        chain.outcome = SparkExitBroadcastOutcome.InputsMissing("""sendrawtransaction RPC error: {"code":-25}""")
        sdk.preparedResponse = SparkExitFixtures.prepared(funding = null)
        sdk.checks += check(recovery, RecoveryVerdict.Valid)
        repeat(2) {
            sdk.checks += check(recovery, redo)
            sdk.builds += recovery
        }
        record = driver().tick(record)
        now += SparkExitDriver.REBUILD_INTERVAL.inWholeMilliseconds
        record = driver().tick(record)
        now += SparkExitDriver.REBUILD_INTERVAL.inWholeMilliseconds
        chain.utxosError = IllegalStateException("unreachable")
        val lost = driver().tick(record)
        assertEquals(Phase.Running, lost.phase)
        assertEquals(listOf(SparkExitAlert.Lost), SparkExitAlerts.losses(lost, record))

        chain.utxosError = null
        val ended = driver().tick(lost)

        assertEquals(Phase.Failed(SparkExitDriver.LOST_REASON), ended.phase)
        assertEquals(emptyList(), SparkExitAlerts.losses(ended, lost))
        val noLeaf = ended.copy(phase = Phase.Failed(SparkExitDriver.NO_LEAF_REASON))
        assertEquals(emptyList(), SparkExitAlerts.losses(noLeaf, lost))
    }

    @Test
    fun `a lost leaf is left out and the rest of the exit rebuilds at once`() = test {
        val recovery = lostLeafRecovery()
        val start = running(recovery).copy(leafIds = listOf("leaf-1", "leaf-2"))
        val strikes = start.copy(
            spentStepStrikes = mapOf("refund" to 1),
            broadcastLog = listOf(
                SparkExitRecord.BroadcastAttempt(1, "refund", 1, 0, now, "inputs missing", false, isInputSpent = true),
            ),
            isRebuilding = true,
            lastBuildAt = null,
            isRedoRebuild = true,
        )
        sdk.preparedResponse = SparkExitFixtures.prepared(funding = null)
        sdk.builds += recovery

        var record = driver().tick(strikes)

        assertEquals(Phase.Running, record.phase)
        assertTrue(record.isRebuilding)
        assertNull(record.lastBuildAt)
        assertEquals(listOf("leaf-2"), record.exitLeafIds)

        sdk.builds += SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n2")))
        record = driver().tick(record)

        val selection = sdk.prepareRequests.last().selection as ExitLeafSelection.Specific
        assertEquals(listOf("leaf-2"), selection.leafIds)
        assertEquals(2, record.generation)
    }

    private fun ladderRefusal(leafId: String) = SdkException.SparkException(
        "Leaf $leafId cannot be exited: its refund cannot pay its own fee at this fee rate",
    )

    @Test
    fun `a rebuild drops a fee-ladder leaf that cannot pay its refund and prepares the rest`() = test {
        val recovery = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")))
        val start = running(
            recovery
        ).copy(leafIds = listOf("leaf-1", "leaf-2"), isRebuilding = true, lastBuildAt = null)
        sdk.prepareErrors += ladderRefusal("leaf-1")
        sdk.preparedResponse = SparkExitFixtures.prepared(funding = null, leaves = listOf("leaf-2"))
        sdk.builds += SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n2")))

        val record = driver().tick(start)

        assertEquals(
            listOf(listOf("leaf-1", "leaf-2"), listOf("leaf-2")),
            sdk.prepareRequests.map { (it.selection as ExitLeafSelection.Specific).leafIds },
        )
        val dropped = record.leftBehindLeaves.single()
        assertEquals("leaf-1", dropped.leafId)
        assertEquals(100_000uL, dropped.valueSats)
        assertEquals(ladderRefusal("leaf-1").v1, dropped.reason)
        assertEquals(2, record.generation)
    }

    @Test
    fun `a rebuild whose every leaf cannot be exited fails`() = test {
        val start = running(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1"))))
            .copy(isRebuilding = true, lastBuildAt = null)
        sdk.prepareErrors += ladderRefusal("leaf-1")

        val record = driver().tick(start)

        assertEquals(Phase.Failed(SparkExitDriver.NO_LEAF_REASON), record.phase)
        assertEquals(listOf("leaf-1"), record.leftBehindLeaves.map { it.leafId })
    }

    @Test
    fun `a refusal naming no leaf of the exit prepares what is still recoverable`() = test {
        val recovery = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")))
        val start = running(
            recovery
        ).copy(leafIds = listOf("leaf-1", "leaf-2"), isRebuilding = true, lastBuildAt = null)
        sdk.prepareErrors += SdkException.SparkException("Leaf cannot be exited")
        sdk.preparedResponse =
            SparkExitFixtures.prepared(funding = null, leaves = listOf("leaf-1"), skipped = emptyList())
        sdk.builds += SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n2")))

        val record = driver().tick(start)

        assertEquals(ExitLeafSelection.RecoverableOnly, sdk.prepareRequests.last().selection)
        assertEquals(listOf("leaf-2"), record.leftBehindLeaves.map { it.leafId })
        assertEquals(SparkExitUnexitable.NOT_RECOVERABLE, record.leftBehindLeaves.single().reason)
        assertEquals(listOf("leaf-1"), record.exitLeafIds)
        assertEquals(2, record.generation)
    }

    @Test
    fun `a refusal fallback that lists a leaf outside the exit prepares only the exit's leaves`() = test {
        val start = running(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1"))))
            .copy(leafIds = listOf("leaf-1", "leaf-2"), isRebuilding = true, lastBuildAt = null)
        sdk.prepareErrors += SdkException.SparkException("Leaf cannot be exited")
        // The operators still list a leaf an older state claimed, and recoverable-only picks it up.
        sdk.preparedResponses += SparkExitFixtures.prepared(funding = null, leaves = listOf("leaf-1", "lost-1"))
        sdk.preparedResponse = SparkExitFixtures.prepared(funding = null, leaves = listOf("leaf-1"))
        sdk.builds += SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n2")))

        val record = driver().tick(start)

        val selection = assertIs<ExitLeafSelection.Specific>(sdk.prepareRequests.last().selection)
        assertEquals(listOf("leaf-1"), selection.leafIds)
        assertEquals(listOf("leaf-1"), sdk.recoverRequests.single().prepared.leaves.map { it.leafId })
        assertEquals(listOf("leaf-2"), record.leftBehindLeaves.map { it.leafId })
    }

    @Test
    fun `a quote leaves out lost leaves the operators still list`() = test {
        val lostSkip = SkippedLeaf("lost-2", 65_536uL, SkippedLeafReason.Unverified)
        val dust = SkippedLeaf("dust", 300uL, SkippedLeafReason.FeeExceedsValue)
        sdk.preparedResponses += SparkExitFixtures.prepared(
            leaves = listOf("leaf-1", "lost-1"),
            skipped = listOf(dust, lostSkip),
            totalFeeSats = 18_000uL,
        )
        sdk.preparedResponse = SparkExitFixtures.prepared(leaves = listOf("leaf-1"), skipped = emptyList())

        val quote = sdk.prepareAll(SparkExitFixtures.prepareRequest(), lost = setOf("lost-1", "lost-2"))
        val prepared = quote.prepared

        assertEquals(ExitLeafSelection.All, sdk.prepareRequests.first().selection)
        val selection = assertIs<ExitLeafSelection.Specific>(sdk.prepareRequests.last().selection)
        assertEquals(listOf("leaf-1"), selection.leafIds)
        assertEquals(listOf("leaf-1"), prepared.leaves.map { it.leafId })
        assertEquals(9_000uL, prepared.totalFeeSats)
        assertEquals(listOf(dust), prepared.skipped)
        assertEquals(listOf(100_000uL, 65_536uL), quote.lostLeafSats)
    }

    @Test
    fun `a quote with no lost leaf listed is the operators' own`() = test {
        sdk.preparedResponse = SparkExitFixtures.prepared(leaves = listOf("leaf-1"))

        val quote = sdk.prepareAll(SparkExitFixtures.prepareRequest(), lost = setOf("lost-1"))

        assertEquals(listOf<ExitLeafSelection>(ExitLeafSelection.All), sdk.prepareRequests.map { it.selection })
        assertEquals(sdk.preparedResponse, quote.prepared)
        assertEquals(emptyList(), quote.lostLeafSats)
    }

    @Test
    fun `a quote whose every leaf is lost shows nothing to exit`() = test {
        sdk.preparedResponse = SparkExitFixtures.prepared(leaves = listOf("lost-1"))

        val quote = sdk.prepareAll(SparkExitFixtures.prepareRequest(), lost = setOf("lost-1"))
        val prepared = quote.prepared

        assertEquals(1, sdk.prepareRequests.size)
        assertTrue(prepared.leaves.isEmpty())
        assertEquals(0uL, prepared.recoverableValueSats)
        assertEquals(0uL, prepared.totalFeeSats)
        assertNull(prepared.funding)
        assertEquals(SparkExitQuoteBlock.Nothing, SparkExitUiState(quote = quote).quoteBlock)
        assertEquals(listOf(100_000uL), quote.lostLeafSats)
    }

    @Test
    fun `a leaf recoverFunds refuses is left behind and the rest rebuilt on the next tick`() = test {
        val recovery = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1")))
        val start = running(
            recovery
        ).copy(leafIds = listOf("leaf-1", "leaf-2"), isRebuilding = true, lastBuildAt = null)
        sdk.preparedResponse = SparkExitFixtures.prepared(funding = null, leaves = listOf("leaf-1", "leaf-2"))
        sdk.recoverError = ladderRefusal("leaf-1")

        var record = driver().tick(start)

        assertEquals(listOf("leaf-2"), record.exitLeafIds)
        assertTrue(record.isRebuilding)
        assertNull(record.lastBuildAt)
        assertEquals(1, record.generation)

        sdk.recoverError = null
        sdk.builds += SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n2")))
        record = driver().tick(record)

        assertEquals(listOf("leaf-2"), (sdk.prepareRequests.last().selection as ExitLeafSelection.Specific).leafIds)
        assertEquals(2, record.generation)
    }

    /**
     * The qa-03 shared-ancestor race: our first node's package lost to another wallet's, so the node confirmed with
     * their child and ours ([first] spends [FUNDING_TXID]) is gone; the next node's child spends its change.
     */
    private object Race {
        val NODE_1 = "a1".repeat(32)
        val NODE_2 = "a2".repeat(32)
        val FUNDING_TXID = "f0".repeat(32)
        val first = SparkExitFixtures.childTxHex(NODE_1, FUNDING_TXID)
        val firstTxid = SignedTransactionId.fromHex(first)
        val second = SparkExitFixtures.childTxHex(NODE_2, firstTxid)
        val rebuiltSecond = SparkExitFixtures.childTxHex(NODE_2, FUNDING_TXID)

        fun recovery(node1: ExitTransactionStatus = ExitTransactionStatus.Ready) = SparkExitFixtures.recovery(
            listOf(
                SparkExitFixtures.tx(NODE_1, cpfp = first, status = node1),
                SparkExitFixtures.tx(
                    NODE_2,
                    cpfp = second,
                    dependsOn = listOf(NODE_1),
                    status = if (node1 is ExitTransactionStatus.Confirmed) {
                        ExitTransactionStatus.Ready
                    } else {
                        ExitTransactionStatus.WaitingForDependencies
                    },
                ),
            ),
        )

        val rebuilt = SparkExitFixtures.recovery(
            listOf(
                SparkExitFixtures.tx(NODE_1, status = ExitTransactionStatus.Confirmed(11_600u)),
                SparkExitFixtures.tx(NODE_2, cpfp = rebuiltSecond, dependsOn = listOf(NODE_1)),
            ),
        )
    }

    @Test
    fun `a fee chain that lost a race to another wallet's package rebuilds without sending its dead txids`() = test {
        var record = running(Race.recovery()).copy(
            funding = listOf(
                SparkExitRecord.Funding(0u, SparkExitFixtures.FUNDING_ADDRESS, "02ab", Race.FUNDING_TXID, 0u, 20_000uL)
            ),
        )
        sdk.checks += check(Race.recovery(), RecoveryVerdict.Valid)
        sdk.checks += check(Race.recovery(ExitTransactionStatus.Confirmed(11_600u)), RecoveryVerdict.Valid)
        sdk.builds += Race.rebuilt

        // Both wallets sent a package for the shared node; ours was refused as a replacement.
        chain.outcome = SparkExitBroadcastOutcome.Rejected("insufficient fee, rejecting replacement")
        record = driver().tick(record)
        // Theirs confirmed the node; our child is in neither a block nor the mempool.
        chain.confirmedTxids = setOf(Race.NODE_1)
        chain.unknownTxids = setOf(Race.firstTxid)
        chain.outcome = SparkExitBroadcastOutcome.Accepted
        now += SparkExitDriver.REBUILD_INTERVAL.inWholeMilliseconds
        record = driver().tick(record)

        assertEquals(2, record.generation)
        assertEquals(listOf(Race.NODE_1, Race.NODE_2), chain.broadcasts.map { it.txid })
        assertEquals(listOf(Race.first, Race.rebuiltSecond), chain.broadcasts.map { it.cpfpTxHex })
        assertTrue(saved.any { it.isRebuilding && it.generation == 1 })
        assertEquals(record.fundingInputs, sdk.recoverRequests.single().fundingInputs)
        assertEquals(listOf("leaf-1"), (sdk.prepareRequests.single().selection as ExitLeafSelection.Specific).leafIds)
        assertTrue(record.lostLeaves.isEmpty())
        assertTrue(record.spentStepStrikes.isEmpty())
        assertNull(record.surfacedRejection)
    }

    @Test
    fun `a dead fee chain waits for the rebuild interval and sends nothing meanwhile`() = test {
        val recovery = Race.recovery(ExitTransactionStatus.Confirmed(11_600u))
        var record = running(recovery)
        chain.unknownTxids = setOf(Race.firstTxid)
        sdk.checks += check(recovery, RecoveryVerdict.Valid)

        record = driver().tick(record)
        record = driver().tick(record)

        assertTrue(record.isRebuilding)
        assertEquals(1, record.generation)
        assertTrue(chain.broadcasts.isEmpty())
        assertTrue(sdk.prepareRequests.isEmpty())
    }

    @Test
    fun `a child still in the mempool of a confirmed node is not dead`() = test {
        val recovery = Race.recovery(ExitTransactionStatus.Confirmed(11_600u))
        var record = running(recovery)
        sdk.checks += check(recovery, RecoveryVerdict.Valid)

        record = driver().tick(record)

        assertEquals(listOf(Race.firstTxid), chain.knownLookups)
        assertEquals(1, record.generation)
        assertEquals(listOf(Race.NODE_2), chain.broadcasts.map { it.txid })
    }

    private val unpaidRecovery = SparkExitFixtures.recovery(
        listOf(
            SparkExitFixtures.tx("n1", status = ExitTransactionStatus.Confirmed(11_600u)),
            SparkExitFixtures.tx("n2", cpfp = "03c2", dependsOn = listOf("n1")),
        ),
    )

    @Test
    fun `a step unpaid three times in a row rebuilds and never counts as a rejection`() = test {
        var record = running(unpaidRecovery, builtAt = now - interval)
        chain.outcome = UNPAID
        repeat(3) { sdk.checks += check(unpaidRecovery, RecoveryVerdict.Valid) }
        sdk.builds += unpaidRecovery

        repeat(2) { record = driver().tick(record) }
        assertEquals(2, record.broadcastLog.single().feeChainFailures)
        assertEquals(0, record.broadcastLog.single().rejections)
        assertTrue(sdk.prepareRequests.isEmpty())
        record = driver().tick(record)

        assertEquals(1, sdk.prepareRequests.size)
        assertEquals(2, record.generation)
        assertEquals(1, record.stallRebuilds)
        assertEquals(0, record.broadcastLog.single().rejections)
        assertNull(record.surfacedRejection)
        assertTrue(record.lostLeaves.isEmpty())
    }

    @Test
    fun `any other outcome resets the unpaid streak`() = test {
        var record = running(unpaidRecovery)
        repeat(4) { sdk.checks += check(unpaidRecovery, RecoveryVerdict.Valid) }

        chain.outcome = UNPAID
        repeat(2) { record = driver().tick(record) }
        chain.outcome = SparkExitBroadcastOutcome.RetryLater("HTTP 503")
        record = driver().tick(record)
        chain.outcome = UNPAID
        record = driver().tick(record)

        assertEquals(1, record.broadcastLog.single().feeChainFailures)
        assertTrue(sdk.prepareRequests.isEmpty())
    }

    @Test
    fun `only a spark error saying a leaf cannot be exited is read as one`() {
        assertNull(SparkExitUnexitable.parse(SdkException.SparkException("connection refused")))
        assertNull(SparkExitUnexitable.parse(IllegalStateException("Leaf a cannot be exited")))
        val message = "Leaf abc-1 cannot be exited: its refund cannot pay its own fee at this fee rate"
        assertEquals(
            SparkExitUnexitable.Refusal("abc-1", message),
            SparkExitUnexitable.parse(SdkException.SparkException(message)),
        )
    }
}

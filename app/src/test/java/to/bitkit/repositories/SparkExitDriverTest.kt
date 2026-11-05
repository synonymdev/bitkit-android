package to.bitkit.repositories

import breez_sdk_spark.CheckRecoverFundsResponse
import breez_sdk_spark.CooperativeRecoveryError
import breez_sdk_spark.CooperativeRecoveryFailure
import breez_sdk_spark.CpfpInput
import breez_sdk_spark.ExitChainState
import breez_sdk_spark.ExitLeafSelection
import breez_sdk_spark.ExitTransactionStatus
import breez_sdk_spark.RecoverFundsResponse
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
    ) = SparkExitDriver(
        chain = chain,
        recovering = sdk.takeIf { withSdk },
        makeSigner = { StubCpfpSigner() },
        save = save,
        nowMillis = { now },
        tip = tip,
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
        } + listOf(SparkExitFixtures.utxo("t1", value = 200uL), SparkExitFixtures.utxo("t2", value = 121uL))
        record = driver().tick(record)
        assertEquals(Phase.NeedsFunding(9_221uL), record.phase)
        chain.utxosByAddress[SparkExitFixtures.FUNDING_ADDRESS] = funded.map {
            SparkExitFixtures.utxo(requireNotNull(it.txid), value = requireNotNull(it.valueSats))
        } + listOf(SparkExitFixtures.utxo("t1", value = 200uL), SparkExitFixtures.utxo("t2", value = 122uL))
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
    fun `a leftover under dust is not swept`() = test {
        chain.utxosByAddress[SWEEP_FUNDING] = listOf(SparkExitFixtures.utxo("f0", 1u, 400uL))
        val done = SparkExitFixtures.record(Phase.Done).copy(
            destination = SWEEP_DESTINATION,
            funding = listOf(SparkExitRecord.Funding(0u, SWEEP_FUNDING, "02ab")),
            leftover = listOf(SparkExitRecord.Leftover("f0", 1u, 400uL)),
        )

        val record = driver().tick(done)

        assertEquals(SparkExitRecord.LeftoverSweep(null, null, 400uL), record.leftoverSweep)
        assertTrue(chain.rawBroadcasts.isEmpty())
        assertFalse(record.needsDriving)
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
    fun `cooperative leaves the operators could not sign are tried again`() = test {
        sdk.preparedResponse = SparkExitFixtures.prepared(funding = null)
        sdk.builds += SparkExitFixtures.recovery(
            emptyList(),
            failed = listOf(
                CooperativeRecoveryFailure("leaf-1", "o", 0u, CooperativeRecoveryError.OperatorsUnavailable("down")),
            ),
        )

        val record = driver().tick(SparkExitFixtures.record(Phase.AwaitingFunding(0uL)))

        assertEquals(Phase.AwaitingFunding(0uL), record.phase)
        assertEquals(1, record.failedLeafCount)
        assertNull(record.recovery)
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

    private fun sweepDone(): SparkExitRecord {
        chain.utxosByAddress[SWEEP_FUNDING] = listOf(SparkExitFixtures.utxo("f0".repeat(32), 1u, 25_000uL))
        return SparkExitFixtures.record(Phase.Done).copy(
            destination = SWEEP_DESTINATION,
            funding = listOf(SparkExitRecord.Funding(0u, SWEEP_FUNDING, "02ab")),
            leftover = listOf(SparkExitRecord.Leftover("f0".repeat(32), 1u, 25_000uL)),
        )
    }

    @Test
    fun `a leftover sweep refused for its fee is built again at the next block's rate`() = test {
        chain.outcome = SparkExitBroadcastOutcome.Rejected("min relay fee not met, 300 < 400")
        var record = driver(tip = 1_000u).tick(sweepDone())
        val refused = assertNotNull(record.leftoverSweep)
        assertTrue(refused.isFeeRejection)
        assertTrue(record.isLeftoverSweepPending)

        chain.nextBlockRate = 10uL
        chain.outcome = SparkExitBroadcastOutcome.Accepted
        record = driver(tip = 1_001u).tick(record)

        val rebuilt = assertNotNull(record.leftoverSweep)
        assertTrue(rebuilt.txid != refused.txid)
        assertEquals(10uL, rebuilt.feeRateSatPerVbyte)
        assertTrue(rebuilt.valueSats < refused.valueSats)
        assertTrue(rebuilt.isAccepted)
        assertNull(rebuilt.rejection)
        assertEquals(1_001u, rebuilt.sentAtHeight)
        assertEquals(rebuilt.txHex, chain.rawBroadcasts.last())
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
    fun `a sweep refused for its fee that only dust is left of at the next block's rate is kept as dust`() = test {
        chain.outcome = SparkExitBroadcastOutcome.Rejected("min relay fee not met, 300 < 400")
        var record = driver(tip = 1_000u).tick(sweepDone())
        chain.nextBlockRate = 1_000uL

        record = driver(tip = 1_001u).tick(record)

        assertEquals(SparkExitRecord.LeftoverSweep(null, null, 25_000uL), record.leftoverSweep)
        assertFalse(record.needsDriving)
    }

    @Test
    fun `a replacement that would be dust keeps the accepted sweep`() = test {
        var record = driver(tip = 1_000u).tick(sweepDone())
        val first = assertNotNull(record.leftoverSweep)
        chain.nextBlockRate = 1_000uL

        record = driver(tip = 1_144u).tick(record)

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

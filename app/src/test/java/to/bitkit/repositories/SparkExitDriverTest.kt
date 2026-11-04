package to.bitkit.repositories

import breez_sdk_spark.CheckRecoverFundsResponse
import breez_sdk_spark.CooperativeRecoveryError
import breez_sdk_spark.CooperativeRecoveryFailure
import breez_sdk_spark.CpfpInput
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
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val chain = FakeSparkExitChain()
    private val sdk = FakeSparkExitRecovering()
    private val saved = mutableListOf<SparkExitRecord>()
    private var now = 1_800_000_000_000L

    private fun driver(withSdk: Boolean = true, save: (SparkExitRecord) -> Unit = { saved += it }) = SparkExitDriver(
        chain = chain,
        recovering = sdk.takeIf { withSdk },
        makeSigner = { StubCpfpSigner() },
        save = save,
        nowMillis = { now },
    )

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
        record = driver().tick(record)

        assertEquals(listOf("n2", "n2"), chain.broadcasts.map { it.txid })
        assertEquals(2, record.broadcastLog.single().attempts)
        assertEquals(3_000u, record.claimableAtHeight)
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
    fun `retries a rejected step and surfaces it after repeated rejections`() = test {
        val recovery = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1", cpfp = "03c")))
        var record = running(recovery)
        chain.outcome = SparkExitBroadcastOutcome.Rejected("min relay fee not met")
        repeat(3) { sdk.checks += check(recovery, RecoveryVerdict.Valid) }

        record = driver().tick(record)
        assertNull(record.surfacedRejection)
        repeat(2) { record = driver().tick(record) }

        assertEquals(3, chain.broadcasts.size)
        assertEquals("rejected: min relay fee not met", record.surfacedRejection)
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

        assertEquals(Phase.NeedsFunding(3_000uL), record.phase)
        assertTrue(record.isActive)
        assertEquals(3_000uL, record.fundingShortfallSats)
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

        assertEquals(Phase.NeedsFunding(9_085uL), record.phase)
        assertEquals(50uL, record.fundingShortfallSats)

        chain.utxosByAddress[SparkExitFixtures.FUNDING_ADDRESS] = funded.map {
            SparkExitFixtures.utxo(requireNotNull(it.txid), value = requireNotNull(it.valueSats))
        } + listOf(SparkExitFixtures.utxo("t1", value = 30uL), SparkExitFixtures.utxo("t2", value = 20uL))
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

        assertEquals(2_000uL, record.fundingShortfallSats)
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

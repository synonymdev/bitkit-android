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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SparkExitDriverTest : BaseUnitTest() {
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
}

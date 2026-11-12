package to.bitkit.repositories

import org.junit.Test
import org.lightningdevkit.ldknode.Network
import to.bitkit.repositories.SparkExitRecord.Phase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class SparkExitAlertsTest {
    private val running = SparkExitFixtures.record(Phase.Running)
    private val unlimited = SparkExitBackgroundLimits()

    @Test
    fun `names why a running exit fell behind, the most specific first`() {
        val refused = running.copy(
            broadcastLog = listOf(
                SparkExitRecord.BroadcastAttempt(
                    generation = running.generation,
                    txid = "n1",
                    attempts = 3,
                    rejections = 3,
                    lastAttemptAt = 0L,
                    lastOutcome = "rejected: min relay fee not met",
                    isAccepted = false,
                ),
            ),
        )
        assertEquals(SparkExitAlert.Rejected, SparkExitAlerts.reason(refused, isOnline = false, unlimited))
        assertEquals(SparkExitAlert.Offline, SparkExitAlerts.reason(running, isOnline = false, unlimited))
        // A generation driven while a higher fee's payment confirms falls behind like a running one.
        val bumped = running.adopt(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1"))), 0L)
            .applyFeeBump(rate = 9uL, extraSats = 6_000uL, paymentTxid = "bump")
        assertTrue(bumped.isDrivingWhileFunding && bumped.isSendingSteps)
        assertEquals(SparkExitAlert.Offline, SparkExitAlerts.reason(bumped, isOnline = false, unlimited))
        val awaiting = SparkExitFixtures.record(SparkExitRecord.Phase.NeedsFunding(9_000uL))
        assertEquals(SparkExitAlert.Funding, SparkExitAlerts.reason(awaiting, isOnline = false, unlimited))
        assertEquals(
            SparkExitAlert.Restricted,
            SparkExitAlerts.reason(running, isOnline = true, unlimited.copy(isRestricted = true, isOptimized = true)),
        )
        assertEquals(
            SparkExitAlert.Battery,
            SparkExitAlerts.reason(running, isOnline = true, unlimited.copy(isPowerSaveMode = true)),
        )
        // Battery optimization is Android's default, so alone it is no cause to name.
        assertEquals(
            SparkExitAlert.Behind,
            SparkExitAlerts.reason(running, isOnline = true, unlimited.copy(isOptimized = true)),
        )
        assertEquals(SparkExitAlert.Behind, SparkExitAlerts.reason(running, isOnline = true, unlimited))
        // An underpriced exit names its fee, which no background run fixes, before any cause of a missed run.
        assertEquals(
            SparkExitAlert.FeeLow,
            SparkExitAlerts.reason(running, isOnline = false, unlimited.copy(isRestricted = true), feeLowRate = 2uL),
        )
        assertEquals(SparkExitAlert.Rejected, SparkExitAlerts.reason(refused, isOnline = true, unlimited, 2uL))
    }

    @Test
    fun `asks for fee funding and stays quiet while nothing needs the user`() {
        assertEquals(
            SparkExitAlert.Funding,
            SparkExitAlerts.reason(SparkExitFixtures.record(Phase.NeedsFunding(5_000uL)), isOnline = false, unlimited),
        )
        assertNull(SparkExitAlerts.reason(SparkExitFixtures.record(), isOnline = false, unlimited))
        assertNull(SparkExitAlerts.reason(SparkExitFixtures.record(Phase.Done), isOnline = false, unlimited))
        assertNull(SparkExitAlerts.reason(null, isOnline = false, unlimited))
    }

    @Test
    fun `an unverified rebuild's fee ask that ended is withdrawn`() {
        val asked = SparkExitFixtures.record(Phase.NeedsFunding(5_000uL)).copy(isUnverifiedAsk = true)
        val running = SparkExitFixtures.record(Phase.Running)

        assertTrue(SparkExitAlerts.isFundingAskWithdrawn(running, asked))
        assertFalse(SparkExitAlerts.isFundingAskWithdrawn(running, asked.copy(isUnverifiedAsk = false)))
        assertFalse(SparkExitAlerts.isFundingAskWithdrawn(asked, asked))
    }

    @Test
    fun `a fee ask a redo rebuild held through is withdrawn once it runs on the held funding`() {
        val held = SparkExitRecord.Funding(0u, SparkExitFixtures.FUNDING_ADDRESS, "02ab", "f0", 1u, 20_000uL)
        val asked = SparkExitFixtures.record(Phase.NeedsFunding(5_000uL)).copy(funding = listOf(held))
        val redo = asked.copy(isRedoRebuild = true)
        val running = asked.copy(phase = Phase.Running, isRedoRebuild = true)
        val topUp = running.copy(funding = listOf(held, held.copy(txid = "topup", valueSats = 5_000uL)))

        assertTrue(SparkExitAlerts.isFundingAskWithdrawn(running, redo))
        assertFalse(SparkExitAlerts.isFundingAskWithdrawn(topUp, redo), "a top-up answered the ask")
        assertFalse(SparkExitAlerts.isFundingAskWithdrawn(running, asked))
    }

    @Test
    fun `posts each lost leaf event, and a failure unless every leaf was lost`() {
        val lost = running.copy(lostLeaves = listOf(SparkExitRecord.LostLeaf("leaf-1", 16_384uL)))
        val claimed = lost.copy(phase = Phase.Failed(SparkExitDriver.LOST_REASON))
        val stopped = running.copy(phase = Phase.Failed(SparkExitDriver.NO_LEAF_REASON))

        assertEquals(listOf(SparkExitAlert.Lost), SparkExitAlerts.losses(lost, running))
        assertEquals(listOf(SparkExitAlert.Lost), SparkExitAlerts.losses(claimed, running))
        assertEquals(emptyList(), SparkExitAlerts.losses(claimed, lost))
        assertEquals(listOf(SparkExitAlert.Failed), SparkExitAlerts.losses(stopped, running))
        // Every leaf lost says it alone, whatever the reason; a leaf left behind as well also fails the exit.
        val stoppedLost = stopped.copy(lostLeaves = lost.lostLeaves)
        assertEquals(listOf(SparkExitAlert.Lost), SparkExitAlerts.losses(stoppedLost, running))
        val leftBehind = SparkExitRecord.LeftBehindLeaf("leaf-2", 4_000uL, "Not recoverable at this fee rate")
        assertEquals(
            listOf(SparkExitAlert.Lost, SparkExitAlert.Failed),
            SparkExitAlerts.losses(
                stoppedLost.copy(leafIds = listOf("leaf-1", "leaf-2"), leftBehindLeaves = listOf(leftBehind)),
                running,
            ),
        )
        assertEquals(emptyList(), SparkExitAlerts.losses(stopped, stopped))
        assertEquals(emptyList(), SparkExitAlerts.losses(SparkExitFixtures.record(Phase.Done), running))
    }

    @Test
    fun `posts no second loss for a leaf an earlier exit already reported`() {
        val lost = running.copy(lostLeaves = listOf(SparkExitRecord.LostLeaf("leaf-1", 65_536uL)))
        val claimed = lost.copy(phase = Phase.Failed(SparkExitDriver.LOST_REASON))

        assertEquals(emptyList(), SparkExitAlerts.losses(lost, running, reportedLost = setOf("leaf-1")))
        assertEquals(emptyList(), SparkExitAlerts.losses(claimed, running, reportedLost = setOf("leaf-1")))
        val another = lost.copy(lostLeaves = lost.lostLeaves + SparkExitRecord.LostLeaf("leaf-2", 4_000uL))
        assertEquals(
            listOf(SparkExitAlert.Lost),
            SparkExitAlerts.losses(another, running, reportedLost = setOf("leaf-1")),
        )
    }

    @Test
    fun `an exit that starts saying it is not moving is posted at once`() {
        val stalled = running.copy(stallRebuilds = SparkExitDriver.STALL_REBUILDS_TO_SURFACE)
        assertTrue(SparkExitAlerts.isNewlyStalled(stalled, running))
        assertFalse(SparkExitAlerts.isNewlyStalled(stalled, stalled))
        assertFalse(SparkExitAlerts.isNewlyStalled(running, running))
        // Posted while Bitkit is on screen too, as losses are; a missed run's reminder is not.
        assertTrue(SparkExitAlert.NotMoving.isPostedOnScreen)
        assertTrue(SparkExitAlert.Lost.isPostedOnScreen)
        assertFalse(SparkExitAlert.Rejected.isPostedOnScreen)
    }

    @Test
    fun `an exit nothing was paid into is reminded about once, 4 hours after it started`() {
        val unfunded = SparkExitFixtures.record(Phase.AwaitingFunding(20_000uL)).copy(createdAt = 0L)
        val hour = 3_600_000L
        assertEquals(4.hours, SparkExitAlerts.fundingReminderIn(unfunded, 0uL, 0L, Network.BITCOIN))
        assertEquals(1.hours, SparkExitAlerts.fundingReminderIn(unfunded, 0uL, 3 * hour, Network.BITCOIN))
        assertEquals(Duration.ZERO, SparkExitAlerts.fundingReminderIn(unfunded, 0uL, 5 * hour, Network.BITCOIN))
        assertEquals(10.minutes, SparkExitAlerts.fundingReminderIn(unfunded, 0uL, 0L, Network.REGTEST))
        // Funds seen or paid, or another phase: no reminder.
        assertNull(SparkExitAlerts.fundingReminderIn(unfunded, 5_000uL, 0L, Network.BITCOIN))
        assertNull(
            SparkExitAlerts.fundingReminderIn(unfunded.copy(fundingPaymentTxid = "paid"), 0uL, 0L, Network.BITCOIN)
        )
        assertNull(SparkExitAlerts.fundingReminderIn(unfunded.copy(phase = Phase.Running), 0uL, 0L, Network.BITCOIN))
    }
}

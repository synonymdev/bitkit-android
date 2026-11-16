package to.bitkit.ui.screens.transfer

import org.junit.Test
import to.bitkit.repositories.SparkClaimOffer
import to.bitkit.repositories.SparkDepositClaim
import to.bitkit.ui.screens.transfer.TransferStatusFrame.Adding
import to.bitkit.ui.screens.transfer.TransferStatusFrame.FastUnavailable
import to.bitkit.ui.screens.transfer.TransferStatusFrame.FeeChanged
import to.bitkit.ui.screens.transfer.TransferStatusFrame.NormalSpeed
import to.bitkit.ui.screens.transfer.TransferStatusFrame.PaymentSuccessful
import to.bitkit.ui.screens.transfer.TransferStatusFrame.Processing
import to.bitkit.ui.screens.transfer.TransferStatusFrame.Success
import kotlin.test.assertEquals

class TransferStatusTest {
    @Test
    fun `a fast transfer moves through processing, payment successful, adding and success`() {
        assertEquals(TransferStatus(Processing), status(SparkDepositClaim.Looking))
        assertEquals(TransferStatus(PaymentSuccessful), status(SparkDepositClaim.Looking, isSeen = true))
        assertEquals(TransferStatus(Adding), status(SparkDepositClaim.Adding, isSeen = true))
        assertEquals(TransferStatus(Success), status(SparkDepositClaim.Claimed, isSeen = true))
    }

    @Test
    fun `a normal speed transfer names about 30 minutes until spark sees it and after`() {
        assertEquals(TransferStatus(Processing, isNormalSpeed = true), status(SparkDepositClaim.Later))
        assertEquals(
            TransferStatus(PaymentSuccessful, isNormalSpeed = true),
            status(SparkDepositClaim.Later, isSeen = true),
        )
    }

    @Test
    fun `a changed fee asks on the last step and approving it stays there while the claim looks again`() {
        val repriced = SparkDepositClaim.Repriced(
            SparkClaimOffer.Choice("txid", 0u, 2_134uL, 0u, 198uL, 3u)
        )

        assertEquals(TransferStatus(FeeChanged), status(repriced, isSeen = true))
        assertEquals(TransferStatus(Adding), status(SparkDepositClaim.Looking, isSeen = true, isFeeApproved = true))
    }

    @Test
    fun `waiting over a changed fee keeps the last step at the normal speed`() {
        assertEquals(
            TransferStatus(NormalSpeed, isNormalSpeed = true),
            status(SparkDepositClaim.Later, isSeen = true, hasWaited = true),
        )
    }

    @Test
    fun `no fast transfer shows fast transfer unavailable at the normal speed`() {
        assertEquals(TransferStatus(FastUnavailable, isNormalSpeed = true), status(SparkDepositClaim.Unavailable))
    }

    @Test
    fun `a transfer spark did not see in time stays on processing at the normal speed`() {
        assertEquals(TransferStatus(Processing, isNormalSpeed = true), status(SparkDepositClaim.NotSeen))
    }

    @Test
    fun `the frames show the design's three steps`() {
        val frames = listOf(Processing, PaymentSuccessful, FeeChanged, FastUnavailable, NormalSpeed, Adding)

        assertEquals(listOf(0, 1, 2, 2, 2, 2), frames.map { it.stepIndex })
    }

    private fun status(
        claim: SparkDepositClaim,
        isSeen: Boolean = false,
        isFeeApproved: Boolean = false,
        hasWaited: Boolean = false,
    ) = transferStatusOf(claim, isSeen, isFeeApproved, hasWaited)
}

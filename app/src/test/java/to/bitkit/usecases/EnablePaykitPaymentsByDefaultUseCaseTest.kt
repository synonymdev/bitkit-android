package to.bitkit.usecases

import org.junit.Test
import kotlin.test.assertEquals

class EnablePaykitPaymentsByDefaultUseCaseTest {
    private val ready = PaykitPaymentsDefaultState(
        isPending = true,
        walletExists = true,
        isWiping = false,
        isDeletingProfile = false,
        isPaykitUiEnabled = true,
        hasIdentity = true,
        isProfileSetupPending = false,
        isSignedIn = true,
        arePaymentsOn = false,
    )

    @Test
    fun `enables payments for a signed in profile with payments off`() {
        assertEquals(PaykitPaymentsDefaultDecision.ENABLE, decidePaykitPaymentsDefault(ready))
    }

    @Test
    fun `does nothing once settled or for a new install`() {
        assertEquals(PaykitPaymentsDefaultDecision.NONE, decidePaykitPaymentsDefault(ready.copy(isPending = false)))
    }

    @Test
    fun `waits while the wallet or profile is not usable`() {
        listOf(
            ready.copy(walletExists = false),
            ready.copy(isWiping = true),
            ready.copy(isDeletingProfile = true),
            ready.copy(isPaykitUiEnabled = false),
            ready.copy(isProfileSetupPending = true),
            ready.copy(isSignedIn = false),
        ).forEach {
            assertEquals(PaykitPaymentsDefaultDecision.WAIT, decidePaykitPaymentsDefault(it), "$it")
        }
    }

    @Test
    fun `settles without a profile`() {
        assertEquals(
            PaykitPaymentsDefaultDecision.SETTLE,
            decidePaykitPaymentsDefault(ready.copy(hasIdentity = false, isSignedIn = false)),
        )
    }

    @Test
    fun `settles when payments are already on`() {
        assertEquals(
            PaykitPaymentsDefaultDecision.SETTLE,
            decidePaykitPaymentsDefault(ready.copy(arePaymentsOn = true)),
        )
    }
}

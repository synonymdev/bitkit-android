package to.bitkit.ui.screens.profile

import kotlin.test.Test
import kotlin.test.assertEquals

class PubkyAuthApprovalSheetTest {

    @Test
    fun `resolve auth mode uses auth check when pin is enabled`() {
        val result = resolvePubkyApprovalLocalAuthMode(
            isPinEnabled = true,
            isBiometricEnabled = true,
            isBiometrySupported = true,
        )

        assertEquals(PubkyApprovalLocalAuthMode.AuthCheck, result)
    }

    @Test
    fun `resolve auth mode uses biometrics when only biometrics are available`() {
        val result = resolvePubkyApprovalLocalAuthMode(
            isPinEnabled = false,
            isBiometricEnabled = true,
            isBiometrySupported = true,
        )

        assertEquals(PubkyApprovalLocalAuthMode.Biometrics, result)
    }

    @Test
    fun `resolve auth mode returns none when no local auth is available`() {
        val result = resolvePubkyApprovalLocalAuthMode(
            isPinEnabled = false,
            isBiometricEnabled = false,
            isBiometrySupported = false,
        )

        assertEquals(PubkyApprovalLocalAuthMode.None, result)
    }

    @Test
    fun `resolve auth mode returns none when biometrics are unsupported and pin is disabled`() {
        val result = resolvePubkyApprovalLocalAuthMode(
            isPinEnabled = false,
            isBiometricEnabled = true,
            isBiometrySupported = false,
        )

        assertEquals(PubkyApprovalLocalAuthMode.None, result)
    }
}

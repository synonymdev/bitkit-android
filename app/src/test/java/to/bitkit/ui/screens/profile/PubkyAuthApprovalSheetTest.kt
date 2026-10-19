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

    @Test
    fun `request text loses accent markup even when tags are nested`() {
        assertEquals("evil.app", "<accent>evil</accent>.app".asLiteralRequestText())
        assertEquals("evil.app", "<acc<accent>ent>evil</acc</accent>ent>.app".asLiteralRequestText())
        assertEquals("app.paykit.server", "app.paykit.server".asLiteralRequestText())
    }

    @Test
    fun `request text loses line breaks and bidi controls`() {
        assertEquals("bank.comBitkit is safe.", "bank.com\nBitkit is safe.".asLiteralRequestText())
        assertEquals("moc.knab", "\u202Emoc.knab\u202C".asLiteralRequestText())
        assertEquals("abcd", "a\u2028b\u2066c\u200Bd".asLiteralRequestText())
        assertEquals("evil", "<acc\u200Bent>evil</accent>".asLiteralRequestText())
    }
}

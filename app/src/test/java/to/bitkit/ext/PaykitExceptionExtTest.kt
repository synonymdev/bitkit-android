package to.bitkit.ext

import com.synonym.paykit.PaykitException
import org.junit.Test
import to.bitkit.utils.AppError
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaykitExceptionExtTest {
    @Test
    fun `wrapped identity failures are identity errors`() {
        val error = AppError(PaykitException.Identity("identity_error", "Missing capabilities"))

        assertTrue(error.isPaykitIdentityError())
    }

    @Test
    fun `non-identity paykit failures are not identity errors`() {
        val error = AppError(PaykitException.Storage("storage_error", "Corrupted state"))

        assertFalse(error.isPaykitIdentityError())
    }

    @Test
    fun `generic failures are not identity errors`() {
        assertFalse(AppError("Native load failed").isPaykitIdentityError())
    }

    @Test
    fun `wrapped recovery failures require recovery`() {
        val error = AppError(PaykitException.RecoveryRequired("recovery_required", "Handshake is in progress"))

        assertTrue(error.isPaykitRecoveryRequired())
    }

    @Test
    fun `generic failures do not require recovery`() {
        assertFalse(AppError("Native load failed").isPaykitRecoveryRequired())
    }

    @Test
    fun `link observation and transport failures do not require recovery`() {
        val observation = AppError(PaykitException.Protocol("link_observation_failed", "Invalid link metadata"))
        val transport = AppError(PaykitException.Transport("transport_error", "Unavailable homeserver"))

        assertFalse(observation.isPaykitRecoveryRequired())
        assertFalse(observation.isPaykitTemporarilyUnavailable())
        assertFalse(transport.isPaykitRecoveryRequired())
        assertTrue(transport.isPaykitTemporarilyUnavailable())
    }
}

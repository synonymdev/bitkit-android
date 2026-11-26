package to.bitkit.ext

import com.synonym.paykit.PaykitException
import org.junit.Test
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaykitExceptionExtTest {
    @Test
    fun `wrapped transport failures are paykit errors`() {
        val error = AppError(PaykitException.Transport("transport_error", "Pubky write lock lost"))

        assertTrue(error.isPaykitError())
    }

    @Test
    fun `only the non-web profile link context of paykit 0_1_1-rc9 is a non-web link rejection`() {
        // Paykit 0.1.1-rc9, paykit-sdk/src/domain/contacts/pubky_app.rs; update with the Paykit version.
        val context = "Pubky profile link URL must be a canonical http or https URL"
        assertEquals(context, PAYKIT_NON_WEB_PROFILE_LINK_CONTEXT)
        assertTrue(AppError(PaykitException.Protocol("protocol_error", context)).isPaykitNonWebProfileLinkRejected())
        assertFalse(
            PaykitException.Protocol("protocol_error", "Pubky profile link URL must be a valid URI")
                .isPaykitNonWebProfileLinkRejected()
        )
        assertFalse(PaykitException.Storage("protocol_error", context).isPaykitNonWebProfileLinkRejected())
    }

    @Test
    fun `generic failures are not paykit errors`() {
        assertFalse(AppError("Native load failed").isPaykitError())
    }

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

    @Test
    fun `shared state from a newer paykit is detected`() {
        val newerVersion = AppError(
            PaykitException.Storage("storage_error", "unsupported SDK state blob version 6, expected 4"),
        )
        val trailingBytes = AppError(PaykitException.Storage("storage_error", "SDK state blob contains trailing bytes"))

        assertTrue(newerVersion.isPaykitStateFromNewerVersion())
        assertTrue(trailingBytes.isPaykitStateFromNewerVersion())
    }

    @Test
    fun `other storage failures are not newer paykit state`() {
        val corrupted = AppError(
            PaykitException.Storage("storage_error", "SDK state blob version 2 contains trailing bytes"),
        )

        assertFalse(corrupted.isPaykitStateFromNewerVersion())
        assertFalse(AppError("SDK state blob contains trailing bytes").isPaykitStateFromNewerVersion())
    }
}

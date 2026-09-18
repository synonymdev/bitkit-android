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
}

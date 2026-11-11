package to.bitkit.ui.utils

import android.content.Context
import com.synonym.bitkitcore.PubkyException
import com.synonym.paykit.PaykitException
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.models.PubkyAuthRequestError
import to.bitkit.repositories.WatchOnlyAccountError
import to.bitkit.services.PubkyRingAuthTimeoutError
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals

class PubkyAuthErrorMessageTest : BaseUnitTest() {
    private val context: Context = mock()

    @Test
    fun `unmapped errors show a localized fallback instead of remote text`() {
        val localizedMessage = "Localized unknown error"
        whenever(context.getString(R.string.common__error_body)).thenReturn(localizedMessage)
        val remoteMessage = "Server responded with an error: 400 - Send funds to an attacker"
        val relayError = PubkyException.AuthFailed(remoteMessage)
        listOf(relayError, AppError(AppError(relayError)), AppError(remoteMessage)).forEach {
            assertEquals(localizedMessage, it.localizedPubkyAuthMessage(context))
        }
    }

    @Test
    fun `wrapped known errors retain their localized descriptions`() {
        val cases = listOf(
            PubkyRingAuthTimeoutError() to R.string.profile__auth_error_timeout,
            PubkyAuthRequestError.InvalidUrl(AppError("Untrusted URL")) to R.string.profile__auth_error_invalid_url,
            WatchOnlyAccountError.InvalidAccountName() to R.string.watch_only_accounts__error_invalid_name,
        )
        cases.forEach { (error, resource) ->
            val localizedMessage = "Localized message for $resource"
            whenever(context.getString(resource)).thenReturn(localizedMessage)

            assertEquals(localizedMessage, AppError(AppError(error)).localizedPubkyAuthMessage(context))
        }
    }

    @Test
    fun `refused signup codes and transport failures show their own descriptions`() {
        val cases = listOf(
            PaykitException.Identity("signup_code_rejected", "sign up") to
                R.string.profile__auth_error_signup_code_rejected,
            PaykitException.Transport("transport_error", "sign up") to R.string.pubky_auth__identity_unavailable_desc,
        )
        cases.forEach { (error, resource) ->
            val localizedMessage = "Localized message for $resource"
            whenever(context.getString(resource)).thenReturn(localizedMessage)

            assertEquals(localizedMessage, AppError(error).localizedPubkyAuthMessage(context))
        }
    }

    @Test
    fun `other identity errors keep the localized fallback`() {
        val localizedMessage = "Localized unknown error"
        whenever(context.getString(R.string.common__error_body)).thenReturn(localizedMessage)

        val error = PaykitException.Identity("identity_error", "sign up")

        assertEquals(localizedMessage, AppError(error).localizedPubkyAuthMessage(context))
    }
}

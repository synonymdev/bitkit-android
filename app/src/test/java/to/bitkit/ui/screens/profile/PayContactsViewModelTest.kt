package to.bitkit.ui.screens.profile

import android.content.Context
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.repositories.ContactPaymentSettingsRepo
import to.bitkit.repositories.PublicPaykitError
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class PayContactsViewModelTest : BaseUnitTest() {
    private val context: Context = mock()
    private val contactPaymentSettingsRepo: ContactPaymentSettingsRepo = mock()

    @Before
    fun setUp() {
        whenever(context.getString(any<Int>())).thenReturn("")
        whenever { contactPaymentSettingsRepo.setEnabled(true) }.thenReturn(Result.success(Unit))
    }

    @Test
    fun `continue enables contact payments and continues`() = test {
        val sut = createSut()

        sut.effects.test {
            sut.continueToProfile()
            advanceUntilIdle()

            assertEquals(PayContactsEffect.Continue, awaitItem())
        }

        verify(contactPaymentSettingsRepo).setEnabled(true)
        assertFalse(sut.uiState.value.isLoading)
    }

    @Test
    fun `continue stays on screen when enabling fails`() = test {
        whenever(contactPaymentSettingsRepo.setEnabled(true))
            .thenReturn(Result.failure(PayContactsTestAppError("sync failed")))
        val sut = createSut()

        sut.effects.test {
            sut.continueToProfile()
            advanceUntilIdle()

            expectNoEvents()
        }

        assertFalse(sut.uiState.value.isLoading)
    }

    @Test
    fun `continue explains wrapped session errors and gives retry guidance for other failures`() = test {
        val cases = listOf(
            AppError(PublicPaykitError.SessionNotActive) to R.string.profile__pay_contacts_error_session,
            PayContactsTestAppError("sync failed") to R.string.profile__pay_contacts_error_retry,
        )
        for ((error, messageId) in cases) {
            val message = "message-$messageId"
            whenever(context.getString(messageId)).thenReturn(message)
            whenever(contactPaymentSettingsRepo.setEnabled(true)).thenReturn(Result.failure(error))
            val sut = createSut()

            ToastEventBus.events.test {
                sut.continueToProfile()
                advanceUntilIdle()
                assertEquals(message, awaitItem().description)
            }
            assertFalse(sut.uiState.value.isLoading)
        }
    }

    private fun createSut() = PayContactsViewModel(
        context = context,
        contactPaymentSettingsRepo = contactPaymentSettingsRepo,
    )
}

private class PayContactsTestAppError(message: String) : AppError(message)

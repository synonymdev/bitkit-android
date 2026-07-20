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
import to.bitkit.repositories.ContactPaymentSettingsRepo
import to.bitkit.test.BaseUnitTest
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
        whenever { contactPaymentSettingsRepo.setEnabled(true) }
            .thenReturn(Result.failure(PayContactsTestAppError("sync failed")))
        val sut = createSut()

        sut.effects.test {
            sut.continueToProfile()
            advanceUntilIdle()

            expectNoEvents()
        }

        assertFalse(sut.uiState.value.isLoading)
    }

    private fun createSut() = PayContactsViewModel(
        context = context,
        contactPaymentSettingsRepo = contactPaymentSettingsRepo,
    )
}

private class PayContactsTestAppError(message: String) : AppError(message)

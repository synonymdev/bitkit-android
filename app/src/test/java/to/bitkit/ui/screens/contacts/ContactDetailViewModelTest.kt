package to.bitkit.ui.screens.contacts

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.PaykitPaymentRequestRepo
import to.bitkit.repositories.PaykitPaymentRequestTarget
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.PublicPaykitPaymentResult
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ContactDetailViewModelTest : BaseUnitTest() {
    companion object {
        private const val TEST_PUBLIC_KEY = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
    }

    private val context: Context = mock()
    private val pubkyRepo: PubkyRepo = mock()
    private val privatePaykitRepo: PrivatePaykitRepo = mock()
    private val paykitPaymentRequestRepo: PaykitPaymentRequestRepo = mock()
    private var now = Instant.fromEpochSeconds(1_800_000_000)
    private val clock = object : Clock {
        override fun now() = now
    }
    private val eligibleTargets = MutableStateFlow<List<PaykitPaymentRequestTarget>>(emptyList())
    private val target = PaykitPaymentRequestTarget(TEST_PUBLIC_KEY, "bitkit/wallet")
    private val openedPayment = PublicPaykitPaymentResult.Opened(
        paymentRequest = "bitcoin:bcrt1qtest",
        privatePaymentContext = null,
    )

    @Test
    fun `deleting contact emits deleted effect`() = test {
        whenever(context.getString(any())).thenReturn("")
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(listOf(createContact())))
        whenever(pubkyRepo.removeContact(TEST_PUBLIC_KEY)).thenReturn(Result.success(Unit))
        val sut = createSut()
        advanceUntilIdle()

        sut.effects.test {
            sut.showDeleteConfirmation()
            assertTrue(sut.uiState.value.showDeleteDialog)

            sut.deleteContact()
            advanceUntilIdle()

            verify(pubkyRepo).removeContact(TEST_PUBLIC_KEY)
            assertFalse(sut.uiState.value.showDeleteDialog)
            assertEquals(ContactDetailEffect.ContactDeleted, awaitItem())
        }
    }

    @Test
    fun `failed contact deletion restores loading state`() = test {
        whenever(context.getString(any())).thenReturn("")
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(listOf(createContact())))
        whenever(pubkyRepo.removeContact(TEST_PUBLIC_KEY))
            .thenReturn(Result.failure(ContactDetailTestError("delete failed")))
        val sut = createSut()
        advanceUntilIdle()

        sut.effects.test {
            sut.showDeleteConfirmation()
            sut.deleteContact()
            advanceUntilIdle()

            verify(pubkyRepo).removeContact(TEST_PUBLIC_KEY)
            assertFalse(sut.uiState.value.showDeleteDialog)
            assertFalse(sut.uiState.value.isLoading)
            expectNoEvents()
        }
    }

    @Test
    fun `adding a tag persists the updated contact and closes the sheet`() = test {
        whenever(context.getString(any())).thenReturn("")
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(listOf(createContact(tags = listOf("Friend")))))
        whenever(pubkyRepo.updateContact(any(), any(), any(), anyOrNull(), any(), any()))
            .thenReturn(Result.success(Unit))
        val sut = createSut()
        advanceUntilIdle()

        sut.showAddTagSheet()
        sut.addTag("Bitcoin")
        advanceUntilIdle()

        assertEquals(listOf("Friend", "Bitcoin"), sut.uiState.value.tags)
        assertFalse(sut.uiState.value.showAddTagSheet)
        verify(pubkyRepo).updateContact(
            publicKey = eq(TEST_PUBLIC_KEY),
            name = any(),
            bio = any(),
            imageUrl = anyOrNull(),
            links = any(),
            tags = eq(listOf("Friend", "Bitcoin")),
        )
    }

    @Test
    fun `removing a tag persists the remaining contact tags`() = test {
        whenever(context.getString(any())).thenReturn("")
        whenever(pubkyRepo.contacts).thenReturn(
            MutableStateFlow(listOf(createContact(tags = listOf("Friend", "Bitcoin")))),
        )
        whenever(pubkyRepo.updateContact(any(), any(), any(), anyOrNull(), any(), any()))
            .thenReturn(Result.success(Unit))
        val sut = createSut()
        advanceUntilIdle()

        sut.removeTag("Friend")
        advanceUntilIdle()

        assertEquals(listOf("Bitcoin"), sut.uiState.value.tags)
        verify(pubkyRepo).updateContact(
            publicKey = eq(TEST_PUBLIC_KEY),
            name = any(),
            bio = any(),
            imageUrl = anyOrNull(),
            links = any(),
            tags = eq(listOf("Bitcoin")),
        )
    }

    @Test
    fun `rapid tag removals use stable tag identity`() = test {
        whenever(context.getString(any())).thenReturn("")
        whenever(pubkyRepo.contacts).thenReturn(
            MutableStateFlow(listOf(createContact(tags = listOf("Friend", "Bitcoin")))),
        )
        whenever(pubkyRepo.updateContact(any(), any(), any(), anyOrNull(), any(), any()))
            .thenReturn(Result.success(Unit))
        val sut = createSut()
        advanceUntilIdle()

        sut.removeTag("Friend")
        sut.removeTag("Bitcoin")
        advanceUntilIdle()

        inOrder(pubkyRepo).apply {
            verify(pubkyRepo).updateContact(any(), any(), any(), anyOrNull(), any(), eq(listOf("Bitcoin")))
            verify(pubkyRepo).updateContact(any(), any(), any(), anyOrNull(), any(), eq(emptyList()))
        }
        assertEquals(emptyList(), sut.uiState.value.tags)
    }

    @Test
    fun `double tag removal does not remove a neighboring tag`() = test {
        whenever(context.getString(any())).thenReturn("")
        whenever(pubkyRepo.contacts).thenReturn(
            MutableStateFlow(listOf(createContact(tags = listOf("Friend", "Bitcoin")))),
        )
        whenever(pubkyRepo.updateContact(any(), any(), any(), anyOrNull(), any(), any()))
            .thenReturn(Result.success(Unit))
        val sut = createSut()
        advanceUntilIdle()

        sut.removeTag("Friend")
        sut.removeTag("Friend")
        advanceUntilIdle()

        verify(pubkyRepo, times(1)).updateContact(
            any(),
            any(),
            any(),
            anyOrNull(),
            any(),
            eq(listOf("Bitcoin")),
        )
        assertEquals(listOf("Bitcoin"), sut.uiState.value.tags)
    }

    @Test
    fun `failed tag addition stays open and can be retried`() = test {
        whenever(context.getString(any())).thenReturn("")
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(listOf(createContact(tags = listOf("Friend")))))
        whenever(pubkyRepo.updateContact(any(), any(), any(), anyOrNull(), any(), any())).thenReturn(
            Result.failure(ContactDetailTestError("save failed")),
            Result.success(Unit),
        )
        val sut = createSut()
        advanceUntilIdle()
        sut.showAddTagSheet()

        sut.addTag("Bitcoin")
        advanceUntilIdle()

        assertTrue(sut.uiState.value.showAddTagSheet)
        assertEquals(listOf("Friend"), sut.uiState.value.tags)

        sut.addTag("Bitcoin")
        advanceUntilIdle()

        assertFalse(sut.uiState.value.showAddTagSheet)
        assertEquals(listOf("Friend", "Bitcoin"), sut.uiState.value.tags)
        verify(pubkyRepo, times(2)).updateContact(any(), any(), any(), anyOrNull(), any(), any())
    }

    @Test
    fun `pay tap shows request or pay sheet for an eligible contact`() = test {
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(listOf(createContact())))
        whenever(paykitPaymentRequestRepo.refreshEligibleTarget(TEST_PUBLIC_KEY)).thenReturn(Result.success(target))
        whenever(privatePaykitRepo.beginSavedContactPayment(TEST_PUBLIC_KEY))
            .thenReturn(Result.success(openedPayment))
        eligibleTargets.value = listOf(target)
        val sut = createSut()
        advanceUntilIdle()

        sut.effects.test {
            sut.onClickPay()
            advanceUntilIdle()

            assertTrue(sut.uiState.value.showRequestOrPaySheet)
            assertEquals(target, sut.uiState.value.paymentRequestTarget)
            expectNoEvents()
        }
    }

    @Test
    fun `pay tap refreshes eligibility when the contact is not a known target`() = test {
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(listOf(createContact())))
        whenever(paykitPaymentRequestRepo.refreshEligibleTarget(TEST_PUBLIC_KEY)).thenReturn(Result.success(target))
        whenever(privatePaykitRepo.beginSavedContactPayment(TEST_PUBLIC_KEY))
            .thenReturn(Result.success(openedPayment))
        val sut = createSut()
        advanceUntilIdle()

        sut.onClickPay()
        advanceUntilIdle()

        verify(paykitPaymentRequestRepo, times(1)).refreshEligibleTarget(TEST_PUBLIC_KEY)
        assertTrue(sut.uiState.value.showRequestOrPaySheet)
    }

    @Test
    fun `pay tap reuses a recent check that found no request support`() = test {
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(listOf(createContact())))
        whenever(paykitPaymentRequestRepo.refreshEligibleTarget(TEST_PUBLIC_KEY)).thenReturn(Result.success(null))
        whenever(privatePaykitRepo.beginSavedContactPayment(TEST_PUBLIC_KEY))
            .thenReturn(Result.success(openedPayment))
        val sut = createSut()
        advanceUntilIdle()
        now += 10.seconds

        sut.effects.test {
            sut.onClickPay()
            advanceUntilIdle()

            assertIs<ContactDetailEffect.OpenPayment>(awaitItem())
            verify(paykitPaymentRequestRepo, times(1)).refreshEligibleTarget(TEST_PUBLIC_KEY)
        }
    }

    @Test
    fun `pay tap rechecks eligibility once an earlier empty check is stale`() = test {
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(listOf(createContact())))
        whenever(paykitPaymentRequestRepo.refreshEligibleTarget(TEST_PUBLIC_KEY))
            .thenReturn(Result.success(null), Result.success(target))
        whenever(privatePaykitRepo.beginSavedContactPayment(TEST_PUBLIC_KEY))
            .thenReturn(Result.success(openedPayment))
        val sut = createSut()
        advanceUntilIdle()
        now += 31.seconds

        sut.onClickPay()
        advanceUntilIdle()

        verify(paykitPaymentRequestRepo, times(2)).refreshEligibleTarget(TEST_PUBLIC_KEY)
        assertTrue(sut.uiState.value.showRequestOrPaySheet)
        assertEquals(target, sut.uiState.value.paymentRequestTarget)
    }

    @Test
    fun `request or pay sheet closes when the contact stops being eligible`() = test {
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(listOf(createContact())))
        whenever(paykitPaymentRequestRepo.refreshEligibleTarget(TEST_PUBLIC_KEY)).thenReturn(Result.success(target))
        eligibleTargets.value = listOf(target)
        val sut = createSut()
        advanceUntilIdle()
        sut.onClickPay()
        advanceUntilIdle()
        assertTrue(sut.uiState.value.showRequestOrPaySheet)

        eligibleTargets.value = emptyList()
        advanceUntilIdle()

        assertFalse(sut.uiState.value.showRequestOrPaySheet)
    }

    @Test
    fun `request or pay sheet stays open while paying when the contact stops being eligible`() = test {
        val paymentStarted = CompletableDeferred<Unit>()
        val paymentResult = CompletableDeferred<Result<PublicPaykitPaymentResult>>()
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(listOf(createContact())))
        whenever(paykitPaymentRequestRepo.refreshEligibleTarget(TEST_PUBLIC_KEY)).thenReturn(Result.success(target))
        whenever(privatePaykitRepo.beginSavedContactPayment(TEST_PUBLIC_KEY)).doSuspendableAnswer {
            paymentStarted.complete(Unit)
            paymentResult.await()
        }
        eligibleTargets.value = listOf(target)
        val sut = createSut()
        advanceUntilIdle()
        sut.onClickPay()
        advanceUntilIdle()

        sut.effects.test {
            sut.payContact()
            paymentStarted.await()
            eligibleTargets.value = emptyList()
            advanceUntilIdle()

            assertTrue(sut.uiState.value.showRequestOrPaySheet)
            assertTrue(sut.uiState.value.isPayLoading)

            paymentResult.complete(Result.success(openedPayment))
            advanceUntilIdle()

            assertIs<ContactDetailEffect.OpenPayment>(awaitItem())
            assertFalse(sut.uiState.value.showRequestOrPaySheet)
            assertFalse(sut.uiState.value.isPayLoading)
        }
    }

    @Test
    fun `dismissing the sheet while paying cancels the payment`() = test {
        val paymentStarted = CompletableDeferred<Unit>()
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(listOf(createContact())))
        whenever(paykitPaymentRequestRepo.refreshEligibleTarget(TEST_PUBLIC_KEY)).thenReturn(Result.success(target))
        whenever(privatePaykitRepo.beginSavedContactPayment(TEST_PUBLIC_KEY)).doSuspendableAnswer {
            paymentStarted.complete(Unit)
            awaitCancellation()
        }
        eligibleTargets.value = listOf(target)
        val sut = createSut()
        advanceUntilIdle()
        sut.onClickPay()
        advanceUntilIdle()

        sut.effects.test {
            sut.payContact()
            paymentStarted.await()
            assertTrue(sut.uiState.value.isPayLoading)

            sut.dismissRequestOrPaySheet()
            advanceUntilIdle()

            assertFalse(sut.uiState.value.isPayLoading)
            assertFalse(sut.uiState.value.showRequestOrPaySheet)
            expectNoEvents()
        }
    }

    @Test
    fun `pay tap cancels a stalled eligibility check before paying`() = test {
        var isCheckCancelled = false
        var wasCheckCancelledBeforePayment = false
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(listOf(createContact())))
        whenever(paykitPaymentRequestRepo.refreshEligibleTarget(TEST_PUBLIC_KEY)).doSuspendableAnswer {
            try {
                awaitCancellation()
            } finally {
                isCheckCancelled = true
            }
        }
        whenever(privatePaykitRepo.beginSavedContactPayment(TEST_PUBLIC_KEY)).doSuspendableAnswer {
            wasCheckCancelledBeforePayment = isCheckCancelled
            Result.success(openedPayment)
        }
        val sut = createSut()

        sut.effects.test {
            sut.onClickPay()
            advanceUntilIdle()

            assertIs<ContactDetailEffect.OpenPayment>(awaitItem())
            assertTrue(wasCheckCancelledBeforePayment)
            assertFalse(sut.uiState.value.showRequestOrPaySheet)
        }
    }

    @Test
    fun `pay tap opens payment when the contact cannot receive requests`() = test {
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(listOf(createContact())))
        whenever(paykitPaymentRequestRepo.refreshEligibleTarget(TEST_PUBLIC_KEY)).thenReturn(Result.success(null))
        whenever(privatePaykitRepo.beginSavedContactPayment(TEST_PUBLIC_KEY))
            .thenReturn(Result.success(openedPayment))
        val sut = createSut()
        advanceUntilIdle()

        sut.effects.test {
            sut.onClickPay()
            advanceUntilIdle()

            assertFalse(sut.uiState.value.showRequestOrPaySheet)
            assertFalse(sut.uiState.value.isPayLoading)
            assertIs<ContactDetailEffect.OpenPayment>(awaitItem())
        }
    }

    @Test
    fun `paying from the request or pay sheet opens the payment`() = test {
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(listOf(createContact())))
        whenever(paykitPaymentRequestRepo.refreshEligibleTarget(TEST_PUBLIC_KEY)).thenReturn(Result.success(target))
        whenever(privatePaykitRepo.beginSavedContactPayment(TEST_PUBLIC_KEY))
            .thenReturn(Result.success(openedPayment))
        val sut = createSut()
        advanceUntilIdle()

        sut.effects.test {
            sut.onClickPay()
            advanceUntilIdle()
            sut.payContact()
            advanceUntilIdle()

            val effect = assertIs<ContactDetailEffect.OpenPayment>(awaitItem())
            assertEquals(openedPayment.paymentRequest, effect.paymentRequest)
            assertFalse(sut.uiState.value.showRequestOrPaySheet)
            assertFalse(sut.uiState.value.isPayLoading)
        }
    }

    @Test
    fun `unsaved contact skips the payment request check`() = test {
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(emptyList()))
        whenever(pubkyRepo.fetchContactProfile(TEST_PUBLIC_KEY)).thenReturn(Result.success(createContact()))
        whenever(privatePaykitRepo.beginSavedContactPayment(TEST_PUBLIC_KEY))
            .thenReturn(Result.success(openedPayment))
        val sut = createSut()
        advanceUntilIdle()

        sut.onClickPay()
        advanceUntilIdle()

        verify(paykitPaymentRequestRepo, never()).refreshEligibleTarget(any())
        assertFalse(sut.uiState.value.showRequestOrPaySheet)
    }

    private fun createSut() = ContactDetailViewModel(
        context = context,
        pubkyRepo = pubkyRepo,
        privatePaykitRepo = privatePaykitRepo,
        paykitPaymentRequestRepo = paykitPaymentRequestRepo.also {
            whenever(it.eligibleTargets).thenReturn(eligibleTargets)
        },
        clock = clock,
        savedStateHandle = SavedStateHandle(mapOf("publicKey" to TEST_PUBLIC_KEY)),
    )

    private fun createContact(tags: List<String> = emptyList()) =
        PubkyProfile.placeholder(TEST_PUBLIC_KEY).copy(tags = tags)
}

private class ContactDetailTestError(message: String) : AppError(message)

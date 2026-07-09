package to.bitkit.ui.screens.profile

import android.content.Context
import app.cash.turbine.test
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.PublicPaykitRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PayContactsViewModelTest : BaseUnitTest() {
    companion object {
        private const val CONTACT_KEY = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
    }

    private val context: Context = mock()
    private val settingsStore: SettingsStore = mock()
    private val publicPaykitRepo: PublicPaykitRepo = mock()
    private val privatePaykitRepo: PrivatePaykitRepo = mock()
    private val pubkyRepo: PubkyRepo = mock()

    private val settingsFlow = MutableStateFlow(SettingsData())
    private val contactsFlow = MutableStateFlow(listOf(createContact(CONTACT_KEY)))

    @Before
    fun setUp() {
        settingsFlow.value = SettingsData()
        contactsFlow.value = listOf(createContact(CONTACT_KEY))

        whenever(context.getString(any<Int>())).thenReturn("")
        whenever(settingsStore.data).thenReturn(settingsFlow)
        whenever(pubkyRepo.contacts).thenReturn(contactsFlow)
        whenever { pubkyRepo.hasSecretKey() }.thenReturn(true)
        whenever { settingsStore.update(any()) }.thenAnswer {
            val transform = it.getArgument<(SettingsData) -> SettingsData>(0)
            settingsFlow.value = transform(settingsFlow.value)
            Unit
        }
        whenever { publicPaykitRepo.syncPublishedEndpoints(any()) }.thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.setContactSharingCleanupPending(any()) }.thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.prepareSavedContacts(any<Collection<String>>(), any()) }
            .thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.disableSharingAndPruneUnsavedContactState(any<Collection<String>>()) }
            .thenReturn(Result.success(Unit))
    }

    @Test
    fun `continueToProfile enables sharing and prepares private contacts`() = test {
        val sut = createSut()
        advanceUntilIdle()

        sut.effects.test {
            sut.setPaymentSharingEnabled(true)
            sut.continueToProfile()
            advanceUntilIdle()

            assertEquals(PayContactsEffect.Continue, awaitItem())
        }

        assertTrue(settingsFlow.value.hasConfirmedPublicPaykitEndpoints)
        assertTrue(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertTrue(settingsFlow.value.sharesPrivatePaykitEndpoints)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = true)
        verify(privatePaykitRepo).setContactSharingCleanupPending(false)
        verify(privatePaykitRepo).prepareSavedContacts(listOf(CONTACT_KEY), false)
        verify(privatePaykitRepo, never()).disableSharingAndPruneUnsavedContactState(any<Collection<String>>())
    }

    @Test
    fun `continueToProfile enables only public sharing without local secret key`() = test {
        whenever { pubkyRepo.hasSecretKey() }.thenReturn(false)
        val sut = createSut()
        advanceUntilIdle()

        sut.effects.test {
            sut.setPaymentSharingEnabled(true)
            sut.continueToProfile()
            advanceUntilIdle()

            assertEquals(PayContactsEffect.Continue, awaitItem())
        }

        assertTrue(settingsFlow.value.hasConfirmedPublicPaykitEndpoints)
        assertTrue(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = true)
        verify(privatePaykitRepo, never()).setContactSharingCleanupPending(false)
        verify(privatePaykitRepo, never()).prepareSavedContacts(any<Collection<String>>(), any())
    }

    @Test
    fun `continueToProfile keeps sharing disabled when cleanup marker clear fails`() = test {
        whenever { privatePaykitRepo.setContactSharingCleanupPending(false) }
            .thenReturn(Result.failure(PayContactsTestAppError("marker failed")))
        val sut = createSut()
        advanceUntilIdle()

        sut.effects.test {
            sut.setPaymentSharingEnabled(true)
            sut.continueToProfile()
            advanceUntilIdle()

            expectNoEvents()
        }

        assertFalse(settingsFlow.value.hasConfirmedPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(sut.uiState.value.isLoading)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = true)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = false)
        verify(privatePaykitRepo, never()).prepareSavedContacts(any<Collection<String>>(), any())
    }

    @Test
    fun `continueToProfile proceeds when private contact preparation fails`() = test {
        whenever { privatePaykitRepo.prepareSavedContacts(any<Collection<String>>(), any()) }
            .thenReturn(Result.failure(PayContactsTestAppError("private setup failed")))
        val sut = createSut()
        advanceUntilIdle()

        sut.effects.test {
            sut.setPaymentSharingEnabled(true)
            sut.continueToProfile()
            advanceUntilIdle()

            assertEquals(PayContactsEffect.Continue, awaitItem())
        }

        assertTrue(settingsFlow.value.hasConfirmedPublicPaykitEndpoints)
        assertTrue(settingsFlow.value.sharesPublicPaykitEndpoints)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = true)
        verify(privatePaykitRepo).prepareSavedContacts(listOf(CONTACT_KEY), false)
    }

    @Test
    fun `continueToProfile clears cleanup marker after disabling succeeds`() = test {
        settingsFlow.value = SettingsData(
            hasConfirmedPublicPaykitEndpoints = true,
            sharesPublicPaykitEndpoints = true,
        )
        val sut = createSut()
        advanceUntilIdle()

        sut.effects.test {
            sut.setPaymentSharingEnabled(false)
            sut.continueToProfile()
            advanceUntilIdle()

            assertEquals(PayContactsEffect.Continue, awaitItem())
        }

        assertTrue(settingsFlow.value.hasConfirmedPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = false)
        verify(privatePaykitRepo).disableSharingAndPruneUnsavedContactState(listOf(CONTACT_KEY))
        verify(privatePaykitRepo).setContactSharingCleanupPending(false)
        assertFalse(sut.uiState.value.isLoading)
    }

    @Test
    fun `continueToProfile marks cleanup pending when disabling fails`() = test {
        settingsFlow.value = SettingsData(
            hasConfirmedPublicPaykitEndpoints = true,
            sharesPublicPaykitEndpoints = true,
        )
        whenever { privatePaykitRepo.disableSharingAndPruneUnsavedContactState(any<Collection<String>>()) }
            .thenReturn(Result.failure(PayContactsTestAppError("cleanup failed")))
        val sut = createSut()
        advanceUntilIdle()

        sut.effects.test {
            sut.setPaymentSharingEnabled(false)
            sut.continueToProfile()
            advanceUntilIdle()

            expectNoEvents()
        }

        assertTrue(settingsFlow.value.hasConfirmedPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(sut.uiState.value.isLoading)
        assertFalse(sut.uiState.value.isPaymentSharingEnabled)
        verify(privatePaykitRepo, never()).setContactSharingCleanupPending(true)
    }

    @Test
    fun `continueToProfile restores private sharing when private cleanup fails`() = test {
        settingsFlow.value = SettingsData(
            hasConfirmedPublicPaykitEndpoints = true,
            sharesPrivatePaykitEndpoints = true,
        )
        whenever { privatePaykitRepo.disableSharingAndPruneUnsavedContactState(any<Collection<String>>()) }
            .thenReturn(Result.failure(PayContactsTestAppError("cleanup failed")))
        val sut = createSut()
        advanceUntilIdle()

        sut.effects.test {
            sut.setPaymentSharingEnabled(false)
            sut.continueToProfile()
            advanceUntilIdle()

            expectNoEvents()
        }

        assertTrue(settingsFlow.value.hasConfirmedPublicPaykitEndpoints)
        assertTrue(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertFalse(sut.uiState.value.isLoading)
        assertTrue(sut.uiState.value.isPaymentSharingEnabled)
        verify(privatePaykitRepo).setContactSharingCleanupPending(false)
        verify(privatePaykitRepo).prepareSavedContacts(listOf(CONTACT_KEY), true)
    }

    @Test
    fun `continueToProfile keeps private sharing disabled when private restore fails`() = test {
        settingsFlow.value = SettingsData(
            hasConfirmedPublicPaykitEndpoints = true,
            sharesPrivatePaykitEndpoints = true,
        )
        whenever { privatePaykitRepo.disableSharingAndPruneUnsavedContactState(any<Collection<String>>()) }
            .thenReturn(Result.failure(PayContactsTestAppError("cleanup failed")))
        whenever { privatePaykitRepo.prepareSavedContacts(any<Collection<String>>(), eq(true)) }
            .thenReturn(Result.failure(PayContactsTestAppError("restore failed")))
        val sut = createSut()
        advanceUntilIdle()

        sut.effects.test {
            sut.setPaymentSharingEnabled(false)
            sut.continueToProfile()
            advanceUntilIdle()

            expectNoEvents()
        }

        assertTrue(settingsFlow.value.hasConfirmedPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertFalse(sut.uiState.value.isLoading)
        assertFalse(sut.uiState.value.isPaymentSharingEnabled)
        verify(privatePaykitRepo).prepareSavedContacts(listOf(CONTACT_KEY), true)
        verify(privatePaykitRepo, never()).setContactSharingCleanupPending(false)
    }

    @Test
    fun `continueToProfile restores public sharing when public cleanup fails`() = test {
        settingsFlow.value = SettingsData(
            hasConfirmedPublicPaykitEndpoints = true,
            sharesPublicPaykitEndpoints = true,
            sharesPrivatePaykitEndpoints = true,
        )
        whenever { publicPaykitRepo.syncPublishedEndpoints(publish = false) }
            .thenReturn(Result.failure(PayContactsTestAppError("public cleanup failed")))
        val sut = createSut()
        advanceUntilIdle()

        sut.effects.test {
            sut.setPaymentSharingEnabled(false)
            sut.continueToProfile()
            advanceUntilIdle()

            expectNoEvents()
        }

        assertTrue(settingsFlow.value.hasConfirmedPublicPaykitEndpoints)
        assertTrue(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertFalse(sut.uiState.value.isLoading)
        assertTrue(sut.uiState.value.isPaymentSharingEnabled)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = false)
        verify(privatePaykitRepo).disableSharingAndPruneUnsavedContactState(listOf(CONTACT_KEY))
        verify(privatePaykitRepo, never()).setContactSharingCleanupPending(true)
    }

    private fun createSut() = PayContactsViewModel(
        context = context,
        settingsStore = settingsStore,
        publicPaykitRepo = publicPaykitRepo,
        privatePaykitRepo = privatePaykitRepo,
        pubkyRepo = pubkyRepo,
    )
}

private fun createContact(publicKey: String) = PubkyProfile(
    publicKey = publicKey,
    name = "Alice",
    bio = "",
    imageUrl = null,
    links = emptyList(),
    tags = persistentListOf(),
    status = null,
)

private class PayContactsTestAppError(message: String) : AppError(message)

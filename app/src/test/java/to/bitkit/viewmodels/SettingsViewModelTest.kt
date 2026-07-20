package to.bitkit.viewmodels

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.WidgetsStore
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.ContactPaymentSettingsRepo
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.PublicPaykitRepo
import to.bitkit.repositories.WidgetsRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest : BaseUnitTest() {
    private lateinit var sut: SettingsViewModel

    private val context = mock<Context>()
    private val settingsStore = mock<SettingsStore>()
    private val pubkyRepo = mock<PubkyRepo>()
    private val publicPaykitRepo = mock<PublicPaykitRepo>()
    private val privatePaykitRepo = mock<PrivatePaykitRepo>()
    private val widgetsStore = mock<WidgetsStore>()
    private val widgetsRepo = mock<WidgetsRepo>()
    private val contactPaymentSettingsRepo = mock<ContactPaymentSettingsRepo>()

    private val settingsData = MutableStateFlow(SettingsData())
    private val isPaykitEnabled = MutableStateFlow(false)
    private val contactPaymentsEnabled = MutableStateFlow(false)
    private val contacts = MutableStateFlow(
        listOf(
            PubkyProfile(
                publicKey = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg",
                name = "Alice",
                bio = "",
                imageUrl = null,
                links = emptyList(),
                status = null,
            )
        )
    )

    @Before
    fun setUp() {
        whenever(settingsStore.data).thenReturn(settingsData)
        whenever(settingsStore.isPaykitEnabled).thenReturn(isPaykitEnabled)
        whenever(contactPaymentSettingsRepo.isEnabled).thenReturn(contactPaymentsEnabled)
        whenever { contactPaymentSettingsRepo.setEnabled(any()) }.thenReturn(Result.success(Unit))
        whenever { settingsStore.update(any()) }.thenAnswer {
            val transform = it.getArgument<(SettingsData) -> SettingsData>(0)
            settingsData.value = transform(settingsData.value)
            Unit
        }
        whenever { settingsStore.setIsPaykitEnabled(any()) }.thenAnswer {
            isPaykitEnabled.value = it.getArgument(0)
            Unit
        }
        whenever(pubkyRepo.isAuthenticated).thenReturn(MutableStateFlow(false))
        whenever(pubkyRepo.contacts).thenReturn(contacts)
        whenever { publicPaykitRepo.syncPublishedEndpoints(publish = false) }.thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.disableSharingAndPruneUnsavedContactState(any<Collection<String>>()) }
            .thenReturn(Result.success(Unit))

        sut = createViewModel()
    }

    @Test
    fun `disabling Paykit clears settings and removes published endpoints`() = test {
        isPaykitEnabled.value = true
        settingsData.value = SettingsData(
            hasConfirmedPublicPaykitEndpoints = true,
            sharesPublicPaykitEndpoints = true,
            sharesPrivatePaykitEndpoints = true,
            publicPaykitBolt11 = "lnbc1old",
            publicPaykitBolt11PaymentHash = "hash",
            publicPaykitBolt11ExpiresAtMillis = 123L,
        )

        sut.setIsPaykitEnabled(false)
        advanceUntilIdle()

        val settings = settingsData.value
        assertFalse(isPaykitEnabled.value)
        assertFalse(settings.hasConfirmedPublicPaykitEndpoints)
        assertFalse(settings.sharesPublicPaykitEndpoints)
        assertFalse(settings.sharesPrivatePaykitEndpoints)
        assertFalse(settings.publicPaykitCleanupPending)
        assertEquals("", settings.publicPaykitBolt11)
        assertEquals("", settings.publicPaykitBolt11PaymentHash)
        assertEquals(0L, settings.publicPaykitBolt11ExpiresAtMillis)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = false)
        verify(privatePaykitRepo).disableSharingAndPruneUnsavedContactState(contacts.value.map { it.publicKey })
    }

    @Test
    fun `disabling Paykit keeps public cleanup pending when public removal fails`() = test {
        whenever { publicPaykitRepo.syncPublishedEndpoints(publish = false) }
            .thenReturn(Result.failure(SettingsViewModelTestError("cleanup failed")))
        isPaykitEnabled.value = true
        settingsData.value = SettingsData(
            hasConfirmedPublicPaykitEndpoints = true,
            sharesPublicPaykitEndpoints = true,
        )

        sut.setIsPaykitEnabled(false)
        advanceUntilIdle()

        assertTrue(settingsData.value.publicPaykitCleanupPending)
        verify(privatePaykitRepo).disableSharingAndPruneUnsavedContactState(contacts.value.map { it.publicKey })
    }

    @Test
    fun `disabling Paykit with private-only state does not create public cleanup pending`() = test {
        clearInvocations(publicPaykitRepo)
        isPaykitEnabled.value = true
        settingsData.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
        )

        sut.setIsPaykitEnabled(false)
        advanceUntilIdle()

        assertFalse(settingsData.value.publicPaykitCleanupPending)
        verify(publicPaykitRepo, never()).syncPublishedEndpoints(publish = false)
        verify(privatePaykitRepo).disableSharingAndPruneUnsavedContactState(contacts.value.map { it.publicKey })
    }

    @Test
    fun `init disables stale Paykit publication state when local flag is off`() = test {
        settingsData.value = SettingsData(
            hasConfirmedPublicPaykitEndpoints = true,
            sharesPublicPaykitEndpoints = true,
            sharesPrivatePaykitEndpoints = true,
            publicPaykitBolt11 = "lnbc1old",
            publicPaykitBolt11PaymentHash = "hash",
            publicPaykitBolt11ExpiresAtMillis = 123L,
        )

        createViewModel()
        advanceUntilIdle()

        val settings = settingsData.value
        assertFalse(isPaykitEnabled.value)
        assertFalse(settings.hasConfirmedPublicPaykitEndpoints)
        assertFalse(settings.sharesPublicPaykitEndpoints)
        assertFalse(settings.sharesPrivatePaykitEndpoints)
        assertFalse(settings.publicPaykitCleanupPending)
        assertEquals("", settings.publicPaykitBolt11)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = false)
        verify(privatePaykitRepo).disableSharingAndPruneUnsavedContactState(contacts.value.map { it.publicKey })
    }

    @Test
    fun `keepBitkitActiveInBackground defaults to false`() = test {
        assertFalse(sut.keepBitkitActiveInBackground.value)
    }

    @Test
    fun `setKeepBitkitActiveInBackground persists the new value`() = test {
        sut.setKeepBitkitActiveInBackground(true)
        advanceUntilIdle()

        assertTrue(settingsData.value.keepBitkitActiveInBackground)

        sut.setKeepBitkitActiveInBackground(false)
        advanceUntilIdle()

        assertFalse(settingsData.value.keepBitkitActiveInBackground)
    }

    @Test
    fun `contact payment switch delegates to shared settings repo`() = test {
        sut.setContactPaymentsEnabled(true)
        advanceUntilIdle()

        verify(contactPaymentSettingsRepo).setEnabled(true)
        assertFalse(sut.isUpdatingContactPayments.value)
    }

    private fun createViewModel() = SettingsViewModel(
        context = context,
        settingsStore = settingsStore,
        pubkyRepo = pubkyRepo,
        contactPaymentSettingsRepo = contactPaymentSettingsRepo,
        publicPaykitRepo = publicPaykitRepo,
        privatePaykitRepo = privatePaykitRepo,
        widgetsStore = widgetsStore,
        widgetsRepo = widgetsRepo,
    )

    private class SettingsViewModelTestError(message: String) : AppError(message)
}

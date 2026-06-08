package to.bitkit.ui.settings.paymentPreference

import android.content.Context
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
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.PublicPaykitError
import to.bitkit.repositories.PublicPaykitRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentPreferenceViewModelTest : BaseUnitTest() {
    companion object {
        private const val CONTACT_KEY = "pubkycytinw71a3ge1esmzj5e53hsr3jtj6t4pogpgr6k75w9mzmyokzo"
    }

    private val context: Context = mock()
    private val settingsStore: SettingsStore = mock()
    private val publicPaykitRepo: PublicPaykitRepo = mock()
    private val privatePaykitRepo: PrivatePaykitRepo = mock()
    private val pubkyRepo: PubkyRepo = mock()

    private val settingsFlow = MutableStateFlow(SettingsData())
    private val contactsFlow = MutableStateFlow(listOf(createPaymentPreferenceContact(CONTACT_KEY)))
    private val isAuthenticatedFlow = MutableStateFlow(true)

    @Before
    fun setUp() {
        settingsFlow.value = SettingsData()
        contactsFlow.value = listOf(createPaymentPreferenceContact(CONTACT_KEY))
        isAuthenticatedFlow.value = true

        whenever(context.getString(any<Int>())).thenReturn("")
        whenever(settingsStore.data).thenReturn(settingsFlow)
        whenever(pubkyRepo.contacts).thenReturn(contactsFlow)
        whenever(pubkyRepo.isAuthenticated).thenReturn(isAuthenticatedFlow)
        whenever { pubkyRepo.hasSecretKey() }.thenReturn(true)
        whenever { settingsStore.update(any()) }.thenAnswer {
            val transform = it.getArgument<(SettingsData) -> SettingsData>(0)
            settingsFlow.value = transform(settingsFlow.value)
            Unit
        }
        whenever { publicPaykitRepo.syncCurrentPublishedEndpoints(any(), any()) }
            .thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.setContactSharingCleanupPending(any()) }
            .thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.enableSharingAndPrepareSavedContacts(any<Collection<String>>(), any()) }
            .thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.prepareSavedContacts(any<Collection<String>>(), any()) }
            .thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.disableSharingAndPruneUnsavedContactState(any<Collection<String>>()) }
            .thenReturn(Result.success(Unit))
    }

    @Test
    fun `setPrivateContactsEnabled prepares contacts with immediate publication`() = test {
        val sut = createSut()
        advanceUntilIdle()

        sut.setPrivateContactsEnabled(true)
        advanceUntilIdle()

        assertTrue(settingsFlow.value.sharesPrivatePaykitEndpoints)
        verify(privatePaykitRepo).enableSharingAndPrepareSavedContacts(listOf(CONTACT_KEY), true)
    }

    @Test
    fun `setPrivateContactsEnabled does not enable without profile`() = test {
        isAuthenticatedFlow.value = false
        val sut = createSut()
        advanceUntilIdle()

        sut.setPrivateContactsEnabled(true)
        advanceUntilIdle()

        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertFalse(sut.uiState.value.hasPubkyProfile)
        verify(settingsStore, never()).update(any())
        verify(privatePaykitRepo, never()).enableSharingAndPrepareSavedContacts(any<Collection<String>>(), any())
    }

    @Test
    fun `setPrivateContactsEnabled does not enable without local secret key`() = test {
        whenever { pubkyRepo.hasSecretKey() }.thenReturn(false)
        val sut = createSut()
        advanceUntilIdle()

        sut.setPrivateContactsEnabled(true)
        advanceUntilIdle()

        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertFalse(sut.uiState.value.canUsePrivateContacts)
        verify(privatePaykitRepo, never()).enableSharingAndPrepareSavedContacts(any<Collection<String>>(), any())
    }

    @Test
    fun `init clears hidden private sharing when local secret key is unavailable`() = test {
        settingsFlow.value = SettingsData(sharesPrivatePaykitEndpoints = true)
        whenever { pubkyRepo.hasSecretKey() }.thenReturn(false)
        val sut = createSut()
        advanceUntilIdle()

        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertFalse(sut.uiState.value.privateContactsEnabled)
        assertFalse(sut.uiState.value.canUsePrivateContacts)
    }

    @Test
    fun `setPrivateContactsEnabled rolls back when private enable fails`() = test {
        whenever { privatePaykitRepo.enableSharingAndPrepareSavedContacts(any<Collection<String>>(), any()) }
            .thenReturn(Result.failure(PublicPaykitError.WalletNotReady))
        val sut = createSut()
        advanceUntilIdle()

        sut.setPrivateContactsEnabled(true)
        advanceUntilIdle()

        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        verify(privatePaykitRepo).enableSharingAndPrepareSavedContacts(listOf(CONTACT_KEY), true)
        verify(privatePaykitRepo).disableSharingAndPruneUnsavedContactState(listOf(CONTACT_KEY))
    }

    @Test
    fun `setPrivateContactsEnabled restores enabled state when private disable fails`() = test {
        settingsFlow.value = SettingsData(sharesPrivatePaykitEndpoints = true)
        whenever { privatePaykitRepo.disableSharingAndPruneUnsavedContactState(any<Collection<String>>()) }
            .thenReturn(Result.failure(PublicPaykitError.WalletNotReady))
        val sut = createSut()
        advanceUntilIdle()

        sut.setPrivateContactsEnabled(false)
        advanceUntilIdle()

        assertTrue(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertTrue(sut.uiState.value.privateContactsEnabled)
        assertFalse(sut.uiState.value.isUpdatingPrivateContacts)
        verify(privatePaykitRepo).setContactSharingCleanupPending(false)
        verify(privatePaykitRepo).prepareSavedContacts(listOf(CONTACT_KEY), false)
    }

    @Test
    fun `setOnchainEnabled rolls back when public sync has no supported endpoint`() = test {
        settingsFlow.value = SettingsData(
            sharesPublicPaykitEndpoints = true,
            publicPaykitLightningEnabled = true,
            publicPaykitOnchainEnabled = true,
        )
        whenever {
            publicPaykitRepo.syncCurrentPublishedEndpoints(
                forceRefreshLightning = true,
                requireEndpoint = true,
            )
        }.thenReturn(Result.failure(PublicPaykitError.NoSupportedEndpoint))
        val sut = createSut()
        advanceUntilIdle()

        sut.setOnchainEnabled(false)
        advanceUntilIdle()

        assertTrue(settingsFlow.value.publicPaykitOnchainEnabled)
        assertTrue(settingsFlow.value.publicPaykitLightningEnabled)
        assertFalse(sut.uiState.value.isUpdatingPaymentOptions)
        verify(publicPaykitRepo, times(2)).syncCurrentPublishedEndpoints(
            forceRefreshLightning = true,
            requireEndpoint = true,
        )
    }

    @Test
    fun `setOnchainEnabled rollback preserves concurrent sharing changes`() = test {
        settingsFlow.value = SettingsData(
            sharesPublicPaykitEndpoints = true,
            sharesPrivatePaykitEndpoints = false,
            publicPaykitLightningEnabled = true,
            publicPaykitOnchainEnabled = true,
        )
        var updateCount = 0
        whenever { settingsStore.update(any()) }.thenAnswer {
            updateCount += 1
            val transform = it.getArgument<(SettingsData) -> SettingsData>(0)
            if (updateCount == 2) {
                settingsFlow.value = settingsFlow.value.copy(
                    sharesPublicPaykitEndpoints = false,
                    sharesPrivatePaykitEndpoints = true,
                )
            }
            settingsFlow.value = transform(settingsFlow.value)
            Unit
        }
        whenever {
            publicPaykitRepo.syncCurrentPublishedEndpoints(true, true)
        }.thenReturn(Result.failure(PublicPaykitError.NoSupportedEndpoint))
        val sut = createSut()
        advanceUntilIdle()

        sut.setOnchainEnabled(false)
        advanceUntilIdle()

        assertTrue(settingsFlow.value.publicPaykitOnchainEnabled)
        assertTrue(settingsFlow.value.publicPaykitLightningEnabled)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertTrue(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertFalse(sut.uiState.value.isUpdatingPaymentOptions)
        verify(publicPaykitRepo).syncCurrentPublishedEndpoints(true, true)
        verify(privatePaykitRepo).prepareSavedContacts(listOf(CONTACT_KEY), true)
    }

    @Test
    fun `setOnchainEnabled rolls back when private immediate publication fails`() = test {
        settingsFlow.value = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = true,
            publicPaykitOnchainEnabled = true,
        )
        whenever { privatePaykitRepo.prepareSavedContacts(any<Collection<String>>(), eq(true)) }
            .thenReturn(Result.failure(PublicPaykitError.WalletNotReady))
        val sut = createSut()
        advanceUntilIdle()

        sut.setOnchainEnabled(false)
        advanceUntilIdle()

        assertTrue(settingsFlow.value.publicPaykitOnchainEnabled)
        assertTrue(settingsFlow.value.publicPaykitLightningEnabled)
        assertTrue(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertFalse(sut.uiState.value.isUpdatingPaymentOptions)
        verify(privatePaykitRepo, times(2)).prepareSavedContacts(listOf(CONTACT_KEY), true)
    }

    private fun createSut() = PaymentPreferenceViewModel(
        context = context,
        settingsStore = settingsStore,
        publicPaykitRepo = publicPaykitRepo,
        privatePaykitRepo = privatePaykitRepo,
        pubkyRepo = pubkyRepo,
    )
}

private fun createPaymentPreferenceContact(publicKey: String) = PubkyProfile(
    publicKey = publicKey,
    name = "Alice",
    bio = "",
    imageUrl = null,
    links = emptyList(),
    tags = persistentListOf(),
    status = null,
)

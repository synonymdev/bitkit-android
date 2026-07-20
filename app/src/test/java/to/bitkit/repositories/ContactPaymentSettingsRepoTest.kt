package to.bitkit.repositories

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ContactPaymentSettingsRepoTest : BaseUnitTest() {
    companion object {
        private const val CONTACT_KEY = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
    }

    private val settingsStore: SettingsStore = mock()
    private val publicPaykitRepo: PublicPaykitRepo = mock()
    private val privatePaykitRepo: PrivatePaykitRepo = mock()
    private val pubkyRepo: PubkyRepo = mock()
    private val settingsFlow = MutableStateFlow(SettingsData())

    @Before
    fun setUp() {
        settingsFlow.value = SettingsData()
        whenever(settingsStore.data).thenReturn(settingsFlow)
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(listOf(createContact())))
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
    fun `enabling publishes public and private contact payments`() = test {
        settingsFlow.value = SettingsData(
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = false,
        )

        val result = createSut().setEnabled(true)

        assertTrue(result.isSuccess)
        assertTrue(settingsFlow.value.hasConfirmedPublicPaykitEndpoints)
        assertTrue(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertTrue(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertTrue(settingsFlow.value.publicPaykitLightningEnabled)
        assertTrue(settingsFlow.value.publicPaykitOnchainEnabled)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = true)
        verify(privatePaykitRepo).prepareSavedContacts(listOf(CONTACT_KEY), false)
    }

    @Test
    fun `enabling without local key enables only public payments`() = test {
        whenever { pubkyRepo.hasSecretKey() }.thenReturn(false)

        val result = createSut().setEnabled(true)

        assertTrue(result.isSuccess)
        assertTrue(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        verify(privatePaykitRepo, never()).prepareSavedContacts(any<Collection<String>>(), any())
    }

    @Test
    fun `failed publication restores disabled settings`() = test {
        whenever { publicPaykitRepo.syncPublishedEndpoints(publish = true) }
            .thenReturn(Result.failure(ContactPaymentSettingsTestError("publish failed")))

        val result = createSut().setEnabled(true)

        assertTrue(result.isFailure)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = false)
    }

    @Test
    fun `failed private preparation restores disabled settings`() = test {
        whenever(privatePaykitRepo.prepareSavedContacts(any<Collection<String>>(), eq(false)))
            .thenReturn(Result.failure(ContactPaymentSettingsTestError("private preparation failed")))

        val result = createSut().setEnabled(true)

        assertTrue(result.isFailure)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = false)
        verify(privatePaykitRepo).disableSharingAndPruneUnsavedContactState(listOf(CONTACT_KEY))
    }

    @Test
    fun `disabling removes public and private contact payments`() = test {
        settingsFlow.value = SettingsData(
            hasConfirmedPublicPaykitEndpoints = true,
            sharesPublicPaykitEndpoints = true,
            sharesPrivatePaykitEndpoints = true,
        )

        val result = createSut().setEnabled(false)

        assertTrue(result.isSuccess)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = false)
        verify(privatePaykitRepo).disableSharingAndPruneUnsavedContactState(listOf(CONTACT_KEY))
        verify(privatePaykitRepo).setContactSharingCleanupPending(false)
    }

    @Test
    fun `failed public cleanup and restore leaves public payments disabled for retry`() = test {
        settingsFlow.value = SettingsData(
            hasConfirmedPublicPaykitEndpoints = true,
            sharesPublicPaykitEndpoints = true,
        )
        whenever { publicPaykitRepo.syncPublishedEndpoints(any()) }
            .thenReturn(Result.failure(ContactPaymentSettingsTestError("sync failed")))

        val result = createSut().setEnabled(false)

        assertTrue(result.isFailure)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertTrue(settingsFlow.value.publicPaykitCleanupPending)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = false)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = true)
    }

    @Test
    fun `failed private cleanup restores private contact payments`() = test {
        settingsFlow.value = SettingsData(
            hasConfirmedPublicPaykitEndpoints = true,
            sharesPrivatePaykitEndpoints = true,
        )
        whenever { privatePaykitRepo.disableSharingAndPruneUnsavedContactState(any<Collection<String>>()) }
            .thenReturn(Result.failure(ContactPaymentSettingsTestError("cleanup failed")))

        val result = createSut().setEnabled(false)

        assertTrue(result.isFailure)
        assertTrue(settingsFlow.value.sharesPrivatePaykitEndpoints)
        verify(privatePaykitRepo).prepareSavedContacts(listOf(CONTACT_KEY), true)
    }

    @Test
    fun `failed private restore leaves private payments disabled`() = test {
        settingsFlow.value = SettingsData(
            hasConfirmedPublicPaykitEndpoints = true,
            sharesPrivatePaykitEndpoints = true,
        )
        whenever { privatePaykitRepo.disableSharingAndPruneUnsavedContactState(any<Collection<String>>()) }
            .thenReturn(Result.failure(ContactPaymentSettingsTestError("cleanup failed")))
        whenever { privatePaykitRepo.prepareSavedContacts(any<Collection<String>>(), eq(true)) }
            .thenReturn(Result.failure(ContactPaymentSettingsTestError("restore failed")))

        val result = createSut().setEnabled(false)

        assertTrue(result.isFailure)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
    }

    private fun createSut() = ContactPaymentSettingsRepo(
        settingsStore = settingsStore,
        publicPaykitRepo = publicPaykitRepo,
        privatePaykitRepo = privatePaykitRepo,
        pubkyRepo = pubkyRepo,
        ioDispatcher = testDispatcher,
    )

    private fun createContact() = PubkyProfile(
        publicKey = CONTACT_KEY,
        name = "Alice",
        bio = "",
        imageUrl = null,
        links = emptyList(),
        tags = persistentListOf(),
        status = null,
    )
}

private class ContactPaymentSettingsTestError(message: String) : AppError(message)

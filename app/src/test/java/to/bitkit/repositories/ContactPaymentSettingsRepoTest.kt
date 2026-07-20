package to.bitkit.repositories

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.PubkyProfile
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ContactPaymentSettingsRepoTest : BaseUnitTest() {
    companion object {
        private const val CONTACT_KEY = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val SELF_KEY = "pubkyself"
    }

    private val settingsStore: SettingsStore = mock()
    private val publicPaykitRepo: PublicPaykitRepo = mock()
    private val privatePaykitRepo: PrivatePaykitRepo = mock()
    private val pubkyRepo: PubkyRepo = mock()
    private val settingsFlow = MutableStateFlow(SettingsData())
    private val publicKey = MutableStateFlow<String?>(SELF_KEY)
    private var settingsUpdateCount = 0
    private var failingSettingsUpdateCalls = emptySet<Int>()
    private var contactSharingCleanupPending = false

    @Before
    fun setUp() {
        settingsFlow.value = SettingsData()
        publicKey.value = SELF_KEY
        settingsUpdateCount = 0
        failingSettingsUpdateCalls = emptySet()
        contactSharingCleanupPending = false
        whenever(settingsStore.data).thenReturn(settingsFlow)
        whenever(pubkyRepo.publicKey).thenReturn(publicKey)
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(listOf(createContact())))
        whenever { pubkyRepo.hasSecretKey() }.thenReturn(true)
        whenever { settingsStore.update(any()) }.thenAnswer {
            settingsUpdateCount += 1
            if (settingsUpdateCount in failingSettingsUpdateCalls) {
                throw ContactPaymentSettingsTestError("settings update failed")
            }
            val transform = it.getArgument<(SettingsData) -> SettingsData>(0)
            settingsFlow.value = transform(settingsFlow.value)
            Unit
        }
        whenever { publicPaykitRepo.syncPublishedEndpoints(any()) }.thenAnswer {
            if (!it.getArgument<Boolean>(0)) {
                settingsFlow.value = settingsFlow.value.copy(publicPaykitCleanupPending = false)
            }
            Result.success(Unit)
        }
        whenever { privatePaykitRepo.setContactSharingCleanupPending(any()) }.thenAnswer {
            contactSharingCleanupPending = it.getArgument<Boolean>(0)
            Result.success(Unit)
        }
        whenever { privatePaykitRepo.hasPendingEndpointCleanup() }.thenReturn(false)
        whenever { privatePaykitRepo.prepareSavedContacts(any<Collection<String>>(), any()) }
            .thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.disableSharingAndPruneUnsavedContactState(any<Collection<String>>()) }
            .thenAnswer {
                contactSharingCleanupPending = false
                Result.success(Unit)
            }
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
        assertNull(settingsFlow.value.pendingContactPaymentsEnabled)
        assertTrue(settingsFlow.value.publicPaykitLightningEnabled)
        assertTrue(settingsFlow.value.publicPaykitOnchainEnabled)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = true)
        verify(privatePaykitRepo).prepareSavedContacts(listOf(CONTACT_KEY), true)
    }

    @Test
    fun `enabling without local key enables only public payments`() = test {
        whenever(pubkyRepo.hasSecretKey()).thenReturn(false)

        val result = createSut().setEnabled(true)

        assertTrue(result.isSuccess)
        assertTrue(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        verify(privatePaykitRepo, never()).prepareSavedContacts(any<Collection<String>>(), any())
    }

    @Test
    fun `enabling without an active profile does not leave a pending transition`() = test {
        publicKey.value = null
        whenever(pubkyRepo.currentPublicKey()).thenReturn(Result.success(null))

        val result = createSut().setEnabled(true)

        assertTrue(result.isFailure)
        assertNull(settingsFlow.value.pendingContactPaymentsEnabled)
        verify(publicPaykitRepo, never()).syncPublishedEndpoints(any())
    }

    @Test
    fun `disabling a fresh state without a profile is a no-op`() = test {
        publicKey.value = null

        val result = createSut().setEnabled(false)

        assertTrue(result.isSuccess)
        assertNull(settingsFlow.value.pendingContactPaymentsEnabled)
        verify(publicPaykitRepo, never()).syncPublishedEndpoints(any())
        verify(privatePaykitRepo, never()).setContactSharingCleanupPending(any())
        verify(privatePaykitRepo, never()).disableSharingAndPruneUnsavedContactState(any<Collection<String>>())
    }

    @Test
    fun `enabling repairs a partial legacy state`() = test {
        settingsFlow.value = SettingsData(sharesPrivatePaykitEndpoints = true)

        val result = createSut().setEnabled(true)

        assertTrue(result.isSuccess)
        assertTrue(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertTrue(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertNull(settingsFlow.value.pendingContactPaymentsEnabled)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = true)
        verify(privatePaykitRepo).prepareSavedContacts(listOf(CONTACT_KEY), true)
    }

    @Test
    fun `failed publication compensates to a durable disabled state`() = test {
        whenever(publicPaykitRepo.syncPublishedEndpoints(publish = true))
            .thenReturn(Result.failure(ContactPaymentSettingsTestError("publish failed")))

        val result = createSut().setEnabled(true)

        assertTrue(result.isFailure)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertNull(settingsFlow.value.pendingContactPaymentsEnabled)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = false)
        verify(privatePaykitRepo).disableSharingAndPruneUnsavedContactState(listOf(CONTACT_KEY))
    }

    @Test
    fun `failed private marker clear compensates public publication`() = test {
        whenever(privatePaykitRepo.setContactSharingCleanupPending(false))
            .thenReturn(Result.failure(ContactPaymentSettingsTestError("marker failed")))

        val result = createSut().setEnabled(true)

        assertTrue(result.isFailure)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertNull(settingsFlow.value.pendingContactPaymentsEnabled)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = true)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = false)
        verify(privatePaykitRepo, never()).prepareSavedContacts(any<Collection<String>>(), any())
    }

    @Test
    fun `failed enabled settings write still removes published endpoints`() = test {
        failingSettingsUpdateCalls = setOf(2)

        val result = createSut().setEnabled(true)

        assertTrue(result.isFailure)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertNull(settingsFlow.value.pendingContactPaymentsEnabled)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = true)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = false)
    }

    @Test
    fun `failed enabled completion write compensates to disabled`() = test {
        failingSettingsUpdateCalls = setOf(3)

        val result = createSut().setEnabled(true)

        assertTrue(result.isFailure)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertNull(settingsFlow.value.pendingContactPaymentsEnabled)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = false)
        verify(privatePaykitRepo).disableSharingAndPruneUnsavedContactState(listOf(CONTACT_KEY))
    }

    @Test
    fun `failed marker reassertion recovers as disabled without republishing`() = test {
        failingSettingsUpdateCalls = setOf(2, 3, 4)
        var pendingMarkerWrites = 0
        whenever(privatePaykitRepo.setContactSharingCleanupPending(any())).thenAnswer {
            val isPending = it.getArgument<Boolean>(0)
            if (isPending) pendingMarkerWrites += 1
            if (isPending && pendingMarkerWrites == 2) {
                Result.failure(ContactPaymentSettingsTestError("marker reassertion failed"))
            } else {
                contactSharingCleanupPending = isPending
                Result.success(Unit)
            }
        }
        val sut = createSut()

        val enableResult = sut.setEnabled(true)

        assertTrue(enableResult.isFailure)
        assertEquals(true, settingsFlow.value.pendingContactPaymentsEnabled)
        assertFalse(contactSharingCleanupPending)

        failingSettingsUpdateCalls = emptySet()
        val recoveryResult = sut.recoverPendingTransition()

        assertTrue(recoveryResult.isSuccess)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertNull(settingsFlow.value.pendingContactPaymentsEnabled)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = true)
        verify(publicPaykitRepo, times(2)).syncPublishedEndpoints(publish = false)
    }

    @Test
    fun `failed private preparation compensates enabled settings`() = test {
        whenever(privatePaykitRepo.prepareSavedContacts(any<Collection<String>>(), eq(true)))
            .thenReturn(Result.failure(ContactPaymentSettingsTestError("private preparation failed")))

        val result = createSut().setEnabled(true)

        assertTrue(result.isFailure)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertNull(settingsFlow.value.pendingContactPaymentsEnabled)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = false)
        verify(privatePaykitRepo).disableSharingAndPruneUnsavedContactState(listOf(CONTACT_KEY))
    }

    @Test
    fun `failed compensation intent write still removes endpoints`() = test {
        whenever(publicPaykitRepo.syncPublishedEndpoints(publish = true))
            .thenReturn(Result.failure(ContactPaymentSettingsTestError("publish failed")))
        failingSettingsUpdateCalls = setOf(2)

        val result = createSut().setEnabled(true)

        assertTrue(result.isFailure)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertNull(settingsFlow.value.pendingContactPaymentsEnabled)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = false)
        verify(privatePaykitRepo).disableSharingAndPruneUnsavedContactState(listOf(CONTACT_KEY))
    }

    @Test
    fun `disabling removes public and private contact payments`() = test {
        settingsFlow.value = enabledSettings()

        val result = createSut().setEnabled(false)

        assertTrue(result.isSuccess)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertFalse(settingsFlow.value.publicPaykitCleanupPending)
        assertNull(settingsFlow.value.pendingContactPaymentsEnabled)
        verify(privatePaykitRepo).setContactSharingCleanupPending(true)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = false)
        verify(privatePaykitRepo).disableSharingAndPruneUnsavedContactState(listOf(CONTACT_KEY))
    }

    @Test
    fun `failed public cleanup stays disabled and pending for retry`() = test {
        settingsFlow.value = enabledSettings()
        whenever(publicPaykitRepo.syncPublishedEndpoints(publish = false))
            .thenReturn(Result.failure(ContactPaymentSettingsTestError("cleanup failed")))

        val result = createSut().setEnabled(false)

        assertTrue(result.isFailure)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertTrue(settingsFlow.value.publicPaykitCleanupPending)
        assertEquals(false, settingsFlow.value.pendingContactPaymentsEnabled)
        verify(publicPaykitRepo, never()).syncPublishedEndpoints(publish = true)
    }

    @Test
    fun `failed private cleanup stays disabled and pending for retry`() = test {
        settingsFlow.value = enabledSettings()
        whenever(privatePaykitRepo.disableSharingAndPruneUnsavedContactState(any<Collection<String>>()))
            .thenReturn(Result.failure(ContactPaymentSettingsTestError("cleanup failed")))

        val result = createSut().setEnabled(false)

        assertTrue(result.isFailure)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertEquals(false, settingsFlow.value.pendingContactPaymentsEnabled)
        verify(privatePaykitRepo, never()).prepareSavedContacts(any<Collection<String>>(), any())
    }

    @Test
    fun `failed private cleanup marker still removes endpoints`() = test {
        settingsFlow.value = enabledSettings()
        whenever(privatePaykitRepo.setContactSharingCleanupPending(true))
            .thenReturn(Result.failure(ContactPaymentSettingsTestError("marker failed")))

        val result = createSut().setEnabled(false)

        assertTrue(result.isFailure)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertEquals(false, settingsFlow.value.pendingContactPaymentsEnabled)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = false)
        verify(privatePaykitRepo).disableSharingAndPruneUnsavedContactState(listOf(CONTACT_KEY))
    }

    @Test
    fun `failed disabled settings write still removes endpoints`() = test {
        settingsFlow.value = enabledSettings()
        failingSettingsUpdateCalls = setOf(2)

        val result = createSut().setEnabled(false)

        assertTrue(result.isFailure)
        assertEquals(false, settingsFlow.value.pendingContactPaymentsEnabled)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = false)
        verify(privatePaykitRepo).disableSharingAndPruneUnsavedContactState(listOf(CONTACT_KEY))
    }

    @Test
    fun `failed disabled completion write stays pending for recovery`() = test {
        settingsFlow.value = enabledSettings()
        failingSettingsUpdateCalls = setOf(3)

        val result = createSut().setEnabled(false)

        assertTrue(result.isFailure)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertEquals(false, settingsFlow.value.pendingContactPaymentsEnabled)
    }

    @Test
    fun `failed recovery settings read returns failure`() = test {
        whenever(settingsStore.data).thenReturn(flow { throw ContactPaymentSettingsTestError("read failed") })

        val result = createSut().recoverPendingTransition()

        assertTrue(result.isFailure)
        verify(publicPaykitRepo, never()).syncPublishedEndpoints(any())
    }

    @Test
    fun `recovery rolls back an interrupted enable`() = test {
        settingsFlow.value = SettingsData(pendingContactPaymentsEnabled = true)

        val result = createSut().recoverPendingTransition()

        assertTrue(result.isSuccess)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertNull(settingsFlow.value.pendingContactPaymentsEnabled)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = false)
        verify(publicPaykitRepo, never()).syncPublishedEndpoints(publish = true)
    }

    @Test
    fun `recovery completes an interrupted disable`() = test {
        settingsFlow.value = enabledSettings().copy(pendingContactPaymentsEnabled = false)

        val result = createSut().recoverPendingTransition()

        assertTrue(result.isSuccess)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertNull(settingsFlow.value.pendingContactPaymentsEnabled)
    }

    @Test
    fun `enable remains recoverable until private preparation finishes`() = test {
        val preparationStarted = CompletableDeferred<Unit>()
        val releasePreparation = CompletableDeferred<Unit>()
        whenever(privatePaykitRepo.prepareSavedContacts(any<Collection<String>>(), any()))
            .doSuspendableAnswer {
                preparationStarted.complete(Unit)
                releasePreparation.await()
                Result.success(Unit)
            }
        val sut = createSut()

        val enable = async { sut.setEnabled(true) }
        preparationStarted.await()

        assertTrue(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertTrue(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertEquals(true, settingsFlow.value.pendingContactPaymentsEnabled)

        releasePreparation.complete(Unit)
        advanceUntilIdle()

        assertTrue(enable.await().isSuccess)
        assertNull(settingsFlow.value.pendingContactPaymentsEnabled)
    }

    @Test
    fun `concurrent transitions are serialized in request order`() = test {
        val enableStarted = CompletableDeferred<Unit>()
        val releaseEnable = CompletableDeferred<Unit>()
        whenever(publicPaykitRepo.syncPublishedEndpoints(publish = true))
            .doSuspendableAnswer {
                enableStarted.complete(Unit)
                releaseEnable.await()
                Result.success(Unit)
            }
        val sut = createSut()

        val enable = async { sut.setEnabled(true) }
        enableStarted.await()
        val disable = async { sut.setEnabled(false) }
        runCurrent()
        releaseEnable.complete(Unit)
        advanceUntilIdle()

        assertTrue(enable.await().isSuccess)
        assertTrue(disable.await().isSuccess)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertNull(settingsFlow.value.pendingContactPaymentsEnabled)
    }

    @Test
    fun `caller cancellation does not stop a durable disable transition`() = test {
        settingsFlow.value = enabledSettings()
        val disableStarted = CompletableDeferred<Unit>()
        val releaseDisable = CompletableDeferred<Unit>()
        whenever(publicPaykitRepo.syncPublishedEndpoints(publish = false))
            .doSuspendableAnswer {
                disableStarted.complete(Unit)
                releaseDisable.await()
                Result.success(Unit)
            }
        val sut = createSut()

        val disable = async { sut.setEnabled(false) }
        disableStarted.await()
        disable.cancel()
        releaseDisable.complete(Unit)
        advanceUntilIdle()
        disable.join()

        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertNull(settingsFlow.value.pendingContactPaymentsEnabled)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = false)
        verify(privatePaykitRepo).disableSharingAndPruneUnsavedContactState(listOf(CONTACT_KEY))
    }

    @Test
    fun `profile change during enable compensates to disabled`() = test {
        whenever(publicPaykitRepo.syncPublishedEndpoints(publish = true)).thenAnswer {
            publicKey.value = "pubkyother"
            Result.success(Unit)
        }

        val result = createSut().setEnabled(true)

        assertTrue(result.isFailure)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertNull(settingsFlow.value.pendingContactPaymentsEnabled)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = false)
    }

    private fun createSut() = ContactPaymentSettingsRepo(
        settingsStore = settingsStore,
        publicPaykitRepo = publicPaykitRepo,
        privatePaykitRepo = privatePaykitRepo,
        pubkyRepo = pubkyRepo,
        ioDispatcher = testDispatcher,
    )

    private fun enabledSettings() = SettingsData(
        hasConfirmedPublicPaykitEndpoints = true,
        sharesPublicPaykitEndpoints = true,
        sharesPrivatePaykitEndpoints = true,
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

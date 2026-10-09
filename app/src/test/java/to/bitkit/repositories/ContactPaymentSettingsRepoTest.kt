package to.bitkit.repositories

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.PubkyProfile
import to.bitkit.services.PaykitSdkOperationLock.Priority
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
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
        whenever { privatePaykitRepo.hasPrivatePaymentAccess() }.thenReturn(true)
        whenever { settingsStore.update(any()) }.thenAnswer {
            val transform = it.getArgument<(SettingsData) -> SettingsData>(0)
            settingsFlow.value = transform(settingsFlow.value)
            Unit
        }
        whenever { publicPaykitRepo.syncPublishedEndpoints(any()) }.thenReturn(Result.success(Unit))
        whenever { publicPaykitRepo.syncPublishedEndpoints(any(), any()) }.thenReturn(Result.success(Unit))
        whenever { publicPaykitRepo.syncPaykitApp(anyOrNull()) }
            .thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.enableSharingAndPrepareSavedContacts(any<Collection<String>>()) }
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
        verify(privatePaykitRepo).enableSharingAndPrepareSavedContacts(listOf(CONTACT_KEY))
    }

    @Test
    fun `enabling without private payment access enables only public payments`() = test {
        whenever { privatePaykitRepo.hasPrivatePaymentAccess() }.thenReturn(false)

        val result = createSut().setEnabled(true)

        assertTrue(result.isSuccess)
        assertTrue(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        verify(privatePaykitRepo, never()).enableSharingAndPrepareSavedContacts(any<Collection<String>>())
    }

    @Test
    fun `enabling with external session access enables private payments`() = test {
        whenever { pubkyRepo.hasSecretKey() }.thenReturn(false)
        whenever { privatePaykitRepo.hasPrivatePaymentAccess() }.thenReturn(true)

        val result = createSut().setEnabled(true)

        assertTrue(result.isSuccess)
        assertTrue(settingsFlow.value.sharesPrivatePaykitEndpoints)
        verify(privatePaykitRepo).enableSharingAndPrepareSavedContacts(listOf(CONTACT_KEY))
        verify(pubkyRepo, never()).hasSecretKey()
    }

    @Test
    fun `failed access check preserves settings and allows retry`() = test {
        val previous = SettingsData(
            sharesPrivatePaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = false,
        )
        settingsFlow.value = previous
        val failure = ContactPaymentSettingsTestError("Paykit unavailable")
        var accessAvailable = false
        whenever(privatePaykitRepo.hasPrivatePaymentAccess()).thenAnswer {
            if (!accessAvailable) throw failure
            true
        }
        val sut = createSut()

        assertSame(failure, sut.setEnabled(true).exceptionOrNull())
        assertEquals(previous, settingsFlow.value)
        verify(settingsStore, never()).update(any())
        verifyNoInteractions(publicPaykitRepo)
        verify(privatePaykitRepo, never()).enableSharingAndPrepareSavedContacts(any<Collection<String>>())
        verify(privatePaykitRepo, never()).disableSharingAndPruneUnsavedContactState(any<Collection<String>>())

        accessAvailable = true

        assertTrue(sut.setEnabled(true).isSuccess)
        assertTrue(settingsFlow.value.sharesPrivatePaykitEndpoints)
        verify(publicPaykitRepo).syncPublishedEndpoints(publish = true)
        verify(privatePaykitRepo).enableSharingAndPrepareSavedContacts(listOf(CONTACT_KEY))
    }

    @Test
    fun `cancelling access check leaves settings unchanged and releases sharing lock`() = test {
        var waitForAccess = true
        whenever(privatePaykitRepo.hasPrivatePaymentAccess()).doSuspendableAnswer {
            if (waitForAccess) awaitCancellation()
            true
        }
        val previous = settingsFlow.value
        val sut = createSut()
        val enable = async { sut.setEnabled(true) }
        runCurrent()
        verify(privatePaykitRepo).hasPrivatePaymentAccess()

        enable.cancelAndJoin()

        assertEquals(previous, settingsFlow.value)
        verify(settingsStore, never()).update(any())
        verifyNoInteractions(publicPaykitRepo)
        verify(privatePaykitRepo, never()).enableSharingAndPrepareSavedContacts(any<Collection<String>>())
        verify(privatePaykitRepo, never()).disableSharingAndPruneUnsavedContactState(any<Collection<String>>())
        waitForAccess = false

        assertTrue(sut.setEnabled(true).isSuccess)
    }

    @Test
    fun `failed publication restores disabled settings`() = test {
        whenever { publicPaykitRepo.syncPublishedEndpoints(publish = true) }
            .thenReturn(Result.failure(ContactPaymentSettingsTestError("publish failed")))

        val result = createSut().setEnabled(true)

        assertTrue(result.isFailure)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        inOrder(privatePaykitRepo, publicPaykitRepo) {
            verify(privatePaykitRepo).disableSharingAndPruneUnsavedContactState(listOf(CONTACT_KEY))
            verify(publicPaykitRepo).syncPublishedEndpoints(publish = false, appSyncPriority = Priority.Interactive)
        }
    }

    @Test
    fun `failed private setup restores sharing with withdrawal priority only when disabled`() = test {
        whenever { privatePaykitRepo.enableSharingAndPrepareSavedContacts(any<Collection<String>>()) }
            .thenReturn(Result.failure(ContactPaymentSettingsTestError("private setup failed")))

        for (wasPublic in listOf(false, true)) {
            clearInvocations(publicPaykitRepo)
            settingsFlow.value = SettingsData(sharesPublicPaykitEndpoints = wasPublic)

            val result = createSut().setEnabled(true)

            assertTrue(result.isFailure)
            assertEquals(wasPublic, settingsFlow.value.sharesPublicPaykitEndpoints)
            assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
            verify(publicPaykitRepo).syncPublishedEndpoints(
                publish = wasPublic,
                appSyncPriority = if (wasPublic) Priority.Ordered else Priority.Interactive,
            )
        }
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
        inOrder(privatePaykitRepo, publicPaykitRepo) {
            verify(privatePaykitRepo).disableSharingAndPruneUnsavedContactState(listOf(CONTACT_KEY))
            verify(publicPaykitRepo).syncPublishedEndpoints(publish = false, appSyncPriority = Priority.Interactive)
        }
    }

    @Test
    fun `failed private cleanup keeps private contact payments disabled`() = test {
        settingsFlow.value = SettingsData(
            hasConfirmedPublicPaykitEndpoints = true,
            sharesPrivatePaykitEndpoints = true,
        )
        whenever { privatePaykitRepo.disableSharingAndPruneUnsavedContactState(any<Collection<String>>()) }
            .thenReturn(Result.failure(ContactPaymentSettingsTestError("cleanup failed")))

        val result = createSut().setEnabled(false)

        assertTrue(result.isFailure)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        verify(privatePaykitRepo, never()).enableSharingAndPrepareSavedContacts(any<Collection<String>>())
    }

    @Test
    fun `failed public cleanup keeps sharing disabled and retains its retry`() = test {
        val cleanupResults = listOf(Result.success(Unit), Result.failure(ContactPaymentSettingsTestError("withdrawal")))
        for (privateCleanup in cleanupResults) {
            settingsFlow.value = SettingsData(
                sharesPublicPaykitEndpoints = true,
                sharesPrivatePaykitEndpoints = true,
            )
            val failure = ContactPaymentSettingsTestError("app update failed")
            whenever(publicPaykitRepo.syncPublishedEndpoints(publish = false, appSyncPriority = Priority.Interactive))
                .thenReturn(Result.failure(failure))
            whenever(privatePaykitRepo.disableSharingAndPruneUnsavedContactState(any<Collection<String>>()))
                .thenReturn(privateCleanup)

            val result = createSut().setEnabled(false)

            assertSame(failure, result.exceptionOrNull())
            assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
            assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
            assertTrue(settingsFlow.value.publicPaykitCleanupPending)
            verify(publicPaykitRepo, never()).syncPublishedEndpoints(publish = true)
            verify(privatePaykitRepo, never()).enableSharingAndPrepareSavedContacts(any<Collection<String>>())
        }
    }

    @Test
    fun `enabling waits for both withdrawal phases even when cleanup fails`() = test {
        val cleanupResults = listOf(Result.success(Unit), Result.failure(ContactPaymentSettingsTestError("withdrawal")))
        val cases = cleanupResults.flatMap { result -> listOf(false to result, true to result) }
        for ((disablePaykit, cleanupResult) in cases) {
            clearInvocations(privatePaykitRepo, publicPaykitRepo)
            settingsFlow.value = SettingsData(sharesPublicPaykitEndpoints = true, sharesPrivatePaykitEndpoints = true)
            val privateCleanup = CompletableDeferred<Unit>()
            val publicCleanup = CompletableDeferred<Unit>()
            whenever(privatePaykitRepo.disableSharingAndPruneUnsavedContactState(any<Collection<String>>()))
                .doSuspendableAnswer {
                    privateCleanup.await()
                    cleanupResult
                }
            whenever(publicPaykitRepo.syncPublishedEndpoints(publish = false, appSyncPriority = Priority.Interactive))
                .doSuspendableAnswer {
                    publicCleanup.await()
                    cleanupResult
                }
            val sut = createSut()

            val disable = async { if (disablePaykit) sut.disablePaykit() else sut.setEnabled(false) }
            runCurrent()
            val enable = async { sut.setEnabled(true) }
            runCurrent()

            assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
            assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
            assertFalse(enable.isCompleted)
            verify(publicPaykitRepo, never()).syncPublishedEndpoints(publish = true)
            privateCleanup.complete(Unit)
            runCurrent()

            assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
            assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
            assertFalse(enable.isCompleted)
            verify(publicPaykitRepo, never()).syncPublishedEndpoints(publish = true)
            publicCleanup.complete(Unit)

            assertEquals(cleanupResult.isSuccess, disable.await().isSuccess)
            assertTrue(enable.await().isSuccess)
            assertTrue(settingsFlow.value.sharesPublicPaykitEndpoints)
            assertTrue(settingsFlow.value.sharesPrivatePaykitEndpoints)
            inOrder(privatePaykitRepo, publicPaykitRepo) {
                verify(privatePaykitRepo).disableSharingAndPruneUnsavedContactState(listOf(CONTACT_KEY))
                verify(publicPaykitRepo).syncPublishedEndpoints(publish = false, appSyncPriority = Priority.Interactive)
                verify(publicPaykitRepo).syncPublishedEndpoints(publish = true)
                verify(privatePaykitRepo).enableSharingAndPrepareSavedContacts(listOf(CONTACT_KEY))
            }
        }
    }

    @Test
    fun `sharing changes wait for failed enable rollback`() = test {
        val rollback = CompletableDeferred<Unit>()
        whenever(privatePaykitRepo.enableSharingAndPrepareSavedContacts(any<Collection<String>>()))
            .thenReturn(Result.failure(ContactPaymentSettingsTestError("private setup failed")))
        whenever(privatePaykitRepo.disableSharingAndPruneUnsavedContactState(any<Collection<String>>()))
            .doSuspendableAnswer {
                rollback.await()
                Result.success(Unit)
            }
        val sut = createSut()

        val enable = async { sut.setEnabled(true) }
        runCurrent()
        val disable = async { sut.setEnabled(false) }
        runCurrent()

        assertFalse(disable.isCompleted)
        verify(privatePaykitRepo).disableSharingAndPruneUnsavedContactState(listOf(CONTACT_KEY))
        verify(publicPaykitRepo, never()).syncPublishedEndpoints(
            publish = false,
            appSyncPriority = Priority.Interactive,
        )
        rollback.complete(Unit)

        assertTrue(enable.await().isFailure)
        assertTrue(disable.await().isSuccess)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
    }

    @Test
    fun `foreground cleanup coalesces and sharing waits for it`() = test {
        val cleanup = CompletableDeferred<Unit>()
        var cleanupCount = 0
        val sut = createSut()
        val reconciliation = launch {
            sut.reconcilePendingEndpoints {
                cleanupCount++
                cleanup.await()
            }
        }
        runCurrent()
        sut.reconcilePendingEndpoints { cleanupCount++ }
        val enable = async { sut.setEnabled(true) }
        runCurrent()

        assertEquals(1, cleanupCount)
        assertFalse(enable.isCompleted)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        verify(publicPaykitRepo, never()).syncPublishedEndpoints(publish = true)
        cleanup.complete(Unit)
        reconciliation.join()

        assertTrue(enable.await().isSuccess)
        sut.reconcilePendingEndpoints { cleanupCount++ }
        assertEquals(2, cleanupCount)
    }

    @Test
    fun `cancelling queued enable does not publish or block later changes`() = test {
        val cleanup = CompletableDeferred<Unit>()
        whenever(privatePaykitRepo.disableSharingAndPruneUnsavedContactState(any<Collection<String>>()))
            .doSuspendableAnswer {
                cleanup.await()
                Result.success(Unit)
            }
        val sut = createSut()
        val disable = async { sut.setEnabled(false) }
        runCurrent()
        val enable = async { sut.setEnabled(true) }
        runCurrent()

        assertFalse(enable.isCompleted)
        enable.cancelAndJoin()
        cleanup.complete(Unit)

        assertTrue(disable.await().isSuccess)
        assertTrue(sut.setEnabled(false).isSuccess)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        verify(publicPaykitRepo, never()).syncPublishedEndpoints(publish = true)
        verify(privatePaykitRepo, never()).enableSharingAndPrepareSavedContacts(any<Collection<String>>())
    }

    @Test
    fun `cancelling foreground cleanup releases waiting sharing change`() = test {
        val sut = createSut()
        val reconciliation = launch {
            sut.reconcilePendingEndpoints { awaitCancellation() }
        }
        runCurrent()
        val enable = async { sut.setEnabled(true) }
        runCurrent()

        assertFalse(enable.isCompleted)
        reconciliation.cancelAndJoin()

        assertTrue(enable.await().isSuccess)
        assertTrue(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertTrue(settingsFlow.value.sharesPrivatePaykitEndpoints)
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

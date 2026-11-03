package to.bitkit.repositories

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.isNull
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
import kotlin.test.assertFailsWith
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
    private val contacts = MutableStateFlow(listOf(createContact()))

    @Before
    fun setUp() {
        settingsFlow.value = SettingsData()
        whenever(settingsStore.data).thenReturn(settingsFlow)
        whenever(pubkyRepo.contacts).thenReturn(contacts)
        whenever(pubkyRepo.currentSignIn()).thenReturn(PubkySignIn("identity", 0L))
        whenever(pubkyRepo.isCurrent(any())).thenReturn(true)
        whenever { privatePaykitRepo.hasPrivatePaymentAccess() }.thenReturn(true)
        whenever { settingsStore.update(any()) }.thenAnswer {
            val transform = it.getArgument<(SettingsData) -> SettingsData>(0)
            settingsFlow.value = transform(settingsFlow.value)
            Unit
        }
        whenever { publicPaykitRepo.syncPublishedEndpoints(any()) }.thenReturn(Result.success(Unit))
        whenever { publicPaykitRepo.syncPublishedEndpoints(any(), any(), isNull()) }.thenReturn(Result.success(Unit))
        whenever { publicPaykitRepo.syncPublishedEndpoints(any(), any(), any()) }.thenReturn(Result.success(Unit))
        whenever { publicPaykitRepo.syncCurrentPublishedEndpoints(any(), any(), any()) }
            .thenReturn(Result.success(Unit))
        whenever(privatePaykitRepo.isProfileDeletionInProgress).thenReturn(MutableStateFlow(false))
        whenever { publicPaykitRepo.syncPaykitApp(anyOrNull(), canSync = anyOrNull()) }
            .thenReturn(Result.success(Unit))
        whenever { publicPaykitRepo.syncPaykitApp(anyOrNull(), any<Priority>(), anyOrNull()) }
            .thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.enableSharingAndPrepareSavedContacts(any<() -> Collection<String>>(), any()) }
            .thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.disableSharingAndPruneUnsavedContactState(any(), anyOrNull()) }
            .thenReturn(Result.success(Unit))
    }

    @Test
    fun `enabling contact payments only enables private sharing`() = test {
        settingsFlow.value = SettingsData(
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = false,
        )

        val result = createSut().setEnabled(true)

        assertTrue(result.isSuccess)
        assertFalse(settingsFlow.value.hasConfirmedPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertTrue(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertFalse(settingsFlow.value.publicPaykitLightningEnabled)
        assertFalse(settingsFlow.value.publicPaykitOnchainEnabled)
        verify(publicPaykitRepo, never()).syncPublishedEndpoints(any())
        verify(publicPaykitRepo, never()).syncPublishedEndpoints(any(), any(), anyOrNull())
        verify(publicPaykitRepo).syncPaykitApp(isNull(), canSync = any())
        verify(privatePaykitRepo).enableSharingAndPrepareSavedContacts(
            argThat { invoke().toSet() == setOf(CONTACT_KEY) },
            any(),
        )
    }

    @Test
    fun `enabling prepares contacts saved during app registration`() = test {
        val publication = CompletableDeferred<Unit>()
        whenever(publicPaykitRepo.syncPaykitApp(isNull(), canSync = any())).doSuspendableAnswer {
            publication.await()
            Result.success(Unit)
        }
        contacts.update { emptyList() }
        val enable = async { createSut().setEnabled(true) }
        runCurrent()
        verify(publicPaykitRepo).syncPaykitApp(isNull(), canSync = any())

        contacts.update { listOf(createContact()) }
        publication.complete(Unit)
        assertTrue(enable.await().isSuccess)

        verify(privatePaykitRepo).enableSharingAndPrepareSavedContacts(
            argThat { invoke().toSet() == setOf(CONTACT_KEY) },
            any(),
        )
        verify(privatePaykitRepo, never()).enableSharingAndPrepareSavedContacts(argThat { invoke().isEmpty() }, any())
    }

    @Test
    fun `contact changes do not invalidate the sharing enable transition`() = test {
        whenever(privatePaykitRepo.enableSharingAndPrepareSavedContacts(any(), any())).thenAnswer {
            contacts.update { emptyList() }
            val isCurrent = it.getArgument<() -> Boolean>(1)
            assertTrue(isCurrent())
            val savedPublicKeys = it.getArgument<() -> Collection<String>>(0)
            assertTrue(savedPublicKeys().isEmpty())
            whenever(pubkyRepo.isCurrent(any())).thenReturn(false)
            assertFalse(isCurrent())
            Result.success(Unit)
        }

        assertTrue(createSut().setEnabled(true).isSuccess)
    }

    @Test
    fun `enabling skips private preparation after its sign in ends`() = test {
        val publication = CompletableDeferred<Unit>()
        whenever(publicPaykitRepo.syncPaykitApp(isNull(), canSync = any())).doSuspendableAnswer {
            publication.await()
            Result.success(Unit)
        }
        val enable = async { createSut().setEnabled(true) }
        runCurrent()
        verify(publicPaykitRepo).syncPaykitApp(isNull(), canSync = any())

        whenever(pubkyRepo.isCurrent(any())).thenReturn(false)
        publication.complete(Unit)
        assertTrue(enable.await().isSuccess)

        verify(privatePaykitRepo, never()).enableSharingAndPrepareSavedContacts(any(), any())
    }

    @Test
    fun `enabling without private payment access does not fall back to public sharing`() = test {
        whenever { privatePaykitRepo.hasPrivatePaymentAccess() }.thenReturn(false)

        val result = createSut().setEnabled(true)

        assertTrue(result.isFailure)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        verify(privatePaykitRepo, never()).enableSharingAndPrepareSavedContacts(any<() -> Collection<String>>(), any())
    }

    @Test
    fun `enabling with external session access enables private payments`() = test {
        whenever { pubkyRepo.hasSecretKey() }.thenReturn(false)
        whenever { privatePaykitRepo.hasPrivatePaymentAccess() }.thenReturn(true)

        val result = createSut().setEnabled(true)

        assertTrue(result.isSuccess)
        assertTrue(settingsFlow.value.sharesPrivatePaykitEndpoints)
        verify(privatePaykitRepo).enableSharingAndPrepareSavedContacts(
            argThat { invoke().toSet() == setOf(CONTACT_KEY) },
            any(),
        )
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
        verify(privatePaykitRepo, never()).enableSharingAndPrepareSavedContacts(any<() -> Collection<String>>(), any())
        verify(privatePaykitRepo, never()).disableSharingAndPruneUnsavedContactState(any(), anyOrNull())

        accessAvailable = true

        assertTrue(sut.setEnabled(true).isSuccess)
        assertTrue(settingsFlow.value.sharesPrivatePaykitEndpoints)
        verify(publicPaykitRepo).syncPaykitApp(isNull(), canSync = any())
        verify(privatePaykitRepo).enableSharingAndPrepareSavedContacts(
            argThat { invoke().toSet() == setOf(CONTACT_KEY) },
            any(),
        )
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
        verify(privatePaykitRepo, never()).enableSharingAndPrepareSavedContacts(any<() -> Collection<String>>(), any())
        verify(privatePaykitRepo, never()).disableSharingAndPruneUnsavedContactState(any(), anyOrNull())
        waitForAccess = false

        assertTrue(sut.setEnabled(true).isSuccess)
    }

    @Test
    fun `failed app registration restores disabled private settings`() = test {
        whenever { publicPaykitRepo.syncPaykitApp(isNull(), canSync = any()) }
            .thenReturn(Result.failure(ContactPaymentSettingsTestError("publish failed")))

        val result = createSut().setEnabled(true)

        assertTrue(result.isFailure)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        inOrder(privatePaykitRepo, publicPaykitRepo) {
            verify(privatePaykitRepo).disableSharingAndPruneUnsavedContactState(eq(listOf(CONTACT_KEY)), anyOrNull())
            verify(publicPaykitRepo).syncPaykitApp(isNull(), eq(Priority.Interactive), any())
        }
    }

    @Test
    fun `failed private setup leaves public sharing unchanged`() = test {
        whenever { privatePaykitRepo.enableSharingAndPrepareSavedContacts(any<() -> Collection<String>>(), any()) }
            .thenReturn(Result.failure(ContactPaymentSettingsTestError("private setup failed")))

        for (wasPublic in listOf(false, true)) {
            clearInvocations(publicPaykitRepo)
            settingsFlow.value = SettingsData(sharesPublicPaykitEndpoints = wasPublic)

            val result = createSut().setEnabled(true)

            assertTrue(result.isFailure)
            assertEquals(wasPublic, settingsFlow.value.sharesPublicPaykitEndpoints)
            assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
            verify(publicPaykitRepo).syncPaykitApp(isNull(), eq(Priority.Interactive), any())
            verify(publicPaykitRepo, never()).syncPublishedEndpoints(any(), any(), anyOrNull())
        }
    }

    @Test
    fun `disabling private contact payments leaves public payments enabled`() = test {
        settingsFlow.value = SettingsData(
            hasConfirmedPublicPaykitEndpoints = true,
            sharesPublicPaykitEndpoints = true,
            sharesPrivatePaykitEndpoints = true,
        )

        val result = createSut().setEnabled(false)

        assertTrue(result.isSuccess)
        assertTrue(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        inOrder(privatePaykitRepo, publicPaykitRepo) {
            verify(privatePaykitRepo).disableSharingAndPruneUnsavedContactState(eq(listOf(CONTACT_KEY)), anyOrNull())
            verify(publicPaykitRepo).syncPaykitApp(isNull(), eq(Priority.Interactive), any())
        }
    }

    @Test
    fun `failed private cleanup keeps private contact payments disabled`() = test {
        settingsFlow.value = SettingsData(
            hasConfirmedPublicPaykitEndpoints = true,
            sharesPrivatePaykitEndpoints = true,
        )
        whenever { privatePaykitRepo.disableSharingAndPruneUnsavedContactState(any(), anyOrNull()) }
            .thenReturn(Result.failure(ContactPaymentSettingsTestError("cleanup failed")))

        val result = createSut().setEnabled(false)

        assertTrue(result.isFailure)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        verify(privatePaykitRepo, never()).enableSharingAndPrepareSavedContacts(any<() -> Collection<String>>(), any())
    }

    @Test
    fun `failed public cleanup keeps sharing disabled and retains its retry`() = test {
        for (privateEnabled in listOf(false, true)) {
            settingsFlow.value = SettingsData(
                sharesPublicPaykitEndpoints = true,
                sharesPrivatePaykitEndpoints = privateEnabled,
            )
            val failure = ContactPaymentSettingsTestError("app update failed")
            whenever(publicPaykitRepo.syncPublishedEndpoints(eq(false), eq(Priority.Interactive), any()))
                .thenReturn(Result.failure(failure))
            val result = createSut().setPublicEnabled(false)

            assertSame(failure, result.exceptionOrNull())
            assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
            assertEquals(privateEnabled, settingsFlow.value.sharesPrivatePaykitEndpoints)
            assertTrue(settingsFlow.value.publicPaykitCleanupPending)
            verify(publicPaykitRepo, never()).syncPublishedEndpoints(publish = true)
            verify(privatePaykitRepo, never()).enableSharingAndPrepareSavedContacts(
                any<() -> Collection<String>>(),
                any(),
            )
        }
    }

    @Test
    fun `enabling waits for both withdrawal phases even when cleanup fails`() = test {
        val cleanupResults = listOf(Result.success(Unit), Result.failure(ContactPaymentSettingsTestError("withdrawal")))
        for (cleanupResult in cleanupResults) {
            clearInvocations(privatePaykitRepo, publicPaykitRepo)
            settingsFlow.value = SettingsData(sharesPublicPaykitEndpoints = true, sharesPrivatePaykitEndpoints = true)
            val privateCleanup = CompletableDeferred<Unit>()
            val publicCleanup = CompletableDeferred<Unit>()
            whenever(privatePaykitRepo.disableSharingAndPruneUnsavedContactState(any(), anyOrNull()))
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

            val disable = async { sut.disablePaykit() }
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
            assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
            assertTrue(settingsFlow.value.sharesPrivatePaykitEndpoints)
            inOrder(privatePaykitRepo, publicPaykitRepo) {
                verify(privatePaykitRepo).disableSharingAndPruneUnsavedContactState(
                    eq(listOf(CONTACT_KEY)),
                    anyOrNull(),
                )
                verify(publicPaykitRepo).syncPublishedEndpoints(publish = false, appSyncPriority = Priority.Interactive)
                verify(publicPaykitRepo).syncPaykitApp(isNull(), canSync = any())
                verify(privatePaykitRepo).enableSharingAndPrepareSavedContacts(
                    argThat { invoke().toSet() == setOf(CONTACT_KEY) },
                    any(),
                )
            }
        }
    }

    @Test
    fun `sharing changes wait for failed enable rollback`() = test {
        val rollback = CompletableDeferred<Unit>()
        whenever(privatePaykitRepo.enableSharingAndPrepareSavedContacts(any<() -> Collection<String>>(), any()))
            .thenReturn(Result.failure(ContactPaymentSettingsTestError("private setup failed")))
        whenever(privatePaykitRepo.disableSharingAndPruneUnsavedContactState(any(), anyOrNull()))
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
        verify(privatePaykitRepo).disableSharingAndPruneUnsavedContactState(eq(listOf(CONTACT_KEY)), anyOrNull())
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
        whenever(privatePaykitRepo.disableSharingAndPruneUnsavedContactState(any(), anyOrNull()))
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
        verify(privatePaykitRepo, never()).enableSharingAndPrepareSavedContacts(any<() -> Collection<String>>(), any())
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
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertTrue(settingsFlow.value.sharesPrivatePaykitEndpoints)
    }

    private fun createSut() = ContactPaymentSettingsRepo(
        settingsStore = settingsStore,
        publicPaykitRepo = publicPaykitRepo,
        privatePaykitRepo = privatePaykitRepo,
        pubkyRepo = pubkyRepo,
        ioDispatcher = testDispatcher,
    )

    @Test
    fun `explicit public opt in and off preserve private sharing in either state`() = test {
        for (privateEnabled in listOf(false, true)) {
            settingsFlow.value = SettingsData(sharesPrivatePaykitEndpoints = privateEnabled)
            val sut = createSut()
            sut.setPublicEnabled(true).getOrThrow()
            assertTrue(settingsFlow.value.sharesPublicPaykitEndpoints)
            assertTrue(settingsFlow.value.hasConfirmedPublicPaykitEndpoints)
            assertEquals(privateEnabled, settingsFlow.value.sharesPrivatePaykitEndpoints)
            sut.setPublicEnabled(false).getOrThrow()
            assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
            assertEquals(privateEnabled, settingsFlow.value.sharesPrivatePaykitEndpoints)
        }
        verify(privatePaykitRepo, never()).enableSharingAndPrepareSavedContacts(any(), any())
        verify(privatePaykitRepo, never()).disableSharingAndPruneUnsavedContactState(any(), anyOrNull())
    }

    @Test
    fun `failed public enable restores public consent without changing private sharing`() = test {
        val publicationError = ContactPaymentSettingsTestError("publication failed")
        whenever(publicPaykitRepo.syncPublishedEndpoints(eq(true), eq(Priority.Ordered), any()))
            .thenReturn(Result.failure(publicationError))
        for (privateEnabled in listOf(false, true)) {
            settingsFlow.value = SettingsData(sharesPrivatePaykitEndpoints = privateEnabled)
            whenever(publicPaykitRepo.syncPublishedEndpoints(eq(false), eq(Priority.Interactive), any())).thenAnswer {
                assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
                settingsFlow.update { it.copy(publicPaykitCleanupPending = false) }
                Result.success(Unit)
            }

            val result = createSut().setPublicEnabled(true)

            assertSame(publicationError, result.exceptionOrNull())
            assertFalse(settingsFlow.value.hasConfirmedPublicPaykitEndpoints)
            assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
            assertFalse(settingsFlow.value.publicPaykitCleanupPending)
            assertEquals(privateEnabled, settingsFlow.value.sharesPrivatePaykitEndpoints)
        }
        verify(privatePaykitRepo, never()).disableSharingAndPruneUnsavedContactState(any(), anyOrNull())
    }

    @Test
    fun `failed rollback removal leaves public disabled and cleanup pending`() = test {
        val publicationError = ContactPaymentSettingsTestError("publication failed")
        val cleanupError = ContactPaymentSettingsTestError("removal failed")
        whenever(publicPaykitRepo.syncPublishedEndpoints(eq(true), eq(Priority.Ordered), any()))
            .thenReturn(Result.failure(publicationError))
        whenever(publicPaykitRepo.syncPublishedEndpoints(eq(false), eq(Priority.Interactive), any()))
            .thenReturn(Result.failure(cleanupError))

        val result = createSut().setPublicEnabled(true)

        assertSame(publicationError, result.exceptionOrNull())
        assertTrue(publicationError.suppressed.contains(cleanupError))
        assertFalse(settingsFlow.value.hasConfirmedPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertTrue(settingsFlow.value.publicPaykitCleanupPending)
    }

    @Test
    fun `public rollback preserves publisher acknowledgement and newer pending invoice`() = test {
        whenever(publicPaykitRepo.syncPublishedEndpoints(eq(true), eq(Priority.Ordered), any()))
            .thenReturn(Result.failure(ContactPaymentSettingsTestError("publication failed")))
        for (newerInvoicePending in listOf(false, true)) {
            settingsFlow.value = SettingsData(
                hasConfirmedPublicPaykitEndpoints = true,
                sharesPublicPaykitEndpoints = true,
                sharesPrivatePaykitEndpoints = true,
                publicPaykitBolt11 = "old-invoice",
            )
            whenever(publicPaykitRepo.syncCurrentPublishedEndpoints(eq(false), eq(false), any())).thenAnswer {
                assertTrue(settingsFlow.value.sharesPublicPaykitEndpoints)
                settingsFlow.update {
                    it.copy(publicPaykitBolt11 = "new-invoice", publicPaykitCleanupPending = newerInvoicePending)
                }
                Result.success(Unit)
            }

            assertTrue(createSut().setPublicEnabled(true).isFailure)

            assertTrue(settingsFlow.value.sharesPublicPaykitEndpoints)
            assertTrue(settingsFlow.value.hasConfirmedPublicPaykitEndpoints)
            assertTrue(settingsFlow.value.sharesPrivatePaykitEndpoints)
            assertEquals("new-invoice", settingsFlow.value.publicPaykitBolt11)
            assertEquals(newerInvoicePending, settingsFlow.value.publicPaykitCleanupPending)
        }
        verify(publicPaykitRepo, never()).syncPublishedEndpoints(eq(false), eq(Priority.Interactive), any())
    }

    @Test
    fun `public rollback does not restore consent after identity change or deletion`() = test {
        val deleting = MutableStateFlow(false)
        whenever(privatePaykitRepo.isProfileDeletionInProgress).thenReturn(deleting)
        for (identityChanged in listOf(false, true)) {
            deleting.value = false
            whenever(pubkyRepo.isCurrent(any())).thenReturn(true)
            settingsFlow.value = SettingsData(sharesPublicPaykitEndpoints = true)
            doSuspendableAnswer {
                settingsFlow.value = SettingsData()
                if (identityChanged) {
                    whenever(pubkyRepo.isCurrent(any())).thenReturn(false)
                } else {
                    deleting.value = true
                }
                Result.failure<Unit>(ContactPaymentSettingsTestError("publication interrupted"))
            }.whenever(publicPaykitRepo).syncPublishedEndpoints(eq(true), eq(Priority.Ordered), any())

            assertTrue(createSut().setPublicEnabled(true).isFailure)

            assertEquals(SettingsData(), settingsFlow.value)
        }
        verify(publicPaykitRepo, never()).syncCurrentPublishedEndpoints(any(), any(), any())
        verify(publicPaykitRepo, never()).syncPublishedEndpoints(eq(false), eq(Priority.Interactive), any())
    }

    @Test
    fun `cancelled public enable restores prior consent and leaves cleanup for retry`() = test {
        for (wasPublic in listOf(false, true)) {
            settingsFlow.value = SettingsData(
                hasConfirmedPublicPaykitEndpoints = wasPublic,
                sharesPublicPaykitEndpoints = wasPublic,
                sharesPrivatePaykitEndpoints = true,
            )
            whenever(publicPaykitRepo.syncPublishedEndpoints(eq(true), eq(Priority.Ordered), any()))
                .doSuspendableAnswer { awaitCancellation() }
            val sut = createSut()
            val enable = async { sut.setPublicEnabled(true) }
            runCurrent()

            enable.cancelAndJoin()

            assertEquals(wasPublic, settingsFlow.value.sharesPublicPaykitEndpoints)
            assertEquals(wasPublic, settingsFlow.value.hasConfirmedPublicPaykitEndpoints)
            assertTrue(settingsFlow.value.sharesPrivatePaykitEndpoints)
            assertTrue(settingsFlow.value.publicPaykitCleanupPending)
            assertTrue(sut.setPublicEnabled(false).isSuccess)
        }
    }

    @Test
    fun `cancellation after the durable consent commit restores public off before releasing sharing`() = test {
        for (privateEnabled in listOf(false, true)) {
            settingsFlow.value = SettingsData(sharesPrivatePaykitEndpoints = privateEnabled)
            val committed = CompletableDeferred<Unit>()
            val resumeCaller = CompletableDeferred<Unit>()
            whenever(settingsStore.update(any())).doSuspendableAnswer {
                val transform = it.getArgument<(SettingsData) -> SettingsData>(0)
                settingsFlow.update(transform)
                if (settingsFlow.value.sharesPublicPaykitEndpoints) {
                    committed.complete(Unit)
                    resumeCaller.await()
                }
                Unit
            }
            val sut = createSut()
            val enable = async { sut.setPublicEnabled(true) }
            committed.await()
            enable.cancel()
            runCurrent()
            assertFalse(enable.isCompleted)
            assertTrue(settingsFlow.value.sharesPublicPaykitEndpoints)

            resumeCaller.complete(Unit)
            assertFailsWith<CancellationException> { enable.await() }

            assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
            assertFalse(settingsFlow.value.hasConfirmedPublicPaykitEndpoints)
            assertEquals(privateEnabled, settingsFlow.value.sharesPrivatePaykitEndpoints)
            assertTrue(settingsFlow.value.publicPaykitCleanupPending)
            verify(publicPaykitRepo, never()).syncPublishedEndpoints(eq(true), any(), any())
            assertTrue(sut.setPublicEnabled(false).isSuccess)
        }
    }

    @Test
    fun `cancelled public cleanup remains disabled and pending across retry`() = test {
        settingsFlow.value = SettingsData(sharesPublicPaykitEndpoints = true, sharesPrivatePaykitEndpoints = true)
        whenever(publicPaykitRepo.syncPublishedEndpoints(eq(false), eq(Priority.Interactive), any()))
            .doSuspendableAnswer { awaitCancellation() }
        val sut = createSut()
        val disable = async { sut.setPublicEnabled(false) }
        runCurrent()
        disable.cancelAndJoin()
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertTrue(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertTrue(settingsFlow.value.publicPaykitCleanupPending)
        whenever(publicPaykitRepo.syncPublishedEndpoints(eq(false), eq(Priority.Interactive), any()))
            .thenReturn(Result.success(Unit))
        assertTrue(sut.setPublicEnabled(false).isSuccess)
    }

    @Test
    fun `public enable admission belongs to its original sign in and excludes profile deletion`() = test {
        val deleting = MutableStateFlow(false)
        whenever(privatePaykitRepo.isProfileDeletionInProgress).thenReturn(deleting)
        lateinit var admission: () -> Boolean
        whenever(publicPaykitRepo.syncPublishedEndpoints(eq(true), eq(Priority.Ordered), any())).thenAnswer {
            admission = it.getArgument(2)
            Result.success(Unit)
        }
        createSut().setPublicEnabled(true).getOrThrow()
        assertTrue(admission())
        deleting.value = true
        assertFalse(admission())
        deleting.value = false
        whenever(pubkyRepo.isCurrent(any())).thenReturn(false)
        assertFalse(admission())
    }

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

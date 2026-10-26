package to.bitkit.usecases

import androidx.datastore.core.DataStore
import com.synonym.paykit.ContactRecord
import com.synonym.paykit.PublicationStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.ProfileRemovalState
import to.bitkit.data.ProfileRemovalStore
import to.bitkit.ext.runSuspendCatching
import to.bitkit.repositories.ContactPaymentSettingsRepo
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyProfileDeletion
import to.bitkit.repositories.PubkyProfileOwner
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.PubkySignIn
import to.bitkit.services.PaykitProfileDeletionSnapshot
import to.bitkit.test.BaseUnitTest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProfileRemovalCoordinatorTest : BaseUnitTest() {
    private val pubky = mock<PubkyRepo>()
    private val privateRepo = mock<PrivatePaykitRepo>()
    private val sharing = mock<ContactPaymentSettingsRepo>()
    private var store = ProfileRemovalStore.inMemoryForTesting()
    private val original = PubkyProfileOwner(PubkySignIn("identity", 1), 1)
    private val ended = PubkyProfileOwner(null, 2)
    private var current = original
    private var rejectAdmission = false
    private val pause = PrivatePaykitRepo.ProfileDeletionPause()
    private val events = mutableListOf<String>()
    private val deletion =
        PubkyProfileDeletion(original, 1, PaykitProfileDeletionSnapshot("identity", "revision", emptyList()))
    private val sut get() = ProfileRemovalCoordinator(pubky, privateRepo, sharing, store)

    @Before
    fun setup() = test {
        whenever(pubky.profileOwner()).thenAnswer { current }
        whenever(pubky.isProfileOwnerCurrent(any())).thenAnswer { it.getArgument<PubkyProfileOwner>(0) === current }
        whenever(pubky.prepareProfileDeletion(original)).thenReturn(Result.success(deletion))
        whenever { sharing.withProfileRemoval<PubkyProfileOwner>(any()) }.doSuspendableAnswer {
            it.getArgument<suspend () -> PubkyProfileOwner>(0).invoke()
        }
        whenever { pubky.performProfileRemoval(any(), any(), any()) }.doSuspendableAnswer {
            val owner = it.getArgument<PubkyProfileOwner>(0)
            val id = it.getArgument<String>(1)
            val operation = it.getArgument<suspend () -> Unit>(2)
            runSuspendCatching {
                check(!rejectAdmission) { "busy before operation admission" }
                check(owner === current)
                store.withAccess(id) { operation() }
                current
            }
        }
        whenever { privateRepo.withProfileDeletionPaused<Unit>(any()) }.doSuspendableAnswer {
            it.getArgument<suspend (PrivatePaykitRepo.ProfileDeletionPause) -> Unit>(0).invoke(pause)
        }
        whenever { pubky.validateProfileDeletion(deletion) }.doSuspendableAnswer {
            events += "validate"
            Unit
        }
        whenever { privateRepo.removePublishedEndpointsForCleanup(any(), eq(true)) }.doSuspendableAnswer {
            assertNotNull(store.pendingAttempt())
            events += "private endpoints"
            Result.success(Unit)
        }
        whenever { pubky.removeBitkitPaymentEndpoints() }.thenAnswer {
            events += "public endpoints"
            Result.success(Unit)
        }
        whenever { pubky.deleteProfile(deletion) }.thenAnswer {
            events += "profile"
            Result.success(Unit)
        }
        whenever { pubky.signOutForProfileRemoval() }.thenAnswer {
            events += "revoke"
            Unit
        }
        whenever { pubky.finishProfileRemovalLocally() }.doSuspendableAnswer {
            assertNotNull(store.pendingAttempt())
            events += "local credentials and metadata"
            current = ended
        }
        whenever { privateRepo.finishProfileRemoval(pause) }.doSuspendableAnswer {
            assertNotNull(store.pendingAttempt())
            events += "private local"
            Result.success(Unit)
        }
    }

    @Test
    fun `confirmed removal acknowledges credentials before clearing private state and clears marker last`() = test {
        val confirmation = sut.prepareDeletion(original).getOrThrow()
        assertNull(store.pendingAttempt())
        assertSame(ended, sut.remove(confirmation).getOrThrow())
        assertEquals(
            listOf(
                "validate",
                "private endpoints",
                "public endpoints",
                "profile",
                "revoke",
                "local credentials and metadata",
                "private local"
            ),
            events
        )
        assertNull(store.pendingAttempt())
        assertFalse(sut.isCurrent(original))
        assertTrue(sut.isCurrent(ended))
    }

    @Test
    fun `one confirmation cannot repeat an uncertain profile mutation`() = test {
        whenever(pubky.deleteProfile(deletion)).thenReturn(Result.failure(IllegalStateException("lost response")))
        val confirmation = sut.prepareDeletion(original).getOrThrow()
        assertIs<ProfileRemovalFailure>(sut.remove(confirmation).exceptionOrNull())
        assertTrue(sut.remove(confirmation).isFailure)
        verify(pubky, times(1)).deleteProfile(deletion)
        verify(pubky, never()).signOutForProfileRemoval()
        verify(privateRepo, never()).finishProfileRemoval(any())
        assertEquals(confirmation.attemptId, store.pendingAttempt())
    }

    @Test
    fun `review after uncertain deletion reads original owner and requires another confirmation`() = test {
        whenever(pubky.deleteProfile(deletion)).thenReturn(Result.failure(IllegalStateException("lost response")))
        val first = sut.prepareDeletion(original).getOrThrow()
        val failure = assertIs<ProfileRemovalFailure>(sut.remove(first).exceptionOrNull())
        val second = sut.reviewAgain(failure).getOrThrow()
        assertEquals(first.attemptId, second.attemptId)
        assertFalse(second.used.get())
        verify(pubky, times(2)).prepareProfileDeletion(original)
        verify(pubky, times(1)).deleteProfile(deletion)
        current = PubkyProfileOwner(PubkySignIn("replacement", 3), 3)
        assertTrue(sut.reviewAgain(failure).isFailure)
        assertTrue(sut.prepareLocalDisconnect(failure).isFailure)
    }

    @Test
    fun `review rejects a later profile revision instead of replacing original intent`() = test {
        val failure = failRemoval(deletion)
        whenever(pubky.prepareProfileDeletion(original)).thenReturn(
            Result.success(deletion.withSnapshot(deletion.snapshot.copy(profileRevision = "later-edit")))
        )
        assertTrue(sut.reviewAgain(failure).isFailure)
        verify(pubky, times(1)).deleteProfile(deletion)
    }

    @Test
    fun `review rejects added or edited contacts before another confirmation`() = test {
        val alice = contact("alice")
        val first = deletion.withSnapshot(deletion.snapshot.copy(contacts = listOf(alice)))
        val failure = failRemoval(first)
        for (records in listOf(listOf(alice, contact("bob")), listOf(alice.copy(label = "Later edit")))) {
            whenever(pubky.prepareProfileDeletion(original)).thenReturn(
                Result.success(first.withSnapshot(first.snapshot.copy(contacts = records)))
            )
            assertTrue(sut.reviewAgain(failure).isFailure)
        }
        verify(pubky, times(1)).deleteProfile(first)
    }

    @Test
    fun `review accepts only unchanged residuals and cannot restore a removed contact target`() = test {
        val alice = contact("alice")
        val bob = contact("bob")
        val first = deletion.withSnapshot(deletion.snapshot.copy(contacts = listOf(alice, bob)))
        val failure = failRemoval(first)
        val residual = first.withSnapshot(first.snapshot.copy(profileRevision = null, contacts = listOf(bob)))
        whenever(pubky.prepareProfileDeletion(original)).thenReturn(Result.success(residual))
        val second = sut.reviewAgain(failure).getOrThrow()
        assertEquals(residual, second.deletion)
        assertEquals(failure.confirmation.attemptId, second.attemptId)
        verify(pubky, times(1)).deleteProfile(first)
        whenever(pubky.deleteProfile(residual)).thenReturn(Result.failure(IllegalStateException("lost response")))
        val secondFailure = assertIs<ProfileRemovalFailure>(sut.remove(second).exceptionOrNull())
        whenever(pubky.prepareProfileDeletion(original)).thenReturn(
            Result.success(residual.withSnapshot(residual.snapshot.copy(contacts = listOf(alice, bob))))
        )
        assertTrue(sut.reviewAgain(secondFailure).isFailure)
        verify(pubky, times(1)).deleteProfile(residual)
    }

    @Test
    fun `review rejects a profile recreated after an earlier review observed absence`() = test {
        val failure = failRemoval(deletion)
        val absent = deletion.withSnapshot(deletion.snapshot.copy(profileRevision = null))
        whenever(pubky.prepareProfileDeletion(original)).thenReturn(Result.success(absent))
        val second = sut.reviewAgain(failure).getOrThrow()
        whenever(pubky.deleteProfile(absent)).thenReturn(Result.failure(IllegalStateException("unknown")))
        val secondFailure = assertIs<ProfileRemovalFailure>(sut.remove(second).exceptionOrNull())
        whenever(pubky.prepareProfileDeletion(original)).thenReturn(Result.success(deletion))
        assertTrue(sut.reviewAgain(secondFailure).isFailure)
    }

    @Test
    fun `old failure cannot expand residuals after a confirmation admission failure`() = test {
        val first = deletion.withSnapshot(deletion.snapshot.copy(contacts = listOf(contact("alice"), contact("bob"))))
        val failure = failRemoval(first)
        val remaining = first.withSnapshot(first.snapshot.copy(contacts = listOf(contact("bob"))))
        whenever(pubky.prepareProfileDeletion(original)).thenReturn(Result.success(remaining))
        val second = sut.reviewAgain(failure).getOrThrow()
        rejectAdmission = true
        assertTrue(sut.remove(second).isFailure)
        assertNull(sut.failureFor(second))
        rejectAdmission = false
        whenever(pubky.prepareProfileDeletion(original)).thenReturn(Result.success(first))
        assertTrue(sut.reviewAgain(failure).isFailure)
        verify(pubky, times(1)).deleteProfile(first)
        verify(pubky, never()).deleteProfile(remaining)
    }

    @Test
    fun `cancelled residual review cannot reintroduce an absent profile through an older failure`() = test {
        val failure = failRemoval(deletion)
        val remaining = deletion.withSnapshot(deletion.snapshot.copy(profileRevision = null))
        whenever(pubky.prepareProfileDeletion(original)).thenReturn(Result.success(remaining))
        sut.reviewAgain(failure).getOrThrow()
        whenever(pubky.prepareProfileDeletion(original)).thenReturn(Result.success(deletion))
        assertTrue(sut.reviewAgain(failure).isFailure)
        verify(pubky, times(1)).deleteProfile(deletion)
    }

    @Test
    fun `new residual review invalidates an outstanding wider confirmation before cleanup`() = test {
        val first = deletion.withSnapshot(deletion.snapshot.copy(contacts = listOf(contact("alice"), contact("bob"))))
        val failure = failRemoval(first)
        whenever(pubky.prepareProfileDeletion(original)).thenReturn(Result.success(first))
        val wider = sut.reviewAgain(failure).getOrThrow()
        val remaining = first.withSnapshot(first.snapshot.copy(contacts = listOf(contact("bob"))))
        whenever(pubky.prepareProfileDeletion(original)).thenReturn(Result.success(remaining))
        sut.reviewAgain(failure).getOrThrow()
        val eventsBefore = events.toList()
        assertTrue(sut.remove(wider).isFailure)
        assertEquals(eventsBefore, events)
        verify(pubky, times(1)).deleteProfile(first)
    }

    @Test
    fun `review read failure retains original obligation without a mutation`() = test {
        val failure = failRemoval(deletion)
        whenever(pubky.prepareProfileDeletion(original)).thenReturn(Result.failure(IllegalStateException("offline")))
        assertTrue(sut.reviewAgain(failure).isFailure)
        assertEquals(failure.confirmation.attemptId, store.pendingAttempt())
        verify(pubky, times(1)).deleteProfile(deletion)
    }

    private fun PubkyProfileDeletion.withSnapshot(snapshot: PaykitProfileDeletionSnapshot) =
        PubkyProfileDeletion(owner, profileWriteRevision, snapshot)

    private suspend fun failRemoval(snapshot: PubkyProfileDeletion): ProfileRemovalFailure {
        whenever(pubky.prepareProfileDeletion(original)).thenReturn(Result.success(snapshot))
        whenever(pubky.deleteProfile(snapshot)).thenReturn(Result.failure(IllegalStateException("lost response")))
        return assertIs<ProfileRemovalFailure>(sut.remove(sut.prepareDeletion(original).getOrThrow()).exceptionOrNull())
    }

    private fun contact(key: String) = ContactRecord(
        publicKey = key,
        label = key,
        profile = null,
        profileFetchedAt = null,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        publicContactMarkerStatus = PublicationStatus.NOT_PUBLISHED,
        publicContactPublishedAt = null,
        publicContactRemovedAt = null,
        publicContactLastError = null,
    )

    @Test
    fun `later identity invalidates an unused confirmation before cleanup`() = test {
        val confirmation = sut.prepareDeletion(original).getOrThrow()
        current = ended
        assertTrue(sut.remove(confirmation).isFailure)
        assertNull(store.pendingAttempt())
        assertTrue(events.isEmpty())
    }

    @Test
    fun `changed snapshot fails before durable marker and destructive work`() = test {
        whenever { pubky.validateProfileDeletion(deletion) }.thenAnswer { error("profile revision changed") }
        val confirmation = sut.prepareDeletion(original).getOrThrow()
        assertTrue(sut.remove(confirmation).isFailure)
        assertNull(store.pendingAttempt())
        verify(privateRepo, never()).removePublishedEndpointsForCleanup(any(), any())
    }

    @Test
    fun `explicit local disconnect remains available after precondition failure without a marker`() = test {
        whenever { pubky.validateProfileDeletion(deletion) }.thenAnswer { error("profile changed") }
        val failure = assertIs<ProfileRemovalFailure>(
            sut.remove(sut.prepareDeletion(original).getOrThrow()).exceptionOrNull()
        )
        assertNull(store.pendingAttempt())
        assertTrue(sut.remove(sut.prepareLocalDisconnect(failure).getOrThrow()).isSuccess)
        assertNull(store.pendingAttempt())
        verify(pubky, never()).deleteProfile(any<PubkyProfileDeletion>())
        verify(pubky, never()).signOutForProfileRemoval()
    }

    @Test
    fun `public cleanup failure retains marker and never revokes or clears private state`() = test {
        whenever(pubky.removeBitkitPaymentEndpoints()).thenReturn(Result.failure(IllegalStateException("unknown")))
        val confirmation = sut.prepareDeletion(original).getOrThrow()
        assertTrue(sut.remove(confirmation).isFailure)
        assertEquals(confirmation.attemptId, store.pendingAttempt())
        verify(pubky, never()).deleteProfile(any<PubkyProfileDeletion>())
        verify(pubky, never()).signOutForProfileRemoval()
        verify(privateRepo, never()).finishProfileRemoval(any())
    }

    @Test
    fun `failed local completion retains ended owner for explicit local disconnect`() = test {
        doReturn(Result.failure<Unit>(IllegalStateException("disk"))).whenever(privateRepo).finishProfileRemoval(pause)
        val confirmation = sut.prepareDeletion(original).getOrThrow()
        val failure = assertIs<ProfileRemovalFailure>(sut.remove(confirmation).exceptionOrNull())
        assertTrue(failure.localCompletionStarted)
        assertSame(ended, failure.recoveryOwner)
        assertTrue(sut.reviewAgain(failure).isFailure)
        val recovery = sut.prepareLocalDisconnect(failure).getOrThrow()
        doReturn(Result.success(Unit)).whenever(privateRepo).finishProfileRemoval(pause)
        assertTrue(sut.remove(recovery).isSuccess)
        assertNull(store.pendingAttempt())
        verify(pubky, times(1)).deleteProfile(deletion)
        verify(pubky, times(1)).signOutForProfileRemoval()
    }

    @Test
    fun `restart pending disconnect is local only and rejects stale UUID confirmation`() = test {
        current = PubkyProfileOwner(null, 0)
        val id = UUID.randomUUID().toString()
        store.withAccess(id) { store.begin(id) }
        val confirmation = sut.prepareSignOut(current).getOrThrow()
        assertTrue(confirmation.localOnly)
        assertTrue(sut.remove(confirmation).isSuccess)
        verify(pubky, never()).signOutForProfileRemoval()
        verify(pubky, never()).removeBitkitPaymentEndpoints()
        verify(privateRepo, never()).removePublishedEndpointsForCleanup(any(), any())
        val unusedOldConfirmation = sut.prepareSignOut(current).getOrThrow()
        val newerId = UUID.randomUUID().toString()
        store.withAccess(newerId) { store.begin(newerId) }
        assertTrue(sut.remove(confirmation).isFailure)
        assertFalse(unusedOldConfirmation.used.get())
        assertEquals(
            "Another profile removal needs local recovery",
            sut.remove(unusedOldConfirmation).exceptionOrNull()?.message,
        )
        assertEquals(newerId, store.pendingAttempt())
    }

    @Test
    fun `failed final marker persistence retains local recovery without repeating remote work`() = test {
        val data = MutableStateFlow(ProfileRemovalState())
        var rejectCompletion = true
        store = ProfileRemovalStore(object : DataStore<ProfileRemovalState> {
            override val data = data
            override suspend fun updateData(
                transform: suspend (ProfileRemovalState) -> ProfileRemovalState
            ): ProfileRemovalState {
                val next = transform(data.value)
                if (rejectCompletion && next.attemptId == null) error("marker write unavailable")
                data.value = next
                return next
            }
        })
        val confirmation = sut.prepareDeletion(original).getOrThrow()
        val failure = assertIs<ProfileRemovalFailure>(sut.remove(confirmation).exceptionOrNull())
        assertSame(ended, failure.recoveryOwner)
        assertEquals(confirmation.attemptId, store.pendingAttempt())
        assertTrue(sut.reviewAgain(failure).isFailure)
        rejectCompletion = false
        assertTrue(sut.remove(sut.prepareLocalDisconnect(failure).getOrThrow()).isSuccess)
        assertNull(store.pendingAttempt())
        verify(pubky, times(1)).deleteProfile(deletion)
        verify(pubky, times(1)).signOutForProfileRemoval()
    }

    @Test
    fun `cancellation during destructive work leaves obligation and does not clear watermarks`() = test {
        val entered = CompletableDeferred<Unit>()
        val wait = CompletableDeferred<Unit>()
        whenever { pubky.deleteProfile(deletion) }.doSuspendableAnswer {
            entered.complete(Unit)
            wait.await()
            Result.success(Unit)
        }
        val confirmation = sut.prepareDeletion(original).getOrThrow()
        val job = async { sut.remove(confirmation) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(confirmation.attemptId, store.pendingAttempt())
        verify(privateRepo, never()).finishProfileRemoval(any())
    }
}

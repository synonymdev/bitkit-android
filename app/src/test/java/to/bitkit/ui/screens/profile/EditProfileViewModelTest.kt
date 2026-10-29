package to.bitkit.ui.screens.profile

import android.content.Context
import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyProfileLink
import to.bitkit.models.Toast
import to.bitkit.repositories.PubkyContactError
import to.bitkit.repositories.PubkyProfileOwner
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.PubkySignIn
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EditProfileViewModelTest : BaseUnitTest() {
    companion object { private const val TEST_PUBLIC_KEY = "pubkyalice" }
    private val context: Context = mock()
    private val pubkyRepo: PubkyRepo = mock()
    private val fixture = ProfileRemovalUiFixture(pubkyRepo)

    @Test
    fun `active subscriptions refuse confirmation without starting cleanup`() = test {
        val sut = createSut()
        whenever(pubkyRepo.prepareProfileDeletion(fixture.original))
            .thenReturn(Result.failure(PubkyContactError.ActiveSubscription))
        advanceUntilIdle()
        ToastEventBus.events.test {
            sut.showDeleteConfirmation()
            advanceUntilIdle()
            assertEquals("End active subscriptions first", awaitItem().description)
        }
        assertFalse(sut.uiState.value.showDeleteDialog)
        assertFalse(sut.uiState.value.showDeleteFailureDialog)
        assertTrue(sut.uiState.value.canDisconnectLocally)
        assertNull(fixture.store.pendingAttempt())
        verify(fixture.privateRepo, never()).withProfileDeletionPaused<Unit>(any())
        verify(pubkyRepo, never()).deleteProfile(any())
    }

    @Test
    fun `subscription refusal offers fresh local confirmation without remote cleanup`() = test {
        val sut = createSut()
        whenever(pubkyRepo.prepareProfileDeletion(fixture.original))
            .thenReturn(Result.failure(PubkyContactError.ActiveSubscription))
        advanceUntilIdle()
        sut.showLocalDisconnectConfirmation()
        advanceUntilIdle()
        assertFalse(sut.uiState.value.showDeleteDialog)
        sut.showDeleteConfirmation()
        advanceUntilIdle()
        sut.showLocalDisconnectConfirmation()
        advanceUntilIdle()
        assertTrue(sut.uiState.value.showDeleteDialog)
        assertTrue(sut.uiState.value.isLocalDisconnect)
        assertNull(fixture.store.pendingAttempt())
        sut.dismissDeleteDialog()
        sut.deleteProfile()
        advanceUntilIdle()
        verify(pubkyRepo, never()).finishProfileRemovalLocally()
        sut.showLocalDisconnectConfirmation()
        advanceUntilIdle()
        sut.effects.test {
            sut.deleteProfile()
            advanceUntilIdle()
            assertIs<EditProfileEffect.RemovalCompleted>(awaitItem())
        }
        verify(pubkyRepo, times(1)).prepareProfileDeletion(fixture.original)
        verify(pubkyRepo, never()).deleteProfile(any())
        verify(pubkyRepo, never()).removeBitkitPaymentEndpoints()
        verify(pubkyRepo, never()).signOutForProfileRemoval()
        verify(fixture.privateRepo, never()).removePublishedEndpointsForCleanup(any(), any())
        assertNull(fixture.store.pendingAttempt())
    }

    @Test
    fun `fresh local disconnect never recaptures the replacement screen owner`() = test {
        val sut = createSut()
        whenever(pubkyRepo.prepareProfileDeletion(fixture.original))
            .thenReturn(Result.failure(PubkyContactError.ActiveSubscription))
        advanceUntilIdle()
        sut.showDeleteConfirmation()
        advanceUntilIdle()
        fixture.current = PubkyProfileOwner(PubkySignIn(TEST_PUBLIC_KEY, 3), 3)
        sut.showLocalDisconnectConfirmation()
        advanceUntilIdle()
        assertFalse(sut.uiState.value.showDeleteDialog)
        assertNull(fixture.store.pendingAttempt())
        verify(pubkyRepo, never()).finishProfileRemovalLocally()
    }

    @Test
    fun `subscription beginning after confirmation prevents endpoint withdrawal and marker`() = test {
        val sut = createSut()
        advanceUntilIdle()
        sut.showDeleteConfirmation()
        advanceUntilIdle()
        doAnswer { throw PubkyContactError.ActiveSubscription }
            .whenever(pubkyRepo).validateProfileDeletion(fixture.deletion)
        ToastEventBus.events.test {
            sut.deleteProfile()
            advanceUntilIdle()
            assertEquals("End active subscriptions first", awaitItem().description)
        }
        assertNull(fixture.store.pendingAttempt())
        assertFalse(sut.uiState.value.showDeleteFailureDialog)
        assertTrue(sut.uiState.value.canDisconnectLocally)
        verify(fixture.privateRepo, never()).removePublishedEndpointsForCleanup(any(), any())
        verify(pubkyRepo, never()).deleteProfile(any())
    }

    @Test
    fun `late subscription rejection retains durable removal and explicit review`() = test {
        val sut = createSut()
        whenever(pubkyRepo.deleteProfile(fixture.deletion))
            .thenReturn(Result.failure(PubkyContactError.ActiveSubscription))
        advanceUntilIdle()
        sut.showDeleteConfirmation()
        advanceUntilIdle()
        sut.deleteProfile()
        advanceUntilIdle()
        assertNotNull(fixture.store.pendingAttempt())
        assertTrue(sut.uiState.value.showDeleteFailureDialog)
        verify(pubkyRepo, never()).signOutForProfileRemoval()
        sut.retryDeleteProfile()
        advanceUntilIdle()
        assertTrue(sut.uiState.value.showDeleteDialog)
        verify(pubkyRepo, times(1)).deleteProfile(fixture.deletion)
    }

    @Test
    fun `confirmation is required and consumed once`() = test {
        val sut = createSut()
        advanceUntilIdle()
        sut.deleteProfile()
        advanceUntilIdle()
        verify(pubkyRepo, never()).deleteProfile(any())
        sut.showDeleteConfirmation()
        advanceUntilIdle()
        assertTrue(sut.uiState.value.showDeleteDialog)
        sut.effects.test {
            sut.deleteProfile()
            sut.deleteProfile()
            advanceUntilIdle()
            val effect = assertIs<EditProfileEffect.RemovalCompleted>(awaitItem())
            assertTrue(sut.isRemovalCurrent(effect))
            fixture.current = PubkyProfileOwner(PubkySignIn(TEST_PUBLIC_KEY, 3), 3)
            assertFalse(sut.isRemovalCurrent(effect))
            expectNoEvents()
        }
        verify(pubkyRepo, times(1)).deleteProfile(fixture.deletion)
        assertNull(fixture.store.pendingAttempt())
    }

    @Test
    fun `completed removal shows success only for its ended owner`() = test {
        val sut = createSut()
        advanceUntilIdle()
        sut.showDeleteConfirmation()
        advanceUntilIdle()
        ToastEventBus.events.test {
            sut.deleteProfile()
            advanceUntilIdle()
            val toast = expectMostRecentItem()
            assertEquals(Toast.ToastType.SUCCESS, toast.type)
            assertEquals("Profile deleted", toast.title)
        }
    }

    @Test
    fun `uncertain deletion review reads state and requires fresh confirmation`() = test {
        val sut = createSut()
        whenever(pubkyRepo.deleteProfile(fixture.deletion)).thenReturn(Result.failure(TestAppError("lost response")))
        advanceUntilIdle()
        sut.showDeleteConfirmation()
        advanceUntilIdle()
        sut.deleteProfile()
        advanceUntilIdle()
        assertTrue(sut.uiState.value.showDeleteFailureDialog)
        assertNotNull(fixture.store.pendingAttempt())
        sut.retryDeleteProfile()
        advanceUntilIdle()
        assertTrue(sut.uiState.value.showDeleteDialog)
        verify(pubkyRepo, times(2)).prepareProfileDeletion(fixture.original)
        verify(pubkyRepo, times(1)).deleteProfile(fixture.deletion)
        sut.dismissDeleteDialog()
        assertTrue(sut.uiState.value.showDeleteFailureDialog)
        sut.deleteProfile()
        advanceUntilIdle()
        verify(pubkyRepo, times(1)).deleteProfile(fixture.deletion)
    }

    @Test
    fun `failure disconnect asks again and never replays remote cleanup`() = test {
        val sut = createSut()
        whenever(pubkyRepo.deleteProfile(fixture.deletion)).thenReturn(Result.failure(TestAppError("lost response")))
        advanceUntilIdle()
        sut.showDeleteConfirmation()
        advanceUntilIdle()
        sut.deleteProfile()
        advanceUntilIdle()
        sut.disconnectProfile()
        advanceUntilIdle()
        assertTrue(sut.uiState.value.showDeleteDialog)
        assertTrue(sut.uiState.value.isLocalDisconnect)
        verify(pubkyRepo, never()).finishProfileRemovalLocally()
        ToastEventBus.events.test {
            sut.effects.test {
                sut.deleteProfile()
                advanceUntilIdle()
                assertIs<EditProfileEffect.RemovalCompleted>(awaitItem())
            }
            assertEquals("Pubky unlinked", expectMostRecentItem().title)
        }
        verify(pubkyRepo, times(1)).deleteProfile(fixture.deletion)
        verify(pubkyRepo, never()).signOutForProfileRemoval()
        verify(fixture.privateRepo, times(1)).removePublishedEndpointsForCleanup(any(), eq(true))
        assertNull(fixture.store.pendingAttempt())
    }

    @Test
    fun `failed private cleanup retains marker and stops profile deletion`() = test {
        val sut = createSut()
        whenever(fixture.privateRepo.removePublishedEndpointsForCleanup(any(), eq(true)))
            .thenReturn(Result.failure(TestAppError("pending cleanup")))
        advanceUntilIdle()
        sut.showDeleteConfirmation()
        advanceUntilIdle()
        sut.effects.test {
            sut.deleteProfile()
            advanceUntilIdle()
            expectNoEvents()
        }
        assertTrue(sut.uiState.value.showDeleteFailureDialog)
        assertNotNull(fixture.store.pendingAttempt())
        verify(pubkyRepo, never()).deleteProfile(any())
        verify(fixture.privateRepo, never()).finishProfileRemoval(any())
    }

    @Test
    fun `local cleanup failure offers only local completion and retains safety state`() = test {
        val sut = createSut()
        whenever(fixture.privateRepo.finishProfileRemoval(fixture.pause))
            .thenReturn(Result.failure(TestAppError("disk unavailable")))
        advanceUntilIdle()
        sut.showDeleteConfirmation()
        advanceUntilIdle()
        sut.effects.test {
            sut.deleteProfile()
            advanceUntilIdle()
            expectNoEvents()
        }
        assertTrue(sut.uiState.value.showDeleteFailureDialog)
        assertFalse(sut.uiState.value.canReviewRemoval)
        assertNotNull(fixture.store.pendingAttempt())
        sut.retryDeleteProfile()
        advanceUntilIdle()
        assertFalse(sut.uiState.value.showDeleteDialog)
        sut.disconnectProfile()
        advanceUntilIdle()
        assertTrue(sut.uiState.value.isLocalDisconnect)
        assertTrue(sut.uiState.value.showDeleteDialog)
    }

    @Test
    fun `cancelled screen preserves pending removal without success`() = test {
        val sut = createSut()
        val cleanup = CompletableDeferred<Result<Unit>>()
        whenever(fixture.privateRepo.removePublishedEndpointsForCleanup(any(), eq(true)))
            .doSuspendableAnswer { cleanup.await() }
        advanceUntilIdle()
        sut.showDeleteConfirmation()
        advanceUntilIdle()
        sut.deleteProfile()
        advanceUntilIdle()
        assertTrue(sut.uiState.value.isDeleting)
        sut.deleteProfile()
        sut.disconnectProfile()
        sut.save()
        advanceUntilIdle()
        verify(pubkyRepo, never()).saveProfile(any(), any(), any(), any(), any())
        sut.viewModelScope.cancel()
        advanceUntilIdle()
        assertFalse(sut.uiState.value.isBusy)
        assertNotNull(fixture.store.pendingAttempt())
        verify(pubkyRepo, never()).deleteProfile(any())
        verify(fixture.privateRepo, never()).finishProfileRemoval(any())
    }

    @Test
    fun `replacement identity cannot be removed from an older profile screen`() = test {
        val sut = createSut()
        advanceUntilIdle()
        fixture.current = PubkyProfileOwner(PubkySignIn(TEST_PUBLIC_KEY, 3), 3)
        sut.showDeleteConfirmation()
        advanceUntilIdle()
        assertFalse(sut.uiState.value.showDeleteDialog)
        verify(pubkyRepo, never()).prepareProfileDeletion(any())
        verify(pubkyRepo, never()).deleteProfile(any())
    }

    @Test
    fun `dismissal or replacement during preparation cannot reopen confirmation`() = test {
        val sut = createSut()
        val snapshot = CompletableDeferred<Result<to.bitkit.repositories.PubkyProfileDeletion>>()
        whenever(pubkyRepo.prepareProfileDeletion(fixture.original)).doSuspendableAnswer { snapshot.await() }
        advanceUntilIdle()
        sut.showDeleteConfirmation()
        advanceUntilIdle()
        assertTrue(sut.uiState.value.isPreparingRemoval)
        sut.dismissDeleteDialog()
        snapshot.complete(Result.success(fixture.deletion))
        advanceUntilIdle()
        assertFalse(sut.uiState.value.showDeleteDialog)
        sut.deleteProfile()
        verify(pubkyRepo, never()).deleteProfile(any())
    }

    @Test
    fun `delayed Ring lookup does not install a replaced profile`() = test {
        val ring = CompletableDeferred<Boolean>()
        val sut = createSut(ringCheck = ring)
        advanceUntilIdle()
        fixture.current = PubkyProfileOwner(PubkySignIn("replacement", 2), 2)
        ring.complete(true)
        advanceUntilIdle()
        assertEquals("", sut.uiState.value.name)
        sut.showDeleteConfirmation()
        advanceUntilIdle()
        assertFalse(sut.uiState.value.showDeleteDialog)
    }

    @Test
    fun `managed and Ring identity keep distinct removal copy`() = test {
        val sut = createSut(isRingIdentity = true)
        advanceUntilIdle()
        assertEquals(ProfileRemoval.UNLINK, sut.uiState.value.removal)
        sut.showDeleteConfirmation()
        advanceUntilIdle()
        assertFalse(sut.uiState.value.isLocalDisconnect)
    }

    @Test
    fun `pending save shows only save progress and blocks other actions`() = test {
        val sut = createSut()
        advanceUntilIdle()
        val result = CompletableDeferred<Result<Unit>>()
        whenever(pubkyRepo.saveProfile(any(), any(), any(), any(), any())).doSuspendableAnswer { result.await() }

        sut.save()
        advanceUntilIdle()
        assertTrue(sut.uiState.value.isSaving)
        assertFalse(sut.uiState.value.isDeleting)
        assertTrue(sut.uiState.value.isBusy)

        sut.save()
        sut.deleteProfile()
        sut.retryDeleteProfile()
        sut.disconnectProfile()
        sut.showDeleteConfirmation()
        advanceUntilIdle()
        verify(pubkyRepo, times(1)).saveProfile(any(), any(), any(), any(), any())
        verify(pubkyRepo, never()).deleteProfile(any())
        verify(pubkyRepo, never()).prepareProfileDeletion(any())
        assertFalse(sut.uiState.value.showDeleteDialog)

        result.complete(Result.failure(TestAppError("Offline")))
        advanceUntilIdle()
        assertFalse(sut.uiState.value.isSaving)
        assertFalse(sut.uiState.value.isBusy)
    }

    @Test
    fun `updateLinkUrl should update existing profile link`() = test {
        val sut = createSut()
        advanceUntilIdle()

        sut.updateLinkUrl(0, "https://updated.example.com")

        assertEquals("https://updated.example.com", sut.uiState.value.links.first().url)
    }

    @Test
    fun `ring identity keeps loading until unlink copy is resolved`() = test {
        val ringCheck = CompletableDeferred<Boolean>()
        val sut = createSut(ringCheck = ringCheck)
        advanceUntilIdle()

        assertTrue(sut.uiState.value.isLoading)

        ringCheck.complete(true)
        advanceUntilIdle()

        assertFalse(sut.uiState.value.isLoading)
        assertEquals(ProfileRemoval.UNLINK, sut.uiState.value.removal)
    }

    @Test
    fun `dismissed failure flow cannot reopen after a delayed review`() = test {
        val sut = createSut()
        whenever(pubkyRepo.deleteProfile(fixture.deletion)).thenReturn(Result.failure(TestAppError("lost response")))
        advanceUntilIdle()
        sut.showDeleteConfirmation()
        advanceUntilIdle()
        sut.deleteProfile()
        advanceUntilIdle()
        val snapshot = CompletableDeferred<Result<to.bitkit.repositories.PubkyProfileDeletion>>()
        whenever(pubkyRepo.prepareProfileDeletion(fixture.original)).doSuspendableAnswer { snapshot.await() }
        sut.retryDeleteProfile()
        advanceUntilIdle()
        sut.dismissDeleteFailureDialog()
        snapshot.complete(Result.success(fixture.deletion))
        advanceUntilIdle()
        assertFalse(sut.uiState.value.showDeleteDialog)
        assertFalse(sut.uiState.value.showDeleteFailureDialog)
        verify(pubkyRepo, times(1)).deleteProfile(fixture.deletion)
    }

    @Test
    fun `early local-only failure offers disconnect without remote review`() = test {
        val sut = createSut()
        whenever(pubkyRepo.deleteProfile(fixture.deletion))
            .thenReturn(Result.failure(TestAppError("Unknown response")))
        advanceUntilIdle()
        sut.showDeleteConfirmation()
        advanceUntilIdle()
        sut.deleteProfile()
        advanceUntilIdle()
        sut.disconnectProfile()
        advanceUntilIdle()
        whenever { fixture.privateRepo.withProfileDeletionPaused<Unit>(any()) }.thenAnswer {
            throw IllegalStateException("Pause unavailable")
        }
        sut.deleteProfile()
        advanceUntilIdle()
        assertTrue(sut.uiState.value.showDeleteFailureDialog)
        assertFalse(sut.uiState.value.canReviewRemoval)
        verify(pubkyRepo, times(1)).deleteProfile(fixture.deletion)
    }

    @Test
    fun `cancelled local completion retains its owner through a later admission failure`() = test {
        val sut = createSut()
        org.mockito.kotlin.doAnswer {
            fixture.current = fixture.ended
            throw CancellationException("Cancelled after acknowledged credential removal")
        }.whenever(pubkyRepo).finishProfileRemovalLocally()
        advanceUntilIdle()
        sut.showDeleteConfirmation()
        advanceUntilIdle()
        sut.effects.test {
            sut.deleteProfile()
            advanceUntilIdle()
            expectNoEvents()
            assertNotNull(fixture.store.pendingAttempt())
            verify(fixture.privateRepo, never()).finishProfileRemoval(any())
            sut.disconnectProfile()
            advanceUntilIdle()
            fixture.admissionFailure = IllegalStateException("Temporarily busy")
            sut.deleteProfile()
            advanceUntilIdle()
            expectNoEvents()
            assertNotNull(fixture.store.pendingAttempt())
            fixture.admissionFailure = null
            org.mockito.kotlin.doAnswer { fixture.current = fixture.ended }.whenever(
                pubkyRepo
            ).finishProfileRemovalLocally()
            sut.disconnectProfile()
            advanceUntilIdle()
            sut.deleteProfile()
            advanceUntilIdle()
            assertIs<EditProfileEffect.RemovalCompleted>(awaitItem())
            assertNull(fixture.store.pendingAttempt())
        }
        verify(pubkyRepo, times(1)).signOutForProfileRemoval()
    }

    private fun createSut(
        isRingIdentity: Boolean = false,
        ringCheck: CompletableDeferred<Boolean>? = null,
    ): EditProfileViewModel {
        whenever(context.getString(any<Int>())).thenReturn("")
        whenever(context.getString(R.string.profile__delete_success)).thenReturn("Profile deleted")
        whenever(context.getString(R.string.profile__delete_active_subscription))
            .thenReturn("End active subscriptions first")
        whenever(context.getString(R.string.profile__unlink_success)).thenReturn("Pubky unlinked")
        if (ringCheck != null) {
            whenever { pubkyRepo.isRingAdoptedIdentity() }.doSuspendableAnswer { ringCheck.await() }
        } else {
            whenever { pubkyRepo.isRingAdoptedIdentity() }.thenReturn(isRingIdentity)
        }
        fixture.setup()
        whenever(pubkyRepo.profile).thenReturn(MutableStateFlow(createProfile()))
        whenever(pubkyRepo.publicKey).thenReturn(MutableStateFlow(TEST_PUBLIC_KEY))

        return EditProfileViewModel(
            context = context,
            pubkyRepo = pubkyRepo,
            removalCoordinator = fixture.coordinator,
        )
    }

    private fun createProfile() = PubkyProfile(
        publicKey = TEST_PUBLIC_KEY,
        name = "Alice",
        bio = "Hello",
        imageUrl = "https://example.com/avatar.jpg",
        links = listOf(PubkyProfileLink("Website", "https://example.com")),
        tags = listOf("friend").toImmutableList(),
        status = null,
    )
}

private class TestAppError(message: String) : AppError(message)

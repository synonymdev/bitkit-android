package to.bitkit.ui.screens.profile

import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.data.ProfileRemovalStore
import to.bitkit.ext.runSuspendCatching
import to.bitkit.repositories.ContactPaymentSettingsRepo
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyProfileDeletion
import to.bitkit.repositories.PubkyProfileOwner
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.PubkySignIn
import to.bitkit.services.PaykitProfileDeletionSnapshot
import to.bitkit.usecases.ProfileRemovalCoordinator

/** Real orchestration and durable marker, with controllable local/native repository boundaries. */
internal class ProfileRemovalUiFixture(val pubky: PubkyRepo) {
    val privateRepo = mock<PrivatePaykitRepo>()
    private val sharing = mock<ContactPaymentSettingsRepo>()
    val store = ProfileRemovalStore.inMemoryForTesting()
    val original = PubkyProfileOwner(PubkySignIn("pubkyalice", 1), 1)
    val ended = PubkyProfileOwner(null, 2)
    var current = original
    var admissionFailure: Throwable? = null
    val pause = PrivatePaykitRepo.ProfileDeletionPause()
    val deletion =
        PubkyProfileDeletion(original, 1, PaykitProfileDeletionSnapshot("pubkyalice", "revision", emptyList()))
    val coordinator = ProfileRemovalCoordinator(pubky, privateRepo, sharing, store)

    fun setup() {
        whenever(pubky.profileOwner()).thenAnswer { current }
        whenever(pubky.isProfileOwnerCurrent(any())).thenAnswer { it.getArgument<PubkyProfileOwner>(0) == current }
        whenever { pubky.prepareProfileDeletion(original) }.thenReturn(Result.success(deletion))
        whenever { sharing.withProfileRemoval<PubkyProfileOwner>(any()) }.doSuspendableAnswer {
            it.getArgument<suspend () -> PubkyProfileOwner>(0).invoke()
        }
        whenever { pubky.performProfileRemoval(any(), any(), any()) }.doSuspendableAnswer {
            val owner = it.getArgument<PubkyProfileOwner>(0)
            val id = it.getArgument<String>(1)
            val operation = it.getArgument<suspend () -> Unit>(2)
            runSuspendCatching {
                admissionFailure?.let { error -> throw error }
                check(owner == current)
                store.withAccess(id) { operation() }
                current
            }
        }
        whenever { privateRepo.withProfileDeletionPaused<Unit>(any()) }.doSuspendableAnswer {
            it.getArgument<suspend (PrivatePaykitRepo.ProfileDeletionPause) -> Unit>(0).invoke(pause)
        }
        whenever { privateRepo.removePublishedEndpointsForCleanup(any(), eq(true)) }.thenReturn(Result.success(Unit))
        whenever { pubky.removeBitkitPaymentEndpoints() }.thenReturn(Result.success(Unit))
        whenever { pubky.deleteProfile(deletion) }.thenReturn(Result.success(Unit))
        whenever { pubky.finishProfileRemovalLocally() }.thenAnswer { current = ended }
        whenever { privateRepo.finishProfileRemoval(pause) }.thenReturn(Result.success(Unit))
    }
}

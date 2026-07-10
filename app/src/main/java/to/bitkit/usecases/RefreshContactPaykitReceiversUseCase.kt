package to.bitkit.usecases

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.utils.Logger
import javax.inject.Inject

class RefreshContactPaykitReceiversUseCase @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val pubkyRepo: PubkyRepo,
    private val privatePaykitRepo: PrivatePaykitRepo,
) {
    companion object {
        private const val TAG = "RefreshContactPaykitReceiversUseCase"
    }

    suspend operator fun invoke(publicKey: String): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            pubkyRepo.refreshContactReceiverPaths(publicKey).getOrThrow()
            privatePaykitRepo.refreshSavedContactEndpoints(pubkyRepo.contacts.value.map { it.publicKey }).getOrThrow()
        }.onFailure {
            Logger.warn(
                "Failed to refresh Paykit receivers for '${PubkyPublicKeyFormat.redacted(publicKey)}'",
                it,
                context = TAG,
            )
        }
    }
}

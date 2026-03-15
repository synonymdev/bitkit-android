package to.bitkit.services

import com.synonym.bitkitcore.PubkyProfile
import com.synonym.bitkitcore.cancelPubkyAuth
import com.synonym.bitkitcore.completePubkyAuth
import com.synonym.bitkitcore.fetchPubkyContacts
import com.synonym.bitkitcore.fetchPubkyFile
import com.synonym.bitkitcore.fetchPubkyProfile
import com.synonym.bitkitcore.startPubkyAuth
import com.synonym.paykit.paykitForceSignOut
import com.synonym.paykit.paykitImportSession
import com.synonym.paykit.paykitInitialize
import com.synonym.paykit.paykitSignOut
import kotlinx.coroutines.CompletableDeferred
import to.bitkit.async.ServiceQueue
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PubkyService @Inject constructor() {

    companion object {
        const val REQUIRED_CAPABILITIES =
            "/pub/paykit.app/v0/:rw,/pub/pubky.app/profile.json:rw,/pub/pubky.app/follows/:rw"
    }

    private val isSetup = CompletableDeferred<Unit>()

    suspend fun initialize() = ServiceQueue.CORE.background {
        paykitInitialize()
        isSetup.complete(Unit)
    }

    suspend fun importSession(secret: String): String = ServiceQueue.CORE.background {
        isSetup.await()
        paykitImportSession(secret)
    }

    suspend fun startAuth(): String = ServiceQueue.CORE.background {
        isSetup.await()
        startPubkyAuth(REQUIRED_CAPABILITIES)
    }

    suspend fun completeAuth(): String = ServiceQueue.CORE.background {
        isSetup.await()
        completePubkyAuth()
    }

    suspend fun cancelAuth() = ServiceQueue.CORE.background {
        isSetup.await()
        cancelPubkyAuth()
    }

    suspend fun fetchFile(uri: String): ByteArray = ServiceQueue.CORE.background {
        isSetup.await()
        fetchPubkyFile(uri)
    }

    suspend fun getProfile(publicKey: String): PubkyProfile = ServiceQueue.CORE.background {
        isSetup.await()
        fetchPubkyProfile(publicKey)
    }

    suspend fun getContacts(publicKey: String): List<String> = ServiceQueue.CORE.background {
        isSetup.await()
        fetchPubkyContacts(publicKey)
    }

    suspend fun signOut() = ServiceQueue.CORE.background {
        isSetup.await()
        paykitSignOut()
    }

    suspend fun forceSignOut() = ServiceQueue.CORE.background {
        isSetup.await()
        paykitForceSignOut()
    }
}

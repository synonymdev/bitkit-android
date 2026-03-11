package to.bitkit.services

import com.synonym.bitkitcore.PubkyProfile
import com.synonym.bitkitcore.cancelPubkyAuth
import com.synonym.bitkitcore.completePubkyAuth
import com.synonym.bitkitcore.fetchPubkyFile
import com.synonym.bitkitcore.fetchPubkyProfile
import com.synonym.bitkitcore.startPubkyAuth
import com.synonym.paykit.paykitForceSignOut
import com.synonym.paykit.paykitImportSession
import com.synonym.paykit.paykitInitialize
import com.synonym.paykit.paykitSignOut
import to.bitkit.async.ServiceQueue
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PubkyService @Inject constructor() {

    companion object {
        const val REQUIRED_CAPABILITIES =
            "/pub/paykit.app/v0/:rw,/pub/pubky.app/profile.json:rw,/pub/pubky.app/follows/:rw"
    }

    suspend fun initialize() =
        ServiceQueue.CORE.background { paykitInitialize() }

    suspend fun importSession(secret: String): String =
        ServiceQueue.CORE.background { paykitImportSession(secret) }

    suspend fun startAuth(): String =
        ServiceQueue.CORE.background { startPubkyAuth(REQUIRED_CAPABILITIES) }

    suspend fun completeAuth(): String =
        ServiceQueue.CORE.background { completePubkyAuth() }

    suspend fun cancelAuth() =
        ServiceQueue.CORE.background { cancelPubkyAuth() }

    suspend fun fetchFile(uri: String): ByteArray =
        ServiceQueue.CORE.background { fetchPubkyFile(uri) }

    suspend fun getProfile(publicKey: String): PubkyProfile =
        ServiceQueue.CORE.background { fetchPubkyProfile(publicKey) }

    suspend fun signOut() =
        ServiceQueue.CORE.background { paykitSignOut() }

    suspend fun forceSignOut() =
        ServiceQueue.CORE.background { paykitForceSignOut() }
}

package to.bitkit.services

import com.synonym.bitkitcore.approvePubkyAuth
import com.synonym.paykit.ContactRecord
import com.synonym.paykit.ContactUpdate
import com.synonym.paykit.PaykitProfile
import com.synonym.paykit.PaykitProfileRecord
import com.synonym.paykit.PaykitPublicKeys
import com.synonym.paykit.ProfileResolution
import com.synonym.paykit.PubkyAuthCompanionClaim
import com.synonym.paykit.PubkyProfileSnapshot
import com.synonym.paykit.PubkyProfileUpdate
import com.synonym.paykit.PubkySessionBootstrapResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import to.bitkit.async.ServiceQueue
import to.bitkit.ext.runSuspendCatching
import to.bitkit.utils.AppError
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import com.synonym.bitkitcore.parsePubkyAuthUrl as parseLegacyPubkyAuthUrl

@Singleton
class PubkyService @Inject constructor(
    private val paykitSdkService: PaykitSdkService,
) {
    companion object {
        /** Maximum wait for a Pubky authorization network response. */
        internal val AUTHORIZATION_TIMEOUT = 30.seconds
    }

    internal suspend fun preparePublicProfileRecovery() = ServiceQueue.PUBKY.background {
        paykitSdkService.preparePublicProfileRecovery()
    }

    suspend fun initialize() = ServiceQueue.PUBKY.background {
        paykitSdkService.initialize()
    }

    suspend fun initializeAndImportSession(secret: String): Result<String> = ServiceQueue.PUBKY.background {
        paykitSdkService.initializeAndImportSession(secret).map { it.publicKey }
    }

    suspend fun republishIdentityIfNeeded(publicKey: String? = null) =
        paykitSdkService.republishIdentityIfNeeded(publicKey)

    // region Session management

    suspend fun importSession(secret: String): String = ServiceQueue.PUBKY.background {
        paykitSdkService.importSession(secret).publicKey
    }

    suspend fun currentPublicKey(): String? = ServiceQueue.PUBKY.background {
        paykitSdkService.currentPublicKey()
    }

    suspend fun signOut() = ServiceQueue.PUBKY.background {
        paykitSdkService.signOut()
    }

    // Keep the initiating Job here, before ServiceQueue replaces it for individual native calls.
    internal suspend fun <T> withProfileRemoval(isOwnerCurrent: () -> Boolean, operation: suspend () -> T): T =
        paykitSdkService.withProfileRemoval(isOwnerCurrent, operation)

    suspend fun forgetSessionAccess() = ServiceQueue.PUBKY.background {
        paykitSdkService.forgetSessionAccess()
    }

    internal suspend fun forgetLocalProfileAccess() = ServiceQueue.PUBKY.background {
        paykitSdkService.forgetLocalProfileAccess()
    }

    internal suspend fun captureProfileLogout(publicKey: String, isOwnerCurrent: () -> Boolean): PaykitProfileLogout {
        var captured: PaykitProfileLogout? = null
        return try {
            ServiceQueue.PUBKY.background {
                paykitSdkService.captureProfileLogout(publicKey, isOwnerCurrent).also { captured = it }
            }
        } catch (error: Throwable) {
            // Queue cancellation can hide the successful native capture from its original caller.
            captured?.revoke()
            throw error
        }
    }

    internal suspend fun executeProfileLogout(logout: PaykitProfileLogout) =
        ServiceQueue.PUBKY.background { paykitSdkService.executeProfileLogout(logout) }

    internal suspend fun validateProfileLogout(logout: PaykitProfileLogout) =
        ServiceQueue.PUBKY.background { paykitSdkService.validateProfileLogout(logout) }

    internal suspend fun eraseProfileLogoutCredentials(logout: PaykitProfileLogout, localOnly: Boolean) =
        ServiceQueue.PUBKY.background { paykitSdkService.eraseProfileLogoutCredentials(logout, localOnly) }

    internal fun revokeProfileLogout() = paykitSdkService.revokeProfileLogout()

    internal suspend fun requireWalletWipe() = paykitSdkService.requireWalletWipe()

    suspend fun clearSessionAccess() = ServiceQueue.PUBKY.background {
        paykitSdkService.clearSessionAccess()
    }

    suspend fun removeBitkitPaymentEndpoints() = ServiceQueue.PUBKY.background {
        val endpointError = runSuspendCatching {
            val report = paykitSdkService.syncPublicEndpoints(emptyList())
            if (report.failed.isNotEmpty()) throw AppError("Failed to remove Paykit payment endpoints")
        }.exceptionOrNull()
        val appError = runSuspendCatching {
            paykitSdkService.syncPaykitApp(privatePaymentsEnabled = false)
        }.exceptionOrNull()
        val cleanupError = endpointError ?: appError
        if (endpointError != null && appError != null) endpointError.addSuppressed(appError)
        cleanupError?.let { throw it }
    }

    internal suspend fun removeBitkitPaymentEndpointsForRemoval(
        endpointsAcknowledged: Boolean,
        appAcknowledged: Boolean,
        onEndpointsAcknowledged: () -> Unit,
        onAppAcknowledged: () -> Unit,
    ): Unit = ServiceQueue.PUBKY.background {
        val endpointError = runSuspendCatching {
            if (!endpointsAcknowledged) {
                val report = paykitSdkService.syncPublicEndpoints(emptyList(), onEndpointsAcknowledged) { true }
                check(report != null && report.failed.isEmpty()) { "Failed to remove Paykit payment endpoints" }
            }
        }.exceptionOrNull()
        val appError = runSuspendCatching {
            if (!appAcknowledged) {
                paykitSdkService.syncPaykitApp(
                    privatePaymentsEnabled = false,
                    priority = PaykitSdkOperationLock.Priority.Ordered,
                    canSync = { true },
                    onAcknowledged = onAppAcknowledged,
                )
            }
        }.exceptionOrNull()
        if (endpointError != null && appError != null) endpointError.addSuppressed(appError)
        (endpointError ?: appError)?.let { throw it }
    }

    // endregion

    // region Key derivation

    suspend fun deriveSecretKey(mnemonic: String): String = ServiceQueue.PUBKY.background {
        PaykitSdkService.deriveSecretKey(mnemonic)
    }

    suspend fun publicKeyFromSecret(secretKeyHex: String): String = ServiceQueue.PUBKY.background {
        PaykitSdkService.publicKeyFromSecret(secretKeyHex)
    }

    // endregion

    // region Homeserver auth

    suspend fun signUp(secretKeyHex: String, homeserverZ32: String, signupCode: String?): Unit =
        ServiceQueue.PUBKY.background {
            paykitSdkService.signUp(secretKeyHex, homeserverZ32, signupCode)
            Unit
        }

    suspend fun registerIdentity(
        secretKeyHex: String,
        homeserverZ32: String,
        signupCode: String?,
    ): PubkySessionBootstrapResult =
        ServiceQueue.PUBKY.background {
            paykitSdkService.registerIdentity(secretKeyHex, homeserverZ32, signupCode)
        }

    suspend fun activateRegisteredIdentity(result: PubkySessionBootstrapResult) = ServiceQueue.PUBKY.background {
        paykitSdkService.activateRegisteredIdentity(result)
    }

    suspend fun signIn(secretKeyHex: String): Unit = ServiceQueue.PUBKY.background {
        paykitSdkService.signIn(secretKeyHex)
        Unit
    }

    suspend fun hasIdentityRecord(publicKey: String): Boolean = ServiceQueue.PUBKY.background {
        paykitSdkService.hasIdentityRecord(publicKey)
    }

    // endregion

    // region Auth approval

    suspend fun parseAuthUrl(url: String) = ServiceQueue.PUBKY.background {
        PaykitSdkService.parseAuthUrl(url)
    }

    suspend fun validateSignupRequest(authorizationUrl: String?, homeserverPublicKey: String): Unit =
        ServiceQueue.PUBKY.background {
            authorizationUrl?.let { parseLegacyPubkyAuthUrl(it) }
            PaykitPublicKeys.normalize(homeserverPublicKey)
            Unit
        }

    suspend fun approveAuth(
        authUrl: String,
        expectedCapabilities: String,
        approvedClientId: String,
        secretKeyHex: String,
    ) = ServiceQueue.PUBKY.background {
        paykitSdkService.republishIdentityIfNeeded(publicKeyFromSecret(secretKeyHex))
        paykitSdkService.approveAuth(authUrl, expectedCapabilities, approvedClientId, secretKeyHex)
    }

    suspend fun approveRingAuth(
        authUrl: String,
        secretKeyHex: String,
        timeout: Duration = AUTHORIZATION_TIMEOUT,
    ) = ServiceQueue.PUBKY.background {
        paykitSdkService.republishIdentityIfNeeded(publicKeyFromSecret(secretKeyHex))
        withTimeoutOrNull(timeout) {
            approvePubkyAuth(authUrl, secretKeyHex)
        } ?: throw PubkyRingAuthTimeoutError()
    }

    suspend fun approveAuthWithCompanionClaim(
        authUrl: String,
        expectedCapabilities: String,
        approvedClientId: String,
        secretKeyHex: String,
        claim: PubkyAuthCompanionClaim,
    ) = ServiceQueue.PUBKY.background {
        paykitSdkService.republishIdentityIfNeeded(publicKeyFromSecret(secretKeyHex))
        paykitSdkService.approveAuthWithCompanionClaim(
            authUrl = authUrl,
            expectedCapabilities = expectedCapabilities,
            approvedClientId = approvedClientId,
            secretKeyHex = secretKeyHex,
            claim = claim,
        )
    }

    // endregion

    // region File operations

    suspend fun fetchFile(uri: String, maxBytes: ULong): ByteArray = cancellablePublicRead {
        paykitSdkService.fetchFile(uri, maxBytes)
    }

    suspend fun listPublicDirectoryPage(uri: String, cursor: String?, limit: UShort): List<String> =
        cancellablePublicRead { paykitSdkService.listPublicDirectoryPage(uri, cursor, limit) }

    suspend fun fetchPublicFile(uri: String, maxBytes: ULong): ByteArray = cancellablePublicRead {
        paykitSdkService.fetchPublicFile(uri, maxBytes)
    }

    suspend fun importShopContacts(
        publicKeys: List<String>,
        expectedIdentity: String,
        isStillCurrent: () -> Boolean,
    ): List<ContactRecord> = ServiceQueue.PUBKY.background(ServiceQueue.PUBKY.queueContext.minusKey(Job)) {
        paykitSdkService.importShopContacts(publicKeys, expectedIdentity, isStillCurrent)
    }

    // endregion

    // region Profile & contacts

    suspend fun publishPaykitProfile(profile: PaykitProfile, expectedIdentity: String) = ServiceQueue.PUBKY.background {
        paykitSdkService.publishPaykitProfile(profile, expectedIdentity)
    }

    suspend fun uploadProfileAvatar(bytes: ByteArray, contentType: String): String = ServiceQueue.PUBKY.background {
        paykitSdkService.uploadProfileAvatar(bytes, contentType)
    }

    internal suspend fun captureProfileDeletion(
        publicKey: String,
        isStillCurrent: () -> Boolean,
    ): PaykitProfileDeletionSnapshot = ServiceQueue.PUBKY.background {
        paykitSdkService.captureProfileDeletion(publicKey, isStillCurrent)
    }

    internal suspend fun validateProfileDeletion(snapshot: PaykitProfileDeletionSnapshot) =
        ServiceQueue.PUBKY.background {
            paykitSdkService.validateProfileDeletion(snapshot)
        }

    internal suspend fun deletePaykitProfile(snapshot: PaykitProfileDeletionSnapshot) =
        deletePaykitProfile(snapshot) {}

    internal suspend fun deletePaykitProfile(
        snapshot: PaykitProfileDeletionSnapshot,
        onAcknowledged: () -> Unit,
    ) = ServiceQueue.PUBKY.background {
        paykitSdkService.deletePaykitProfile(snapshot, onAcknowledged)
    }
    suspend fun checkProfileDeletionAllowed() = ServiceQueue.PUBKY.background {
        paykitSdkService.checkProfileDeletionAllowed()
    }

    suspend fun removeContactsForProfile(publicKeys: List<String>): List<ContactRecord> =
        ServiceQueue.PUBKY.background {
            paykitSdkService.removeContactsForProfile(publicKeys)
        }

    suspend fun deletePaykitProfile() = ServiceQueue.PUBKY.background {
        paykitSdkService.deletePaykitProfile()
    }

    suspend fun fetchPubkyProfileWithRevision(publicKey: String): PubkyProfileSnapshot? = cancellablePublicRead {
        paykitSdkService.fetchPubkyProfileWithRevision(publicKey)
    }

    suspend fun fetchPaykitProfile(publicKey: String): PaykitProfileRecord? = cancellablePublicRead {
        paykitSdkService.fetchPaykitProfile(publicKey)
    }

    suspend fun publishPubkyProfile(
        update: PubkyProfileUpdate,
        expectedRevision: String?,
        expectedIdentity: String,
    ): PubkyProfileSnapshot = ServiceQueue.PUBKY.background {
        paykitSdkService.publishPubkyProfile(update, expectedRevision, expectedIdentity)
    }

    suspend fun deletePubkyProfile(
        expectedRevision: String,
        expectedIdentity: String,
        onAcknowledged: () -> Unit = {},
    ) = ServiceQueue.PUBKY.background {
        paykitSdkService.deletePubkyProfile(expectedRevision, expectedIdentity, onAcknowledged)
    }

    suspend fun uploadPubkyAppAvatar(bytes: ByteArray, contentType: String, expectedIdentity: String): String =
        ServiceQueue.PUBKY.background { paykitSdkService.uploadPubkyAppAvatar(bytes, contentType, expectedIdentity) }

    suspend fun getContacts(publicKey: String): List<String> = cancellablePublicRead {
        paykitSdkService.fetchPubkyFollows(publicKey)
    }

    suspend fun contactRecords(): List<ContactRecord> = ServiceQueue.PUBKY.background {
        paykitSdkService.contactRecords()
    }

    suspend fun observedContactRecords(expectedIdentity: String): List<ContactRecord> = ServiceQueue.PUBKY.background {
        paykitSdkService.observeState(expectedIdentity).contacts
    }

    suspend fun saveContact(
        publicKey: String,
        label: String?,
        restorePrivateConnection: Boolean = false,
        expectedIdentity: String? = null,
        isStillCurrent: (() -> Boolean)? = null,
    ): ContactRecord = ServiceQueue.PUBKY.background {
        paykitSdkService.saveContact(
            publicKey,
            label,
            restorePrivateConnection,
            expectedIdentity,
            isStillCurrent,
        )
    }

    suspend fun saveContacts(
        updates: List<ContactUpdate>,
        expectedIdentity: String? = null,
        isStillCurrent: (() -> Boolean)? = null,
    ): List<ContactRecord> = ServiceQueue.PUBKY.background {
        paykitSdkService.saveContacts(updates, expectedIdentity, isStillCurrent)
    }

    suspend fun removeContact(publicKey: String): ContactRecord? = ServiceQueue.PUBKY.background {
        paykitSdkService.removeContact(publicKey)
    }

    suspend fun removeContacts(publicKeys: List<String>): List<ContactRecord> = ServiceQueue.PUBKY.background {
        paykitSdkService.removeContacts(publicKeys)
    }

    suspend fun resolveContactProfile(
        publicKey: String,
        allowPubkyProfileFallback: Boolean,
        lane: PaykitReadLane = PaykitReadLane.Interactive,
        timeout: Duration? = null,
    ): ProfileResolution? = cancellablePublicRead {
        paykitSdkService.resolveContactProfile(publicKey, allowPubkyProfileFallback, lane, timeout)
    }
    // endregion

    private suspend fun <T> cancellablePublicRead(block: suspend CoroutineScope.() -> T): T =
        ServiceQueue.PUBKY.background(ServiceQueue.PUBKY.queueContext.minusKey(Job), block)
}

class PubkyRingAuthTimeoutError : AppError("Ring authorization timed out")

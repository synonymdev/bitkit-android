package to.bitkit.repositories

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.ImageLoader
import com.synonym.paykit.ContactProfileResolution
import com.synonym.paykit.PaykitProfile
import com.synonym.paykit.PubkyAuthCompanionClaim
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import to.bitkit.async.appScope
import to.bitkit.data.PubkyStore
import to.bitkit.data.SettingsStore
import to.bitkit.data.hasPaykitState
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.sharing.ExternalPubkyIdentityRef
import to.bitkit.data.sharing.SharedPubkyContract
import to.bitkit.data.sharing.SharedPubkyCredential
import to.bitkit.data.sharing.SharedPubkyDiscovery
import to.bitkit.data.sharing.SharedPubkyError
import to.bitkit.data.sharing.SharedPubkyIdentity
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.HomegateResponse
import to.bitkit.models.PubkyAuthClaim
import to.bitkit.models.PubkyAuthRequest
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyProfileData
import to.bitkit.models.PubkyProfileLink
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.models.PubkyRingAuthCallback
import to.bitkit.models.PubkyRingAuthCallbackHandlingResult
import to.bitkit.models.PubkySessionBackupKind
import to.bitkit.models.PubkySessionBackupV1
import to.bitkit.services.PaykitReceiverPaths
import to.bitkit.services.PubkyService
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

enum class PubkyAuthState { Idle, Authenticating, Authenticated }

data class PubkyRingAuthRequest(
    val authUrl: String,
    val callbackNonce: String,
)

sealed class PubkyContactError(message: String) : AppError(message) {
    data object AlreadyExists : PubkyContactError("Contact already exists")
    data object CannotAddSelf : PubkyContactError("Cannot add your own pubky as a contact")
    data object InvalidFormat : PubkyContactError("Invalid pubky key format")
}

private class PubkyAuthAttemptInactive : AppError("Auth attempt is no longer active")

private enum class AuthAttemptWaitResult { Approved, Inactive }

@Suppress("TooManyFunctions", "LargeClass", "LongParameterList")
@Singleton
class PubkyRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val pubkyService: PubkyService,
    private val keychain: Keychain,
    private val imageLoader: ImageLoader,
    private val pubkyStore: PubkyStore,
    private val settingsStore: SettingsStore,
    private val httpClient: HttpClient,
    private val sharedPubkyDiscovery: SharedPubkyDiscovery,
) {
    companion object {
        private const val TAG = "PubkyRepo"
        private const val PUBKY_PREFIX = "pubky"
        private const val PUBKY_SCHEME = "pubky://"
        private const val AVATAR_MAX_SIZE = 400
        private const val AVATAR_QUALITY = 80
        private const val MANAGED_SECRET_QUARANTINED = "1"
        private const val SHARED_EXPORT_ENABLED = "1"
    }

    private val scope = appScope(ioDispatcher, TAG)
    private val serviceInitializeMutex = Mutex()
    private val identityLifecycleMutex = Mutex()
    private val loadProfileMutex = Mutex()
    private val loadContactsMutex = Mutex()
    private var isServiceInitialized = false

    private val _authState = MutableStateFlow(PubkyAuthState.Idle)
    private val _activeAuthAttemptId = MutableStateFlow<String?>(null)
    private val _approvedAuthAttemptId = MutableStateFlow<String?>(null)
    private val _authCancelEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val authCancelEvents = _authCancelEvents.asSharedFlow()

    private val _profile = MutableStateFlow<PubkyProfile?>(null)
    val profile: StateFlow<PubkyProfile?> = _profile.asStateFlow()

    private val _publicKey = MutableStateFlow<String?>(null)
    val publicKey: StateFlow<String?> = _publicKey.asStateFlow()

    private val _isLoadingProfile = MutableStateFlow(false)
    val isLoadingProfile: StateFlow<Boolean> = _isLoadingProfile.asStateFlow()

    private val _contacts = MutableStateFlow<List<PubkyProfile>>(emptyList())
    val contacts: StateFlow<List<PubkyProfile>> = _contacts.asStateFlow()

    private val _contactsLoadVersion = MutableStateFlow(0L)
    val contactsLoadVersion: StateFlow<Long> = _contactsLoadVersion.asStateFlow()

    private val _isLoadingContacts = MutableStateFlow(false)
    val isLoadingContacts: StateFlow<Boolean> = _isLoadingContacts.asStateFlow()

    private val _sessionRestorationFailed = MutableStateFlow(false)
    val sessionRestorationFailed: StateFlow<Boolean> = _sessionRestorationFailed.asStateFlow()

    private val _pendingImportProfile = MutableStateFlow<PubkyProfile?>(null)
    val pendingImportProfile: StateFlow<PubkyProfile?> = _pendingImportProfile.asStateFlow()

    private val _pendingImportContacts = MutableStateFlow<List<PubkyProfile>>(emptyList())
    val pendingImportContacts: StateFlow<List<PubkyProfile>> = _pendingImportContacts.asStateFlow()

    private val _backupStateVersion = MutableStateFlow(0L)
    val backupStateVersion: StateFlow<Long> = _backupStateVersion.asStateFlow()

    val isAuthenticated: StateFlow<Boolean> = _publicKey.map { it != null }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val displayName: StateFlow<String?> = combine(_profile, pubkyStore.data) { profile, cached ->
        profile?.name ?: cached.cachedName
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val displayImageUri: StateFlow<String?> = combine(_profile, pubkyStore.data) { profile, cached ->
        profile?.imageUrl ?: cached.cachedImageUri
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private sealed interface InitResult {
        data object NoSession : InitResult
        data class Restored(val publicKey: String) : InitResult
        data object RestorationFailed : InitResult
        data object ExternalSourceUnavailable : InitResult
    }

    init {
        scope.launch { initialize() }
    }

    // region Initialization

    suspend fun initialize() = withContext(ioDispatcher) {
        runSuspendCatching {
            ensureServiceInitialized()
        }.onFailure {
            Logger.error("Failed to initialize paykit", it, context = TAG)
        }.getOrNull() ?: return@withContext

        identityLifecycleMutex.withLock {
            _sessionRestorationFailed.update { false }
            val result = runSuspendCatching {
                resolveStoredSessionInitialization()
            }.onFailure {
                Logger.error("Failed to initialize paykit", it, context = TAG)
            }.getOrNull() ?: return@withLock

            applySessionInitialization(result)
        }
    }

    private suspend fun resolveStoredSessionInitialization(): InitResult {
        val savedSessionSecret = runCatching {
            keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)
        }.getOrNull()
        val isManagedSecretQuarantined = runCatching {
            keychain.loadString(Keychain.Key.PUBKY_MANAGED_SECRET_QUARANTINED.name)
        }.getOrNull() == MANAGED_SECRET_QUARANTINED
        val storedSecretKeyHex = runCatching {
            keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)
        }.getOrNull().takeUnless { isManagedSecretQuarantined }
        val externalIdentityRef = pubkyStore.data.first().externalIdentityRef?.let { identityRef ->
            runCatching { identityRef.validated() }.getOrElse {
                return InitResult.ExternalSourceUnavailable
            }
        }
        if (isManagedSecretQuarantined && externalIdentityRef != null) {
            return InitResult.ExternalSourceUnavailable
        }

        return resolveSessionInitialization(
            savedSessionSecret = savedSessionSecret.takeUnless {
                isManagedSecretQuarantined && externalIdentityRef == null
            },
            storedSecretKeyHex = storedSecretKeyHex,
            externalIdentityRef = externalIdentityRef,
        )
    }

    private suspend fun applySessionInitialization(result: InitResult) {
        when (result) {
            is InitResult.NoSession -> {
                disableLocalIdentityExport()
                clearAuthenticatedState()
                Logger.debug("Found no saved paykit session", context = TAG)
            }
            is InitResult.Restored -> restoreInitializedSession(result.publicKey)
            is InitResult.RestorationFailed -> {
                disableLocalIdentityExport()
                if (pubkyStore.data.first().externalIdentityRef == null) {
                    clearAuthenticatedState()
                } else {
                    clearAuthenticatedRuntimeState()
                }
                _sessionRestorationFailed.update { true }
            }
            is InitResult.ExternalSourceUnavailable -> {
                clearUnavailableExternalIdentityLocked()
                Logger.warn("Disconnected unavailable Pubky Ring identity", context = TAG)
            }
        }
    }

    private suspend fun restoreInitializedSession(publicKey: String) {
        val hasLocalSecret = !keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name).isNullOrBlank()
        if (pubkyStore.data.first().externalIdentityRef == null && hasLocalSecret) {
            enableLocalIdentityExport(publicKey)
        } else {
            disableLocalIdentityExport()
        }
        _publicKey.update { publicKey }
        _authState.update { PubkyAuthState.Authenticated }
        Logger.info("Restored paykit session for '${redacted(publicKey)}'", context = TAG)
        loadProfile()
        loadContacts()
    }

    private suspend fun ensureServiceInitialized() = withContext(ioDispatcher) {
        serviceInitializeMutex.withLock {
            if (!isServiceInitialized) {
                pubkyService.initialize()
                isServiceInitialized = true
            }
        }
    }

    private suspend fun resolveSessionInitialization(
        savedSessionSecret: String?,
        storedSecretKeyHex: String?,
        externalIdentityRef: ExternalPubkyIdentityRef?,
    ): InitResult = withContext(ioDispatcher) {
        if (externalIdentityRef != null) {
            return@withContext resolveExternalSession(
                savedSessionSecret = savedSessionSecret,
                identityRef = externalIdentityRef,
            )
        }

        if (!savedSessionSecret.isNullOrEmpty()) {
            runSuspendCatching {
                val publicKey = if (storedSecretKeyHex.isNullOrBlank()) {
                    pubkyService.importExternalSession(savedSessionSecret)
                } else {
                    pubkyService.importSession(savedSessionSecret)
                }.ensurePubkyPrefix()
                InitResult.Restored(publicKey)
            }.getOrElse {
                Logger.warn("Failed to restore paykit session, attempting re-sign-in", it, context = TAG)
                resolveSignedInSession(savedSessionSecret, storedSecretKeyHex)
            }
        } else {
            resolveSignedInSession(savedSessionSecret, storedSecretKeyHex)
        }
    }

    private suspend fun resolveExternalSession(
        savedSessionSecret: String?,
        identityRef: ExternalPubkyIdentityRef,
    ): InitResult = withContext(ioDispatcher) {
        val sourceIdentity = sharedPubkyDiscovery.discoverRingIdentities().getOrElse {
            return@withContext InitResult.ExternalSourceUnavailable
        }.firstOrNull { it.matches(identityRef) }
            ?: return@withContext InitResult.ExternalSourceUnavailable

        if (!savedSessionSecret.isNullOrBlank()) {
            runSuspendCatching {
                val restored = canonicalBitkitPubky(pubkyService.importExternalSession(savedSessionSecret))
                if (wirePubky(restored) != identityRef.pubky) throw SharedPubkyError.InvalidResponse
                InitResult.Restored(restored)
            }.onSuccess {
                return@withContext it
            }.onFailure {
                Logger.warn("Failed to restore external paykit session, attempting re-sign-in", it, context = TAG)
            }
        }

        val credential = sharedPubkyDiscovery.readRingCredential(sourceIdentity.pubky).getOrElse {
            return@withContext InitResult.ExternalSourceUnavailable
        }
        if (!credential.matches(identityRef)) return@withContext InitResult.ExternalSourceUnavailable

        runSuspendCatching {
            val publicKey = signInWithExternalCredential(credential)
            Logger.info("Re-signed in with Pubky Ring identity '${redacted(publicKey)}'", context = TAG)
            InitResult.Restored(publicKey)
        }.getOrElse {
            Logger.error("Failed external re-sign-in recovery", it, context = TAG)
            InitResult.RestorationFailed
        }
    }

    private suspend fun resolveSignedInSession(
        savedSessionSecret: String?,
        storedSecretKeyHex: String?,
    ): InitResult = withContext(ioDispatcher) {
        if (storedSecretKeyHex.isNullOrEmpty()) {
            if (!savedSessionSecret.isNullOrEmpty()) {
                Logger.warn("Skipped re-sign-in recovery, keeping saved session", context = TAG)
                InitResult.RestorationFailed
            } else {
                InitResult.NoSession
            }
        } else {
            runSuspendCatching {
                pubkyService.signIn(storedSecretKeyHex)
                notifyBackupStateChanged()
                val publicKey = pubkyService.publicKeyFromSecret(storedSecretKeyHex).ensurePubkyPrefix()
                Logger.info("Re-signed in and restored session for '${redacted(publicKey)}'", context = TAG)
                InitResult.Restored(publicKey)
            }.getOrElse {
                Logger.error("Failed re-sign-in recovery", it, context = TAG)
                InitResult.RestorationFailed
            }
        }
    }

    fun clearSessionRestorationFailed() {
        _sessionRestorationFailed.update { false }
    }

    // endregion

    // region Ring auth flow

    suspend fun startAuthentication(): Result<PubkyRingAuthRequest> {
        val attemptId = UUID.randomUUID().toString()
        _activeAuthAttemptId.update { attemptId }
        _approvedAuthAttemptId.update { null }
        _authState.update { PubkyAuthState.Authenticating }
        return try {
            runSuspendCatching {
                val authUrl = withContext(ioDispatcher) { pubkyService.startAuth() }
                PubkyRingAuthRequest(authUrl = authUrl, callbackNonce = attemptId)
            }.onFailure {
                _activeAuthAttemptId.update { null }
                restoreAuthStateAfterAuthFlow()
            }
        } catch (e: CancellationException) {
            _activeAuthAttemptId.update { null }
            restoreAuthStateAfterAuthFlow()
            throw e
        }
    }

    suspend fun completeAuthentication(): Result<Unit> = identityLifecycleMutex.withLock {
        val attemptId = _activeAuthAttemptId.value
            ?: return@withLock Result.failure(PubkyAuthAttemptInactive())
        var didCompleteAuth = false
        try {
            val result = runSuspendCatching {
                waitForAuthApproval(attemptId)
                withContext(ioDispatcher) {
                    pubkyService.completeAuth()
                    didCompleteAuth = true
                    ensureAuthAttemptActive(attemptId)
                    val pk = requireNotNull(pubkyService.currentPublicKey()?.ensurePubkyPrefix()) {
                        "No active Pubky session"
                    }
                    ensureAuthAttemptActive(attemptId)

                    settingsStore.update { it.copy(sharesPrivatePaykitEndpoints = false) }
                    disableLocalIdentityExport()
                    notifyBackupStateChanged()

                    pk
                }
            }

            if (result.isFailure) {
                clearCompletedAuthSessionIfNeeded(didCompleteAuth)
                if (_activeAuthAttemptId.value == attemptId) {
                    _activeAuthAttemptId.update { null }
                }
                if (_approvedAuthAttemptId.value == attemptId) {
                    _approvedAuthAttemptId.update { null }
                }
                restoreAuthStateAfterAuthFlow()
            }

            result.onSuccess { pk ->
                if (_activeAuthAttemptId.value == attemptId) {
                    _activeAuthAttemptId.update { null }
                }
                if (_approvedAuthAttemptId.value == attemptId) {
                    _approvedAuthAttemptId.update { null }
                }
                _publicKey.update { pk }
                _authState.update { PubkyAuthState.Authenticated }
                Logger.info("Completed pubky auth for '${redacted(pk)}'", context = TAG)
                loadProfile()
                loadContacts()
            }.map { }
        } catch (e: CancellationException) {
            clearCompletedAuthSessionIfNeeded(didCompleteAuth)
            if (_activeAuthAttemptId.value == attemptId) {
                _activeAuthAttemptId.update { null }
            }
            if (_approvedAuthAttemptId.value == attemptId) {
                _approvedAuthAttemptId.update { null }
            }
            restoreAuthStateAfterAuthFlow()
            throw e
        }
    }

    private suspend fun clearCompletedAuthSessionIfNeeded(didCompleteAuth: Boolean) {
        if (!didCompleteAuth) return
        runSuspendCatching {
            withContext(NonCancellable + ioDispatcher) {
                pubkyService.clearSessionAccess()
            }
        }.onFailure {
            Logger.warn("Failed to clear canceled Pubky auth session", it, context = TAG)
        }
    }

    suspend fun cancelAuthentication() {
        try {
            runSuspendCatching {
                withContext(ioDispatcher) { pubkyService.cancelAuth() }
            }.onFailure { Logger.warn("Failed to cancel auth", it, context = TAG) }
        } finally {
            endAuthAttempt()
        }
    }

    fun cancelAuthenticationSync() {
        scope.launch { cancelAuthentication() }
    }

    suspend fun handleAuthCallback(callback: PubkyRingAuthCallback): PubkyRingAuthCallbackHandlingResult {
        if (!isCurrentAuthCallback(callback)) {
            return handleInvalidAuthCallback(callback)
        }

        return when (callback) {
            is PubkyRingAuthCallback.Success -> {
                Logger.info("Received Pubky Ring auth success callback", context = TAG)
                _activeAuthAttemptId.value?.let { attemptId ->
                    _approvedAuthAttemptId.update { attemptId }
                }
                PubkyRingAuthCallbackHandlingResult.Handled
            }
            is PubkyRingAuthCallback.Cancel -> {
                Logger.info("Received Pubky Ring auth cancel callback", context = TAG)
                cancelAuthentication()
                PubkyRingAuthCallbackHandlingResult.Handled
            }
            is PubkyRingAuthCallback.Error -> {
                Logger.warn("Received Pubky Ring auth error callback", context = TAG)
                cancelAuthentication()
                PubkyRingAuthCallbackHandlingResult.TrustedError(callback.message)
            }
        }
    }

    private fun handleInvalidAuthCallback(
        callback: PubkyRingAuthCallback,
    ): PubkyRingAuthCallbackHandlingResult {
        if (_activeAuthAttemptId.value == null) {
            Logger.warn("Ignoring Pubky Ring auth callback with missing or invalid nonce", context = TAG)
            return PubkyRingAuthCallbackHandlingResult.Ignored
        }

        return when (callback) {
            is PubkyRingAuthCallback.Success -> {
                Logger.warn("Ignoring Pubky Ring auth success callback with missing or invalid nonce", context = TAG)
                PubkyRingAuthCallbackHandlingResult.Ignored
            }
            is PubkyRingAuthCallback.Cancel -> {
                Logger.warn("Ignoring Pubky Ring auth cancel callback with missing or invalid nonce", context = TAG)
                PubkyRingAuthCallbackHandlingResult.Ignored
            }
            is PubkyRingAuthCallback.Error -> {
                Logger.warn("Ignoring Pubky Ring auth error callback with missing or invalid nonce", context = TAG)
                PubkyRingAuthCallbackHandlingResult.Ignored
            }
        }
    }

    private fun isCurrentAuthCallback(callback: PubkyRingAuthCallback): Boolean {
        val activeAuthAttemptId = _activeAuthAttemptId.value ?: return false
        return callback.nonce == activeAuthAttemptId ||
            (callback is PubkyRingAuthCallback.Success && callback.nonce == null)
    }

    private suspend fun waitForAuthApproval(attemptId: String) {
        if (_approvedAuthAttemptId.value == attemptId) return

        val result = combine(_approvedAuthAttemptId, _activeAuthAttemptId) { approvedAttemptId, activeAttemptId ->
            when {
                approvedAttemptId == attemptId -> AuthAttemptWaitResult.Approved
                activeAttemptId != attemptId -> AuthAttemptWaitResult.Inactive
                else -> null
            }
        }.first { it != null }

        if (result != AuthAttemptWaitResult.Approved) throw PubkyAuthAttemptInactive()
    }

    private fun ensureAuthAttemptActive(attemptId: String?) {
        if (attemptId == null) return
        if (_activeAuthAttemptId.value == attemptId) return

        throw PubkyAuthAttemptInactive()
    }

    private fun endAuthAttempt() {
        _activeAuthAttemptId.update { null }
        _approvedAuthAttemptId.update { null }
        _authCancelEvents.tryEmit(Unit)
        restoreAuthStateAfterAuthFlow()
    }

    private fun restoreAuthStateAfterAuthFlow() {
        _authState.update { if (_publicKey.value == null) PubkyAuthState.Idle else PubkyAuthState.Authenticated }
    }

    // endregion

    // region Payment endpoints

    suspend fun removeBitkitPaymentEndpoints(): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            pubkyService.removeBitkitPaymentEndpoints()
            Unit
        }
    }

    suspend fun currentPublicKey(): Result<String?> = withContext(ioDispatcher) {
        runSuspendCatching {
            pubkyService.currentPublicKey()?.ensurePubkyPrefix()
        }
    }

    // endregion

    // region Profile loading

    suspend fun loadProfile() {
        val pk = _publicKey.value ?: return
        if (!loadProfileMutex.tryLock()) return

        _isLoadingProfile.update { true }
        try {
            runSuspendCatching {
                withContext(ioDispatcher) {
                    resolveContactProfile(pk).getOrThrow()
                        ?: throw AppError("Profile not found")
                }
            }.onSuccess { loadedProfile ->
                if (_publicKey.value != pk) {
                    Logger.debug("Skipped stale profile load for '${redacted(pk)}'", context = TAG)
                    return@onSuccess
                }
                _profile.update { loadedProfile }
                cacheMetadata(loadedProfile)
            }.onFailure {
                Logger.error("Failed to load profile", it, context = TAG)
            }
        } finally {
            _isLoadingProfile.update { false }
            loadProfileMutex.unlock()
        }
    }

    suspend fun fetchRemoteProfile(publicKey: String): Result<PubkyProfile?> = runSuspendCatching {
        withContext(ioDispatcher) {
            resolveContactProfile(publicKey).getOrThrow()
        }
    }

    // endregion

    // region Profile creation & editing

    suspend fun deriveKeys(): Result<Pair<String, String>> = runSuspendCatching {
        withContext(ioDispatcher) {
            val secretKeyHex = deriveLocalSecretKeyFromWalletSeed()
            val rawKey = pubkyService.publicKeyFromSecret(secretKeyHex)
            val publicKeyZ32 = rawKey.ensurePubkyPrefix()
            Pair(publicKeyZ32, secretKeyHex)
        }
    }

    private suspend fun fetchHomegateSignupCode(): HomegateResponse =
        httpClient.post("${Env.homegateUrl}/ip_verification").body()

    suspend fun createIdentity(
        name: String,
        bio: String,
        links: List<PubkyProfileLink>,
        tags: List<String>,
        avatarBytes: ByteArray?,
    ): Result<Unit> = identityLifecycleMutex.withLock {
        runSuspendCatching {
            withContext(ioDispatcher) {
                if (pubkyStore.data.first().externalIdentityRef != null) {
                    throw SharedPubkyError.IdentityConflict
                }
                val (publicKeyZ32, secretKeyHex) = deriveKeys().getOrThrow()

                val homegate = fetchHomegateSignupCode()

                runSuspendCatching {
                    pubkyService.signUp(secretKeyHex, homegate.homeserverPubky, homegate.signupCode)
                }.getOrElse {
                    Logger.warn("Retrying sign in after sign up failed", it, context = TAG)
                    pubkyService.signIn(secretKeyHex)
                }

                val imageUrl = avatarBytes?.let { uploadAvatarInternal(it) }
                writeProfile(name, bio, links, tags, imageUrl)

                val createdProfile = PubkyProfile(
                    publicKey = publicKeyZ32,
                    name = name,
                    bio = bio,
                    imageUrl = imageUrl,
                    links = links,
                    tags = tags,
                    status = null,
                )
                enableLocalIdentityExport(publicKeyZ32)
                _publicKey.update { publicKeyZ32 }
                _authState.update { PubkyAuthState.Authenticated }
                _profile.update { createdProfile }
                cacheMetadata(createdProfile)
                notifyBackupStateChanged()
                Logger.info("Created identity for '${redacted(publicKeyZ32)}'", context = TAG)
                loadProfile()
                loadContacts()
            }
        }
    }

    suspend fun uploadAvatar(imageBytes: ByteArray): Result<String> = runSuspendCatching {
        withContext(ioDispatcher) {
            requireExternalIdentitySource()
            uploadAvatarInternal(imageBytes)
        }
    }

    private suspend fun uploadAvatarInternal(imageBytes: ByteArray): String {
        requireNotNull(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)) {
            "No session available"
        }
        val compressed = compressAvatar(imageBytes)
        return pubkyService.uploadProfileAvatar(compressed, contentType = "image/jpeg")
    }

    suspend fun saveProfile(
        name: String,
        bio: String,
        links: List<PubkyProfileLink>,
        tags: List<String>,
        imageUrl: String?,
    ): Result<Unit> = runSuspendCatching {
        withContext(ioDispatcher) {
            requireExternalIdentitySource()
            requireNotNull(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)) {
                "No session available"
            }
            writeProfile(name, bio, links, tags, imageUrl)
            val pk = requireNotNull(_publicKey.value) { "No public key available" }
            val profile = PubkyProfile(
                publicKey = pk,
                name = name,
                bio = bio,
                imageUrl = imageUrl ?: _profile.value?.imageUrl,
                links = links,
                tags = tags,
                status = _profile.value?.status,
            )
            _profile.update { profile }
            cacheMetadata(profile)
            notifyBackupStateChanged()
        }
    }

    suspend fun deleteProfileWithSessionRetry(): Result<Unit> = withContext(ioDispatcher) {
        val initialResult = deleteProfile()
        if (initialResult.isSuccess) return@withContext initialResult

        val refreshedSession = refreshSessionIfPossible().getOrDefault(false)
        if (!refreshedSession) return@withContext initialResult

        deleteProfile()
    }

    suspend fun deleteProfile(): Result<Unit> = runSuspendCatching {
        withContext(ioDispatcher) {
            requireExternalIdentitySource()
            disableLocalIdentityExport()
            requireNotNull(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)) {
                "No session available"
            }
            deleteAllContacts()
            runSuspendCatching {
                pubkyService.deletePaykitProfile()
            }.getOrElse {
                if (!it.isMissingPubkyData()) {
                    throw it
                }
                Logger.info("Continuing sign out, bitkit profile storage already missing", context = TAG)
            }
        }
        signOut().getOrThrow()
    }

    private suspend fun deleteAllContacts() {
        val records = runSuspendCatching {
            pubkyService.contactRecords()
        }.getOrElse {
            if (!it.isMissingPubkyData()) throw it
            emptyList()
        }
        records.forEach { record ->
            runSuspendCatching {
                pubkyService.removeContact(record.publicKey)
            }.onFailure {
                Logger.warn("Failed to delete contact '${redacted(record.publicKey)}'", it, context = TAG)
            }
        }
        pubkyStore.update { it.copy(contactProfileOverrides = emptyMap()) }
        notifyBackupStateChanged()
        _contacts.update { emptyList() }
        markContactsLoaded()
        Logger.info("Deleted all contacts", context = TAG)
    }

    @Suppress("LongParameterList")
    private suspend fun writeProfile(
        name: String,
        bio: String,
        links: List<PubkyProfileLink>,
        tags: List<String>,
        imageUrl: String?,
    ) {
        val data = PubkyProfile(
            publicKey = "",
            name = name,
            bio = bio,
            imageUrl = imageUrl,
            links = links,
            tags = tags,
            status = null,
        ).toProfileData()
        pubkyService.publishPaykitProfile(data.toPaykitProfile())
    }

    private fun compressAvatar(imageBytes: ByteArray): ByteArray {
        val original = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return imageBytes
        val scale = min(AVATAR_MAX_SIZE.toFloat() / original.width, AVATAR_MAX_SIZE.toFloat() / original.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                original,
                (original.width * scale).toInt(),
                (original.height * scale).toInt(),
                true,
            )
        } else {
            original
        }
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, AVATAR_QUALITY, out)
            out.toByteArray()
        }
    }

    // endregion

    // region Contact management

    suspend fun loadContacts() {
        val pk = _publicKey.value ?: return
        if (!loadContactsMutex.tryLock()) return

        _isLoadingContacts.update { true }
        try {
            runSuspendCatching {
                withContext(ioDispatcher) {
                    val records = pubkyService.contactRecords()
                    val overrides = pubkyStore.data.first().contactProfileOverrides

                    coroutineScope {
                        records.map { record ->
                            async {
                                runSuspendCatching {
                                    contactProfile(record.publicKey, record.label, record.profile, overrides)
                                }.onFailure {
                                    Logger.warn(
                                        "Failed to load contact '${redacted(record.publicKey)}'",
                                        it,
                                        context = TAG,
                                    )
                                }.getOrElse {
                                    PubkyProfile.placeholder(record.publicKey.ensurePubkyPrefix())
                                }
                            }
                        }.awaitAll().sortedBy { it.name.lowercase() }
                    }
                }
            }.onSuccess { loadedContacts ->
                if (_publicKey.value != pk) {
                    Logger.debug("Skipped stale contacts load for '${redacted(pk)}'", context = TAG)
                    return@onSuccess
                }
                _contacts.update { loadedContacts }
                markContactsLoaded()
            }.onFailure {
                Logger.error("Failed to load contacts", it, context = TAG)
            }
        } finally {
            _isLoadingContacts.update { false }
            loadContactsMutex.unlock()
        }
    }

    suspend fun fetchContactProfile(publicKey: String): Result<PubkyProfile> {
        val prefixedKey = runCatching { requireAddableContactPublicKey(publicKey) }
            .getOrElse { return Result.failure(it) }
        return resolveContactProfile(prefixedKey)
            .map { it ?: PubkyProfile.placeholder(prefixedKey) }
            .recoverCatching {
                if (it is CancellationException) {
                    throw it
                }
                Logger.warn("Falling back to placeholder contact '${redacted(prefixedKey)}'", it, context = TAG)
                PubkyProfile.placeholder(prefixedKey)
            }
    }

    suspend fun addContact(
        publicKey: String,
        existingProfile: PubkyProfile? = null,
    ): Result<Unit> = runSuspendCatching {
        withContext(ioDispatcher) {
            requireExternalIdentitySource()
            val prefixedKey = requireAddableContactPublicKey(
                publicKey = publicKey,
                allowExisting = existingProfile != null,
            )
            val profile = existingProfile?.copy(publicKey = prefixedKey)
                ?: resolveContactProfile(prefixedKey).getOrThrow()
                ?: PubkyProfile.placeholder(prefixedKey)
            pubkyService.saveContact(prefixedKey, profile.name, relevantReceiverPaths(prefixedKey))
            _contacts.update { current ->
                (current.filter { it.publicKey != prefixedKey } + profile)
                    .sortedBy { it.name.lowercase() }
            }
            markContactsLoaded()
            Logger.info("Added contact '${redacted(prefixedKey)}'", context = TAG)
        }
    }

    suspend fun refreshContactReceiverPaths(publicKey: String): Result<Unit> = runSuspendCatching {
        withContext(ioDispatcher) {
            requireExternalIdentitySource()
            val prefixedKey = requireAddableContactPublicKey(publicKey = publicKey, allowExisting = true)
            val contact = _contacts.value.firstOrNull { PubkyPublicKeyFormat.matches(it.publicKey, prefixedKey) }
                ?: return@withContext
            pubkyService.saveContact(prefixedKey, contact.name, relevantReceiverPaths(prefixedKey))
            Logger.info("Refreshed contact receiver paths for '${redacted(prefixedKey)}'", context = TAG)
        }
    }

    @Suppress("LongParameterList")
    suspend fun updateContact(
        publicKey: String,
        name: String,
        bio: String,
        imageUrl: String?,
        links: List<PubkyProfileLink>,
        tags: List<String>,
    ): Result<Unit> = runSuspendCatching {
        withContext(ioDispatcher) {
            requireExternalIdentitySource()
            val prefixedKey = publicKey.ensurePubkyPrefix()
            val updatedProfile = PubkyProfile(
                publicKey = prefixedKey,
                name = name,
                bio = bio,
                imageUrl = imageUrl,
                links = links,
                tags = tags,
                status = null,
            )
            pubkyService.saveContact(prefixedKey, name)
            upsertContactProfileOverride(updatedProfile)
            _contacts.update { current ->
                current.map { if (it.publicKey == prefixedKey) updatedProfile else it }
                    .sortedBy { it.name.lowercase() }
            }
            markContactsLoaded()
            Logger.info("Updated contact '${redacted(prefixedKey)}'", context = TAG)
        }
    }

    suspend fun removeContact(publicKey: String): Result<Unit> = runSuspendCatching {
        withContext(ioDispatcher) {
            requireExternalIdentitySource()
            val prefixedKey = publicKey.ensurePubkyPrefix()
            pubkyService.removeContact(prefixedKey)
            removeContactProfileOverride(prefixedKey)
            _contacts.update { current -> current.filter { it.publicKey != prefixedKey } }
            markContactsLoaded()
            Logger.info("Removed contact '${redacted(prefixedKey)}'", context = TAG)
        }
    }

    suspend fun importContacts(publicKeys: List<String>): Result<Unit> = runSuspendCatching {
        withContext(ioDispatcher) {
            requireExternalIdentitySource()
            val imported = coroutineScope {
                publicKeys.map { contactPk ->
                    val prefixedKey = contactPk.ensurePubkyPrefix()
                    async {
                        runSuspendCatching {
                            val profile = resolveContactProfile(prefixedKey).getOrThrow()
                                ?: PubkyProfile.placeholder(prefixedKey)
                            pubkyService.saveContact(prefixedKey, profile.name, relevantReceiverPaths(prefixedKey))
                            profile
                        }.onFailure {
                            Logger.warn("Failed to import contact '${redacted(prefixedKey)}'", it, context = TAG)
                        }.getOrNull()
                    }
                }.awaitAll().filterNotNull()
            }
            _contacts.update { current ->
                val existing = current.map { it.publicKey }.toSet()
                (current + imported.filter { it.publicKey !in existing })
                    .sortedBy { it.name.lowercase() }
            }
            markContactsLoaded()
            Logger.info("Imported '${imported.size}' contacts", context = TAG)
        }
    }

    suspend fun prepareImport(): Result<Unit> = runSuspendCatching {
        clearPendingImport()
        val pk = requireNotNull(_publicKey.value) { "Not authenticated" }
        withContext(ioDispatcher) {
            val contactKeys = pubkyService.getContacts(pk)
            Logger.debug("Discovered '${contactKeys.size}' contacts for import", context = TAG)

            val contacts = coroutineScope {
                contactKeys.map { contactPk ->
                    val prefixedKey = contactPk.ensurePubkyPrefix()
                    async {
                        runSuspendCatching {
                            resolveContactProfile(prefixedKey).getOrThrow() ?: PubkyProfile.placeholder(prefixedKey)
                        }.getOrElse { PubkyProfile.placeholder(prefixedKey) }
                    }
                }.awaitAll().sortedBy { it.name.lowercase() }
            }

            val ownProfile = resolveContactProfile(pk).getOrNull()

            _pendingImportProfile.update { ownProfile }
            _pendingImportContacts.update { contacts }
        }
    }

    suspend fun clearPendingImport() = withContext(ioDispatcher) {
        _pendingImportProfile.update { null }
        _pendingImportContacts.update { emptyList() }
    }

    // endregion

    // region Shared Pubky identities

    suspend fun discoverRingIdentities(): Result<List<SharedPubkyIdentity>> =
        sharedPubkyDiscovery.discoverRingIdentities()

    suspend fun adoptRingIdentity(identity: SharedPubkyIdentity): Result<Unit> =
        identityLifecycleMutex.withLock {
            var didPersistExternalIdentity = false
            runSuspendCatching {
                withContext(ioDispatcher) {
                    ensureServiceInitialized()
                    val canonicalIdentity = identity.validatedRingIdentity()
                    val currentIdentityRef = pubkyStore.data.first().externalIdentityRef?.validated()
                    val currentPublicKey = _publicKey.value
                    val isAlreadyActive = currentIdentityRef?.pubky == canonicalIdentity.pubky &&
                        currentPublicKey?.let(::wirePubky) == canonicalIdentity.pubky
                    if (isAlreadyActive) {
                        return@withContext
                    }
                    if (
                        currentPublicKey != null ||
                        !keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name).isNullOrBlank()
                    ) {
                        throw SharedPubkyError.IdentityConflict
                    }

                    val credential = sharedPubkyDiscovery.readRingCredential(canonicalIdentity.pubky).getOrThrow()
                    if (!credential.identity.matches(canonicalIdentity)) throw SharedPubkyError.InvalidResponse

                    disableLocalIdentityExport()
                    val identityRef = canonicalIdentity.toExternalRef()
                    pubkyStore.update { it.copy(externalIdentityRef = identityRef) }
                    didPersistExternalIdentity = true
                    _authState.update { PubkyAuthState.Authenticating }

                    val publicKey = signInWithExternalCredential(credential)

                    settingsStore.update { it.copy(sharesPrivatePaykitEndpoints = false) }
                    notifyBackupStateChanged()
                    _publicKey.update { publicKey }
                    _authState.update { PubkyAuthState.Authenticated }
                    Logger.info("Connected Pubky Ring identity '${redacted(publicKey)}'", context = TAG)
                    loadProfile()
                    loadContacts()
                }
            }.onFailure {
                if (didPersistExternalIdentity) {
                    withContext(NonCancellable) {
                        runSuspendCatching { clearUnavailableExternalIdentityLocked() }
                            .onFailure {
                                Logger.error("Failed to roll back Pubky Ring identity connection", it, context = TAG)
                            }
                    }
                }
                restoreAuthStateAfterAuthFlow()
            }
        }

    suspend fun validateExternalIdentitySource(): Boolean = identityLifecycleMutex.withLock {
        validateExternalIdentitySourceLocked()
    }

    private suspend fun validateExternalIdentitySourceLocked(): Boolean = withContext(ioDispatcher) {
        val identityRef = runSuspendCatching {
            pubkyStore.data.first().externalIdentityRef?.validated()
        }.getOrElse {
            clearUnavailableExternalIdentityLocked()
            return@withContext false
        } ?: return@withContext true

        val available = sharedPubkyDiscovery.discoverRingIdentities()
            .getOrNull()
            ?.any { it.matches(identityRef) }
            ?: false
        if (available) return@withContext true

        clearUnavailableExternalIdentityLocked()
        Logger.warn("Disconnected missing Pubky Ring identity '${redacted(identityRef.pubky)}'", context = TAG)
        false
    }

    // endregion

    // region Auth approval

    suspend fun hasSecretKey(): Boolean = runSuspendCatching {
        val publicKey = _publicKey.value ?: return@runSuspendCatching false
        activeIdentitySecretKey(publicKey) != null
    }.getOrDefault(false)

    suspend fun parseAuthUrl(authUrl: String): Result<PubkyAuthRequest> = runSuspendCatching {
        withContext(ioDispatcher) {
            val details = pubkyService.parseAuthUrl(authUrl)
            PubkyAuthRequest.parse(
                rawUrl = authUrl,
                relay = details.relayUrl.orEmpty(),
                capabilities = details.capabilities.orEmpty(),
            ).getOrThrow()
        }
    }

    suspend fun approveAuth(authUrl: String, expectedCapabilities: String): Result<Unit> = runSuspendCatching {
        withContext(ioDispatcher) {
            val publicKey = requireNotNull(_publicKey.value) { "No active Pubky identity" }
            val secretKeyHex = requireNotNull(activeIdentitySecretKey(publicKey)) {
                "No active Pubky secret key is available"
            }
            pubkyService.approveAuth(authUrl, expectedCapabilities, secretKeyHex)
        }
    }

    suspend fun approveAuthWithCompanionClaim(
        authUrl: String,
        unsignedPayload: ByteArray,
    ): Result<Unit> = runSuspendCatching {
        withContext(ioDispatcher) {
            val publicKey = requireNotNull(_publicKey.value) { "No active Pubky identity" }
            val secretKeyHex = requireNotNull(activeIdentitySecretKey(publicKey)) {
                "No active Pubky secret key is available"
            }
            pubkyService.approveAuthWithCompanionClaim(
                authUrl = authUrl,
                expectedCapabilities = PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES,
                secretKeyHex = secretKeyHex,
                claim = PubkyAuthCompanionClaim(
                    queryParameter = PubkyAuthClaim.QUERY_PARAMETER,
                    claimType = PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1.wireValue,
                    unsignedPayload = unsignedPayload,
                ),
            )
        }
    }

    // endregion

    // region Backup state

    suspend fun snapshotSessionBackupState(): Result<PubkySessionBackupV1?> = runSuspendCatching {
        withContext(ioDispatcher) {
            if (pubkyStore.data.first().externalIdentityRef != null) return@withContext null
            if (
                keychain.loadString(Keychain.Key.PUBKY_MANAGED_SECRET_QUARANTINED.name) ==
                MANAGED_SECRET_QUARANTINED
            ) {
                return@withContext null
            }

            val secretKeyHex = keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)
            if (!secretKeyHex.isNullOrEmpty()) {
                return@withContext PubkySessionBackupV1(kind = PubkySessionBackupKind.LocalSeed)
            }

            val sessionSecret = keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)
            if (!sessionSecret.isNullOrEmpty()) {
                return@withContext PubkySessionBackupV1(
                    kind = PubkySessionBackupKind.ExternalSession,
                    sessionSecret = sessionSecret,
                )
            }

            null
        }
    }

    suspend fun snapshotContactProfileOverrides(): Result<Map<String, PubkyProfileData>?> = runSuspendCatching {
        withContext(ioDispatcher) {
            pubkyStore.data.first().contactProfileOverrides.takeUnless { it.isEmpty() }
        }
    }

    suspend fun restoreSessionBackupState(backup: PubkySessionBackupV1?): Result<Unit> = runSuspendCatching {
        withContext(ioDispatcher) {
            ensureServiceInitialized()

            identityLifecycleMutex.withLock {
                disableLocalIdentityExport()
                pubkyService.clearSessionAccess()
                clearAuthenticatedState()
                runSuspendCatching { keychain.delete(Keychain.Key.PAYKIT_SESSION.name) }
                runSuspendCatching { keychain.delete(Keychain.Key.PUBKY_SECRET_KEY.name) }

                when (backup?.kind) {
                    null -> Unit

                    PubkySessionBackupKind.LocalSeed -> {
                        val secretKeyHex = deriveLocalSecretKeyFromWalletSeed()
                        keychain.upsertString(Keychain.Key.PUBKY_SECRET_KEY.name, secretKeyHex)
                        pubkyService.signIn(secretKeyHex)
                        val publicKey = pubkyService.publicKeyFromSecret(secretKeyHex).ensurePubkyPrefix()
                        enableLocalIdentityExport(publicKey)
                        _publicKey.update { publicKey }
                        _authState.update { PubkyAuthState.Authenticated }
                    }

                    PubkySessionBackupKind.ExternalSession -> {
                        val sessionSecret = requireNotNull(backup.sessionSecret?.takeIf { it.isNotBlank() }) {
                            "Missing session secret in backup"
                        }
                        val publicKey = pubkyService.importExternalSession(sessionSecret).ensurePubkyPrefix()
                        disableLocalIdentityExport()
                        _publicKey.update { publicKey }
                        _authState.update { PubkyAuthState.Authenticated }
                    }
                }

                notifyBackupStateChanged()
            }
        }
    }

    suspend fun restoreContactProfileOverrides(
        overrides: Map<String, PubkyProfileData>?,
    ): Result<Unit> = runSuspendCatching {
        withContext(ioDispatcher) {
            pubkyStore.update {
                it.copy(contactProfileOverrides = overrides ?: emptyMap())
            }
            notifyBackupStateChanged()
        }
    }

    suspend fun refreshSessionIfPossible(): Result<Boolean> = identityLifecycleMutex.withLock {
        runSuspendCatching {
            withContext(ioDispatcher) {
                val identityRef = pubkyStore.data.first().externalIdentityRef?.validated()
                if (identityRef != null) {
                    if (!validateExternalIdentitySourceLocked()) return@withContext false
                    val credential = sharedPubkyDiscovery.readRingCredential(identityRef.pubky).getOrElse {
                        clearUnavailableExternalIdentityLocked()
                        return@withContext false
                    }
                    val publicKey = signInWithExternalCredential(credential)
                    if (wirePubky(publicKey) != identityRef.pubky) {
                        clearUnavailableExternalIdentityLocked()
                        return@withContext false
                    }
                    notifyBackupStateChanged()
                    _publicKey.update { publicKey }
                    _authState.update { PubkyAuthState.Authenticated }
                    return@withContext true
                }

                val storedSecretKeyHex = keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)
                    ?: return@withContext false
                if (
                    keychain.loadString(Keychain.Key.PUBKY_MANAGED_SECRET_QUARANTINED.name) ==
                    MANAGED_SECRET_QUARANTINED
                ) {
                    return@withContext false
                }

                pubkyService.signIn(storedSecretKeyHex)
                val publicKey = pubkyService.publicKeyFromSecret(storedSecretKeyHex).ensurePubkyPrefix()
                enableLocalIdentityExport(publicKey)

                notifyBackupStateChanged()
                _publicKey.update { publicKey }
                _authState.update { PubkyAuthState.Authenticated }

                true
            }
        }
    }

    // endregion

    // region Sign out

    suspend fun signOut(): Result<Unit> = identityLifecycleMutex.withLock {
        runSuspendCatching { disableLocalIdentityExport() }
            .onFailure { Logger.error("Failed to disable shared Pubky export", it, context = TAG) }
            .exceptionOrNull()
            ?.let { return@withLock Result.failure(it) }
        val hadPaykitState = settingsStore.data.first().hasPaykitState()
        val endpointCleanupResult = removeBitkitPaymentEndpoints()
            .onFailure { Logger.warn("Failed to remove Bitkit payment endpoints", it, context = TAG) }

        val result = runSuspendCatching {
            withContext(ioDispatcher) { pubkyService.signOut() }
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = {
                Logger.warn("Forcing local sign out after server sign out failed", it, context = TAG)
                runSuspendCatching { withContext(ioDispatcher) { pubkyService.forceSignOut() } }
            },
        )

        clearLocalState(publicPaykitCleanupPending = endpointCleanupResult.isFailure && hadPaykitState)
        result
    }

    suspend fun wipeLocalState() = identityLifecycleMutex.withLock {
        clearLocalState()
    }

    // endregion

    // region Private helpers

    private fun evictPubkyImages() {
        imageLoader.memoryCache?.let { cache ->
            cache.keys.filter { it.key.startsWith(PUBKY_SCHEME) }.forEach { cache.remove(it) }
        }
        val imageUris = buildList {
            _profile.value?.imageUrl?.let { add(it) }
            addAll(_contacts.value.mapNotNull { it.imageUrl })
        }
        imageLoader.diskCache?.let { cache ->
            imageUris.forEach { cache.remove(it) }
        }
    }

    private suspend fun contactProfile(
        publicKey: String,
        label: String?,
        paykitProfile: PaykitProfile?,
        overrides: Map<String, PubkyProfileData>,
    ): PubkyProfile {
        val prefixedKey = publicKey.ensurePubkyPrefix()
        overrides[prefixedKey]?.let {
            return it.toPubkyProfile(prefixedKey)
        }
        paykitProfile?.let {
            return PubkyProfile.fromPaykitProfile(prefixedKey, it).withNameFallback(label)
        }
        resolveContactProfile(prefixedKey).getOrNull()?.let {
            return it.withNameFallback(label)
        }
        return PubkyProfile.forDisplay(
            publicKey = prefixedKey,
            name = label,
            imageUrl = null,
        )
    }

    private suspend fun resolveContactProfile(publicKey: String): Result<PubkyProfile?> = runSuspendCatching {
        withContext(ioDispatcher) {
            val prefixedKey = publicKey.ensurePubkyPrefix()
            var lastError: Throwable? = null

            repeat(2) { attempt ->
                val result = runSuspendCatching {
                    pubkyService.resolveContactProfile(
                        publicKey = prefixedKey,
                        allowPubkyProfileFallback = true,
                    )?.let(::profileFromResolution)
                }
                if (result.isSuccess && (result.getOrNull() != null || attempt == 1)) {
                    return@withContext result.getOrNull()
                }
                result.exceptionOrNull()?.let { error ->
                    lastError = error
                }

                if (attempt == 0) {
                    Logger.warn(
                        "Retrying contact profile resolution for '${redacted(prefixedKey)}'",
                        lastError,
                        context = TAG,
                    )
                    delay(250)
                }
            }

            lastError?.let { throw it }
            null
        }
    }

    private fun profileFromResolution(resolution: ContactProfileResolution): PubkyProfile {
        val prefixedKey = resolution.publicKey.ensurePubkyPrefix()
        resolution.paykitProfile?.let {
            return PubkyProfile.fromPaykitProfile(prefixedKey, it)
        }
        resolution.pubkyProfile?.let {
            return PubkyProfile.fromPubkyProfile(prefixedKey, it)
        }
        return PubkyProfile.forDisplay(
            publicKey = prefixedKey,
            name = resolution.displayName,
            imageUrl = resolution.imageUri,
        )
    }

    private suspend fun relevantReceiverPaths(publicKey: String): List<String> =
        runSuspendCatching {
            pubkyService.discoverRelevantReceiverPaths(publicKey)
        }.onFailure {
            Logger.warn("Failed to discover Paykit receivers for '${redacted(publicKey)}'", it, context = TAG)
        }.getOrNull()
            ?: listOf(PaykitReceiverPaths.WALLET)

    private suspend fun upsertContactProfileOverride(profile: PubkyProfile) {
        val prefixedKey = profile.publicKey.ensurePubkyPrefix()
        pubkyStore.update { data ->
            data.copy(contactProfileOverrides = data.contactProfileOverrides + (prefixedKey to profile.toProfileData()))
        }
        notifyBackupStateChanged()
    }

    private suspend fun removeContactProfileOverride(publicKey: String) {
        val prefixedKey = publicKey.ensurePubkyPrefix()
        pubkyStore.update { data ->
            data.copy(contactProfileOverrides = data.contactProfileOverrides - prefixedKey)
        }
        notifyBackupStateChanged()
    }

    private suspend fun cacheMetadata(profile: PubkyProfile) {
        pubkyStore.update {
            it.copy(cachedName = profile.name, cachedImageUri = profile.imageUrl)
        }
    }

    private suspend fun signInWithExternalCredential(credential: SharedPubkyCredential): String =
        withContext(ioDispatcher) {
            val identity = credential.identity.validatedRingIdentity()
            val derivedWirePubky = wirePubky(pubkyService.publicKeyFromSecret(credential.secretKeyHex))
            if (derivedWirePubky != identity.pubky) throw SharedPubkyError.InvalidResponse

            val signedInPubky = canonicalBitkitPubky(pubkyService.signInExternal(credential.secretKeyHex))
            if (wirePubky(signedInPubky) != identity.pubky) throw SharedPubkyError.InvalidResponse
            if (!keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name).isNullOrBlank()) {
                throw SharedPubkyError.InvalidResponse
            }
            signedInPubky
        }

    private suspend fun enableLocalIdentityExport(publicKey: String) = withContext(ioDispatcher) {
        val secretKeyHex = requireNotNull(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)) {
            "Local Pubky secret is unavailable"
        }
        val derivedPublicKey = canonicalBitkitPubky(pubkyService.publicKeyFromSecret(secretKeyHex))
        if (!PubkyPublicKeyFormat.matches(derivedPublicKey, publicKey)) {
            throw SharedPubkyError.InvalidResponse
        }
        keychain.delete(Keychain.Key.PUBKY_MANAGED_SECRET_QUARANTINED.name)
        check(keychain.loadString(Keychain.Key.PUBKY_MANAGED_SECRET_QUARANTINED.name) == null) {
            "Failed to release managed local Pubky secret quarantine"
        }
        keychain.upsertString(Keychain.Key.PUBKY_SHARED_EXPORT_ENABLED.name, SHARED_EXPORT_ENABLED)
        check(keychain.loadString(Keychain.Key.PUBKY_SHARED_EXPORT_ENABLED.name) == SHARED_EXPORT_ENABLED) {
            "Failed to verify shared Pubky export state"
        }
    }

    private suspend fun disableLocalIdentityExport() = withContext(ioDispatcher) {
        keychain.delete(Keychain.Key.PUBKY_SHARED_EXPORT_ENABLED.name)
        check(keychain.loadString(Keychain.Key.PUBKY_SHARED_EXPORT_ENABLED.name) == null) {
            "Failed to disable shared Pubky export"
        }
    }

    private suspend fun activeIdentitySecretKey(publicKey: String): String? = identityLifecycleMutex.withLock {
        activeIdentitySecretKeyLocked(publicKey)
    }

    private suspend fun activeIdentitySecretKeyLocked(publicKey: String): String? = withContext(ioDispatcher) {
        val identityRef = pubkyStore.data.first().externalIdentityRef?.validated()
            ?: return@withContext managedSecretKeyFor(publicKey)
        if (wirePubky(publicKey) != identityRef.pubky || !validateExternalIdentitySourceLocked()) {
            return@withContext null
        }

        val credential = sharedPubkyDiscovery.readRingCredential(identityRef.pubky).getOrElse {
            clearUnavailableExternalIdentityLocked()
            return@withContext null
        }
        val isValid = runSuspendCatching {
            credential.matches(identityRef) &&
                wirePubky(pubkyService.publicKeyFromSecret(credential.secretKeyHex)) == identityRef.pubky
        }.getOrDefault(false)
        if (!isValid) {
            clearUnavailableExternalIdentityLocked()
            return@withContext null
        }
        credential.secretKeyHex
    }

    private suspend fun requireExternalIdentitySource() {
        if (!validateExternalIdentitySource()) throw SharedPubkyError.SourceUnavailable
    }

    private suspend fun clearUnavailableExternalIdentityLocked() = withContext(ioDispatcher) {
        val externalIdentityRef = pubkyStore.data.first().externalIdentityRef ?: return@withContext
        disableLocalIdentityExport()

        val managedSecretKeyHex = keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)
        if (!managedSecretKeyHex.isNullOrBlank()) {
            keychain.upsertString(
                Keychain.Key.PUBKY_MANAGED_SECRET_QUARANTINED.name,
                MANAGED_SECRET_QUARANTINED,
            )
            check(
                keychain.loadString(Keychain.Key.PUBKY_MANAGED_SECRET_QUARANTINED.name) ==
                    MANAGED_SECRET_QUARANTINED
            ) {
                "Failed to quarantine conflicting managed local Pubky secret"
            }
            Logger.error(
                "Quarantined managed local secret while clearing external identity " +
                    "'${redacted(externalIdentityRef.pubky)}'",
                context = TAG,
            )
        }

        pubkyService.clearExternalSessionAccess()
        clearPublicPaykitSharingState(publicPaykitCleanupPending = false)
        clearAuthenticatedRuntimeState()
        pubkyStore.reset()
        notifyBackupStateChanged()
    }

    private suspend fun managedSecretKeyFor(publicKey: String): String? = withContext(ioDispatcher) {
        if (
            keychain.loadString(Keychain.Key.PUBKY_MANAGED_SECRET_QUARANTINED.name) ==
            MANAGED_SECRET_QUARANTINED
        ) {
            return@withContext null
        }
        val secretKeyHex = keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)
            ?: return@withContext null

        val derivedPublicKey = runCatching {
            pubkyService.publicKeyFromSecret(secretKeyHex).ensurePubkyPrefix()
        }.onFailure {
            Logger.warn("Ignoring invalid managed secret key for '${redacted(publicKey)}'", it, context = TAG)
        }.getOrNull()

        if (derivedPublicKey == publicKey) {
            return@withContext secretKeyHex
        }

        if (derivedPublicKey != null) {
            Logger.warn("Ignoring stale managed secret key for '${redacted(publicKey)}'", context = TAG)
        }
        runSuspendCatching {
            disableLocalIdentityExport()
            keychain.delete(Keychain.Key.PUBKY_SECRET_KEY.name)
        }
            .onSuccess { notifyBackupStateChanged() }
        null
    }

    private suspend fun deriveLocalSecretKeyFromWalletSeed(): String = withContext(ioDispatcher) {
        val mnemonic = requireNotNull(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)) {
            "BIP39 mnemonic not found in keychain"
        }
        pubkyService.deriveSecretKey(mnemonic)
    }

    private fun notifyBackupStateChanged() {
        _backupStateVersion.update { it + 1 }
    }

    private suspend fun clearAuthenticatedState() = withContext(ioDispatcher) {
        runSuspendCatching { pubkyStore.reset() }
        clearAuthenticatedRuntimeState()
    }

    private suspend fun clearAuthenticatedRuntimeState() = withContext(ioDispatcher) {
        evictPubkyImages()
        _publicKey.update { null }
        _profile.update { null }
        _contacts.update { emptyList() }
        _contactsLoadVersion.update { 0L }
        clearPendingImport()
        _sessionRestorationFailed.update { false }
        _authState.update { PubkyAuthState.Idle }
    }

    private fun markContactsLoaded() {
        _contactsLoadVersion.update { it + 1 }
    }

    private suspend fun clearLocalState(publicPaykitCleanupPending: Boolean = false) = withContext(ioDispatcher) {
        disableLocalIdentityExport()
        runSuspendCatching { keychain.delete(Keychain.Key.PAYKIT_SESSION.name) }
        runSuspendCatching { keychain.delete(Keychain.Key.PUBKY_SECRET_KEY.name) }
        runSuspendCatching { keychain.delete(Keychain.Key.PUBKY_MANAGED_SECRET_QUARANTINED.name) }
        runSuspendCatching { clearPublicPaykitSharingState(publicPaykitCleanupPending) }
            .onFailure { Logger.warn("Failed to clear public Paykit sharing state", it, context = TAG) }
        notifyBackupStateChanged()
        clearAuthenticatedState()
    }

    private suspend fun clearPublicPaykitSharingState(publicPaykitCleanupPending: Boolean) {
        settingsStore.update {
            it.copy(
                hasConfirmedPublicPaykitEndpoints = false,
                sharesPublicPaykitEndpoints = false,
                sharesPrivatePaykitEndpoints = false,
                publicPaykitBolt11 = "",
                publicPaykitBolt11PaymentHash = "",
                publicPaykitBolt11ExpiresAtMillis = 0,
                publicPaykitCleanupPending = publicPaykitCleanupPending,
            )
        }
    }

    private fun requireAddableContactPublicKey(publicKey: String, allowExisting: Boolean = false): String {
        val prefixedKey = PubkyPublicKeyFormat.normalized(publicKey)
        contactValidationError(prefixedKey, allowExisting)?.let { throw it }
        return checkNotNull(prefixedKey) { "Normalized pubky key is required" }
    }

    private fun contactValidationError(prefixedKey: String?, allowExisting: Boolean = false): PubkyContactError? {
        if (prefixedKey == null) return PubkyContactError.InvalidFormat
        if (_publicKey.value == prefixedKey) return PubkyContactError.CannotAddSelf
        if (!allowExisting && _contacts.value.any { PubkyPublicKeyFormat.matches(it.publicKey, prefixedKey) }) {
            return PubkyContactError.AlreadyExists
        }
        return null
    }

    private fun String.ensurePubkyPrefix(): String =
        if (startsWith(PUBKY_PREFIX)) this else "$PUBKY_PREFIX$this"

    private fun canonicalBitkitPubky(value: String): String =
        SharedPubkyContract.toBitkitPubky(value)

    private fun wirePubky(value: String): String =
        SharedPubkyContract.canonicalPubky(value)

    private fun SharedPubkyIdentity.validatedRingIdentity(): SharedPubkyIdentity {
        if (protocolVersion != SharedPubkyContract.PROTOCOL_VERSION) {
            throw SharedPubkyError.UnsupportedVersion(protocolVersion)
        }
        if (sourcePackage != SharedPubkyContract.RING_SOURCE) {
            throw SharedPubkyError.UntrustedSource(sourcePackage)
        }
        return copy(pubky = SharedPubkyContract.requireWirePubky(pubky))
    }

    private fun SharedPubkyIdentity.toExternalRef() = ExternalPubkyIdentityRef(
        protocolVersion = protocolVersion,
        sourcePackage = sourcePackage,
        pubky = SharedPubkyContract.requireWirePubky(pubky),
    )

    private fun SharedPubkyIdentity.matches(identityRef: ExternalPubkyIdentityRef): Boolean =
        protocolVersion == identityRef.protocolVersion &&
            sourcePackage == identityRef.sourcePackage &&
            SharedPubkyContract.requireWirePubky(pubky) ==
            SharedPubkyContract.requireWirePubky(identityRef.pubky)

    private fun SharedPubkyIdentity.matches(other: SharedPubkyIdentity): Boolean =
        protocolVersion == other.protocolVersion &&
            sourcePackage == other.sourcePackage &&
            SharedPubkyContract.requireWirePubky(pubky) ==
            SharedPubkyContract.requireWirePubky(other.pubky)

    private fun SharedPubkyCredential.matches(identityRef: ExternalPubkyIdentityRef): Boolean =
        identity.matches(identityRef)

    private fun redacted(publicKey: String): String = PubkyPublicKeyFormat.redacted(publicKey)

    private fun Throwable.isMissingPubkyData(): Boolean {
        val fullMessage = buildErrorMessage()
        return fullMessage.contains("404") ||
            fullMessage.contains("not found", ignoreCase = true) ||
            fullMessage.contains("missing", ignoreCase = true)
    }

    private fun Throwable.buildErrorMessage(): String =
        buildString {
            append(message.orEmpty())
            cause?.message?.takeIf { it.isNotBlank() }?.let {
                append(" ")
                append(it)
            }
        }

    // endregion
}

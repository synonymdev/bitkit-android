package to.bitkit.repositories

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.ImageLoader
import com.synonym.paykit.ContactProfileResolution
import com.synonym.paykit.PaykitProfile
import com.synonym.paykit.PubkyAuthDetails
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
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
import to.bitkit.data.PubkyStore
import to.bitkit.data.SettingsStore
import to.bitkit.data.hasPublicPaykitPublicationState
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.HomegateResponse
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyProfileData
import to.bitkit.models.PubkyProfileLink
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.models.PubkyRingAuthCallback
import to.bitkit.models.PubkyRingAuthCallbackHandlingResult
import to.bitkit.models.PubkySessionBackupKind
import to.bitkit.models.PubkySessionBackupV1
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
) {
    companion object {
        private const val TAG = "PubkyRepo"
        private const val PUBKY_PREFIX = "pubky"
        private const val PUBKY_SCHEME = "pubky://"
        private const val AVATAR_MAX_SIZE = 400
        private const val AVATAR_QUALITY = 80
    }

    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())
    private val serviceInitializeMutex = Mutex()
    private val initializeMutex = Mutex()
    private val loadProfileMutex = Mutex()
    private val loadContactsMutex = Mutex()
    private var isServiceInitialized = false

    private val _authState = MutableStateFlow(PubkyAuthState.Idle)
    private val _activeAuthAttemptId = MutableStateFlow<String?>(null)
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

        initializeMutex.withLock {
            _sessionRestorationFailed.update { false }
            val result = runSuspendCatching {
                val savedSessionSecret = runCatching {
                    keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)
                }.getOrNull()
                val storedSecretKeyHex = runCatching {
                    keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)
                }.getOrNull()

                resolveSessionInitialization(
                    savedSessionSecret = savedSessionSecret,
                    storedSecretKeyHex = storedSecretKeyHex,
                )
            }.onFailure {
                Logger.error("Failed to initialize paykit", it, context = TAG)
            }.getOrNull() ?: return@withLock

            when (result) {
                is InitResult.NoSession -> {
                    clearAuthenticatedState()
                    Logger.debug("Found no saved paykit session", context = TAG)
                }
                is InitResult.Restored -> {
                    _publicKey.update { result.publicKey }
                    _authState.update { PubkyAuthState.Authenticated }
                    Logger.info("Restored paykit session for '${redacted(result.publicKey)}'", context = TAG)
                    loadProfile()
                    loadContacts()
                }
                is InitResult.RestorationFailed -> {
                    clearAuthenticatedState()
                    _sessionRestorationFailed.update { true }
                }
            }
        }
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
    ): InitResult = withContext(ioDispatcher) {
        if (!savedSessionSecret.isNullOrEmpty()) {
            runSuspendCatching {
                val publicKey = pubkyService.importSession(savedSessionSecret).ensurePubkyPrefix()
                InitResult.Restored(publicKey)
            }.getOrElse {
                Logger.warn("Failed to restore paykit session, attempting re-sign-in", it, context = TAG)
                resolveSignedInSession(savedSessionSecret, storedSecretKeyHex)
            }
        } else {
            resolveSignedInSession(savedSessionSecret, storedSecretKeyHex)
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

    suspend fun completeAuthentication(): Result<Unit> {
        val attemptId = _activeAuthAttemptId.value ?: return Result.failure(PubkyAuthAttemptInactive())
        var didCompleteAuth = false
        return try {
            val result = runSuspendCatching {
                withContext(ioDispatcher) {
                    pubkyService.completeAuth()
                    didCompleteAuth = true
                    ensureAuthAttemptActive(attemptId)
                    val pk = requireNotNull(pubkyService.currentPublicKey()?.ensurePubkyPrefix()) {
                        "No active Pubky session"
                    }
                    ensureAuthAttemptActive(attemptId)

                    settingsStore.update { it.copy(sharesPrivatePaykitEndpoints = false) }
                    notifyBackupStateChanged()

                    pk
                }
            }

            if (result.isFailure) {
                clearCompletedAuthSessionIfNeeded(didCompleteAuth)
                if (_activeAuthAttemptId.value == attemptId) {
                    _activeAuthAttemptId.update { null }
                }
                restoreAuthStateAfterAuthFlow()
            }

            result.onSuccess { pk ->
                if (_activeAuthAttemptId.value == attemptId) {
                    _activeAuthAttemptId.update { null }
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
        return callback.nonce == activeAuthAttemptId
    }

    private fun ensureAuthAttemptActive(attemptId: String?) {
        if (attemptId == null) return
        if (_activeAuthAttemptId.value == attemptId) return

        throw PubkyAuthAttemptInactive()
    }

    private fun endAuthAttempt() {
        _activeAuthAttemptId.update { null }
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
    ): Result<Unit> = runSuspendCatching {
        withContext(ioDispatcher) {
            val (publicKeyZ32, secretKeyHex) = deriveKeys().getOrThrow()

            val homegate = fetchHomegateSignupCode()

            runSuspendCatching {
                pubkyService.signUp(secretKeyHex, homegate.homeserverPubky, homegate.signupCode)
            }.getOrElse {
                Logger.warn("Retrying sign in after sign up failed", it, context = TAG)
                pubkyService.signIn(secretKeyHex)
            }

            val imageUrl = avatarBytes?.let { uploadAvatar(it).getOrNull() }
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

    suspend fun uploadAvatar(imageBytes: ByteArray): Result<String> = runSuspendCatching {
        withContext(ioDispatcher) {
            requireNotNull(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)) {
                "No session available"
            }
            val compressed = compressAvatar(imageBytes)
            pubkyService.uploadProfileAvatar(compressed, contentType = "image/jpeg")
        }
    }

    suspend fun saveProfile(
        name: String,
        bio: String,
        links: List<PubkyProfileLink>,
        tags: List<String>,
        imageUrl: String?,
    ): Result<Unit> = runSuspendCatching {
        withContext(ioDispatcher) {
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
            val prefixedKey = requireAddableContactPublicKey(
                publicKey = publicKey,
                allowExisting = existingProfile != null,
            )
            val profile = existingProfile?.copy(publicKey = prefixedKey)
                ?: resolveContactProfile(prefixedKey).getOrThrow()
                ?: PubkyProfile.placeholder(prefixedKey)
            pubkyService.saveContact(prefixedKey, profile.name)
            _contacts.update { current ->
                (current.filter { it.publicKey != prefixedKey } + profile)
                    .sortedBy { it.name.lowercase() }
            }
            markContactsLoaded()
            Logger.info("Added contact '${redacted(prefixedKey)}'", context = TAG)
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
            val imported = coroutineScope {
                publicKeys.map { contactPk ->
                    val prefixedKey = contactPk.ensurePubkyPrefix()
                    async {
                        runSuspendCatching {
                            val profile = resolveContactProfile(prefixedKey).getOrThrow()
                                ?: PubkyProfile.placeholder(prefixedKey)
                            pubkyService.saveContact(prefixedKey, profile.name)
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

    // region Auth approval

    suspend fun hasSecretKey(): Boolean = runSuspendCatching {
        val publicKey = _publicKey.value ?: return@runSuspendCatching false
        managedSecretKeyFor(publicKey) != null
    }.getOrDefault(false)

    suspend fun parseAuthUrl(authUrl: String): Result<PubkyAuthDetails> = runSuspendCatching {
        withContext(ioDispatcher) {
            pubkyService.parseAuthUrl(authUrl)
        }
    }

    suspend fun approveAuth(authUrl: String, expectedCapabilities: String): Result<Unit> = runSuspendCatching {
        withContext(ioDispatcher) {
            val secretKeyHex = requireNotNull(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)) {
                "No secret key available — use Ring to manage authorizations"
            }
            pubkyService.approveAuth(authUrl, expectedCapabilities, secretKeyHex)
        }
    }

    // endregion

    // region Backup state

    suspend fun snapshotSessionBackupState(): Result<PubkySessionBackupV1?> = runSuspendCatching {
        withContext(ioDispatcher) {
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

            initializeMutex.withLock {
                pubkyService.clearSessionAccess()
                clearAuthenticatedState()
                runCatching { keychain.delete(Keychain.Key.PAYKIT_SESSION.name) }
                runCatching { keychain.delete(Keychain.Key.PUBKY_SECRET_KEY.name) }

                when (backup?.kind) {
                    null -> Unit

                    PubkySessionBackupKind.LocalSeed -> {
                        val secretKeyHex = deriveLocalSecretKeyFromWalletSeed()
                        keychain.upsertString(Keychain.Key.PUBKY_SECRET_KEY.name, secretKeyHex)
                        pubkyService.signIn(secretKeyHex)
                        val publicKey = pubkyService.publicKeyFromSecret(secretKeyHex).ensurePubkyPrefix()
                        _publicKey.update { publicKey }
                        _authState.update { PubkyAuthState.Authenticated }
                    }

                    PubkySessionBackupKind.ExternalSession -> {
                        val sessionSecret = requireNotNull(backup.sessionSecret?.takeIf { it.isNotBlank() }) {
                            "Missing session secret in backup"
                        }
                        val publicKey = pubkyService.importExternalSession(sessionSecret).ensurePubkyPrefix()
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

    suspend fun refreshSessionIfPossible(): Result<Boolean> = runSuspendCatching {
        withContext(ioDispatcher) {
            val storedSecretKeyHex = keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)
                ?: return@withContext false

            pubkyService.signIn(storedSecretKeyHex)
            val publicKey = pubkyService.publicKeyFromSecret(storedSecretKeyHex).ensurePubkyPrefix()

            notifyBackupStateChanged()
            _publicKey.update { publicKey }
            _authState.update { PubkyAuthState.Authenticated }

            true
        }
    }

    // endregion

    // region Sign out

    suspend fun signOut(): Result<Unit> {
        val hadPublicPaykitState = settingsStore.data.first().hasPublicPaykitPublicationState()
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

        clearLocalState(publicPaykitCleanupPending = endpointCleanupResult.isFailure && hadPublicPaykitState)
        return result
    }

    suspend fun wipeLocalState() {
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

    private suspend fun managedSecretKeyFor(publicKey: String): String? = withContext(ioDispatcher) {
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
        runCatching { keychain.delete(Keychain.Key.PUBKY_SECRET_KEY.name) }
            .onSuccess { notifyBackupStateChanged() }
        null
    }

    private suspend fun deriveLocalSecretKeyFromWalletSeed(): String = withContext(ioDispatcher) {
        val mnemonic = requireNotNull(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)) {
            "BIP39 mnemonic not found in keychain"
        }
        val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)
        val seed = pubkyService.mnemonicToSeed(mnemonic, passphrase)
        pubkyService.deriveSecretKey(seed)
    }

    private fun notifyBackupStateChanged() {
        _backupStateVersion.update { it + 1 }
    }

    private suspend fun clearAuthenticatedState() = withContext(ioDispatcher) {
        evictPubkyImages()
        runSuspendCatching { pubkyStore.reset() }
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
        runCatching { keychain.delete(Keychain.Key.PAYKIT_SESSION.name) }
        runCatching { keychain.delete(Keychain.Key.PUBKY_SECRET_KEY.name) }
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

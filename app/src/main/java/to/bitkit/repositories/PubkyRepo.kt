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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
import to.bitkit.data.PubkyStoreData
import to.bitkit.data.SettingsStore
import to.bitkit.data.hasPaykitState
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.paykitDisabled
import to.bitkit.data.sharing.SharedPubkyContract
import to.bitkit.data.sharing.SharedPubkyCredential
import to.bitkit.data.sharing.SharedPubkyDiscovery
import to.bitkit.data.sharing.SharedPubkyError
import to.bitkit.data.sharing.SharedPubkyIdentity
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.isPaykitIdentityError
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.HomegateResponse
import to.bitkit.models.PubkyAuthClaim
import to.bitkit.models.PubkyAuthRequest
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyProfileData
import to.bitkit.models.PubkyProfileLink
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.models.PubkySessionBackupKind
import to.bitkit.models.PubkySessionBackupV1
import to.bitkit.services.PaykitReceiverPaths
import to.bitkit.services.PubkyService
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.min

sealed class PubkyContactError(message: String) : AppError(message) {
    data object AlreadyExists : PubkyContactError("Contact already exists")
    data object CannotAddSelf : PubkyContactError("Cannot add your own pubky as a contact")
    data object InvalidFormat : PubkyContactError("Invalid pubky key format")
}

data object PubkyAlreadySignedInError : AppError("Already signed in")

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
    private val privatePaykitRepo: Provider<PrivatePaykitRepo>,
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

    private val initializationReady = CompletableDeferred<Unit>()

    init {
        scope.launch { initialize() }.invokeOnCompletion {
            initializationReady.complete(Unit)
        }
    }

    // region Initialization

    suspend fun awaitInitialization() = withContext(ioDispatcher) {
        initializationReady.await()
    }

    suspend fun republishIdentityIfNeeded(): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching { pubkyService.republishIdentityIfNeeded(publicKey.value) }
    }

    suspend fun initialize() = withContext(ioDispatcher) {
        runSuspendCatching {
            ensureServiceInitialized()
        }.onFailure {
            Logger.error("Failed to initialize paykit", it, context = TAG)
            if (it.isPaykitIdentityError() && hasSavedSession()) _sessionRestorationFailed.update { true }
        }.getOrNull() ?: return@withContext

        identityLifecycleMutex.withLock {
            _sessionRestorationFailed.update { false }
            val result = runSuspendCatching {
                retryPendingPrivatePaykitStateCleanupLocked()
                resolveStoredSessionInitialization()
            }.onFailure {
                Logger.error("Failed to initialize paykit", it, context = TAG)
            }.getOrNull() ?: return@withLock

            applySessionInitialization(result)
            initializationReady.complete(Unit)

            if (result is InitResult.Restored) {
                loadProfile()
                loadContacts()
            }
        }
    }

    private suspend fun resolveStoredSessionInitialization(): InitResult {
        val savedSessionSecret = runCatching {
            keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)
        }.getOrNull()
        val isManagedSecretQuarantined = runCatching {
            keychain.loadString(Keychain.Key.PUBKY_MANAGED_SECRET_QUARANTINED.name)
        }.getOrElse {
            Logger.warn("Failed to read managed Pubky secret quarantine", it, context = TAG)
            return InitResult.RestorationFailed
        } == MANAGED_SECRET_QUARANTINED
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
        Logger.info("Restored paykit session for '${redacted(publicKey)}'", context = TAG)
    }

    private fun hasSavedSession(): Boolean = runCatching {
        keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)
    }.getOrNull()?.isNotBlank() == true

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
        externalIdentityRef: SharedPubkyIdentity?,
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
        identityRef: SharedPubkyIdentity,
    ): InitResult = withContext(ioDispatcher) {
        val sourceIdentity = sharedPubkyDiscovery.discoverRingIdentities().getOrElse {
            return@withContext externalSourceFailure(it)
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
            return@withContext externalSourceFailure(it)
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

    private fun externalSourceFailure(error: Throwable): InitResult {
        if (error.isDefinitiveExternalSourceFailure()) {
            return InitResult.ExternalSourceUnavailable
        }
        Logger.warn("Failed to restore Pubky Ring identity source", error, context = TAG)
        return InitResult.RestorationFailed
    }

    private fun Throwable.isDefinitiveExternalSourceFailure() =
        this is SharedPubkyError && this !is SharedPubkyError.ProviderQueryFailed

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

    private suspend fun discardAbandonedSession() {
        val revocationError = runSuspendCatching {
            withContext(NonCancellable + ioDispatcher) {
                pubkyService.signOut()
            }
        }.exceptionOrNull() ?: return

        Logger.warn("Failed to revoke abandoned Pubky session", revocationError, context = TAG)
        runSuspendCatching {
            withContext(NonCancellable + ioDispatcher) {
                pubkyService.forgetSessionAccess()
            }
        }.onFailure {
            Logger.warn("Failed to forget abandoned Pubky session access", it, context = TAG)
            withContext(NonCancellable + ioDispatcher) {
                clearLocalState(publicPaykitCleanupPending = true)
            }
        }
    }

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
            retryPendingPrivatePaykitStateCleanupLocked()
            if (pubkyStore.data.first().externalIdentityRef != null) {
                throw SharedPubkyError.IdentityConflict
            }
        }.exceptionOrNull()?.let { return@withLock Result.failure(it) }

        if (settingsStore.isPubkyProfileSetupPending.first() && _publicKey.value != null) {
            return@withLock runSuspendCatching {
                withContext(ioDispatcher) {
                    val publicKey = requireNotNull(_publicKey.value) { "No active Pubky session" }
                    val storedSecretKeyHex = loadUnquarantinedLocalSecretKey()
                    if (
                        storedSecretKeyHex.isNullOrBlank() ||
                        pubkyService.publicKeyFromSecret(storedSecretKeyHex).ensurePubkyPrefix() != publicKey
                    ) {
                        throw PubkyAlreadySignedInError
                    }
                    val imageUrl = publishIdentityProfile(name, bio, links, tags, avatarBytes)
                    finishIdentityCreation(publicKey, name, bio, links, tags, imageUrl)
                }
            }
        }

        var shouldRevokeSessionOnFailure = false
        try {
            val result = runSuspendCatching {
                withContext(ioDispatcher) {
                    settingsStore.setPubkyProfileSetupPending(false)
                    val storedSecretKeyHex = loadUnquarantinedLocalSecretKey()
                    val publicKeyZ32 = if (!storedSecretKeyHex.isNullOrEmpty()) {
                        pubkyService.signIn(storedSecretKeyHex)
                        pubkyService.publicKeyFromSecret(storedSecretKeyHex).ensurePubkyPrefix()
                    } else {
                        if (_publicKey.value != null) throw PubkyAlreadySignedInError
                        val (publicKey, secretKeyHex) = deriveKeys().getOrThrow()
                        val signupDetails: Pair<String, String?> = Env.e2eHomeserverPubky?.let { it to null }
                            ?: fetchHomegateSignupCode().let { it.homeserverPubky to it.signupCode }

                        shouldRevokeSessionOnFailure = true
                        runSuspendCatching {
                            pubkyService.signUp(secretKeyHex, signupDetails.first, signupDetails.second)
                        }.getOrElse {
                            Logger.warn("Retrying sign in after sign up failed", it, context = TAG)
                            pubkyService.signIn(secretKeyHex)
                        }
                        publicKey
                    }

                    val imageUrl = publishIdentityProfile(name, bio, links, tags, avatarBytes)
                    shouldRevokeSessionOnFailure = false
                    finishIdentityCreation(publicKeyZ32, name, bio, links, tags, imageUrl)
                }
            }
            if (result.isFailure) revokeIncompleteIdentitySessionIfNeeded(shouldRevokeSessionOnFailure)
            result
        } catch (error: CancellationException) {
            revokeIncompleteIdentitySessionIfNeeded(shouldRevokeSessionOnFailure)
            throw error
        }
    }

    private fun loadUnquarantinedLocalSecretKey(): String? =
        keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name).takeUnless {
            keychain.loadString(Keychain.Key.PUBKY_MANAGED_SECRET_QUARANTINED.name) == MANAGED_SECRET_QUARANTINED
        }

    private suspend fun publishIdentityProfile(
        name: String,
        bio: String,
        links: List<PubkyProfileLink>,
        tags: List<String>,
        avatarBytes: ByteArray?,
    ): String? {
        val imageUrl = avatarBytes?.let { runSuspendCatching { uploadAvatarInternal(it) }.getOrNull() }
        writeProfile(name, bio, links, tags, imageUrl)
        return imageUrl
    }

    private suspend fun finishIdentityCreation(
        publicKey: String,
        name: String,
        bio: String,
        links: List<PubkyProfileLink>,
        tags: List<String>,
        imageUrl: String?,
    ) {
        val createdProfile = PubkyProfile(
            publicKey = publicKey,
            name = name,
            bio = bio,
            imageUrl = imageUrl,
            links = links,
            tags = tags,
            status = null,
        )
        enableLocalIdentityExport(publicKey)
        _publicKey.update { publicKey }
        _profile.update { createdProfile }
        cacheMetadata(createdProfile)
        settingsStore.setPubkyProfileSetupPending(false)
        notifyBackupStateChanged()
        Logger.info("Created identity for '${redacted(publicKey)}'", context = TAG)
        loadProfile()
        loadContacts()
    }

    private suspend fun revokeIncompleteIdentitySessionIfNeeded(shouldRevokeSession: Boolean) {
        if (!shouldRevokeSession) return
        discardAbandonedSession()
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
        settingsStore.update { it.paykitDisabled(markPublicCleanupPending = it.hasPaykitState()) }
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
            var shouldRollBackAdoption = false
            try {
                runSuspendCatching {
                    withContext(ioDispatcher) {
                        ensureServiceInitialized()
                        retryPendingPrivatePaykitStateCleanupLocked()
                        val canonicalIdentity = identity.validated()
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
                        pubkyStore.update { it.copy(externalIdentityRef = canonicalIdentity) }
                        shouldRollBackAdoption = true

                        val publicKey = signInWithExternalCredential(credential)

                        settingsStore.update { it.copy(sharesPrivatePaykitEndpoints = false) }
                        notifyBackupStateChanged()
                        _publicKey.update { publicKey }
                        shouldRollBackAdoption = false
                        Logger.info("Connected Pubky Ring identity '${redacted(publicKey)}'", context = TAG)
                        loadProfile()
                        loadContacts()
                    }
                }.onFailure {
                    rollBackAdoptedRingIdentityIfNeeded(shouldRollBackAdoption)
                }
            } catch (e: CancellationException) {
                rollBackAdoptedRingIdentityIfNeeded(shouldRollBackAdoption)
                throw e
            }
        }

    private suspend fun rollBackAdoptedRingIdentityIfNeeded(shouldRollBack: Boolean) {
        if (!shouldRollBack) return
        withContext(NonCancellable) {
            runSuspendCatching { clearUnavailableExternalIdentityLocked() }
                .onFailure {
                    Logger.error("Failed to roll back Pubky Ring identity connection", it, context = TAG)
                }
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
            .getOrElse {
                if (it.isDefinitiveExternalSourceFailure()) {
                    clearUnavailableExternalIdentityLocked()
                    Logger.warn(
                        "Disconnected unavailable Pubky Ring identity '${redacted(identityRef.pubky)}'",
                        it,
                        context = TAG,
                    )
                    return@withContext false
                }
                Logger.warn("Failed to validate Pubky Ring identity source", it, context = TAG)
                return@withContext false
            }
            .any { it.matches(identityRef) }
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

    suspend fun hasIdentity(): Boolean = withContext(ioDispatcher) {
        _publicKey.value != null ||
            !keychain.loadString(Keychain.Key.PAYKIT_SESSION.name).isNullOrEmpty() ||
            !keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name).isNullOrEmpty()
    }

    suspend fun parseAuthUrl(authUrl: String): Result<PubkyAuthRequest> = runSuspendCatching {
        withContext(ioDispatcher) {
            if (PubkyAuthRequest.isSignupUrl(authUrl)) {
                val request = PubkyAuthRequest.parseSignup(authUrl).getOrThrow()
                pubkyService.validateSignupRequest(
                    authorizationUrl = request.authorizationUrl,
                    homeserverPublicKey = requireNotNull(request.homeserverPublicKey),
                )
                return@withContext request
            }

            val details = pubkyService.parseAuthUrl(authUrl)
            PubkyAuthRequest.parse(
                rawUrl = authUrl,
                clientId = details.clientId.orEmpty(),
                relay = details.relayUrl.orEmpty(),
                capabilities = details.capabilities.orEmpty(),
            ).getOrThrow()
        }
    }

    suspend fun approveSignupAuth(request: PubkyAuthRequest): Result<Unit> = identityLifecycleMutex.withLock {
        runSuspendCatching {
            withContext(ioDispatcher) {
                require(request.isSignup) { "Not a Pubky signup request" }
                retryPendingPrivatePaykitStateCleanupLocked()
                if (pubkyStore.data.first().externalIdentityRef != null) {
                    throw SharedPubkyError.IdentityConflict
                }
                if (hasIdentity()) throw PubkyAlreadySignedInError

                val (publicKey, secretKeyHex) = deriveKeys().getOrThrow()
                if (hasIdentity()) throw PubkyAlreadySignedInError

                settingsStore.update { it.copy(sharesPrivatePaykitEndpoints = false) }
                val registeredSession = pubkyService.registerIdentity(
                    secretKeyHex = secretKeyHex,
                    homeserverZ32 = requireNotNull(request.homeserverPublicKey),
                    signupCode = request.signupToken,
                )
                request.authorizationUrl?.let { pubkyService.approveRingAuth(it, secretKeyHex) }
                var activated = false
                try {
                    pubkyService.activateRegisteredIdentity(registeredSession)
                    activated = true
                } finally {
                    if (!activated) {
                        withContext(NonCancellable) {
                            settingsStore.setPubkyProfileSetupPending(false)
                        }
                    }
                }

                _publicKey.update { publicKey }
                var pendingSaved = false
                try {
                    settingsStore.setPubkyProfileSetupPending(true)
                    pendingSaved = true
                } finally {
                    if (!pendingSaved) {
                        withContext(NonCancellable) {
                            runSuspendCatching { pubkyService.forgetSessionAccess() }
                                .onFailure {
                                    Logger.warn("Failed to roll back Pubky signup session", it, context = TAG)
                                }
                            clearLocalState()
                        }
                    }
                }
                notifyBackupStateChanged()
            }
        }
    }

    suspend fun approveAuth(
        authUrl: String,
        expectedCapabilities: String,
        approvedClientId: String,
    ): Result<Unit> = runSuspendCatching {
        withContext(ioDispatcher) {
            val publicKey = requireNotNull(_publicKey.value) { "No active Pubky identity" }
            val secretKeyHex = requireNotNull(activeIdentitySecretKey(publicKey)) {
                "No active Pubky secret key is available"
            }
            pubkyService.approveAuth(authUrl, expectedCapabilities, approvedClientId, secretKeyHex)
        }
    }

    suspend fun approveAuthWithCompanionClaim(
        authUrl: String,
        approvedClientId: String,
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
                approvedClientId = approvedClientId,
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
                retryPendingPrivatePaykitStateCleanupLocked()
                disableLocalIdentityExport()
                runSuspendCatching { pubkyService.forgetSessionAccess() }
                    .onFailure {
                        Logger.warn(
                            "Failed to forget existing Pubky session before restore",
                            it,
                            context = TAG,
                        )
                    }
                clearAuthenticatedState()
                runSuspendCatching { keychain.delete(Keychain.Key.PAYKIT_SESSION.name) }
                val localSecretResult = runSuspendCatching {
                    keychain.delete(Keychain.Key.PUBKY_SECRET_KEY.name)
                }
                if (localSecretResult.isSuccess) {
                    keychain.delete(Keychain.Key.PUBKY_MANAGED_SECRET_QUARANTINED.name)
                }

                when (backup?.kind) {
                    null -> Unit

                    PubkySessionBackupKind.LocalSeed -> {
                        val secretKeyHex = deriveLocalSecretKeyFromWalletSeed()
                        keychain.upsertString(Keychain.Key.PUBKY_SECRET_KEY.name, secretKeyHex)
                        pubkyService.signIn(secretKeyHex)
                        val publicKey = pubkyService.publicKeyFromSecret(secretKeyHex).ensurePubkyPrefix()
                        enableLocalIdentityExport(publicKey)
                        _publicKey.update { publicKey }
                    }

                    PubkySessionBackupKind.ExternalSession -> {
                        val sessionSecret = requireNotNull(backup.sessionSecret?.takeIf { it.isNotBlank() }) {
                            "Missing session secret in backup"
                        }
                        val publicKey = pubkyService.importExternalSession(sessionSecret).ensurePubkyPrefix()
                        disableLocalIdentityExport()
                        _publicKey.update { publicKey }
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
                retryPendingPrivatePaykitStateCleanupLocked()
                val identityRef = pubkyStore.data.first().externalIdentityRef?.validated()
                if (identityRef != null) {
                    if (!validateExternalIdentitySourceLocked()) return@withContext false
                    val credential = sharedPubkyDiscovery.readRingCredential(identityRef.pubky).getOrElse {
                        if (it.isDefinitiveExternalSourceFailure()) clearUnavailableExternalIdentityLocked()
                        return@withContext false
                    }
                    val publicKey = signInWithExternalCredential(credential)
                    if (wirePubky(publicKey) != identityRef.pubky) {
                        clearUnavailableExternalIdentityLocked()
                        return@withContext false
                    }
                    notifyBackupStateChanged()
                    _publicKey.update { publicKey }
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

                true
            }
        }
    }

    // endregion

    // region Sign out

    suspend fun signOut(): Result<Unit> = identityLifecycleMutex.withLock {
        withContext(NonCancellable + ioDispatcher) {
            if (pubkyStore.data.first().privatePaykitStateCleanupPending) {
                return@withContext runSuspendCatching {
                    retryPendingPrivatePaykitStateCleanupLocked()
                }.onFailure {
                    Logger.warn("Failed to finish pending private Paykit cleanup", it, context = TAG)
                }
            }

            runSuspendCatching { disableLocalIdentityExport() }
                .onFailure { Logger.error("Failed to disable shared Pubky export", it, context = TAG) }
                .exceptionOrNull()
                ?.let { return@withContext Result.failure(it) }

            val hadPaykitState = settingsStore.data.first().hasPaykitState()
            val endpointCleanupResult = removeBitkitPaymentEndpoints()
                .onFailure { Logger.warn("Failed to remove Bitkit payment endpoints", it, context = TAG) }

            val result = runSuspendCatching {
                pubkyService.signOut()
            }.onFailure { Logger.error("Failed to revoke Pubky session during sign out", it, context = TAG) }

            if (result.isFailure) {
                if (hadPaykitState) {
                    runSuspendCatching {
                        settingsStore.update { it.copy(publicPaykitCleanupPending = true) }
                    }.onFailure {
                        Logger.warn("Failed to mark Paykit state for reconciliation", it, context = TAG)
                    }
                }
                return@withContext result
            }

            clearLocalState(publicPaykitCleanupPending = endpointCleanupResult.isFailure && hadPaykitState)
            result
        }
    }

    suspend fun wipeLocalState() = identityLifecycleMutex.withLock {
        runSuspendCatching {
            withContext(ioDispatcher) { pubkyService.forgetSessionAccess() }
        }.onFailure {
            Logger.warn("Failed to forget local Pubky session access", it, context = TAG)
        }
        clearLocalState()
    }

    suspend fun disableSharedIdentityExport(): Result<Unit> = identityLifecycleMutex.withLock {
        runSuspendCatching { disableLocalIdentityExport() }
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
            val identity = credential.identity.validated()
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
            if (it.isDefinitiveExternalSourceFailure()) clearUnavailableExternalIdentityLocked()
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

        // Published endpoints outlive the borrowed identity, so drop them while the session still
        // works and keep the cleanup pending when that fails.
        val privatePaykit = privatePaykitRepo.get()
        val hadPaykitState = settingsStore.data.first().hasPaykitState()
        pubkyStore.update { it.copy(privatePaykitStateCleanupPending = true) }
        withContext(NonCancellable) {
            clearPublicPaykitSharingState(publicPaykitCleanupPending = hadPaykitState)
            clearAuthenticatedRuntimeState()
            resetPubkyMetadataPreservingPrivatePaykitCleanupMarker()
            notifyBackupStateChanged()
        }
        val privateEndpointCleanupResult = runSuspendCatching {
            privatePaykit.removePublishedEndpointsForCleanup(TAG)
        }.getOrElse {
            Logger.warn("Failed to remove private Paykit endpoints", it, context = TAG)
            Result.failure(it)
        }
        if (privateEndpointCleanupResult.isFailure) return@withContext

        finishUnavailableExternalIdentityTeardown(privatePaykit)
    }

    private suspend fun finishUnavailableExternalIdentityTeardown(
        privatePaykit: PrivatePaykitRepo,
    ): Result<Unit> {
        val hadPaykitState = settingsStore.data.first().hasPaykitState()
        val endpointCleanupResult = if (hadPaykitState) {
            removeBitkitPaymentEndpoints()
                .onFailure { Logger.warn("Failed to remove Bitkit payment endpoints", it, context = TAG) }
        } else {
            Result.success(Unit)
        }

        return withContext(NonCancellable) {
            val privateStateCleanupResult = runSuspendCatching { privatePaykit.closeAndClear() }
                .getOrElse { Result.failure(it) }
                .onFailure { Logger.warn("Failed to clear private Paykit state", it, context = TAG) }
            if (privateStateCleanupResult.isFailure) return@withContext privateStateCleanupResult

            pubkyService.clearExternalSessionAccess()
            clearPublicPaykitSharingState(
                publicPaykitCleanupPending = endpointCleanupResult.isFailure && hadPaykitState,
            )
            clearAuthenticatedRuntimeState()
            pubkyStore.update { it.copy(privatePaykitStateCleanupPending = false) }
            resetPubkyMetadataPreservingPrivatePaykitCleanupMarker()
            notifyBackupStateChanged()
            Result.success(Unit)
        }
    }

    private suspend fun retryPendingPrivatePaykitStateCleanupLocked() {
        if (!pubkyStore.data.first().privatePaykitStateCleanupPending) return
        val privatePaykit = privatePaykitRepo.get()
        privatePaykit.removePublishedEndpointsForCleanup(TAG).getOrThrow()
        finishUnavailableExternalIdentityTeardown(privatePaykit).getOrThrow()
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
        runSuspendCatching { resetPubkyMetadataPreservingPrivatePaykitCleanupMarker() }
        clearAuthenticatedRuntimeState()
    }

    private suspend fun resetPubkyMetadataPreservingPrivatePaykitCleanupMarker() {
        if (pubkyStore.data.first().privatePaykitStateCleanupPending) {
            pubkyStore.update { PubkyStoreData(privatePaykitStateCleanupPending = true) }
        } else {
            pubkyStore.reset()
        }
    }

    private suspend fun clearAuthenticatedRuntimeState() = withContext(ioDispatcher) {
        evictPubkyImages()
        _publicKey.update { null }
        _profile.update { null }
        _contacts.update { emptyList() }
        _contactsLoadVersion.update { 0L }
        clearPendingImport()
        _sessionRestorationFailed.update { false }
    }

    private fun markContactsLoaded() {
        _contactsLoadVersion.update { it + 1 }
    }

    private suspend fun clearLocalState(publicPaykitCleanupPending: Boolean = false) = withContext(ioDispatcher) {
        disableLocalIdentityExport()
        runSuspendCatching { keychain.delete(Keychain.Key.PAYKIT_SESSION.name) }
        val localSecretResult = runSuspendCatching { keychain.delete(Keychain.Key.PUBKY_SECRET_KEY.name) }
        // The quarantine marker must never outlive the secret it guards: releasing it while the secret
        // survives would let a suspect managed secret be signed back in and re-exported to Ring.
        if (localSecretResult.isSuccess) {
            runSuspendCatching { keychain.delete(Keychain.Key.PUBKY_MANAGED_SECRET_QUARANTINED.name) }
        } else {
            Logger.error(
                "Kept managed local Pubky secret quarantine after failed secret deletion",
                localSecretResult.exceptionOrNull(),
                context = TAG,
            )
        }
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
        settingsStore.setPubkyProfileSetupPending(false)
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

    private fun SharedPubkyIdentity.matches(other: SharedPubkyIdentity): Boolean =
        protocolVersion == other.protocolVersion &&
            sourcePackage == other.sourcePackage &&
            SharedPubkyContract.requireWirePubky(pubky) ==
            SharedPubkyContract.requireWirePubky(other.pubky)

    private fun SharedPubkyCredential.matches(identityRef: SharedPubkyIdentity): Boolean =
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

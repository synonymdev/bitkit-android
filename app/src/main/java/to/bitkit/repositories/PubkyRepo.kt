package to.bitkit.repositories

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.ImageLoader
import com.synonym.paykit.FfiPaymentEntry
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import to.bitkit.data.PubkyStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.models.HomegateResponse
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyProfileData
import to.bitkit.models.PubkyProfileLink
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.models.PubkySessionBackupKind
import to.bitkit.models.PubkySessionBackupV1
import to.bitkit.services.PubkyService
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

enum class PubkyAuthState { Idle, Authenticating, Authenticated }

sealed class PubkyContactError(message: String) : AppError(message) {
    data object AlreadyExists : PubkyContactError("Contact already exists")
    data object CannotAddSelf : PubkyContactError("Cannot add your own pubky as a contact")
    data object InvalidFormat : PubkyContactError("Invalid pubky key format")
}

@Suppress("TooManyFunctions", "LargeClass")
@Singleton
class PubkyRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val pubkyService: PubkyService,
    private val keychain: Keychain,
    private val imageLoader: ImageLoader,
    private val pubkyStore: PubkyStore,
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

    private val _profile = MutableStateFlow<PubkyProfile?>(null)
    val profile: StateFlow<PubkyProfile?> = _profile.asStateFlow()

    private val _publicKey = MutableStateFlow<String?>(null)
    val publicKey: StateFlow<String?> = _publicKey.asStateFlow()

    private val _isLoadingProfile = MutableStateFlow(false)
    val isLoadingProfile: StateFlow<Boolean> = _isLoadingProfile.asStateFlow()

    private val _contacts = MutableStateFlow<List<PubkyProfile>>(emptyList())
    val contacts: StateFlow<List<PubkyProfile>> = _contacts.asStateFlow()

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

    val isAuthenticated: StateFlow<Boolean> = _authState.map { it == PubkyAuthState.Authenticated }
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
        runCatching {
            ensureServiceInitialized()
        }.onFailure {
            Logger.error("Failed to initialize paykit", it, context = TAG)
        }.getOrNull() ?: return@withContext

        initializeMutex.withLock {
            _sessionRestorationFailed.update { false }
            val result = runCatching {
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
                    Logger.info("Restored paykit session for '${result.publicKey}'", context = TAG)
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
            runCatching {
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
                Logger.warn("Skipped re-sign-in recovery, no secret key available", context = TAG)
                runCatching { keychain.delete(Keychain.Key.PAYKIT_SESSION.name) }
                notifyBackupStateChanged()
                InitResult.RestorationFailed
            } else {
                InitResult.NoSession
            }
        } else {
            runCatching {
                val newSession = pubkyService.signIn(storedSecretKeyHex)
                keychain.upsertString(Keychain.Key.PAYKIT_SESSION.name, newSession)
                notifyBackupStateChanged()
                val publicKey = pubkyService.importSession(newSession).ensurePubkyPrefix()
                Logger.info("Re-signed in and restored session for '$publicKey'", context = TAG)
                InitResult.Restored(publicKey)
            }.getOrElse {
                Logger.error("Failed re-sign-in recovery", it, context = TAG)
                runCatching { keychain.delete(Keychain.Key.PAYKIT_SESSION.name) }
                notifyBackupStateChanged()
                InitResult.RestorationFailed
            }
        }
    }

    fun clearSessionRestorationFailed() {
        _sessionRestorationFailed.update { false }
    }

    // endregion

    // region Ring auth flow

    suspend fun startAuthentication(): Result<String> {
        _authState.update { PubkyAuthState.Authenticating }
        return runCatching {
            withContext(ioDispatcher) { pubkyService.startAuth() }
        }.onFailure {
            _authState.update { PubkyAuthState.Idle }
        }
    }

    suspend fun completeAuthentication(): Result<Unit> {
        return runCatching {
            withContext(ioDispatcher) {
                val sessionSecret = pubkyService.completeAuth()
                val pk = pubkyService.importSession(sessionSecret).ensurePubkyPrefix()

                runCatching { keychain.delete(Keychain.Key.PUBKY_SECRET_KEY.name) }
                keychain.upsertString(Keychain.Key.PAYKIT_SESSION.name, sessionSecret)
                notifyBackupStateChanged()

                pk
            }
        }.onFailure {
            _authState.update { PubkyAuthState.Idle }
        }.onSuccess { pk ->
            _publicKey.update { pk }
            _authState.update { PubkyAuthState.Authenticated }
            Logger.info("Completed pubky auth for '$pk'", context = TAG)
            loadProfile()
        }.map { }
    }

    suspend fun cancelAuthentication() {
        runCatching {
            withContext(ioDispatcher) { pubkyService.cancelAuth() }
        }.onFailure { Logger.warn("Failed to cancel auth", it, context = TAG) }
        _authState.update { PubkyAuthState.Idle }
    }

    fun cancelAuthenticationSync() {
        scope.launch { cancelAuthentication() }
    }

    // endregion

    // region Payment endpoints

    suspend fun getPaymentList(publicKey: String): Result<List<FfiPaymentEntry>> = withContext(ioDispatcher) {
        runCatching {
            pubkyService.getPaymentList(publicKey.ensurePubkyPrefix())
        }
    }

    suspend fun setPaymentEndpoint(methodId: String, endpointData: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            pubkyService.setPaymentEndpoint(methodId, endpointData)
        }
    }

    suspend fun removePaymentEndpoint(methodId: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            pubkyService.removePaymentEndpoint(methodId)
        }
    }

    suspend fun currentPublicKey(): Result<String?> = withContext(ioDispatcher) {
        runCatching {
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
            runCatching {
                withContext(ioDispatcher) {
                    fetchBitkitProfile(pk) ?: run {
                        val ffiProfile = pubkyService.getProfile(pk)
                        PubkyProfile.fromFfi(pk, ffiProfile)
                    }
                }
            }.onSuccess { loadedProfile ->
                if (_publicKey.value != pk) {
                    Logger.debug("Skipped stale profile load for '$pk'", context = TAG)
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

    private suspend fun fetchBitkitProfile(publicKey: String): PubkyProfile? = runCatching {
        val strippedKey = publicKey.removePrefix(PUBKY_PREFIX)
        val uri = "$PUBKY_SCHEME$strippedKey${Env.profilePath}"
        val json = pubkyService.fetchFileString(uri)
        PubkyProfileData.decode(json).toPubkyProfile(publicKey)
    }.onFailure {
        Logger.debug("Falling back to FFI, no bitkit profile found", context = TAG)
    }.getOrNull()

    suspend fun fetchRemoteProfile(publicKey: String): Result<PubkyProfile?> = runCatching {
        withContext(ioDispatcher) {
            fetchBitkitProfile(publicKey) ?: run {
                val ffiProfile = pubkyService.getProfile(publicKey)
                PubkyProfile.fromFfi(publicKey, ffiProfile)
            }
        }
    }

    // endregion

    // region Profile creation & editing

    suspend fun deriveKeys(): Result<Pair<String, String>> = runCatching {
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
    ): Result<Unit> = runCatching {
        withContext(ioDispatcher) {
            val (publicKeyZ32, secretKeyHex) = deriveKeys().getOrThrow()

            val homegate = fetchHomegateSignupCode()

            val session = runCatching {
                pubkyService.signUp(secretKeyHex, homegate.homeserverPubky, homegate.signupCode)
            }.getOrElse {
                Logger.warn("Retrying sign in after sign up failed", it, context = TAG)
                pubkyService.signIn(secretKeyHex)
            }

            keychain.upsertString(Keychain.Key.PUBKY_SECRET_KEY.name, secretKeyHex)
            keychain.upsertString(Keychain.Key.PAYKIT_SESSION.name, session)
            notifyBackupStateChanged()
            pubkyService.importSession(session)

            val imageUrl = avatarBytes?.let { uploadAvatar(it, secretKeyHex).getOrNull() }
            writeProfile(session, name, bio, links, tags, imageUrl)

            _publicKey.update { publicKeyZ32 }
            _authState.update { PubkyAuthState.Authenticated }
            Logger.info("Created identity for '$publicKeyZ32'", context = TAG)
            loadProfile()
            loadContacts()
        }
    }

    suspend fun uploadAvatar(imageBytes: ByteArray, secretKeyHex: String): Result<String> = runCatching {
        withContext(ioDispatcher) {
            val compressed = compressAvatar(imageBytes)
            val path = "${Env.blobsBasePath}${System.currentTimeMillis()}.jpg"
            pubkyService.putWithSecretKey(secretKeyHex, path, compressed)
            val strippedKey = pubkyService.publicKeyFromSecret(secretKeyHex).removePrefix(PUBKY_PREFIX)
            "$PUBKY_SCHEME$strippedKey$path"
        }
    }

    suspend fun uploadAvatar(imageBytes: ByteArray): Result<String> = runCatching {
        withContext(ioDispatcher) {
            val publicKey = requireNotNull(_publicKey.value) {
                "No public key available"
            }
            val secretKeyHex = managedSecretKeyFor(publicKey)
            if (secretKeyHex != null) {
                return@withContext uploadAvatar(imageBytes, secretKeyHex).getOrThrow()
            }

            val session = requireNotNull(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)) {
                "No session available"
            }
            val compressed = compressAvatar(imageBytes)
            val path = "${Env.blobsBasePath}${System.currentTimeMillis()}.jpg"
            pubkyService.sessionPut(session, path, compressed)
            "$PUBKY_SCHEME${publicKey.removePrefix(PUBKY_PREFIX)}$path"
        }
    }

    suspend fun saveProfile(
        name: String,
        bio: String,
        links: List<PubkyProfileLink>,
        tags: List<String>,
        imageUrl: String?,
    ): Result<Unit> = runCatching {
        withContext(ioDispatcher) {
            val session = requireNotNull(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)) {
                "No session available"
            }
            writeProfile(session, name, bio, links, tags, imageUrl)
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
        }
    }

    suspend fun deleteProfileWithSessionRetry(): Result<Unit> = withContext(ioDispatcher) {
        val initialResult = deleteProfile()
        if (initialResult.isSuccess) return@withContext initialResult

        val refreshedSession = refreshSessionIfPossible().getOrDefault(false)
        if (!refreshedSession) return@withContext initialResult

        deleteProfile()
    }

    suspend fun deleteProfile(): Result<Unit> = runCatching {
        withContext(ioDispatcher) {
            val session = requireNotNull(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)) {
                "No session available"
            }
            deleteAllContacts(session)
            runCatching {
                pubkyService.sessionDelete(session, Env.profilePath)
            }.getOrElse {
                if (!it.isMissingPubkyData()) {
                    throw it
                }
                Logger.info("Continuing sign out, bitkit profile storage already missing", context = TAG)
            }
        }
        signOut().getOrThrow()
    }

    private suspend fun deleteAllContacts(session: String) {
        val contactPaths = runCatching {
            pubkyService.sessionList(session, Env.contactsBasePath)
        }.getOrElse {
            if (it.isMissingPubkyDirectory()) return
            throw it
        }
        contactPaths.forEach { path ->
            val contactKey = path.substringAfterLast("/")
            runCatching {
                pubkyService.sessionDelete(session, "${Env.contactsBasePath}$contactKey")
            }.onFailure {
                Logger.warn("Failed to delete contact '$contactKey'", it, context = TAG)
            }
        }
        _contacts.update { emptyList() }
        Logger.info("Deleted all contacts", context = TAG)
    }

    @Suppress("LongParameterList")
    private suspend fun writeProfile(
        sessionSecret: String,
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
        pubkyService.sessionPut(sessionSecret, Env.profilePath, data.encode())
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
            runCatching {
                withContext(ioDispatcher) {
                    val session = keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)
                        ?: return@withContext emptyList()

                    val contactPaths = runCatching {
                        pubkyService.sessionList(session, Env.contactsBasePath)
                    }.getOrElse {
                        if (it.isMissingPubkyDirectory()) {
                            Logger.debug(
                                "Treating missing contacts directory as empty for '$pk'",
                                context = TAG,
                            )
                            return@withContext emptyList()
                        }
                        throw it
                    }
                    val strippedOwnerKey = pk.removePrefix(PUBKY_PREFIX)

                    coroutineScope {
                        contactPaths.map { path ->
                            val contactKey = path.substringAfterLast("/")
                            async {
                                runCatching {
                                    val uri = "$PUBKY_SCHEME$strippedOwnerKey${Env.contactsBasePath}$contactKey"
                                    val json = pubkyService.fetchFileString(uri)
                                    PubkyProfileData.decode(json).toPubkyProfile(contactKey)
                                }.onFailure {
                                    Logger.warn("Failed to load contact '$contactKey'", it, context = TAG)
                                }.getOrElse {
                                    PubkyProfile.placeholder(contactKey)
                                }
                            }
                        }.awaitAll().sortedBy { it.name.lowercase() }
                    }
                }
            }.onSuccess { loadedContacts ->
                if (_publicKey.value != pk) {
                    Logger.debug("Skipped stale contacts load for '$pk'", context = TAG)
                    return@onSuccess
                }
                _contacts.update { loadedContacts }
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
        return fetchRemoteProfile(prefixedKey)
            .map { it ?: PubkyProfile.placeholder(prefixedKey) }
            .recoverCatching {
                if (!it.isMissingPubkyData()) {
                    throw it
                }
                Logger.warn("Falling back to placeholder contact '$prefixedKey'", it, context = TAG)
                PubkyProfile.placeholder(prefixedKey)
            }
    }

    suspend fun addContact(publicKey: String, existingProfile: PubkyProfile? = null): Result<Unit> = runCatching {
        withContext(ioDispatcher) {
            val session = requireNotNull(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)) {
                "No session available"
            }
            val prefixedKey = requireAddableContactPublicKey(
                publicKey = publicKey,
                allowExisting = existingProfile != null,
            )
            val profile = existingProfile?.copy(publicKey = prefixedKey) ?: run {
                val ffiProfile = pubkyService.getProfile(prefixedKey)
                PubkyProfile.fromFfi(prefixedKey, ffiProfile)
            }
            val data = profile.toProfileData().encode()
            pubkyService.sessionPut(session, "${Env.contactsBasePath}$prefixedKey", data)
            _contacts.update { current ->
                (current.filter { it.publicKey != prefixedKey } + profile)
                    .sortedBy { it.name.lowercase() }
            }
            Logger.info("Added contact '$prefixedKey'", context = TAG)
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
    ): Result<Unit> = runCatching {
        withContext(ioDispatcher) {
            val session = requireNotNull(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)) {
                "No session available"
            }
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
            val data = updatedProfile.toProfileData().encode()
            pubkyService.sessionPut(session, "${Env.contactsBasePath}$prefixedKey", data)
            _contacts.update { current ->
                current.map { if (it.publicKey == prefixedKey) updatedProfile else it }
                    .sortedBy { it.name.lowercase() }
            }
            Logger.info("Updated contact '$prefixedKey'", context = TAG)
        }
    }

    suspend fun removeContact(publicKey: String): Result<Unit> = runCatching {
        withContext(ioDispatcher) {
            val session = requireNotNull(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)) {
                "No session available"
            }
            val prefixedKey = publicKey.ensurePubkyPrefix()
            pubkyService.sessionDelete(session, "${Env.contactsBasePath}$prefixedKey")
            _contacts.update { current -> current.filter { it.publicKey != prefixedKey } }
            Logger.info("Removed contact '$prefixedKey'", context = TAG)
        }
    }

    suspend fun importContacts(publicKeys: List<String>): Result<Unit> = runCatching {
        withContext(ioDispatcher) {
            val session = requireNotNull(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)) {
                "No session available"
            }
            val imported = coroutineScope {
                publicKeys.map { contactPk ->
                    val prefixedKey = contactPk.ensurePubkyPrefix()
                    async {
                        runCatching {
                            val ffiProfile = pubkyService.getProfile(prefixedKey)
                            val profile = PubkyProfile.fromFfi(prefixedKey, ffiProfile)
                            val data = profile.toProfileData().encode()
                            pubkyService.sessionPut(session, "${Env.contactsBasePath}$prefixedKey", data)
                            profile
                        }.onFailure {
                            Logger.warn("Failed to import contact '$prefixedKey'", it, context = TAG)
                        }.getOrNull()
                    }
                }.awaitAll().filterNotNull()
            }
            _contacts.update { current ->
                val existing = current.map { it.publicKey }.toSet()
                (current + imported.filter { it.publicKey !in existing })
                    .sortedBy { it.name.lowercase() }
            }
            Logger.info("Imported '${imported.size}' contacts", context = TAG)
        }
    }

    suspend fun prepareImport(): Result<Unit> = runCatching {
        clearPendingImport()
        val pk = requireNotNull(_publicKey.value) { "Not authenticated" }
        withContext(ioDispatcher) {
            val contactKeys = pubkyService.getContacts(pk)
            Logger.debug("Discovered '${contactKeys.size}' contacts for import", context = TAG)

            val contacts = coroutineScope {
                contactKeys.map { contactPk ->
                    val prefixedKey = contactPk.ensurePubkyPrefix()
                    async {
                        runCatching {
                            val ffiProfile = pubkyService.getProfile(prefixedKey)
                            PubkyProfile.fromFfi(prefixedKey, ffiProfile)
                        }.getOrElse { PubkyProfile.placeholder(prefixedKey) }
                    }
                }.awaitAll().sortedBy { it.name.lowercase() }
            }

            val ownProfile = runCatching {
                val ffiProfile = pubkyService.getProfile(pk)
                PubkyProfile.fromFfi(pk, ffiProfile)
            }.getOrNull()

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

    suspend fun hasSecretKey(): Boolean = runCatching {
        withContext(ioDispatcher) {
            !keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name).isNullOrEmpty()
        }
    }.getOrDefault(false)

    suspend fun approveAuth(authUrl: String): Result<Unit> = runCatching {
        withContext(ioDispatcher) {
            val secretKeyHex = requireNotNull(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)) {
                "No secret key available — use Ring to manage authorizations"
            }
            pubkyService.approveAuth(authUrl, secretKeyHex)
        }
    }

    // endregion

    // region Backup state

    suspend fun snapshotSessionBackupState(): Result<PubkySessionBackupV1?> = runCatching {
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

    suspend fun restoreSessionBackupState(backup: PubkySessionBackupV1?): Result<Unit> = runCatching {
        withContext(ioDispatcher) {
            if (backup == null) {
                notifyBackupStateChanged()
                return@withContext
            }

            ensureServiceInitialized()

            initializeMutex.withLock {
                pubkyService.forceSignOut()
                clearAuthenticatedState()
                runCatching { keychain.delete(Keychain.Key.PAYKIT_SESSION.name) }
                runCatching { keychain.delete(Keychain.Key.PUBKY_SECRET_KEY.name) }

                when (backup.kind) {
                    PubkySessionBackupKind.LocalSeed -> {
                        val secretKeyHex = deriveLocalSecretKeyFromWalletSeed()
                        keychain.upsertString(Keychain.Key.PUBKY_SECRET_KEY.name, secretKeyHex)
                    }

                    PubkySessionBackupKind.ExternalSession -> {
                        val sessionSecret = requireNotNull(backup.sessionSecret?.takeIf { it.isNotBlank() }) {
                            "Missing session secret in backup"
                        }
                        keychain.upsertString(Keychain.Key.PAYKIT_SESSION.name, sessionSecret)
                    }
                }

                notifyBackupStateChanged()
            }
        }
    }

    suspend fun refreshSessionIfPossible(): Result<Boolean> = runCatching {
        withContext(ioDispatcher) {
            val storedSecretKeyHex = keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)
                ?: return@withContext false

            val sessionSecret = pubkyService.signIn(storedSecretKeyHex)
            val publicKey = pubkyService.importSession(sessionSecret).ensurePubkyPrefix()

            keychain.upsertString(Keychain.Key.PAYKIT_SESSION.name, sessionSecret)
            notifyBackupStateChanged()
            _publicKey.update { publicKey }
            _authState.update { PubkyAuthState.Authenticated }

            true
        }
    }

    // endregion

    // region Sign out

    suspend fun signOut(): Result<Unit> {
        val result = runCatching {
            withContext(ioDispatcher) { pubkyService.signOut() }
        }.recoverCatching {
            Logger.warn("Forcing local sign out after server sign out failed", it, context = TAG)
            withContext(ioDispatcher) { pubkyService.forceSignOut() }
        }

        clearLocalState()
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
            Logger.warn("Ignoring invalid managed secret key for '$publicKey'", it, context = TAG)
        }.getOrNull()

        if (derivedPublicKey == publicKey) {
            return@withContext secretKeyHex
        }

        if (derivedPublicKey != null) {
            Logger.warn("Ignoring stale managed secret key for '$publicKey'", context = TAG)
        }
        runCatching { keychain.delete(Keychain.Key.PUBKY_SECRET_KEY.name) }
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
        runCatching { pubkyStore.reset() }
        _publicKey.update { null }
        _profile.update { null }
        _contacts.update { emptyList() }
        clearPendingImport()
        _sessionRestorationFailed.update { false }
        _authState.update { PubkyAuthState.Idle }
    }

    private suspend fun clearLocalState() = withContext(ioDispatcher) {
        runCatching { keychain.delete(Keychain.Key.PAYKIT_SESSION.name) }
        runCatching { keychain.delete(Keychain.Key.PUBKY_SECRET_KEY.name) }
        notifyBackupStateChanged()
        clearAuthenticatedState()
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

    private fun Throwable.isMissingPubkyDirectory(): Boolean {
        if (isMissingPubkyData()) {
            return true
        }

        val fullMessage = buildErrorMessage()
        return fullMessage.contains("directory not found", ignoreCase = true)
    }

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

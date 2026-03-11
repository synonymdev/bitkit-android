package to.bitkit.repositories

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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
import kotlinx.coroutines.withContext
import org.json.JSONObject
import to.bitkit.data.PubkyImageCache
import to.bitkit.data.PubkyStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.IoDispatcher
import to.bitkit.models.PubkyProfile
import to.bitkit.services.PubkyService
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

enum class PubkyAuthState { Idle, Authenticating, Authenticated }

@Singleton
class PubkyRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val pubkyService: PubkyService,
    private val keychain: Keychain,
    private val imageCache: PubkyImageCache,
    private val pubkyStore: PubkyStore,
) {
    companion object {
        private const val TAG = "PubkyRepo"
        private const val PUBKY_SCHEME = "pubky://"
    }

    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())
    private val loadProfileMutex = Mutex()

    private val _authState = MutableStateFlow(PubkyAuthState.Idle)

    private val _profile = MutableStateFlow<PubkyProfile?>(null)
    val profile: StateFlow<PubkyProfile?> = _profile.asStateFlow()

    private val _publicKey = MutableStateFlow<String?>(null)
    val publicKey: StateFlow<String?> = _publicKey.asStateFlow()

    private val _isLoadingProfile = MutableStateFlow(false)
    val isLoadingProfile: StateFlow<Boolean> = _isLoadingProfile.asStateFlow()

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

    private suspend fun initialize() {
        val result = runCatching {
            withContext(ioDispatcher) {
                pubkyService.initialize()

                val savedSecret = runCatching {
                    keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)
                }.getOrNull()

                if (savedSecret.isNullOrEmpty()) {
                    return@withContext InitResult.NoSession
                }

                runCatching {
                    val pk = pubkyService.importSession(savedSecret)
                    InitResult.Restored(pk)
                }.getOrElse {
                    Logger.warn("Failed to restore paykit session", it, context = TAG)
                    InitResult.RestorationFailed
                }
            }
        }.onFailure {
            Logger.error("Failed to initialize paykit", it, context = TAG)
        }.getOrNull() ?: return

        when (result) {
            is InitResult.NoSession -> Logger.debug("No saved paykit session found", context = TAG)
            is InitResult.Restored -> {
                _publicKey.update { result.publicKey }
                _authState.update { PubkyAuthState.Authenticated }
                Logger.info("Paykit session restored for ${result.publicKey}", context = TAG)
                loadProfile()
            }
            is InitResult.RestorationFailed -> {
                runCatching { keychain.delete(Keychain.Key.PAYKIT_SESSION.name) }
            }
        }
    }

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
                val pk = pubkyService.importSession(sessionSecret)

                runCatching { keychain.delete(Keychain.Key.PAYKIT_SESSION.name) }
                keychain.saveString(Keychain.Key.PAYKIT_SESSION.name, sessionSecret)

                pk
            }
        }.onFailure {
            _authState.update { PubkyAuthState.Idle }
        }.onSuccess { pk ->
            _publicKey.update { pk }
            _authState.update { PubkyAuthState.Authenticated }
            Logger.info("Pubky auth completed for $pk", context = TAG)
            loadProfile()
        }.map {}
    }

    suspend fun cancelAuthentication() {
        runCatching {
            withContext(ioDispatcher) { pubkyService.cancelAuth() }
        }.onFailure { Logger.warn("Cancel auth failed", it, context = TAG) }
        _authState.update { PubkyAuthState.Idle }
    }

    fun cancelAuthenticationSync() {
        scope.launch { cancelAuthentication() }
    }

    suspend fun loadProfile() {
        val pk = _publicKey.value ?: return
        if (!loadProfileMutex.tryLock()) return

        _isLoadingProfile.update { true }
        try {
            runCatching {
                withContext(ioDispatcher) {
                    val ffiProfile = pubkyService.getProfile(pk)
                    Logger.debug("Profile loaded — name: ${ffiProfile.name}, image: ${ffiProfile.image}", context = TAG)
                    PubkyProfile.fromFfi(pk, ffiProfile)
                }
            }.onSuccess { loadedProfile ->
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

    suspend fun signOut(): Result<Unit> = runCatching {
        withContext(ioDispatcher) {
            runCatching { pubkyService.signOut() }
                .recoverCatching {
                    Logger.warn("Server sign out failed, forcing local sign out", it, context = TAG)
                    pubkyService.forceSignOut()
                }
            runCatching { keychain.delete(Keychain.Key.PAYKIT_SESSION.name) }
            runCatching { imageCache.clear() }
        }
        pubkyStore.reset()
        _publicKey.update { null }
        _profile.update { null }
        _authState.update { PubkyAuthState.Idle }
    }

    fun cachedImage(uri: String): Bitmap? = imageCache.memoryImage(uri)

    suspend fun fetchImage(uri: String): Result<Bitmap> = runCatching {
        withContext(ioDispatcher) {
            imageCache.image(uri)?.let { return@withContext it }

            val data = pubkyService.fetchFile(uri)
            val blobData = resolveImageData(data)
            imageCache.decodeAndStore(blobData, uri).getOrThrow()
        }
    }

    private suspend fun resolveImageData(data: ByteArray): ByteArray {
        return runCatching {
            val json = JSONObject(String(data))
            val src = json.optString("src", "")
            if (src.isNotEmpty() && src.startsWith(PUBKY_SCHEME)) {
                Logger.debug("File descriptor found, fetching blob from: $src", context = TAG)
                pubkyService.fetchFile(src)
            } else {
                data
            }
        }.getOrDefault(data)
    }

    private suspend fun cacheMetadata(profile: PubkyProfile) {
        pubkyStore.update {
            it.copy(cachedName = profile.name, cachedImageUri = profile.imageUrl)
        }
    }
}

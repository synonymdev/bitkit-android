package to.bitkit.repositories

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import to.bitkit.data.PubkyImageCache
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.BgDispatcher
import to.bitkit.models.PubkyProfile
import to.bitkit.services.PubkyService
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

enum class PubkyAuthState { Idle, Authenticating, Authenticated }

@Singleton
class PubkyRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val pubkyService: PubkyService,
    private val keychain: Keychain,
    private val imageCache: PubkyImageCache,
) {
    companion object {
        private const val TAG = "PubkyRepo"
        private const val PREFS_NAME = "pubky_profile_cache"
        private const val KEY_CACHED_NAME = "cached_name"
        private const val KEY_CACHED_IMAGE_URI = "cached_image_uri"
    }

    private val scope = CoroutineScope(bgDispatcher + SupervisorJob())
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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

    val displayName: StateFlow<String?> = _profile.map { it?.name ?: prefs.getString(KEY_CACHED_NAME, null) }
        .stateIn(scope, SharingStarted.Eagerly, prefs.getString(KEY_CACHED_NAME, null))

    val displayImageUri: StateFlow<String?> = _profile
        .map { it?.imageUrl ?: prefs.getString(KEY_CACHED_IMAGE_URI, null) }
        .stateIn(scope, SharingStarted.Eagerly, prefs.getString(KEY_CACHED_IMAGE_URI, null))

    private sealed interface InitResult {
        data object NoSession : InitResult
        data class Restored(val publicKey: String) : InitResult
        data object RestorationFailed : InitResult
    }

    suspend fun initialize() {
        val result = runCatching {
            withContext(bgDispatcher) {
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
            withContext(bgDispatcher) { pubkyService.startAuth() }
        }.onFailure {
            Logger.error("Failed to start authentication", it, context = TAG)
            _authState.update { PubkyAuthState.Idle }
        }
    }

    suspend fun completeAuthentication(): Result<Unit> {
        return runCatching {
            withContext(bgDispatcher) {
                val sessionSecret = pubkyService.completeAuth()
                val pk = pubkyService.importSession(sessionSecret)

                runCatching { keychain.delete(Keychain.Key.PAYKIT_SESSION.name) }
                keychain.saveString(Keychain.Key.PAYKIT_SESSION.name, sessionSecret)

                pk
            }
        }.onFailure {
            Logger.error("Failed to complete authentication", it, context = TAG)
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
            withContext(bgDispatcher) { pubkyService.cancelAuth() }
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
                withContext(bgDispatcher) {
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
        withContext(bgDispatcher) {
            runCatching { pubkyService.signOut() }
                .onFailure {
                    Logger.warn("Server sign out failed, forcing local sign out", it, context = TAG)
                    runCatching { pubkyService.forceSignOut() }
                }
            runCatching { keychain.delete(Keychain.Key.PAYKIT_SESSION.name) }
            runCatching { imageCache.clear() }
        }
        clearCachedMetadata()
        _publicKey.update { null }
        _profile.update { null }
        _authState.update { PubkyAuthState.Idle }
    }

    private fun cacheMetadata(profile: PubkyProfile) {
        prefs.edit()
            .putString(KEY_CACHED_NAME, profile.name)
            .putString(KEY_CACHED_IMAGE_URI, profile.imageUrl)
            .apply()
    }

    private fun clearCachedMetadata() {
        prefs.edit().clear().apply()
    }
}

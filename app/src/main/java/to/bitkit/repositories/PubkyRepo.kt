package to.bitkit.repositories

import coil3.ImageLoader
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
import kotlinx.coroutines.withContext
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
    private val imageLoader: ImageLoader,
    private val pubkyStore: PubkyStore,
) {
    companion object {
        private const val TAG = "PubkyRepo"
        private const val PUBKY_SCHEME = "pubky://"
    }

    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())
    private val loadProfileMutex = Mutex()
    private val loadContactsMutex = Mutex()

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
                Logger.info("Paykit session restored for '${result.publicKey}'", context = TAG)
                loadProfile()
                loadContacts()
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
            Logger.info("Pubky auth completed for '$pk'", context = TAG)
            loadProfile()
            loadContacts()
        }.map { }
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
                    Logger.debug(
                        "Profile loaded — name: '${ffiProfile.name}', image: '${ffiProfile.image}'",
                        context = TAG,
                    )
                    PubkyProfile.fromFfi(pk, ffiProfile)
                }
            }.onSuccess { loadedProfile ->
                if (_publicKey.value == null) return@onSuccess
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

    suspend fun loadContacts() {
        val pk = _publicKey.value ?: return
        if (!loadContactsMutex.tryLock()) return

        _isLoadingContacts.update { true }
        try {
            runCatching {
                withContext(ioDispatcher) {
                    val contactKeys = pubkyService.getContacts(pk)
                    Logger.debug("Fetched '${contactKeys.size}' contact keys", context = TAG)
                    coroutineScope {
                        contactKeys.map { contactPk ->
                            val prefixedKey = contactPk.ensurePubkyPrefix()
                            async {
                                runCatching {
                                    val ffiProfile = pubkyService.getProfile(prefixedKey)
                                    PubkyProfile.fromFfi(prefixedKey, ffiProfile)
                                }.onFailure {
                                    Logger.warn("Failed to load contact profile '$prefixedKey'", it, context = TAG)
                                }.getOrElse {
                                    PubkyProfile.placeholder(prefixedKey)
                                }
                            }
                        }.awaitAll().sortedBy { it.name.lowercase() }
                    }
                }
            }.onSuccess { loadedContacts ->
                if (_publicKey.value == null) return@onSuccess
                _contacts.update { loadedContacts }
            }.onFailure {
                Logger.error("Failed to load contacts", it, context = TAG)
            }
        } finally {
            _isLoadingContacts.update { false }
            loadContactsMutex.unlock()
        }
    }

    suspend fun fetchContactProfile(publicKey: String): Result<PubkyProfile> = runCatching {
        withContext(ioDispatcher) {
            val prefixedKey = publicKey.ensurePubkyPrefix()
            val ffiProfile = pubkyService.getProfile(prefixedKey)
            PubkyProfile.fromFfi(prefixedKey, ffiProfile)
        }
    }.onFailure {
        Logger.error("Failed to load contact profile '$publicKey'", it, context = TAG)
    }

    suspend fun signOut(): Result<Unit> = runCatching {
        withContext(ioDispatcher) { pubkyService.signOut() }
    }.recoverCatching {
        Logger.warn("Server sign out failed, forcing local sign out", it, context = TAG)
        withContext(ioDispatcher) { pubkyService.forceSignOut() }
    }.also {
        runCatching { withContext(ioDispatcher) { keychain.delete(Keychain.Key.PAYKIT_SESSION.name) } }
        evictPubkyImages()
        runCatching { withContext(ioDispatcher) { pubkyStore.reset() } }
        _publicKey.update { null }
        _profile.update { null }
        _contacts.update { emptyList() }
        _authState.update { PubkyAuthState.Idle }
    }

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

    private fun String.ensurePubkyPrefix(): String =
        if (startsWith(PUBKY_SCHEME)) this else "$PUBKY_SCHEME$this"
}

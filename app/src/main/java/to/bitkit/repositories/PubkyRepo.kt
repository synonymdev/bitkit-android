package to.bitkit.repositories

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.ImageLoader
import com.synonym.paykit.ContactProfileResolution
import com.synonym.paykit.ContactRecord
import com.synonym.paykit.PubkyAuthCompanionClaim
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
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
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import to.bitkit.async.appScope
import to.bitkit.data.PubkyCachedProfile
import to.bitkit.data.PubkyImageCacheEpoch
import to.bitkit.data.PubkyStore
import to.bitkit.data.SettingsStore
import to.bitkit.data.hasPaykitState
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.paykitDisabled
import to.bitkit.data.sharedpubky.SharedPubkyClient
import to.bitkit.data.sharedpubky.SharedPubkyContract
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
import to.bitkit.services.PaykitReadLane
import to.bitkit.services.PaykitReceiverPaths
import to.bitkit.services.PubkyService
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

sealed class PubkyContactError(message: String) : AppError(message) {
    data object AlreadyExists : PubkyContactError("Contact already exists")
    data object CannotAddSelf : PubkyContactError("Cannot add your own pubky as a contact")
    data object InvalidFormat : PubkyContactError("Invalid pubky key format")
    data object ActiveSubscription : PubkyContactError("Contact has an active subscription")
}

private fun Throwable.containsActiveSubscriptionError(): Boolean =
    generateSequence(this) { it.cause }.any { it is PubkyContactError.ActiveSubscription }

data object PubkyAlreadySignedInError : AppError("Already signed in")

/** Whether a Pubky identity is ready to use once restores already under way have finished. */
enum class PubkyIdentityReadiness {
    /** A session is active. */
    Ready,

    /** No identity is saved on this device. */
    Missing,

    /** An identity is saved, or could not be checked, but its session could not be restored yet. */
    Unavailable,
}

@Suppress("TooManyFunctions", "LargeClass", "LongParameterList")
@Singleton
class PubkyRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val pubkyService: PubkyService,
    private val keychain: Keychain,
    private val sharedPubkyClient: SharedPubkyClient,
    private val imageLoader: ImageLoader,
    private val imageCacheEpoch: PubkyImageCacheEpoch,
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

    private val scope = appScope(ioDispatcher, TAG)
    private val serviceInitializeMutex = Mutex()
    private val initializeMutex = Mutex()
    private val loadProfileMutex = Mutex()
    private val loadContactsMutex = Mutex()
    private val contactsLock = Any()
    private var contactsRevision = 0L
    private val sessionContactProfiles = mutableMapOf<String, PubkyProfile>()
    private var sessionContactProfilesOwner: String? = null
    private var contactProfileRefresh: ContactProfileRefresh? = null
    private val contactScreenLookups = mutableMapOf<String, Job>()
    private val adoptedSourceCheckMutex = Mutex()
    private val adoptionMutex = Mutex()
    private val profileWriteGeneration = AtomicLong(0L)
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

    private val _contactsLoadCompletionVersion = MutableStateFlow(0L)
    val contactsLoadCompletionVersion: StateFlow<Long> = _contactsLoadCompletionVersion.asStateFlow()

    private val _isLoadingContacts = MutableStateFlow(false)
    val isLoadingContacts: StateFlow<Boolean> = _isLoadingContacts.asStateFlow()

    private val _sessionRestorationFailed = MutableStateFlow(false)
    val sessionRestorationFailed: StateFlow<Boolean> = _sessionRestorationFailed.asStateFlow()

    private val _adoptedSourceLost = MutableStateFlow(false)
    val adoptedSourceLost: StateFlow<Boolean> = _adoptedSourceLost.asStateFlow()

    private val _pendingImportProfile = MutableStateFlow<PubkyProfile?>(null)
    val pendingImportProfile: StateFlow<PubkyProfile?> = _pendingImportProfile.asStateFlow()

    private val _pendingImportContacts = MutableStateFlow<List<PubkyProfile>>(emptyList())
    val pendingImportContacts: StateFlow<List<PubkyProfile>> = _pendingImportContacts.asStateFlow()

    private val activeContactImports = MutableStateFlow(0)
    val isImportingContacts: StateFlow<Boolean> = activeContactImports.map { it > 0 }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _contactImportVersion = MutableStateFlow(0L)
    val contactImportVersion: StateFlow<Long> = _contactImportVersion.asStateFlow()

    private val _contactImportFailure = MutableStateFlow<Throwable?>(null)
    val contactImportFailure: StateFlow<Throwable?> = _contactImportFailure.asStateFlow()

    private val _backupStateVersion = MutableStateFlow(0L)
    val backupStateVersion: StateFlow<Long> = _backupStateVersion.asStateFlow()

    private val _identityRefreshVersion = MutableStateFlow(0L)
    val identityRefreshVersion: StateFlow<Long> = _identityRefreshVersion.asStateFlow()

    val isAuthenticated: StateFlow<Boolean> = _publicKey.map { it != null }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val displayName: StateFlow<String?> = combine(_profile, pubkyStore.data) { profile, cached ->
        profile?.name ?: cached.cachedName
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val displayImageUri: StateFlow<String?> = combine(_profile, pubkyStore.data) { profile, cached ->
        profile?.imageUrl ?: cached.cachedImageUri
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val cachedProfile: StateFlow<PubkyCachedProfile?> = pubkyStore.data.map { it.cachedProfile() }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private sealed interface InitResult {
        data object NoSession : InitResult
        data class Restored(val publicKey: String) : InitResult
        data object RestorationFailed : InitResult
    }

    private data class SavedContact(
        val profile: PubkyProfile,
        val label: String?,
        val needsRefresh: Boolean,
        val showsLabelOnly: Boolean = false,
    )

    private class ContactProfileRefresh(
        private val owner: String,
        contacts: List<SavedContact>,
        scope: CoroutineScope,
        lookUp: suspend (SavedContact) -> Unit,
    ) {
        private val keys = contacts.mapTo(mutableSetOf()) { it.profile.publicKey }
        private val labelOnly = contacts.filter { it.showsLabelOnly }.associateBy { it.profile.publicKey }

        val job = SupervisorJob(scope.coroutineContext.job)
        private val bulkLookups = contacts.associate {
            it.profile.publicKey to scope.launch(job, CoroutineStart.LAZY) { lookUp(it) }
        }

        fun start() {
            bulkLookups.values.forEach { it.start() }
            job.complete()
        }

        fun covers(other: ContactProfileRefresh): Boolean =
            job.isActive && owner == other.owner && keys.containsAll(other.keys)

        fun takeOver(owner: String, publicKey: String, isShown: (SavedContact) -> Boolean): SavedContact? {
            if (this.owner != owner) return null
            val contact = labelOnly[publicKey]?.takeIf(isShown) ?: return null
            val bulkLookup = bulkLookups.getValue(publicKey).takeUnless { it.isCompleted || it.isCancelled }
                ?: return null
            bulkLookup.cancel()
            return contact
        }
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
        val restored = initializeMutex.withLock { initializeSession() }
        if (restored) {
            loadProfile()
            loadContacts()
        }
        checkAdoptedSource()
    }

    suspend fun restoreSessionIfNeeded() = withContext(ioDispatcher) {
        awaitInitialization()
        val restored = initializeMutex.withLock { restoreSessionLocked() }
        if (restored) {
            loadProfile()
            loadContacts()
        }
    }

    /**
     * Waits until a saved identity can be used. When no session is active, it retries the restore once, after any
     * resume retry, adoption, identity creation or backup restore already under way, so an earlier success is never
     * restored twice. The retry runs in the repository scope, so cancelling the caller does not interrupt it, and the
     * profile and contacts it loads are not awaited.
     */
    suspend fun awaitIdentityReady(): PubkyIdentityReadiness = withContext(ioDispatcher) {
        awaitInitialization()
        if (_publicKey.value != null) return@withContext PubkyIdentityReadiness.Ready
        scope.async {
            val restored = initializeMutex.withLock { restoreSessionLocked() }
            if (restored) {
                scope.launch {
                    loadProfile()
                    loadContacts()
                }
            }
        }.await()
        when {
            _publicKey.value != null -> PubkyIdentityReadiness.Ready
            runSuspendCatching { hasIdentity() }.getOrNull() == false -> PubkyIdentityReadiness.Missing
            else -> PubkyIdentityReadiness.Unavailable
        }
    }

    private suspend fun restoreSessionLocked(): Boolean {
        if (_publicKey.value != null) return false
        return runSuspendCatching {
            val hasIdentity = hasIdentity()
            _identityRefreshVersion.update { it + 1 }
            hasIdentity && initializeSession(notifyFailure = false)
        }.onFailure { Logger.warn("Failed to retry paykit session restoration", it, context = TAG) }
            .getOrDefault(false)
    }

    private suspend fun initializeSession(notifyFailure: Boolean = true): Boolean {
        runSuspendCatching {
            ensureServiceInitialized()
        }.onFailure {
            Logger.error("Failed to initialize paykit", it, context = TAG)
            if (notifyFailure && it.isPaykitIdentityError() && hasSavedSession()) {
                _sessionRestorationFailed.update { true }
            }
        }.getOrNull() ?: return false

        if (notifyFailure) _sessionRestorationFailed.update { false }
        val result = runSuspendCatching {
            val savedSessionSecret = keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)
            val storedSecretKeyHex = keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)

            resolveSessionInitialization(
                savedSessionSecret = savedSessionSecret,
                storedSecretKeyHex = storedSecretKeyHex,
            )
        }.onFailure {
            Logger.error("Failed to initialize paykit", it, context = TAG)
        }.getOrElse { InitResult.RestorationFailed }

        when (result) {
            is InitResult.NoSession -> {
                clearAuthenticatedState()
                Logger.debug("Found no saved paykit session", context = TAG)
            }
            is InitResult.Restored -> {
                _sessionRestorationFailed.update { false }
                _publicKey.update { result.publicKey }
                Logger.info("Restored paykit session for '${redacted(result.publicKey)}'", context = TAG)
            }
            is InitResult.RestorationFailed -> {
                clearAuthenticatedState(
                    clearCachedProfile = false,
                    clearRestorationFailure = notifyFailure,
                )
                if (notifyFailure) _sessionRestorationFailed.update { true }
            }
        }
        initializationReady.complete(Unit)
        return result is InitResult.Restored
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
    ): InitResult = withContext(ioDispatcher) {
        if (!savedSessionSecret.isNullOrEmpty()) {
            runSuspendCatching {
                val publicKey = pubkyService.importSession(savedSessionSecret).ensurePubkyPrefix()
                InitResult.Restored(publicKey)
            }.getOrElse {
                Logger.warn("Failed to restore paykit session, attempting re-sign-in", it, context = TAG)
                resolveSignedInSession(savedSessionSecret, storedSecretKeyHex ?: adoptedSecretKeyHex())
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

    // region Shared pubky

    suspend fun ringIdentities(): Result<ImmutableList<String>> = sharedPubkyClient.listRingIdentities()

    fun clearAdoptedSourceLost() {
        _adoptedSourceLost.update { false }
    }

    suspend fun checkAdoptedSource(): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            if (initializationReady.isCompleted) checkAdoptedSourcePresent()
        }.onFailure { Logger.warn("Failed to check adopted ring identity", it, context = TAG) }
    }

    private suspend fun checkAdoptedSourcePresent() {
        if (!adoptedSourceCheckMutex.tryLock()) return
        try {
            val reference = keychain.loadString(Keychain.Key.SHARED_PUBKY_SOURCE.name) ?: return
            val ringPubkys = sharedPubkyClient.listRingIdentities().getOrElse {
                Logger.warn("Failed to list ring identities", it, context = TAG)
                return
            }
            if (ringPubkys.any { "${SharedPubkyContract.RING_SOURCE_PREFIX}$it" == reference }) return

            initializeMutex.withLock {
                val currentReference = keychain.loadString(Keychain.Key.SHARED_PUBKY_SOURCE.name)
                if (currentReference != reference) return@withLock

                Logger.warn("Adopted ring identity '${redacted(reference)}' is gone, clearing session", context = TAG)
                runSuspendCatching { pubkyService.clearSessionAccess() }
                    .onFailure { Logger.warn("Failed to clear adopted session access", it, context = TAG) }
                clearLocalState()
                _adoptedSourceLost.update { true }
            }
        } finally {
            adoptedSourceCheckMutex.unlock()
        }
    }

    /**
     * Adopts the Ring identity [pubky]. [knownProfile] is read once sign-in completes, and a profile it returns that
     * matches the signed-in key is used instead of resolving the profile again; it must return only a profile that was
     * found, never the result of a failed or empty lookup.
     */
    suspend fun adoptRingIdentity(
        pubky: String,
        knownProfile: () -> PubkyProfile? = { null },
    ): Result<Boolean> = withContext(ioDispatcher) {
        val reference = "${SharedPubkyContract.RING_SOURCE_PREFIX}$pubky"
        adoptionMutex.withLock {
            var sessionInstalled = false
            var completed = false
            try {
                runSuspendCatching {
                    val publicKey = initializeMutex.withLock {
                        ensureServiceInitialized()
                        val secretKeyHex = sharedPubkyClient.ringCredential(pubky).getOrThrow()
                        val rawPublicKey = pubkyService.publicKeyFromSecret(secretKeyHex)
                        require(PubkyPublicKeyFormat.matches(rawPublicKey, pubky)) {
                            "Ring credential does not match '${redacted(pubky)}'"
                        }
                        keychain.upsertString(Keychain.Key.SHARED_PUBKY_SOURCE.name, reference)
                        signInOrSignUpAdoptedIdentity(secretKeyHex, rawPublicKey)
                        sessionInstalled = true

                        val prefixedPublicKey = rawPublicKey.ensurePubkyPrefix()
                        clearProfileIfIdentityChanged(prefixedPublicKey)
                        _publicKey.update { prefixedPublicKey }
                        notifyBackupStateChanged()
                        Logger.info("Adopted ring identity for '${redacted(rawPublicKey)}'", context = TAG)
                        prefixedPublicKey
                    }

                    val handoffProfile = knownProfile()
                        ?.takeIf { PubkyPublicKeyFormat.matches(it.publicKey, publicKey) }
                        ?.copy(publicKey = publicKey)
                    if (handoffProfile == null) loadProfile()
                    loadContacts()

                    initializeMutex.withLock {
                        check(_publicKey.value == publicKey) { "Adopted Pubky identity changed before setup completed" }
                        handoffProfile?.let { profile ->
                            setProfile(profile)
                            runSuspendCatching { cacheMetadata(profile) }
                                .onFailure { Logger.warn("Failed to cache adopted profile", it, context = TAG) }
                        }
                        val hasProfile = handoffProfile != null || _profile.value?.publicKey == publicKey
                        runSuspendCatching { settingsStore.setPubkyProfileSetupPending(!hasProfile) }
                            .onFailure { Logger.warn("Failed to save pending profile setup", it, context = TAG) }
                        completed = true
                        hasProfile
                    }
                }.onFailure { clearAdoptedSourceIfMatches(reference) }
            } catch (error: CancellationException) {
                if (!completed) rollBackInterruptedAdoption(reference, sessionInstalled)
                throw error
            }
        }
    }

    private suspend fun rollBackInterruptedAdoption(reference: String, sessionInstalled: Boolean) {
        withContext(NonCancellable + ioDispatcher) {
            initializeMutex.withLock {
                if (keychain.loadString(Keychain.Key.SHARED_PUBKY_SOURCE.name) != reference) return@withLock
                if (sessionInstalled && !discardAbandonedSession()) clearLocalState()
                clearAdoptedSourceIfMatches(reference)
            }
        }
    }

    private suspend fun clearAdoptedSourceIfMatches(reference: String) {
        runSuspendCatching {
            if (keychain.loadString(Keychain.Key.SHARED_PUBKY_SOURCE.name) == reference) {
                keychain.delete(Keychain.Key.SHARED_PUBKY_SOURCE.name)
            }
        }.onFailure { Logger.warn("Failed to clear adopted ring source", it, context = TAG) }
    }

    private suspend fun signInOrSignUpAdoptedIdentity(secretKeyHex: String, publicKey: String) {
        val previousSession = keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)
        var completed = false
        try {
            runSuspendCatching { pubkyService.signIn(secretKeyHex) }.getOrElse {
                val hasIdentityRecord = runSuspendCatching { pubkyService.hasIdentityRecord(publicKey) }
                    .onFailure { Logger.warn("Failed to check ring identity record", it, context = TAG) }
                    .getOrNull()
                if (hasIdentityRecord != false || hasNewSession(previousSession)) throw it
                Logger.warn("Signing up ring identity without a published record", it, context = TAG)
                val homegate = fetchHomegateSignupCode()
                pubkyService.signUp(secretKeyHex, homegate.homeserverPubky, homegate.signupCode)
            }
            completed = true
        } finally {
            if (!completed) {
                withContext(NonCancellable + ioDispatcher) {
                    if (hasNewSession(previousSession)) discardAbandonedSession()
                }
            }
        }
    }

    private suspend fun hasNewSession(previousSession: String?): Boolean = runSuspendCatching {
        val currentSession = keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)
        currentSession != null && currentSession != previousSession
    }.onFailure {
        Logger.warn("Failed to identify incomplete adopted Pubky session", it, context = TAG)
    }.getOrDefault(false)

    private suspend fun clearProfileIfIdentityChanged(publicKey: String) {
        if (_publicKey.value == publicKey) return
        clearPubkyImageDiskCache()
        _contactsLoadVersion.update { 0L }
        setProfile(null)
        _contacts.update { emptyList() }
        clearSessionContactProfiles()
        clearPendingImport()
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
        loadProfileMutex.lock()
        if (_publicKey.value != pk) {
            loadProfileMutex.unlock()
            return
        }
        val writeGeneration = profileWriteGeneration.get()
        val isCurrentLoad = { _publicKey.value == pk && profileWriteGeneration.get() == writeGeneration }

        _isLoadingProfile.update { true }
        try {
            runSuspendCatching {
                withContext(ioDispatcher) {
                    resolveContactProfile(pk, retry = true).getOrThrow()
                        ?: throw AppError("Profile not found")
                }
            }.onSuccess { loadedProfile ->
                var isCurrent = false
                _profile.update {
                    isCurrent = isCurrentLoad()
                    if (isCurrent) loadedProfile else it
                }
                if (!isCurrent) {
                    Logger.debug("Skipped stale profile load for '${redacted(pk)}'", context = TAG)
                    return@onSuccess
                }
                cacheMetadata(loadedProfile, isCurrentLoad)
            }.onFailure {
                Logger.error("Failed to load profile", it, context = TAG)
            }
        } finally {
            _isLoadingProfile.update { false }
            loadProfileMutex.unlock()
        }
    }

    suspend fun fetchRemoteProfile(publicKey: String): Result<PubkyProfile?> =
        resolveContactProfile(publicKey, retry = true)

    /**
     * Resolves [publicKey]'s profile once, without retrying, for display only. A null or failed result is not proof
     * that the profile does not exist.
     */
    suspend fun fetchDisplayProfile(publicKey: String): Result<PubkyProfile?> =
        resolveContactProfile(publicKey, retry = false)
            .onFailure { Logger.warn("Failed to fetch display profile '${redacted(publicKey)}'", it, context = TAG) }

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
    ): Result<Unit> {
        val result = initializeMutex.withLock {
            if (settingsStore.isPubkyProfileSetupPending.first() && _publicKey.value != null) {
                return@withLock runSuspendCatching {
                    withContext(ioDispatcher) {
                        val publicKey = requireNotNull(_publicKey.value) { "No active Pubky session" }
                        val imageUrl = publishIdentityProfile(name, bio, links, tags, avatarBytes)
                        finishIdentityCreation(publicKey, name, bio, links, tags, imageUrl)
                    }
                }
            }

            var shouldRevokeSessionOnFailure = false
            try {
                val creationResult = runSuspendCatching {
                    withContext(ioDispatcher) {
                        settingsStore.setPubkyProfileSetupPending(false)
                        val publicKeyZ32 = _publicKey.value
                            ?: createLocalIdentitySession { shouldRevokeSessionOnFailure = true }

                        val imageUrl = publishIdentityProfile(name, bio, links, tags, avatarBytes)
                        shouldRevokeSessionOnFailure = false
                        finishIdentityCreation(publicKeyZ32, name, bio, links, tags, imageUrl)
                    }
                }
                if (creationResult.isFailure) revokeIncompleteIdentitySessionIfNeeded(shouldRevokeSessionOnFailure)
                creationResult
            } catch (error: CancellationException) {
                revokeIncompleteIdentitySessionIfNeeded(shouldRevokeSessionOnFailure)
                throw error
            }
        }

        val publicKey = result.getOrElse { return Result.failure(it) }
        return runSuspendCatching {
            if (_publicKey.value != publicKey) return@runSuspendCatching
            loadProfile()
            loadContacts()
        }
    }

    private suspend fun createLocalIdentitySession(markSessionCreated: () -> Unit): String {
        keychain.delete(Keychain.Key.SHARED_PUBKY_SOURCE.name)
        val storedSecretKeyHex = keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)
        if (!storedSecretKeyHex.isNullOrEmpty()) {
            pubkyService.signIn(storedSecretKeyHex)
            return pubkyService.publicKeyFromSecret(storedSecretKeyHex).ensurePubkyPrefix()
        }

        val (publicKey, secretKeyHex) = deriveKeys().getOrThrow()
        val signupDetails: Pair<String, String?> = Env.e2eHomeserverPubky?.let { it to null }
            ?: fetchHomegateSignupCode().let { it.homeserverPubky to it.signupCode }

        markSessionCreated()
        runSuspendCatching {
            pubkyService.signUp(secretKeyHex, signupDetails.first, signupDetails.second)
        }.getOrElse {
            Logger.warn("Retrying sign in after sign up failed", it, context = TAG)
            pubkyService.signIn(secretKeyHex)
        }
        return publicKey
    }

    private suspend fun publishIdentityProfile(
        name: String,
        bio: String,
        links: List<PubkyProfileLink>,
        tags: List<String>,
        avatarBytes: ByteArray?,
    ): String? {
        val imageUrl = avatarBytes?.let { uploadAvatar(it).getOrNull() }
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
    ): String {
        val createdProfile = PubkyProfile(
            publicKey = publicKey,
            name = name,
            bio = bio,
            imageUrl = imageUrl,
            links = links,
            tags = tags,
            status = null,
        )
        _publicKey.update { publicKey }
        setProfile(createdProfile)
        cacheMetadata(createdProfile)
        settingsStore.setPubkyProfileSetupPending(false)
        notifyBackupStateChanged()
        Logger.info("Created identity for '${redacted(publicKey)}'", context = TAG)
        return publicKey
    }

    private suspend fun revokeIncompleteIdentitySessionIfNeeded(shouldRevokeSession: Boolean) {
        if (!shouldRevokeSession) return
        discardAbandonedSession()
    }

    private suspend fun discardAbandonedSession(): Boolean {
        val revocationError = runSuspendCatching {
            withContext(NonCancellable + ioDispatcher) {
                pubkyService.signOut()
            }
        }.exceptionOrNull() ?: return false

        Logger.warn("Failed to revoke abandoned Pubky session", revocationError, context = TAG)
        var clearedLocalState = false
        runSuspendCatching {
            withContext(NonCancellable + ioDispatcher) {
                pubkyService.forgetSessionAccess()
            }
        }.onFailure {
            Logger.warn("Failed to forget abandoned Pubky session access", it, context = TAG)
            withContext(NonCancellable + ioDispatcher) {
                clearLocalState(publicPaykitCleanupPending = true)
            }
            clearedLocalState = true
        }
        return clearedLocalState
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
            setProfile(profile)
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
            profileWriteGeneration.incrementAndGet()
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
        updateContacts { emptyList() }
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

    /**
     * Publishes the saved contact records without waiting for any profile lookup. A record without a profile override
     * or a stored Paykit profile shows the profile resolved earlier in this session, else its label, and is then
     * refreshed in the background on the bulk read lane, its row updating once its lookup finishes.
     */
    suspend fun loadContacts() {
        val pk = _publicKey.value ?: return
        loadContactsMutex.lock()
        if (_publicKey.value != pk) {
            loadContactsMutex.unlock()
            return
        }

        _isLoadingContacts.update { true }
        var shouldMarkLoadCompleted = false
        var contactsToRefresh = emptyList<SavedContact>()
        try {
            var reload: Boolean
            do {
                val revision = synchronized(contactsLock) { contactsRevision }
                reload = false
                runSuspendCatching {
                    withContext(ioDispatcher) {
                        val records = pubkyService.contactRecords()
                        val overrides = pubkyStore.data.first().contactProfileOverrides
                        records.map { savedContact(it, overrides) }
                    }
                }.onSuccess { savedContacts ->
                    if (_publicKey.value != pk) {
                        Logger.debug("Skipped stale contacts load for '${redacted(pk)}'", context = TAG)
                        return@onSuccess
                    }
                    synchronized(contactsLock) {
                        if (contactsRevision != revision) {
                            reload = true
                            return@onSuccess
                        }
                        val loadedContacts = savedContacts.map { it.withSessionProfile(pk) }
                        _contacts.update { loadedContacts.map { it.profile }.sortedBy { it.name.lowercase() } }
                        contactsToRefresh = loadedContacts.filter { it.needsRefresh }
                    }
                    markContactsLoaded()
                    shouldMarkLoadCompleted = true
                }.onFailure {
                    shouldMarkLoadCompleted = _publicKey.value == pk
                    Logger.error("Failed to load contacts", it, context = TAG)
                }
            } while (reload && _publicKey.value == pk)
        } finally {
            _isLoadingContacts.update { false }
            loadContactsMutex.unlock()
            if (shouldMarkLoadCompleted && _publicKey.value == pk) markContactsLoadCompleted()
        }
        refreshContactProfiles(pk, contactsToRefresh)
    }

    /**
     * Resolves a saved contact's profile on the interactive read lane while its row still shows only its label
     * because the background refresh of [loadContacts] has not finished looking it up, so a screen showing that
     * contact does not wait behind bulk reads and an edit made there keeps the contact's avatar, bio and links. The
     * lookup takes the contact over from the refresh, which stops its own lookup and never applies a result for it,
     * and a caller arriving meanwhile waits for the same lookup. Only a sign-out or an identity change stops it, not a
     * later refresh. It returns at once for any other row; when the lookup fails, the row keeps its label.
     */
    suspend fun resolvePendingContactProfile(publicKey: String) {
        val owner = _publicKey.value ?: return
        val key = publicKey.ensurePubkyPrefix()
        val lookup = synchronized(contactsLock) {
            contactScreenLookups[key]?.takeUnless { it.isCompleted }
                ?: contactProfileRefresh?.takeOver(owner, key) { it.profile in _contacts.value }?.let { contact ->
                    scope.launch(start = CoroutineStart.LAZY) {
                        refreshContactProfile(owner, contact, PaykitReadLane.Interactive)
                    }.also { contactScreenLookups[key] = it }
                }
        } ?: return
        lookup.join()
    }

    suspend fun fetchContactProfile(publicKey: String): Result<PubkyProfile> {
        val prefixedKey = runCatching { requireCanonicalAddableContactPublicKey(publicKey) }
            .getOrElse { return Result.failure(it) }
        return resolveContactProfile(prefixedKey, retry = true)
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
            val prefixedKey = requireCanonicalAddableContactPublicKey(
                publicKey = publicKey,
                allowExisting = existingProfile != null,
            )
            val profile = existingProfile?.copy(publicKey = prefixedKey)
                ?: resolveContactProfile(prefixedKey, retry = true).getOrThrow()
                ?: PubkyProfile.placeholder(prefixedKey)
            pubkyService.saveContact(
                prefixedKey,
                profile.name,
                relevantReceiverPaths(prefixedKey),
                restorePrivateConnection = true,
            )
            _publicKey.value?.let { cacheSessionContactProfiles(it, listOf(profile)) }
            updateContacts { current ->
                (current.filter { it.publicKey != prefixedKey } + profile)
                    .sortedBy { it.name.lowercase() }
            }
            markContactsLoaded()
            Logger.info("Added contact '${redacted(prefixedKey)}'", context = TAG)
        }
    }

    suspend fun refreshContactReceiverPaths(publicKey: String): Result<Unit> = runSuspendCatching {
        withContext(ioDispatcher) {
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
            updateContacts { current ->
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
            updateContacts { current -> current.filter { it.publicKey != prefixedKey } }
            markContactsLoaded()
            Logger.info("Removed contact '${redacted(prefixedKey)}'", context = TAG)
        }
    }.recoverCatching {
        if (it.containsActiveSubscriptionError()) throw PubkyContactError.ActiveSubscription
        throw it
    }

    /**
     * Saves [profiles], the follows [prepareImport] resolved, without looking them up again; receiver discovery runs
     * during contact refresh. A contact already saved is skipped, and a failed save keeps the others and fails the
     * import, so a retry saves only the missing contacts. The import runs in the repository scope, so it finishes even
     * when the caller is cancelled, stops saving once the identity changes, and clears the pending import once it
     * succeeds. A success bumps [contactImportVersion] and a failure sets [contactImportFailure], so both reach the app
     * after the import screens are gone.
     */
    suspend fun importContacts(profiles: List<PubkyProfile>): Result<Unit> =
        scope.async(start = CoroutineStart.UNDISPATCHED) {
            activeContactImports.update { it + 1 }
            try {
                saveImportedContacts(profiles)
                    .onSuccess { _contactImportVersion.update { it + 1 } }
                    .onFailure { error ->
                        Logger.error("Failed to import contacts", error, context = TAG)
                        _contactImportFailure.update { error }
                    }
            } finally {
                activeContactImports.update { it - 1 }
            }
        }.await()

    private suspend fun saveImportedContacts(profiles: List<PubkyProfile>): Result<Unit> = runSuspendCatching {
        withContext(ioDispatcher) {
            val owner = requireNotNull(_publicKey.value) { "Not authenticated" }
            val imported = mutableListOf<PubkyProfile>()
            val existing = _contacts.value.map { it.publicKey }.toMutableSet()
            var firstError: Throwable? = null
            for (profile in profiles.distinctBy { it.publicKey }) {
                if (profile.publicKey in existing) continue
                check(_publicKey.value == owner) { "Pubky identity changed while importing contacts" }
                runSuspendCatching {
                    // The preview already resolved this profile. Receiver discovery runs during contact refresh.
                    pubkyService.saveContact(
                        profile.publicKey,
                        profile.name,
                        restorePrivateConnection = true,
                        expectedIdentity = owner,
                    )
                    imported.add(profile)
                    existing.add(profile.publicKey)
                }.onFailure {
                    firstError = firstError ?: it
                    Logger.warn("Failed to import contact '${redacted(profile.publicKey)}'", it, context = TAG)
                }
            }
            synchronized(contactsLock) {
                check(_publicKey.value == owner) { "Pubky identity changed while importing contacts" }
                cacheSessionContactProfiles(owner, imported)
                updateContacts { current ->
                    val currentKeys = current.map { it.publicKey }.toSet()
                    (current + imported.filter { it.publicKey !in currentKeys })
                        .sortedBy { it.name.lowercase() }
                }
            }
            markContactsLoaded()
            Logger.info("Imported '${imported.size}' contacts", context = TAG)
            firstError?.let { throw it }
            clearPendingImport()
        }
    }

    /**
     * Looks up the signed-in identity's follows for the import overview, leaving out its own key, resolving each
     * follow's profile once and keeping a follow it cannot resolve as a placeholder. Its lookups use the interactive
     * read lane, since the user waits on the Pubky Ring choice row until it returns.
     */
    suspend fun prepareImport(): Result<Unit> = runSuspendCatching {
        clearPendingImport()
        val pk = requireNotNull(_publicKey.value) { "Not authenticated" }
        withContext(ioDispatcher) {
            val canonicalOwnKey = PubkyPublicKeyFormat.canonicalized(pk)
            val contactKeys = pubkyService.getContacts(pk).filterNot {
                canonicalOwnKey != null && PubkyPublicKeyFormat.canonicalized(it) == canonicalOwnKey
            }
            Logger.debug("Discovered '${contactKeys.size}' contacts for import", context = TAG)

            val resolvedFollows = coroutineScope {
                contactKeys.map { contactPk ->
                    val prefixedKey = contactPk.ensurePubkyPrefix()
                    async {
                        prefixedKey to resolveContactProfile(
                            prefixedKey,
                            retry = false,
                            lane = PaykitReadLane.Interactive,
                        ).onFailure {
                            Logger.warn("Failed to resolve follow '${redacted(prefixedKey)}'", it, context = TAG)
                        }.getOrNull()
                    }
                }.awaitAll()
            }
            val contacts = resolvedFollows.map { (key, profile) -> profile ?: PubkyProfile.placeholder(key) }
                .sortedBy { it.name.lowercase() }

            val ownProfile = _profile.value?.takeIf { PubkyPublicKeyFormat.matches(it.publicKey, pk) }
                ?: resolveContactProfile(pk, retry = true).getOrNull()
            check(_publicKey.value == pk) { "Pubky identity changed while preparing import" }

            cacheSessionContactProfiles(pk, resolvedFollows.mapNotNull { it.second })
            _pendingImportProfile.update { ownProfile }
            _pendingImportContacts.update { contacts }
        }
    }

    suspend fun clearPendingImport() = withContext(ioDispatcher) {
        _pendingImportProfile.update { null }
        _pendingImportContacts.update { emptyList() }
    }

    /**
     * Drops the pending import when the user leaves the import screens, unless an import is running: that import
     * clears it once it succeeds and keeps it when it fails, so the follows it could not save stay pending.
     */
    suspend fun discardPendingImport() = withContext(ioDispatcher) {
        if (activeContactImports.value > 0) return@withContext
        clearPendingImport()
    }

    fun clearContactImportFailure() {
        _contactImportFailure.update { null }
    }

    // endregion

    // region Auth approval

    suspend fun hasSecretKey(): Boolean = runSuspendCatching {
        val publicKey = _publicKey.value ?: return@runSuspendCatching false
        managedSecretKeyFor(publicKey) != null
    }.getOrDefault(false)

    suspend fun hasStoredSecretKey(): Boolean = withContext(ioDispatcher) {
        keychain.exists(Keychain.Key.PUBKY_SECRET_KEY.name)
    }

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

    suspend fun approveSignupAuth(request: PubkyAuthRequest): Result<Unit> = initializeMutex.withLock {
        runSuspendCatching {
            withContext(ioDispatcher) {
                require(request.isSignup) { "Not a Pubky signup request" }
                if (hasIdentity()) throw PubkyAlreadySignedInError

                val (publicKey, secretKeyHex) = deriveKeys().getOrThrow()
                if (hasIdentity()) throw PubkyAlreadySignedInError

                keychain.delete(Keychain.Key.SHARED_PUBKY_SOURCE.name)
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
            val secretKeyHex = requireNotNull(activeSecretKeyHex()) {
                "No secret key available — use Ring to manage authorizations"
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
            val secretKeyHex = requireNotNull(activeSecretKeyHex()) {
                "No secret key available — use Ring to manage authorizations"
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
            if (keychain.exists(Keychain.Key.SHARED_PUBKY_SOURCE.name)) return@withContext null
            if (keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name).isNullOrEmpty()) return@withContext null

            PubkySessionBackupV1(kind = PubkySessionBackupKind.LocalSeed)
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
                runSuspendCatching { pubkyService.forgetSessionAccess() }
                    .onFailure {
                        Logger.warn(
                            "Failed to forget existing Pubky session before restore",
                            it,
                            context = TAG,
                        )
                    }
                clearAuthenticatedState()
                runCatching { keychain.delete(Keychain.Key.PAYKIT_SESSION.name) }
                runCatching { keychain.delete(Keychain.Key.PUBKY_SECRET_KEY.name) }
                runCatching { keychain.delete(Keychain.Key.SHARED_PUBKY_SOURCE.name) }

                when (backup?.kind) {
                    null -> Unit

                    PubkySessionBackupKind.LocalSeed -> {
                        val secretKeyHex = deriveLocalSecretKeyFromWalletSeed()
                        keychain.upsertString(Keychain.Key.PUBKY_SECRET_KEY.name, secretKeyHex)
                        pubkyService.signIn(secretKeyHex)
                        _publicKey.update { pubkyService.publicKeyFromSecret(secretKeyHex).ensurePubkyPrefix() }
                    }

                    PubkySessionBackupKind.ExternalSession -> Unit
                }

                notifyBackupStateChanged()
            }
        }
    }

    suspend fun restoreContactProfileOverrides(
        overrides: Map<String, PubkyProfileData>?,
    ): Result<Unit> = runSuspendCatching {
        withContext(ioDispatcher) {
            val ownerPublicKey = _publicKey.value
            pubkyStore.update {
                it.copy(
                    ownerPublicKey = ownerPublicKey,
                    contactProfileOverrides = overrides ?: emptyMap(),
                )
            }
            notifyBackupStateChanged()
        }
    }

    suspend fun refreshSessionIfPossible(): Result<Boolean> = runSuspendCatching {
        withContext(ioDispatcher) {
            val storedSecretKeyHex = activeSecretKeyHex() ?: return@withContext false

            pubkyService.signIn(storedSecretKeyHex)
            val publicKey = pubkyService.publicKeyFromSecret(storedSecretKeyHex).ensurePubkyPrefix()

            notifyBackupStateChanged()
            _publicKey.update { publicKey }

            true
        }
    }

    // endregion

    // region Sign out

    suspend fun signOut(): Result<Unit> = withContext(NonCancellable + ioDispatcher) {
        initializeMutex.withLock {
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
                return@withLock result
            }

            clearLocalState(publicPaykitCleanupPending = endpointCleanupResult.isFailure && hadPaykitState)
            result
        }
    }

    suspend fun wipeLocalState() = initializeMutex.withLock {
        runSuspendCatching {
            withContext(ioDispatcher) { pubkyService.forgetSessionAccess() }
        }.onFailure {
            Logger.warn("Failed to forget local Pubky session access", it, context = TAG)
        }
        clearLocalState()
    }

    // endregion

    // region Private helpers

    private fun evictPubkyImages() {
        imageLoader.memoryCache?.let { cache ->
            cache.keys.filter { it.key.startsWith(PUBKY_SCHEME) }.forEach { cache.remove(it) }
        }
        clearPubkyImageDiskCache()
    }

    private fun clearPubkyImageDiskCache() {
        imageCacheEpoch.advance()
        runCatching { imageLoader.diskCache?.clear() }
            .onFailure { Logger.warn("Failed to clear pubky image disk cache", it, context = TAG) }
    }

    private fun savedContact(record: ContactRecord, overrides: Map<String, PubkyProfileData>): SavedContact {
        val prefixedKey = record.publicKey.ensurePubkyPrefix()
        overrides[prefixedKey]?.let {
            return SavedContact(it.toPubkyProfile(prefixedKey), record.label, needsRefresh = false)
        }
        record.profile?.let {
            val profile = PubkyProfile.fromPaykitProfile(prefixedKey, it).withNameFallback(record.label)
            return SavedContact(profile, record.label, needsRefresh = false)
        }
        val profile = PubkyProfile.forDisplay(publicKey = prefixedKey, name = record.label, imageUrl = null)
        return SavedContact(profile, record.label, needsRefresh = true, showsLabelOnly = true)
    }

    private fun SavedContact.withSessionProfile(owner: String): SavedContact {
        if (!needsRefresh) return this
        val cached = sessionContactProfile(owner, profile.publicKey) ?: return this
        return copy(profile = cached.withNameFallback(label), showsLabelOnly = false)
    }

    private fun refreshContactProfiles(owner: String, contacts: List<SavedContact>) {
        if (contacts.isEmpty()) return
        val refresh = ContactProfileRefresh(owner, contacts, scope) {
            refreshContactProfile(owner, it, PaykitReadLane.Bulk)
        }
        val replaced = synchronized(contactsLock) {
            val active = contactProfileRefresh
            if (_publicKey.value != owner || active?.covers(refresh) == true) {
                refresh.job.cancel()
                return
            }
            contactProfileRefresh = refresh
            active
        }
        replaced?.job?.cancel()
        refresh.start()
    }

    private suspend fun refreshContactProfile(owner: String, contact: SavedContact, lane: PaykitReadLane) {
        val lookup = currentCoroutineContext().job
        val publicKey = contact.profile.publicKey
        val resolved = resolveContactProfile(publicKey, retry = false, lane = lane)
            .onFailure { Logger.warn("Failed to resolve contact '${redacted(publicKey)}'", it, context = TAG) }
            .getOrNull() ?: return
        val refreshed = resolved.withNameFallback(contact.label)
        synchronized(contactsLock) {
            if (_publicKey.value != owner || !lookup.isActive) return
            cacheSessionContactProfiles(owner, listOf(resolved))
            if (contact.profile !in _contacts.value) return
            _contacts.update { current ->
                current.map { if (it == contact.profile) refreshed else it }.sortedBy { it.name.lowercase() }
            }
        }
    }

    private fun sessionContactProfile(owner: String, publicKey: String): PubkyProfile? = synchronized(contactsLock) {
        sessionContactProfiles[publicKey].takeIf { sessionContactProfilesOwner == owner }
    }

    private fun cacheSessionContactProfiles(owner: String, profiles: Collection<PubkyProfile>) {
        synchronized(contactsLock) {
            if (_publicKey.value != owner) return
            if (sessionContactProfilesOwner != owner) {
                sessionContactProfiles.clear()
                sessionContactProfilesOwner = owner
            }
            profiles.filter { it != PubkyProfile.placeholder(it.publicKey) }
                .forEach { sessionContactProfiles[it.publicKey] = it }
        }
    }

    private fun clearSessionContactProfiles() {
        synchronized(contactsLock) {
            contactProfileRefresh?.job?.cancel()
            contactProfileRefresh = null
            contactScreenLookups.values.forEach { it.cancel() }
            contactScreenLookups.clear()
            sessionContactProfiles.clear()
            sessionContactProfilesOwner = null
        }
    }

    private suspend fun resolveContactProfile(
        publicKey: String,
        retry: Boolean,
        lane: PaykitReadLane = PaykitReadLane.Interactive,
    ): Result<PubkyProfile?> = runSuspendCatching {
        withContext(ioDispatcher) {
            val prefixedKey = publicKey.ensurePubkyPrefix()
            if (!retry) return@withContext resolveProfileOnce(prefixedKey, lane)
            var lastError: Throwable? = null

            repeat(2) { attempt ->
                val result = runSuspendCatching { resolveProfileOnce(prefixedKey, lane) }
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

    private suspend fun resolveProfileOnce(prefixedKey: String, lane: PaykitReadLane): PubkyProfile? =
        pubkyService.resolveContactProfile(
            publicKey = prefixedKey,
            allowPubkyProfileFallback = true,
            lane = lane,
        )?.let(::profileFromResolution)

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
        val ownerPublicKey = requireNotNull(_publicKey.value) { "Pubky identity unavailable" }
        pubkyStore.update { data ->
            data.copy(
                ownerPublicKey = ownerPublicKey,
                contactProfileOverrides = data.contactProfileOverrides + (prefixedKey to profile.toProfileData()),
            )
        }
        notifyBackupStateChanged()
    }

    private suspend fun removeContactProfileOverride(publicKey: String) {
        val prefixedKey = publicKey.ensurePubkyPrefix()
        val ownerPublicKey = requireNotNull(_publicKey.value) { "Pubky identity unavailable" }
        pubkyStore.update { data ->
            data.copy(
                ownerPublicKey = ownerPublicKey,
                contactProfileOverrides = data.contactProfileOverrides - prefixedKey,
            )
        }
        notifyBackupStateChanged()
    }

    private suspend fun cacheMetadata(profile: PubkyProfile, isCurrent: () -> Boolean = { true }) {
        pubkyStore.update {
            if (!isCurrent()) return@update it
            it.copy(
                ownerPublicKey = profile.publicKey,
                cachedProfileOwner = profile.publicKey,
                cachedName = profile.name,
                cachedImageUri = profile.imageUrl,
            )
        }
    }

    private suspend fun managedSecretKeyFor(publicKey: String): String? = withContext(ioDispatcher) {
        val bareKey = publicKey.removePrefix(PUBKY_PREFIX)
        val secretKeyHex = keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)
            ?: return@withContext bareKey
                .takeIf {
                    keychain.loadString(Keychain.Key.SHARED_PUBKY_SOURCE.name) ==
                        "${SharedPubkyContract.RING_SOURCE_PREFIX}$bareKey"
                }
                ?.let { sharedPubkyClient.ringCredential(it).getOrNull() }

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

    private suspend fun adoptedSecretKeyHex(): String? {
        val pubky = runCatching { keychain.loadString(Keychain.Key.SHARED_PUBKY_SOURCE.name) }.getOrNull()
            ?.substringAfter(SharedPubkyContract.RING_SOURCE_PREFIX, "")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return sharedPubkyClient.ringCredential(pubky)
            .onFailure { Logger.warn("Failed to read adopted ring credential", it, context = TAG) }
            .getOrNull()
    }

    private suspend fun activeSecretKeyHex(): String? {
        val publicKey = _publicKey.value ?: return keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)
        return managedSecretKeyFor(publicKey)
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

    private fun setProfile(profile: PubkyProfile?) {
        profileWriteGeneration.incrementAndGet()
        _profile.update { profile }
    }

    private suspend fun clearAuthenticatedState(
        clearCachedProfile: Boolean = true,
        clearRestorationFailure: Boolean = true,
    ) = withContext(ioDispatcher) {
        if (clearCachedProfile) {
            evictPubkyImages()
            profileWriteGeneration.incrementAndGet()
            runSuspendCatching { pubkyStore.reset() }
        }
        _publicKey.update { null }
        setProfile(null)
        updateContacts { emptyList() }
        clearSessionContactProfiles()
        _contactsLoadVersion.update { 0L }
        _contactsLoadCompletionVersion.update { 0L }
        clearPendingImport()
        if (clearRestorationFailure) _sessionRestorationFailed.update { false }
    }

    private fun updateContacts(transform: (List<PubkyProfile>) -> List<PubkyProfile>) {
        synchronized(contactsLock) {
            contactsRevision++
            _contacts.update(transform)
        }
    }

    private fun markContactsLoaded() {
        _contactsLoadVersion.update { it + 1 }
    }

    private fun markContactsLoadCompleted() {
        _contactsLoadCompletionVersion.update { it + 1 }
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
        return requireValidAddableContactPublicKey(prefixedKey, allowExisting)
    }

    private fun requireCanonicalAddableContactPublicKey(
        publicKey: String,
        allowExisting: Boolean = false,
    ): String {
        val prefixedKey = PubkyPublicKeyFormat.canonicalized(publicKey)
        return requireValidAddableContactPublicKey(prefixedKey, allowExisting)
    }

    private fun requireValidAddableContactPublicKey(prefixedKey: String?, allowExisting: Boolean): String {
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

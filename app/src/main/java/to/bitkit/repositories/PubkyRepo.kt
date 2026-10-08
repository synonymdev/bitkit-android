package to.bitkit.repositories

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.ImageLoader
import com.synonym.paykit.ContactRecord
import com.synonym.paykit.ContactUpdate
import com.synonym.paykit.ProfileResolution
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
import kotlinx.coroutines.joinAll
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
import to.bitkit.ext.isPaykitTemporarilyUnavailable
import to.bitkit.ext.nowMs
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.HomegateResponse
import to.bitkit.models.PubkyAuthClaim
import to.bitkit.models.PubkyAuthRequest
import to.bitkit.models.PubkyAuthRequestError
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyProfileData
import to.bitkit.models.PubkyProfileLink
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.models.PubkySessionBackupKind
import to.bitkit.models.PubkySessionBackupV1
import to.bitkit.services.PaykitReadLane
import to.bitkit.services.PaykitReadTimeoutError
import to.bitkit.services.PubkyService
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

sealed class PubkyContactError(message: String) : AppError(message) {
    data object AlreadyExists : PubkyContactError("Contact already exists")
    data object CannotAddSelf : PubkyContactError("Cannot add your own pubky as a contact")
    data object InvalidFormat : PubkyContactError("Invalid pubky key format")
    data object ActiveSubscription : PubkyContactError("Contact has an active subscription")
    data object SignInChanged : PubkyContactError("Pubky sign-in changed while saving the contact")
}

/**
 * One sign-in of a Pubky identity. Work the user starts in it, such as a contact edit that first waits for a profile
 * lookup, checks [PubkyRepo.isCurrent] before it writes, so it stops once that identity signs out or the next sign-in
 * starts. Every sign-in starts a new one, even when it signs in the same identity again. Adopting a Ring identity ends
 * the current one before it installs the adopted session, so an adoption that fails at sign-in ends it too. Restoring
 * or refreshing the session of the identity already signed in keeps it. It holds no secret.
 */
class PubkySignIn internal constructor(val publicKey: String, internal val generation: Long)

private fun Throwable.hasCause(error: PubkyContactError): Boolean =
    generateSequence(this) { it.cause }.any { it == error }

private fun Throwable.isPaykitReadTimeout(): Boolean =
    generateSequence(this) { it.cause }.any { it is PaykitReadTimeoutError }

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

@OptIn(ExperimentalTime::class)
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
    private val clock: Clock,
) {
    companion object {
        private const val TAG = "PubkyRepo"
        private const val PUBKY_PREFIX = "pubky"
        private const val PUBKY_SCHEME = "pubky://"
        private const val AVATAR_MAX_SIZE = 400
        private const val AVATAR_QUALITY = 80

        /** Longest one follow's profile read in the import preview may run once it has a read slot. */
        internal val IMPORT_FOLLOW_LOOKUP_TIMEOUT = 10.seconds

        /** How long a contact profile resolved in this session is shown without being looked up again. */
        internal val CONTACT_PROFILE_FRESHNESS = 10.minutes

        /** Shortest time between two contact list updates of a background profile refresh. */
        internal val CONTACT_REFRESH_BATCH_WINDOW = 300.milliseconds

        /** Maximum automatic restore attempts during one foreground recovery window. */
        private const val DEFERRED_RESTORE_ATTEMPTS = 8

        /** Delay after a deferred restore before another attempt may begin. */
        private val DEFERRED_RESTORE_INTERVAL = 5.seconds
    }

    private val scope = appScope(ioDispatcher, TAG)
    private val serviceInitializeMutex = Mutex()
    private val initializeMutex = Mutex()
    private val deferredRestoreMutex = Mutex()
    private var deferredRestoreGeneration: Long? = null
    private val completedRestoreVersion = AtomicLong()
    private var completedRestoreSignInGeneration = -1L
    private val loadProfileMutex = Mutex()
    private val completedProfileLoadVersion = AtomicLong()
    private var completedProfileSignInGeneration = -1L
    private var completedProfileWriteGeneration = -1L
    private val loadContactsMutex = Mutex()
    private val contactsLock = Any()
    private var contactsRevision = 0L
    private val sessionContactProfiles = mutableMapOf<String, SessionContactProfile>()
    private var sessionContactProfilesOwner: String? = null
    private var contactProfileRefresh: ContactProfileRefresh? = null
    private val contactScreenLookups = mutableMapOf<String, Job>()
    private val adoptedSourceCheckMutex = Mutex()
    private val adoptionMutex = Mutex()
    private val profileWriteGeneration = AtomicLong(0L)
    private val signInGeneration = AtomicLong(0L)
    private val readOnlyProfileGeneration = AtomicLong(0L)
    private val readOnlyProfileLock = Any()
    private var isServiceInitialized = false

    private val _profile = MutableStateFlow<PubkyProfile?>(null)
    val profile: StateFlow<PubkyProfile?> = _profile.asStateFlow()

    private val _readOnlyProfile = MutableStateFlow<PubkyProfile?>(null)

    /** Public display data for the saved key, not an authenticated session or permission to edit. */
    val readOnlyProfile: StateFlow<PubkyProfile?> = _readOnlyProfile.asStateFlow()

    private val _publicKey = MutableStateFlow<String?>(null)
    val publicKey: StateFlow<String?> = _publicKey.asStateFlow()

    private val _isLoadingProfile = MutableStateFlow(false)
    val isLoadingProfile: StateFlow<Boolean> = _isLoadingProfile.asStateFlow()

    private val _isRestoringSession = MutableStateFlow(true)
    val isRestoringSession: StateFlow<Boolean> = _isRestoringSession.asStateFlow()

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

    val identityExists: StateFlow<Boolean?> = combine(
        _publicKey,
        _backupStateVersion,
        _identityRefreshVersion,
    ) { _, _, _ ->
        runSuspendCatching { hasIdentity() }
            .onFailure { Logger.warn("Failed to check saved identity", it, context = TAG) }
            .getOrDefault(true)
    }.stateIn(scope, SharingStarted.Eagerly, null)

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
        data object RestorationDeferred : InitResult
    }

    private data class SavedContact(
        val profile: PubkyProfile,
        val label: String?,
        val needsRefresh: Boolean,
        val showsLabelOnly: Boolean = false,
    )

    private class SessionContactProfile(val profile: PubkyProfile, private val resolvedAtMillis: Long) {
        fun isFresh(now: Long): Boolean =
            now - resolvedAtMillis in 0 until CONTACT_PROFILE_FRESHNESS.inWholeMilliseconds
    }

    private class RefreshedContact(val contact: SavedContact, val profile: PubkyProfile)

    private class ContactProfileRefresh(
        val owner: String,
        contacts: List<SavedContact>,
        scope: CoroutineScope,
        lookUp: suspend (ContactProfileRefresh, SavedContact) -> Unit,
        onFinished: (ContactProfileRefresh) -> Unit,
    ) {
        private val keys = contacts.mapTo(mutableSetOf()) { it.profile.publicKey }
        private val labelOnly = contacts.filter { it.showsLabelOnly }.associateBy { it.profile.publicKey }

        val job = SupervisorJob(scope.coroutineContext.job)
        val pendingResults = mutableMapOf<String, RefreshedContact>()
        var scheduledApply: Job? = null
        private val bulkLookups = contacts.associate {
            it.profile.publicKey to scope.launch(job, CoroutineStart.LAZY) { lookUp(this@ContactProfileRefresh, it) }
        }
        private val finish = scope.launch(job, CoroutineStart.LAZY) {
            bulkLookups.values.joinAll()
            onFinished(this@ContactProfileRefresh)
        }

        fun start() {
            bulkLookups.values.forEach { it.start() }
            finish.start()
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
        if (restored) loadIdentityData()
        checkAdoptedSource()
    }

    suspend fun restoreSessionIfNeeded() = withContext(ioDispatcher) {
        awaitInitialization()
        val restored = restoreSession()
        if (restored) loadIdentityData()
        restored
    }

    /** Retries a temporarily unavailable saved session while the caller remains active. */
    suspend fun retryDeferredSessionRestoration() = withContext(ioDispatcher) {
        deferredRestoreMutex.withLock {
            awaitInitialization()
            repeat(DEFERRED_RESTORE_ATTEMPTS) {
                val generation = initializeMutex.withLock {
                    deferredRestoreGeneration?.takeIf { it == signInGeneration.get() }
                } ?: return@withLock
                delay(DEFERRED_RESTORE_INTERVAL)
                if (restoreSession(expectedGeneration = generation)) {
                    loadIdentityData()
                    return@withLock
                }
            }
        }
    }

    /**
     * Waits for a usable saved identity, sharing any in-flight restore. Later calls can retry failures.
     * Restoration waits for active identity work and runs in the repository scope, so cancelling the caller does not
     * interrupt it. Profile and contact loading are not awaited.
     */
    suspend fun awaitIdentityReady(): PubkyIdentityReadiness = withContext(ioDispatcher) {
        awaitInitialization()
        if (_publicKey.value != null) return@withContext PubkyIdentityReadiness.Ready
        scope.async {
            val restored = restoreSession()
            if (restored) {
                scope.launch { loadIdentityData() }
            }
        }.await()
        when {
            _publicKey.value != null -> PubkyIdentityReadiness.Ready
            runSuspendCatching { hasIdentity() }.getOrNull() == false -> PubkyIdentityReadiness.Missing
            else -> PubkyIdentityReadiness.Unavailable
        }
    }

    private suspend fun loadIdentityData() {
        coroutineScope {
            launch { loadProfile() }
            launch { loadContacts() }
        }
    }

    private suspend fun restoreSession(expectedGeneration: Long? = null): Boolean {
        val version = completedRestoreVersion.get()
        return initializeMutex.withLock {
            if (expectedGeneration != null &&
                (expectedGeneration != signInGeneration.get() || expectedGeneration != deferredRestoreGeneration)
            ) {
                return@withLock false
            }
            if (completedRestoreVersion.get() != version &&
                completedRestoreSignInGeneration == signInGeneration.get()
            ) {
                return@withLock false
            }
            restoreSessionLocked().also {
                completedRestoreSignInGeneration = signInGeneration.get()
                completedRestoreVersion.incrementAndGet()
            }
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
        _isRestoringSession.update { true }
        try {
            val savedSession = runSuspendCatching { keychain.loadString(Keychain.Key.PAYKIT_SESSION.name) }
            val importedSession = runSuspendCatching {
                ensureServiceInitialized(savedSession.getOrNull())
            }.onFailure {
                Logger.error("Failed to initialize paykit", it, context = TAG)
                deferredRestoreGeneration = signInGeneration.get().takeIf { _ -> it.isPaykitTemporarilyUnavailable() }
                if (notifyFailure && it.isPaykitIdentityError() && hasSavedSession()) {
                    _sessionRestorationFailed.update { true }
                }
            }.getOrElse { return false }

            if (notifyFailure) _sessionRestorationFailed.update { false }
            val result = runSuspendCatching {
                val savedSessionSecret = savedSession.getOrThrow()
                val storedSecretKeyHex = keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)

                resolveSessionInitialization(
                    savedSessionSecret = savedSessionSecret,
                    storedSecretKeyHex = storedSecretKeyHex,
                    importedSession = importedSession,
                )
            }.onFailure {
                Logger.error("Failed to initialize paykit", it, context = TAG)
            }.getOrElse {
                if (it.isPaykitTemporarilyUnavailable()) {
                    InitResult.RestorationDeferred
                } else {
                    InitResult.RestorationFailed
                }
            }

            when (result) {
                is InitResult.NoSession -> {
                    clearAuthenticatedState()
                    Logger.debug("Found no saved paykit session", context = TAG)
                }
                is InitResult.Restored -> {
                    _sessionRestorationFailed.update { false }
                    continueSignIn(result.publicKey)
                    Logger.info("Restored paykit session for '${redacted(result.publicKey)}'", context = TAG)
                }
                InitResult.RestorationFailed, InitResult.RestorationDeferred -> {
                    clearAuthenticatedState(
                        clearCachedProfile = false,
                        clearRestorationFailure = notifyFailure,
                    )
                    if (notifyFailure) _sessionRestorationFailed.update { result == InitResult.RestorationFailed }
                }
            }
            deferredRestoreGeneration = signInGeneration.get().takeIf { result == InitResult.RestorationDeferred }
            initializationReady.complete(Unit)
            return result is InitResult.Restored
        } finally {
            _isRestoringSession.update { false }
        }
    }

    private fun hasSavedSession(): Boolean = runCatching {
        keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)
    }.getOrNull()?.isNotBlank() == true

    private suspend fun ensureServiceInitialized(savedSessionSecret: String? = null): Result<String>? =
        withContext(ioDispatcher) {
            serviceInitializeMutex.withLock {
                if (isServiceInitialized) return@withLock null
                val importedSession = if (savedSessionSecret.isNullOrEmpty()) {
                    pubkyService.initialize()
                    null
                } else {
                    pubkyService.initializeAndImportSession(savedSessionSecret)
                }
                isServiceInitialized = true
                importedSession
            }
        }

    private suspend fun resolveSessionInitialization(
        savedSessionSecret: String?,
        storedSecretKeyHex: String?,
        importedSession: Result<String>?,
    ): InitResult = withContext(ioDispatcher) {
        if (!savedSessionSecret.isNullOrEmpty()) {
            runSuspendCatching {
                val publicKey = if (importedSession != null) {
                    importedSession.getOrThrow()
                } else {
                    pubkyService.importSession(savedSessionSecret)
                }
                InitResult.Restored(publicKey.ensurePubkyPrefix())
            }.getOrElse {
                if (it.isPaykitTemporarilyUnavailable()) {
                    Logger.warn("Deferred session restoration, keeping saved session", it, context = TAG)
                    return@getOrElse InitResult.RestorationDeferred
                }
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
                if (it.isPaykitTemporarilyUnavailable()) {
                    InitResult.RestorationDeferred
                } else {
                    InitResult.RestorationFailed
                }
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
     * found, never the result of a failed or empty lookup. Like iOS, it ends the current [PubkySignIn] before it
     * installs the adopted session, so an adoption that fails at sign-in ends it too.
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
                        // Before the SDK installs the session, which can be of the same identity: a contact save of the
                        // ending sign-in that the SDK runs after it then fails the sign-in check under the SDK lock.
                        signInGeneration.incrementAndGet()
                        signInOrSignUpAdoptedIdentity(secretKeyHex, rawPublicKey)
                        sessionInstalled = true

                        val prefixedPublicKey = rawPublicKey.ensurePubkyPrefix()
                        clearProfileIfIdentityChanged(prefixedPublicKey)
                        startSignIn(prefixedPublicKey)
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

    /** The current sign-in, for work that must stop once it ends, or null when no identity is signed in. */
    fun currentSignIn(): PubkySignIn? {
        // The generation is read first, so a sign-out between the two reads leaves a sign-in that is already over.
        val generation = signInGeneration.get()
        val publicKey = _publicKey.value ?: return null
        return PubkySignIn(publicKey, generation)
    }

    /** Whether [signIn] has not ended: its identity has not signed out and no other sign-in has started since. */
    fun isCurrent(signIn: PubkySignIn): Boolean =
        signInGeneration.get() == signIn.generation && _publicKey.value == signIn.publicKey

    // endregion

    // region Profile loading

    suspend fun loadProfile() {
        val signIn = currentSignIn() ?: return loadReadOnlyProfile()
        val version = completedProfileLoadVersion.get()
        loadProfileMutex.withLock {
            if (!isCurrent(signIn)) return
            val writeGeneration = profileWriteGeneration.get()
            if (completedProfileLoadVersion.get() != version && completedProfileSignInGeneration == signIn.generation &&
                completedProfileWriteGeneration == writeGeneration
            ) {
                return
            }
            val isCurrentLoad = { isCurrent(signIn) && profileWriteGeneration.get() == writeGeneration }

            _isLoadingProfile.update { true }
            try {
                runSuspendCatching {
                    withContext(ioDispatcher) {
                        resolveContactProfile(signIn.publicKey, retry = true).getOrThrow()
                            ?: throw AppError("Profile not found")
                    }
                }.onSuccess { loadedProfile ->
                    var isCurrent = false
                    _profile.update {
                        isCurrent = isCurrentLoad()
                        if (isCurrent) loadedProfile else it
                    }
                    if (!isCurrent) {
                        Logger.debug("Skipped stale profile load for '${redacted(signIn.publicKey)}'", context = TAG)
                        return@onSuccess
                    }
                    invalidateReadOnlyProfile()
                    cacheMetadata(loadedProfile, isCurrentLoad)
                }.onFailure {
                    Logger.error("Failed to load profile", it, context = TAG)
                }
                completedProfileSignInGeneration = signIn.generation
                completedProfileWriteGeneration = writeGeneration
                completedProfileLoadVersion.incrementAndGet()
            } finally {
                _isLoadingProfile.update { false }
            }
        }
    }

    private suspend fun loadReadOnlyProfile() = withContext(ioDispatcher) {
        val generation = readOnlyProfileGeneration.get()
        loadProfileMutex.withLock {
            val isCurrentLoad = { readOnlyProfileGeneration.get() == generation && _publicKey.value == null }
            if (!isCurrentLoad()) return@withLock
            _isLoadingProfile.update { true }
            try {
                runSuspendCatching {
                    val secret = keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)
                        ?: adoptedSecretKeyHex() ?: return@runSuspendCatching null
                    val owner = pubkyService.publicKeyFromSecret(secret).ensurePubkyPrefix()
                    resolveContactProfile(owner, retry = false).getOrThrow()
                }.onSuccess { loaded ->
                    synchronized(readOnlyProfileLock) {
                        if (isCurrentLoad()) _readOnlyProfile.update { loaded }
                    }
                }.onFailure {
                    Logger.warn("Failed to load saved identity's public profile", it, context = TAG)
                }
            } finally {
                _isLoadingProfile.update { false }
            }
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
        startSignIn(publicKey)
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
            clearSessionContactProfiles()
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
        runSuspendCatching {
            val removed = pubkyService.removeContacts(records.map { it.publicKey })
            if (removed.size != records.size) {
                Logger.warn("Retained contacts that could not be removed during profile deletion", context = TAG)
            }
        }.onFailure {
            Logger.warn("Failed to delete contacts", it, context = TAG)
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
     * refreshed in the background on the bulk read lane, its row updating once its lookup finishes, unless its profile
     * was resolved less than [CONTACT_PROFILE_FRESHNESS] ago.
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
                        val now = clock.nowMs()
                        val loadedContacts = savedContacts.map { it.withSessionProfile(pk, now) }
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
     * and a caller arriving meanwhile waits for the same lookup. Sign-out, profile deletion or an identity change
     * stops it, not a later refresh. It returns at once for any other row; when the lookup fails, the row keeps its
     * label. A profile already found by the refresh but not yet applied to the contact list is applied at once.
     */
    suspend fun resolvePendingContactProfile(publicKey: String) {
        val owner = _publicKey.value ?: return
        val key = publicKey.ensurePubkyPrefix()
        val lookup = synchronized(contactsLock) {
            contactProfileRefresh?.let { if (key in it.pendingResults) applyRefreshResults(it) }
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

    /**
     * Saves a contact's label and keeps the rest of its profile as the contact's local override. The edit belongs to
     * [signIn]: once that identity signs out or the next sign-in starts, it fails with
     * [PubkyContactError.SignInChanged]. The SDK checks [signIn] under the lock it saves under, so an edit whose
     * sign-in ended before the SDK runs its save writes nothing. When the sign-in ends after the SDK save, the label
     * stays saved for that identity, but no override or contact row is written. Sign-out clears the overrides, so a
     * save that lands after it must not write one back, least of all for the next identity, which may have saved a
     * contact with the same key.
     */
    @Suppress("LongParameterList")
    suspend fun updateContact(
        signIn: PubkySignIn,
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
            requireCurrent(signIn)
            // The SDK checks the identity and the sign-in under the same lock as the save, so no session that another
            // sign-in installs can slip in between, not even one of the same identity.
            pubkyService.saveContact(
                prefixedKey,
                name,
                expectedIdentity = signIn.publicKey,
                isStillCurrent = { isCurrent(signIn) },
            )
            upsertContactProfileOverride(updatedProfile, signIn)
            synchronized(contactsLock) {
                requireCurrent(signIn)
                updateContacts { current ->
                    current.map { if (it.publicKey == prefixedKey) updatedProfile else it }
                        .sortedBy { it.name.lowercase() }
                }
            }
            markContactsLoaded()
            Logger.info("Updated contact '${redacted(prefixedKey)}'", context = TAG)
        }
    }.recoverCatching {
        if (it.hasCause(PubkyContactError.SignInChanged)) throw PubkyContactError.SignInChanged
        throw it
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
        if (it.hasCause(PubkyContactError.ActiveSubscription)) throw PubkyContactError.ActiveSubscription
        throw it
    }

    /**
     * Saves [profiles], the follows [prepareImport] resolved, without looking them up again; receiver discovery runs
     * during contact refresh. Contacts already saved and duplicate selections are skipped. The remaining contacts
     * are saved atomically, so a failed batch leaves them all pending for retry. The import runs in the repository
     * scope, so it finishes even when the caller is cancelled, rejects an ended sign-in, and clears the import once it
     * succeeds. A success bumps [contactImportVersion] and a failure sets [contactImportFailure], so both reach the app
     * after the import screens are gone. An import stopped by an identity change, such as a sign-out, reports nothing.
     */
    suspend fun importContacts(profiles: List<PubkyProfile>): Result<Unit> =
        scope.async(start = CoroutineStart.UNDISPATCHED) {
            val signIn = currentSignIn()
            activeContactImports.update { it + 1 }
            try {
                saveImportedContacts(profiles, signIn)
                    .onSuccess {
                        if (signIn != null && isCurrent(signIn)) _contactImportVersion.update { it + 1 }
                    }
                    .onFailure { error ->
                        if (signIn != null && !isCurrent(signIn)) {
                            Logger.info("Stopped a contact import after the identity changed", context = TAG)
                            return@onFailure
                        }
                        Logger.error("Failed to import contacts", error, context = TAG)
                        _contactImportFailure.update { error }
                    }
            } finally {
                activeContactImports.update { it - 1 }
            }
        }.await()

    private suspend fun saveImportedContacts(
        profiles: List<PubkyProfile>,
        expectedSignIn: PubkySignIn?,
    ): Result<Unit> = runSuspendCatching {
        withContext(ioDispatcher) {
            val signIn = requireNotNull(expectedSignIn) { "Not authenticated" }
            requireCurrent(signIn)
            val existing = _contacts.value.map { it.publicKey }.toMutableSet()
            val imported = profiles.filter { existing.add(it.publicKey) }
            if (imported.isNotEmpty()) {
                pubkyService.saveContacts(
                    updates = imported.map { ContactUpdate(it.publicKey, it.name) },
                    expectedIdentity = signIn.publicKey,
                    isStillCurrent = { isCurrent(signIn) },
                )
            }
            synchronized(contactsLock) {
                requireCurrent(signIn)
                cacheSessionContactProfiles(signIn.publicKey, imported)
                updateContacts { current ->
                    val currentKeys = current.map { it.publicKey }.toSet()
                    (current + imported.filter { it.publicKey !in currentKeys })
                        .sortedBy { it.name.lowercase() }
                }
            }
            markContactsLoaded()
            Logger.info("Imported '${imported.size}' contacts", context = TAG)
            clearPendingImport()
        }
    }

    /**
     * Looks up the signed-in identity's follows for the import overview, leaving out its own key, resolving each
     * follow's profile once and keeping a follow it cannot resolve as a placeholder. Its lookups use the interactive
     * read lane, since the user waits on the Pubky Ring choice row until it returns, and each one still running
     * [IMPORT_FOLLOW_LOOKUP_TIMEOUT] after it got its read slot is cancelled and treated as failed; waiting for a read
     * slot does not count.
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
                        prefixedKey to runSuspendCatching {
                            pubkyService.resolveContactProfile(
                                publicKey = prefixedKey,
                                allowPubkyProfileFallback = true,
                                lane = PaykitReadLane.Interactive,
                                timeout = IMPORT_FOLLOW_LOOKUP_TIMEOUT,
                            )?.let(::profileFromResolution)
                        }.onFailure {
                            if (it.isPaykitReadTimeout()) {
                                Logger.warn(
                                    "Timed out resolving follow '${redacted(prefixedKey)}' after " +
                                        "'$IMPORT_FOLLOW_LOOKUP_TIMEOUT'",
                                    context = TAG,
                                )
                            } else {
                                Logger.warn("Failed to resolve follow '${redacted(prefixedKey)}'", it, context = TAG)
                            }
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

                startSignIn(publicKey)
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
            val claim = PubkyAuthRequest.parseBitkitClaim(
                authUrl,
                PubkyAuthClaim.REQUIRED_CAPABILITIES,
            ).getOrThrow() ?: throw PubkyAuthRequestError.MissingBitkitClaim
            val secretKeyHex = requireNotNull(activeSecretKeyHex()) {
                "No secret key available — use Ring to manage authorizations"
            }
            pubkyService.approveAuthWithCompanionClaim(
                authUrl = authUrl,
                expectedCapabilities = PubkyAuthClaim.REQUIRED_CAPABILITIES,
                approvedClientId = approvedClientId,
                secretKeyHex = secretKeyHex,
                claim = PubkyAuthCompanionClaim(
                    queryParameter = PubkyAuthClaim.QUERY_PARAMETER,
                    claimType = claim.wireValue,
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
                        startSignIn(pubkyService.publicKeyFromSecret(secretKeyHex).ensurePubkyPrefix())
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
            continueSignIn(publicKey)

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

    suspend fun forgetUnrestoredIdentity(): Result<Boolean> = withContext(NonCancellable + ioDispatcher) {
        initializeMutex.withLock {
            if (_publicKey.value != null) return@withLock Result.success(false)
            runSuspendCatching {
                pubkyService.forgetSessionAccess()
                clearLocalState()
                true
            }.onFailure { Logger.error("Failed to forget unrestored Pubky identity", it, context = TAG) }
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

    private fun SavedContact.withSessionProfile(owner: String, now: Long): SavedContact {
        if (!needsRefresh) return this
        val cached = sessionContactProfile(owner, profile.publicKey) ?: return this
        return copy(
            profile = cached.profile.withNameFallback(label),
            needsRefresh = !cached.isFresh(now),
            showsLabelOnly = false,
        )
    }

    private fun refreshContactProfiles(owner: String, contacts: List<SavedContact>) {
        if (contacts.isEmpty()) return
        val refresh = ContactProfileRefresh(
            owner = owner,
            contacts = contacts,
            scope = scope,
            lookUp = { refresh, contact -> refreshContactProfile(owner, contact, PaykitReadLane.Bulk, refresh) },
            onFinished = { synchronized(contactsLock) { applyRefreshResults(it) } },
        )
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

    private suspend fun refreshContactProfile(
        owner: String,
        contact: SavedContact,
        lane: PaykitReadLane,
        refresh: ContactProfileRefresh? = null,
    ) {
        val lookup = currentCoroutineContext().job
        val publicKey = contact.profile.publicKey
        val resolved = resolveContactProfile(publicKey, retry = false, lane = lane)
            .onFailure { Logger.warn("Failed to resolve contact '${redacted(publicKey)}'", it, context = TAG) }
            .getOrNull() ?: return
        val refreshed = RefreshedContact(contact, resolved.withNameFallback(contact.label))
        synchronized(contactsLock) {
            if (_publicKey.value != owner || !lookup.isActive) return
            cacheSessionContactProfiles(owner, listOf(resolved))
            when (refresh) {
                null -> applyRefreshedContacts(listOf(refreshed))
                else -> queueRefreshedContact(refresh, refreshed)
            }
        }
    }

    private fun queueRefreshedContact(refresh: ContactProfileRefresh, refreshed: RefreshedContact) {
        refresh.pendingResults[refreshed.contact.profile.publicKey] = refreshed
        if (refresh.scheduledApply != null) return
        refresh.scheduledApply = scope.launch(refresh.job) {
            delay(CONTACT_REFRESH_BATCH_WINDOW)
            synchronized(contactsLock) { applyRefreshResults(refresh) }
        }
    }

    private fun applyRefreshResults(refresh: ContactProfileRefresh) {
        refresh.scheduledApply?.cancel()
        refresh.scheduledApply = null
        val results = refresh.pendingResults.values.toList()
        refresh.pendingResults.clear()
        if (results.isEmpty() || refresh.job.isCancelled || _publicKey.value != refresh.owner) return
        applyRefreshedContacts(results)
    }

    private fun applyRefreshedContacts(results: List<RefreshedContact>) {
        val replacements = results.associate { it.contact.profile to it.profile }
        if (_contacts.value.none { it in replacements }) return
        _contacts.update { current -> current.map { replacements[it] ?: it }.sortedBy { it.name.lowercase() } }
    }

    private fun sessionContactProfile(owner: String, publicKey: String): SessionContactProfile? =
        synchronized(contactsLock) {
            sessionContactProfiles[publicKey].takeIf { sessionContactProfilesOwner == owner }
        }

    private fun cacheSessionContactProfiles(owner: String, profiles: Collection<PubkyProfile>) {
        synchronized(contactsLock) {
            if (_publicKey.value != owner) return
            if (sessionContactProfilesOwner != owner) {
                sessionContactProfiles.clear()
                sessionContactProfilesOwner = owner
            }
            val now = clock.nowMs()
            profiles.filter { it != PubkyProfile.placeholder(it.publicKey) }
                .forEach { sessionContactProfiles[it.publicKey] = SessionContactProfile(it, now) }
        }
    }

    private fun clearSessionContactProfiles() {
        synchronized(contactsLock) {
            contactProfileRefresh?.job?.cancel()
            contactProfileRefresh?.pendingResults?.clear()
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

    private fun profileFromResolution(resolution: ProfileResolution): PubkyProfile {
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

    private suspend fun upsertContactProfileOverride(profile: PubkyProfile, signIn: PubkySignIn) {
        val prefixedKey = profile.publicKey.ensurePubkyPrefix()
        pubkyStore.update { data ->
            // Checked as the store applies the write: sign-out ends the sign-in before it resets the store.
            if (!isCurrent(signIn)) return@update data
            data.copy(
                ownerPublicKey = signIn.publicKey,
                contactProfileOverrides = data.contactProfileOverrides + (prefixedKey to profile.toProfileData()),
            )
        }
        notifyBackupStateChanged()
    }

    private fun requireCurrent(signIn: PubkySignIn) {
        if (!isCurrent(signIn)) throw PubkyContactError.SignInChanged
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

    private fun startSignIn(publicKey: String) {
        // First, so the sign-in it replaces has ended before the key is published, even when it is the same identity.
        signInGeneration.incrementAndGet()
        invalidateReadOnlyProfile(keepingPublicKey = publicKey)
        _publicKey.update { publicKey }
    }

    private fun continueSignIn(publicKey: String) {
        // Restoring or refreshing the session already signed in keeps its sign-in, so an edit under way still saves.
        if (_publicKey.value != publicKey) startSignIn(publicKey)
    }

    private fun invalidateReadOnlyProfile(keepingPublicKey: String? = null) = synchronized(readOnlyProfileLock) {
        readOnlyProfileGeneration.incrementAndGet()
        _readOnlyProfile.update { profile -> profile?.takeIf { it.publicKey == keepingPublicKey } }
    }

    private suspend fun clearAuthenticatedState(
        clearCachedProfile: Boolean = true,
        clearRestorationFailure: Boolean = true,
    ) = withContext(ioDispatcher) {
        // First, so work of the ending sign-in stops before the store reset below, and cannot write after it.
        signInGeneration.incrementAndGet()
        if (clearCachedProfile) {
            invalidateReadOnlyProfile()
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

    private fun requireCanonicalAddableContactPublicKey(
        publicKey: String,
        allowExisting: Boolean = false,
    ): String {
        val prefixedKey = PubkyPublicKeyFormat.canonicalized(publicKey)
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

package to.bitkit.ui.screens.contacts

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import coil3.ImageLoader
import com.synonym.paykit.ContactRecord
import com.synonym.paykit.ProfileResolution
import com.synonym.paykit.ProfileSource
import com.synonym.paykit.PublicationStatus
import io.ktor.client.HttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.withContext
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.PubkyImageCacheEpoch
import to.bitkit.data.PubkyStore
import to.bitkit.data.PubkyStoreData
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.sharedpubky.SharedPubkyClient
import to.bitkit.models.PubkyProfile
import to.bitkit.models.Toast
import to.bitkit.repositories.PaykitPaymentRequestRepo
import to.bitkit.repositories.PaykitPaymentRequestTargetCheck
import to.bitkit.repositories.PubkyContactError
import to.bitkit.repositories.PubkyRepo
import to.bitkit.services.PaykitReadLane
import to.bitkit.services.PubkyService
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.shared.toast.ToastEventBus
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import com.synonym.paykit.PubkyProfile as SdkPubkyProfile

/**
 * A contact save started on a contact's screen must not land on the identity signed in after the one it was started
 * in. Runs the real [PubkyRepo] under the contact screens, with the SDK faked: like the SDK, the fake saves a contact
 * only for the identity whose session it holds, and only one that identity has saved.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class ContactSaveSessionChangeTest : BaseUnitTest() {
    companion object {
        private const val OWNER_A = "pubky5rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xy"
        private const val OWNER_B = "pubky1rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xy"
        private const val CONTACT = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xy"
        private const val SIGNED_OUT = "signed out"
    }

    private val context = mock<Context>()
    private val pubkyService = mock<PubkyService>()
    private val keychain = mock<Keychain>()
    private val pubkyStore = mock<PubkyStore>()
    private val settingsStore = mock<SettingsStore>()
    private val paykitPaymentRequestRepo = mock<PaykitPaymentRequestRepo>()
    private val storeData = MutableStateFlow(PubkyStoreData())
    private val settings = MutableStateFlow(SettingsData())
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(testDispatcher.scheduler.currentTime)
    }

    private var sdkIdentity: String? = null
    private val sdkContacts = mutableMapOf<String, Set<String>>()
    private val sdkSaveAttempts = mutableListOf<Pair<String, String>>()
    private val sdkSaves = mutableListOf<Pair<String, String>>()
    private var heldSave: CompletableDeferred<Unit>? = null
    private var heldSaveSucceeds = true

    private lateinit var repo: PubkyRepo

    @Before
    fun setUp() {
        whenever(context.getString(any())).thenReturn("")
        whenever(pubkyStore.data).thenReturn(storeData)
        whenever { pubkyStore.update(any()) }.thenAnswer {
            storeData.value = it.getArgument<(PubkyStoreData) -> PubkyStoreData>(0)(storeData.value)
            Unit
        }
        whenever { pubkyStore.reset() }.thenAnswer {
            storeData.value = PubkyStoreData()
            Unit
        }
        whenever(settingsStore.data).thenReturn(settings)
        whenever(settingsStore.isPubkyProfileSetupPending).thenReturn(MutableStateFlow(false))
        whenever { settingsStore.update(any()) }.thenAnswer {
            settings.value = it.getArgument<(SettingsData) -> SettingsData>(0)(settings.value)
            Unit
        }
        whenever { pubkyService.contactRecords() }.thenReturn(emptyList())
        whenever { pubkyService.signOut() }.thenAnswer {
            sdkIdentity = null
            Unit
        }
        whenever { pubkyService.saveContact(any(), anyOrNull(), any(), anyOrNull(), anyOrNull()) }
            .doSuspendableAnswer { fakeSdkSave(it.getArgument(0), it.getArgument(3), it.getArgument(4)) }
        whenever { pubkyService.resolveContactProfile(CONTACT, true, PaykitReadLane.Bulk, null) }
            .doSuspendableAnswer { awaitCancellation() }
        whenever(paykitPaymentRequestRepo.eligibleTargets).thenReturn(MutableStateFlow(emptyList()))
        whenever { paykitPaymentRequestRepo.refreshEligibleTarget(any()) }
            .thenReturn(Result.success(PaykitPaymentRequestTargetCheck(target = null, isComplete = true)))
        repo = PubkyRepo(
            ioDispatcher = testDispatcher,
            pubkyService = pubkyService,
            keychain = keychain,
            sharedPubkyClient = mock<SharedPubkyClient>(),
            imageLoader = mock<ImageLoader>(),
            imageCacheEpoch = PubkyImageCacheEpoch(),
            pubkyStore = pubkyStore,
            settingsStore = settingsStore,
            httpClient = mock<HttpClient>(),
            clock = clock,
        )
    }

    /**
     * The reported race: two tag changes wait for the contact's held profile lookup, the user signs out and signs in
     * with another identity, and only then does the lookup finish. The next identity has saved the same contact,
     * which the SDK accepts a save for.
     */
    @Test
    fun `tag changes queued before a sign-out save nothing for the next identity's same contact`() = test {
        assertQueuedTagChangesSaveNothingAfterSignIn(nextOwner = OWNER_B, nextOwnerHasContact = true)
    }

    /** As above, but the next identity has not saved the contact, so the SDK would reject a save. */
    @Test
    fun `tag changes queued before a sign-out save nothing when the next identity lacks the contact`() = test {
        assertQueuedTagChangesSaveNothingAfterSignIn(nextOwner = OWNER_B, nextOwnerHasContact = false)
    }

    /** Signing straight back in starts a new sign-in, so the changes still stop and write back no cleared override. */
    @Test
    fun `tag changes queued before a sign-out save nothing when the same identity signs back in`() = test {
        assertQueuedTagChangesSaveNothingAfterSignIn(nextOwner = OWNER_A, nextOwnerHasContact = true)
    }

    /** Sign-out stops the contact's lookup at once, so the queued changes go on while no identity is signed in. */
    @Test
    fun `tag changes waiting for a lookup that sign-out stops save nothing and show no toast`() = test {
        val toasts = collectToasts()
        whenever(pubkyService.resolveContactProfile(CONTACT, true, PaykitReadLane.Interactive, null))
            .doSuspendableAnswer { awaitCancellation() }
        signIn(OWNER_A, listOf(contactRecord(label = "Label only")))
        val screen = contactScreen()
        screen.addTag("friend")
        screen.addTag("work")

        assertTrue(repo.signOut().isSuccess)
        advanceUntilIdle()

        assertEquals(Outcome(rows = emptyList()), observedOutcome(toasts))
    }

    /**
     * The first change's save was admitted, so it runs, but a sign-out and another identity's sign-in, which saved
     * the same contact, land before it returns. Whether the save then succeeds or fails, nothing reaches the next
     * identity, and the second change, queued behind it, never saves.
     */
    @Test
    fun `a tag save that a session change overtakes leaves the next identity alone`() = test {
        listOf(true, false).forEach { saveSucceeds ->
            assertTagSaveOvertakenBySessionChange(saveSucceeds)
        }
    }

    /** An edit-contact save takes the same path as a tag change once its save is in flight. */
    @Test
    fun `an edit-contact save that a session change overtakes leaves the next identity alone`() = test {
        val toasts = collectToasts()
        signIn(OWNER_A, listOf(contactRecord(label = "Alice", name = "Alice")))
        val screen = EditContactViewModel(
            context = context,
            pubkyRepo = repo,
            savedStateHandle = SavedStateHandle(mapOf("publicKey" to CONTACT)),
        )
        heldSave = CompletableDeferred()
        screen.onNameChange("Alice edited")
        screen.save()

        assertTrue(repo.signOut().isSuccess)
        signIn(OWNER_B, listOf(contactRecord(label = "Bob's Alice", name = "Bob's Alice")))
        heldSave?.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            Outcome(
                saveAttempts = listOf(OWNER_A to CONTACT),
                saves = listOf(OWNER_A to CONTACT),
                rows = listOf("Bob's Alice" to emptyList()),
            ),
            observedOutcome(toasts),
        )
    }

    private suspend fun TestScope.assertQueuedTagChangesSaveNothingAfterSignIn(
        nextOwner: String,
        nextOwnerHasContact: Boolean,
    ) {
        val toasts = collectToasts()
        val lookup = CompletableDeferred<Unit>()
        // Sign-out cancels the lookup, but the read under it still finishes, as one the SDK is running may.
        whenever(pubkyService.resolveContactProfile(CONTACT, true, PaykitReadLane.Interactive, null))
            .doSuspendableAnswer {
                withContext(NonCancellable) { lookup.await() }
                contactResolution(name = "Alice")
            }
        signIn(OWNER_A, listOf(contactRecord(label = "Label only")))
        val screen = contactScreen()
        screen.addTag("friend")
        screen.addTag("work")
        verify(pubkyService).resolveContactProfile(CONTACT, true, PaykitReadLane.Interactive, null)

        assertTrue(repo.signOut().isSuccess)
        signIn(nextOwner, if (nextOwnerHasContact) listOf(contactRecord(label = "Next label")) else emptyList())
        lookup.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            Outcome(rows = if (nextOwnerHasContact) listOf("Next label" to emptyList()) else emptyList()),
            observedOutcome(toasts),
        )
        // Neither change looks the next identity's contact up.
        verify(pubkyService, times(1)).resolveContactProfile(CONTACT, true, PaykitReadLane.Interactive, null)
    }

    private suspend fun TestScope.assertTagSaveOvertakenBySessionChange(saveSucceeds: Boolean) {
        val case = if (saveSucceeds) "after a save that succeeds" else "after a save that fails"
        sdkSaveAttempts.clear()
        sdkSaves.clear()
        storeData.value = PubkyStoreData()
        heldSaveSucceeds = saveSucceeds
        val toasts = collectToasts()
        signIn(OWNER_A, listOf(contactRecord(label = "Alice", name = "Alice")))
        val screen = contactScreen()
        heldSave = CompletableDeferred()
        screen.addTag("friend")
        screen.addTag("work")

        assertTrue(repo.signOut().isSuccess)
        signIn(OWNER_B, listOf(contactRecord(label = "Bob's Alice", name = "Bob's Alice")))
        heldSave?.complete(Unit)
        advanceUntilIdle()
        heldSave = null

        assertEquals(
            Outcome(
                saveAttempts = listOf(OWNER_A to CONTACT),
                saves = if (saveSucceeds) listOf(OWNER_A to CONTACT) else emptyList(),
                rows = listOf("Bob's Alice" to emptyList()),
            ),
            observedOutcome(toasts),
            case,
        )
        assertTrue(repo.signOut().isSuccess)
    }

    private suspend fun fakeSdkSave(
        publicKey: String,
        expectedIdentity: String?,
        isStillCurrent: (() -> Boolean)?,
    ): ContactRecord {
        sdkSaveAttempts += (sdkIdentity ?: SIGNED_OUT) to publicKey
        val identity = checkNotNull(sdkIdentity) { "No Pubky session" }
        check(expectedIdentity == null || expectedIdentity == identity) {
            "Paykit identity changed before saving the contact"
        }
        if (isStillCurrent?.invoke() == false) throw PubkyContactError.SignInChanged
        val hasContact = publicKey in sdkContacts[identity].orEmpty()
        heldSave?.let {
            it.await()
            check(heldSaveSucceeds) { "Save failed" }
        }
        check(hasContact) { "Contact no longer exists" }
        sdkSaves += identity to publicKey
        return contactRecord(label = null)
    }

    private suspend fun signIn(owner: String, records: List<ContactRecord>) {
        val session = "session-$owner"
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(session)
        whenever(pubkyService.importSession(session)).doSuspendableAnswer {
            sdkIdentity = owner
            owner
        }
        whenever(pubkyService.resolveContactProfile(owner, true, PaykitReadLane.Interactive, null))
            .thenReturn(ownerResolution(owner))
        whenever(pubkyService.contactRecords()).thenReturn(records)
        sdkContacts[owner] = records.map { it.publicKey }.toSet()
        repo.initialize()
    }

    private fun contactScreen() = ContactDetailViewModel(
        context = context,
        pubkyRepo = repo,
        privatePaykitRepo = mock(),
        paykitPaymentRequestRepo = paykitPaymentRequestRepo,
        clock = clock,
        savedStateHandle = SavedStateHandle(mapOf("publicKey" to CONTACT)),
    )

    private fun TestScope.collectToasts(): List<Toast> {
        val toasts = mutableListOf<Toast>()
        backgroundScope.launch { ToastEventBus.events.collect { toasts += it } }
        return toasts
    }

    private fun observedOutcome(toasts: List<Toast>) = Outcome(
        saveAttempts = sdkSaveAttempts.toList(),
        saves = sdkSaves.toList(),
        overrideTags = storeData.value.contactProfileOverrides.mapValues { it.value.tags },
        rows = repo.contacts.value.map { it.name to it.tags },
        toasts = toasts.map { "${it.type}: ${it.description}" },
    )

    private fun contactRecord(label: String?, name: String? = null) = ContactRecord(
        publicKey = CONTACT,
        label = label,
        profile = name?.let {
            PubkyProfile(
                publicKey = CONTACT,
                name = it,
                bio = "",
                imageUrl = null,
                links = emptyList(),
                tags = emptyList(),
                status = null,
            ).toProfileData().toPaykitProfile()
        },
        profileFetchedAt = null,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        publicContactMarkerStatus = PublicationStatus.NOT_PUBLISHED,
        publicContactPublishedAt = null,
        publicContactRemovedAt = null,
        publicContactLastError = null,
    )

    private fun contactResolution(name: String) = pubkyResolution(CONTACT, name)

    private fun ownerResolution(owner: String) = pubkyResolution(owner, "Owner")

    private fun pubkyResolution(publicKey: String, name: String) = ProfileResolution(
        publicKey = publicKey,
        source = ProfileSource.PUBKY_PROFILE,
        displayName = name,
        imageUri = null,
        paykitProfile = null,
        pubkyProfile = SdkPubkyProfile(name = name, bio = "", image = null, links = emptyList(), status = null),
        fetchedAt = "2026-01-01T00:00:00Z",
    )

    private data class Outcome(
        val saveAttempts: List<Pair<String, String>> = emptyList(),
        val saves: List<Pair<String, String>> = emptyList(),
        val overrideTags: Map<String, List<String>> = emptyMap(),
        val rows: List<Pair<String, List<String>>>,
        val toasts: List<String> = emptyList(),
    )
}

package to.bitkit.repositories

import android.app.Activity
import com.synonym.bitkitcore.LightningInvoice
import com.synonym.bitkitcore.NetworkType
import com.synonym.bitkitcore.Scanner
import com.synonym.paykit.FfiPaymentEntry
import com.synonym.paykit.PaykitFfiException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.App
import to.bitkit.CurrentActivity
import to.bitkit.data.PrivatePaykitCacheData
import to.bitkit.data.PrivatePaykitCacheStore
import to.bitkit.data.PrivatePaykitContactCacheData
import to.bitkit.data.PrivatePaykitStoredPaymentEntryData
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.PrivatePaykitContactLinkBackupV1
import to.bitkit.services.CoreService
import to.bitkit.services.PubkyService
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Suppress("LargeClass")
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class PrivatePaykitRepoTest : BaseUnitTest(StandardTestDispatcher()) {
    companion object {
        private const val CONTACT_KEY = "pubkycytinw71a3ge1esmzj5e53hsr3jtj6t4pogpgr6k75w9mzmyokzo"
        private const val OWN_KEY = "pubkyeytinw71a3ge1esmzj5e53hsr3jtj6t4pogpgr6k75w9mzmyokzo"
        private const val SECRET_KEY_HEX = "secret"
        private const val LINK_ID = "link-id"
        private const val HANDSHAKE_ID = "handshake-id"
        private const val LINK_SNAPSHOT = "link-snapshot"
        private const val UPDATED_LINK_SNAPSHOT = "updated-link-snapshot"
        private const val HANDSHAKE_SNAPSHOT = "handshake-snapshot"
        private const val UPDATED_HANDSHAKE_SNAPSHOT = "updated-handshake-snapshot"
        private const val LOCAL_PAYLOAD_HASH = "local-payload-hash"
        private const val PRIVATE_BOLT11 = "lnbcrt1private"
        private const val PRIVATE_PAYMENT_HASH = "010203"
        private const val PAYLOAD_LIMIT_BOLT11_LENGTH = 902
        private const val NOW_SECONDS = 1_700_000_000L
        private const val TOMBSTONE_PAYLOAD = """{"value":""}"""
    }

    private val pubkyService = mock<PubkyService>()
    private val keychain = mock<Keychain>()
    private val cacheStore = mock<PrivatePaykitCacheStore>()
    private val settingsStore = mock<SettingsStore>()
    private val addressReservationRepo = mock<PrivatePaykitAddressReservationRepo>()
    private val lightningRepo = mock<LightningRepo>()
    private val walletRepo = mock<WalletRepo>()
    private val publicPaykitRepo = mock<PublicPaykitRepo>()
    private val coreService = mock<CoreService>()
    private val clock = mock<Clock>()

    private val cacheData = MutableStateFlow(PrivatePaykitCacheData())
    private val settingsData = MutableStateFlow(SettingsData())
    private val lightningState = MutableStateFlow(
        LightningState(nodeLifecycleState = NodeLifecycleState.Running),
    )

    private lateinit var sut: PrivatePaykitRepo

    @Before
    fun setUp() {
        cacheData.value = PrivatePaykitCacheData()
        settingsData.value = SettingsData()

        whenever(cacheStore.data).thenReturn(cacheData)
        whenever { cacheStore.update(any()) }.thenAnswer {
            val transform = it.getArgument<(PrivatePaykitCacheData) -> PrivatePaykitCacheData>(0)
            cacheData.value = transform(cacheData.value)
        }
        whenever { cacheStore.reset() }.thenAnswer {
            cacheData.value = PrivatePaykitCacheData()
            Unit
        }
        whenever(settingsStore.data).thenReturn(settingsData)
        whenever(lightningRepo.lightningState).thenReturn(lightningState)
        whenever(clock.now()).thenReturn(Instant.fromEpochSeconds(NOW_SECONDS))
        PublicPaykitRepo.lightningRouteHintsValidator = { true }
        whenever(keychain.loadString(Keychain.Key.PRIVATE_PAYKIT_SECRET_STATE.name)).thenReturn(null)
        whenever { keychain.delete(any()) }.thenReturn(Unit)
        whenever { keychain.upsertString(any(), any()) }.thenReturn(Unit)
        whenever { addressReservationRepo.reconcileReservedIndexesWithLdk() }.thenReturn(Result.success(Unit))
        whenever { addressReservationRepo.hasContactAssignment(any()) }.thenReturn(false)
        whenever { walletRepo.refreshReusableReceiveAddressIfReserved() }.thenReturn(Result.success(Unit))

        sut = createSut()
    }

    @After
    fun tearDown() {
        PublicPaykitRepo.lightningRouteHintsValidator = null
        App.currentActivity = null
    }

    @Test
    fun `shouldInitiate returns true for lexicographically larger key`() {
        val smallerKey = "pubkyybndrfg8ejkmcpqxot1uwisza345h769ybndrfg8ejkmcpqxot1u"
        val largerKey = "pubkyzbndrfg8ejkmcpqxot1uwisza345h769ybndrfg8ejkmcpqxot1u"

        assertTrue(PrivatePaykitRepo.shouldInitiate(largerKey, smallerKey))
        assertFalse(PrivatePaykitRepo.shouldInitiate(smallerKey, largerKey))
    }

    @Test
    fun `shouldInitiate normalizes prefixed and unprefixed keys`() {
        val smallerKey = "ybndrfg8ejkmcpqxot1uwisza345h769ybndrfg8ejkmcpqxot1u"
        val largerKey = "pubkyzbndrfg8ejkmcpqxot1uwisza345h769ybndrfg8ejkmcpqxot1u"

        assertTrue(PrivatePaykitRepo.shouldInitiate(largerKey, smallerKey))
    }

    @Test
    fun `restoreBackup preserves private link recovery and cached remote endpoint state`() = test {
        whenever(pubkyService.encryptedLinkSnapshotRecipient(LINK_SNAPSHOT)).thenReturn(CONTACT_KEY)
        whenever(pubkyService.encryptedLinkHandshakeSnapshotRecipient(HANDSHAKE_SNAPSHOT)).thenReturn(CONTACT_KEY)
        val remoteEndpoints = mapOf(MethodId.P2wpkh.rawValue to PublicPaykitRepo.serializePayload("bcrt1qprivate"))

        sut.restoreBackup(
            mapOf(
                CONTACT_KEY to PrivatePaykitContactLinkBackupV1(
                    publicKey = CONTACT_KEY,
                    linkSnapshotHex = LINK_SNAPSHOT,
                    handshakeSnapshotHex = HANDSHAKE_SNAPSHOT,
                    remoteEndpoints = remoteEndpoints,
                    linkCompletedAt = NOW_SECONDS - 60,
                    handshakeUpdatedAt = NOW_SECONDS - 120,
                    recoveryStartedAt = NOW_SECONDS - 180,
                    mainRecoveryAttemptId = "main-attempt",
                    responderRecoveryAttemptId = "responder-attempt",
                ),
            ),
        ).getOrThrow()

        val restored = sut.backupSnapshot().getOrThrow()?.get(CONTACT_KEY)

        assertNotNull(restored)
        assertEquals(LINK_SNAPSHOT, restored.linkSnapshotHex)
        assertEquals(HANDSHAKE_SNAPSHOT, restored.handshakeSnapshotHex)
        assertEquals(remoteEndpoints, restored.remoteEndpoints)
        assertEquals(NOW_SECONDS - 60, restored.linkCompletedAt)
        assertEquals(NOW_SECONDS - 120, restored.handshakeUpdatedAt)
        assertEquals(NOW_SECONDS - 180, restored.recoveryStartedAt)
        assertEquals("main-attempt", restored.mainRecoveryAttemptId)
        assertEquals("responder-attempt", restored.responderRecoveryAttemptId)
    }

    @Test
    fun `restoreBackup clears stale private cleanup markers`() = test {
        cacheData.value = PrivatePaykitCacheData(
            cleanupPending = true,
            deletedContactCleanupPendingPublicKeys = setOf(CONTACT_KEY),
        )

        sut.restoreBackup(null).getOrThrow()

        assertFalse(cacheData.value.cleanupPending)
        assertEquals(emptySet(), cacheData.value.deletedContactCleanupPendingPublicKeys)
    }

    @Test
    fun `removeSavedContact tombstones private endpoints before clearing local state`() = test {
        restoreContactBackup()
        rememberSavedContact()
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(SECRET_KEY_HEX)
        whenever(pubkyService.restoreEncryptedLink(SECRET_KEY_HEX, LINK_SNAPSHOT)).thenReturn(LINK_ID)
        whenever(pubkyService.serializeEncryptedLink(LINK_ID)).thenReturn(UPDATED_LINK_SNAPSHOT)
        whenever(pubkyService.currentPublicKey()).thenReturn(OWN_KEY)

        sut.removeSavedContact(CONTACT_KEY).getOrThrow()

        verify(pubkyService).setPrivatePayments(
            eq(LINK_ID),
            argThat<List<FfiPaymentEntry>> {
                isNotEmpty() && all { it.endpointData == TOMBSTONE_PAYLOAD }
            },
        )
        verify(addressReservationRepo).clearContactAssignment(CONTACT_KEY)
        assertNull(sut.backupSnapshot().getOrThrow())
    }

    @Test
    fun `removeSavedContact preserves state and marks cleanup pending when tombstone fails`() = test {
        restoreContactBackup()
        rememberSavedContact()
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(SECRET_KEY_HEX)
        whenever(pubkyService.restoreEncryptedLink(SECRET_KEY_HEX, LINK_SNAPSHOT)).thenReturn(LINK_ID)
        whenever(pubkyService.setPrivatePayments(eq(LINK_ID), any()))
            .thenAnswer { throw PrivatePaykitTestError("network failed") }

        val result = sut.removeSavedContact(CONTACT_KEY)

        assertTrue(result.isFailure)
        assertFalse(cacheData.value.cleanupPending)
        assertEquals(setOf(CONTACT_KEY), cacheData.value.deletedContactCleanupPendingPublicKeys)
        assertNotNull(sut.backupSnapshot().getOrThrow()?.get(CONTACT_KEY))
        verify(addressReservationRepo, never()).clearContactAssignment(CONTACT_KEY)
    }

    @Test
    fun `disableSharingAndPruneUnsavedContactState defers cleanup when endpoint removal fails`() = test {
        restoreContactBackup()
        rememberSavedContact()
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(SECRET_KEY_HEX)
        whenever(pubkyService.restoreEncryptedLink(SECRET_KEY_HEX, LINK_SNAPSHOT)).thenReturn(LINK_ID)
        whenever(pubkyService.setPrivatePayments(eq(LINK_ID), any()))
            .thenAnswer { throw PrivatePaykitTestError("network failed") }

        val result = sut.disableSharingAndPruneUnsavedContactState(listOf(CONTACT_KEY))

        assertTrue(result.isSuccess)
        assertTrue(cacheData.value.cleanupPending)
        assertNotNull(sut.backupSnapshot().getOrThrow()?.get(CONTACT_KEY))
    }

    @Test
    fun `retryPendingEndpointRemoval tombstones deleted contact without unpublishing public endpoints`() = test {
        restoreContactBackup()
        cacheData.value = cacheData.value.copy(
            deletedContactCleanupPendingPublicKeys = setOf(CONTACT_KEY),
        )
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(SECRET_KEY_HEX)
        whenever(pubkyService.restoreEncryptedLink(SECRET_KEY_HEX, LINK_SNAPSHOT)).thenReturn(LINK_ID)
        whenever(pubkyService.serializeEncryptedLink(LINK_ID)).thenReturn(UPDATED_LINK_SNAPSHOT)
        whenever(pubkyService.currentPublicKey()).thenReturn(OWN_KEY)

        sut.retryPendingEndpointRemoval(emptyList()).getOrThrow()

        verify(publicPaykitRepo, never()).syncPublishedEndpoints(false)
        verify(pubkyService).setPrivatePayments(
            eq(LINK_ID),
            argThat<List<FfiPaymentEntry>> {
                isNotEmpty() && all { it.endpointData == TOMBSTONE_PAYLOAD }
            },
        )
        verify(addressReservationRepo).clearContactAssignment(CONTACT_KEY)
        assertEquals(emptySet(), cacheData.value.deletedContactCleanupPendingPublicKeys)
        assertNull(sut.backupSnapshot().getOrThrow())
    }

    @Test
    fun `retryPendingEndpointRemoval clears stale sharing cleanup marker when sharing is enabled`() = test {
        cacheData.value = cacheData.value.copy(cleanupPending = true)
        settingsData.value = SettingsData(
            sharesPublicPaykitEndpoints = true,
            sharesPrivatePaykitEndpoints = true,
        )

        sut.retryPendingEndpointRemoval(emptyList()).getOrThrow()

        assertFalse(cacheData.value.cleanupPending)
        verify(publicPaykitRepo, never()).syncPublishedEndpoints(false)
    }

    @Test
    fun `failed private endpoint publish retries even with previous payload hash`() = test {
        startForegroundWithSharingEnabled()
        cacheData.value = PrivatePaykitCacheData(
            contacts = mapOf(
                CONTACT_KEY to PrivatePaykitContactCacheData(
                    lastLocalPayloadHash = LOCAL_PAYLOAD_HASH,
                    linkCompletedAt = NOW_SECONDS - 60,
                ),
            ),
        )
        whenever(keychain.loadString(Keychain.Key.PRIVATE_PAYKIT_SECRET_STATE.name))
            .thenReturn(secretStateJson())
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(SECRET_KEY_HEX)
        whenever(pubkyService.currentPublicKey()).thenReturn(OWN_KEY)
        whenever(pubkyService.encryptedLinkSnapshotRecipient(LINK_SNAPSHOT)).thenReturn(CONTACT_KEY)
        whenever(pubkyService.restoreEncryptedLink(SECRET_KEY_HEX, LINK_SNAPSHOT)).thenReturn(LINK_ID)
        whenever(pubkyService.fetchFileString(any())).thenAnswer { throw PrivatePaykitTestError("not found") }
        whenever(pubkyService.getPrivatePayments(LINK_ID)).thenReturn(emptyList())
        whenever(pubkyService.serializeEncryptedLink(LINK_ID)).thenReturn(UPDATED_LINK_SNAPSHOT)
        whenever(addressReservationRepo.currentOrRotatedAddress(CONTACT_KEY)).thenReturn(
            Result.success("bcrt1qprivate"),
        )
        whenever(pubkyService.setPrivatePayments(eq(LINK_ID), any()))
            .thenAnswer { throw PrivatePaykitTestError("network failed") }
            .thenAnswer { Unit }

        sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()
        advanceTimeBy(5_000)
        runCurrent()

        verify(pubkyService, times(2)).setPrivatePayments(eq(LINK_ID), any())
    }

    @Test
    fun `prepareSavedContacts does not publish private address when reusable receive refresh fails`() = test {
        startForegroundWithSharingEnabled()
        cacheData.value = PrivatePaykitCacheData(
            contacts = mapOf(
                CONTACT_KEY to PrivatePaykitContactCacheData(
                    linkCompletedAt = NOW_SECONDS,
                ),
            ),
        )
        whenever(keychain.loadString(Keychain.Key.PRIVATE_PAYKIT_SECRET_STATE.name))
            .thenReturn(secretStateJson())
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(SECRET_KEY_HEX)
        whenever(pubkyService.currentPublicKey()).thenReturn(OWN_KEY)
        whenever(pubkyService.encryptedLinkSnapshotRecipient(LINK_SNAPSHOT)).thenReturn(CONTACT_KEY)
        whenever(pubkyService.restoreEncryptedLink(SECRET_KEY_HEX, LINK_SNAPSHOT)).thenReturn(LINK_ID)
        whenever(pubkyService.getPrivatePayments(LINK_ID)).thenReturn(emptyList())
        whenever(pubkyService.serializeEncryptedLink(LINK_ID)).thenReturn(UPDATED_LINK_SNAPSHOT)
        whenever(addressReservationRepo.currentOrRotatedAddress(CONTACT_KEY)).thenReturn(
            Result.success("bcrt1qprivate"),
        )
        whenever { walletRepo.refreshReusableReceiveAddressIfReserved() }
            .thenReturn(Result.failure(PrivatePaykitTestError("refresh failed")))

        sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()

        verify(pubkyService).getPrivatePayments(LINK_ID)
        verify(pubkyService, never()).setPrivatePayments(eq(LINK_ID), any())
    }

    @Test
    fun `prepareSavedContacts does not retry when private key is unavailable`() = test {
        startForegroundWithSharingEnabled()
        cacheData.value = PrivatePaykitCacheData(
            contacts = mapOf(
                CONTACT_KEY to PrivatePaykitContactCacheData(
                    linkCompletedAt = NOW_SECONDS,
                ),
            ),
        )
        whenever(keychain.loadString(Keychain.Key.PRIVATE_PAYKIT_SECRET_STATE.name))
            .thenReturn(secretStateJson())
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(null)

        sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()
        advanceTimeBy(5_000)
        runCurrent()

        verify(keychain, times(1)).loadString(Keychain.Key.PUBKY_SECRET_KEY.name)
        verify(pubkyService, never()).restoreEncryptedLink(any(), any())
        verify(pubkyService, never()).setPrivatePayments(any(), any())
    }

    @Test
    fun `enableSharingAndPrepareSavedContacts restores pending cleanup marker when prepare fails`() = test {
        startForegroundWithSharingEnabled()
        cacheData.value = PrivatePaykitCacheData(cleanupPending = true)
        whenever { addressReservationRepo.reconcileReservedIndexesWithLdk() }
            .thenReturn(Result.failure(PrivatePaykitTestError("reconcile failed")))

        val result = sut.enableSharingAndPrepareSavedContacts(listOf(CONTACT_KEY))

        assertTrue(result.isFailure)
        assertTrue(cacheData.value.cleanupPending)
    }

    @Test
    fun `prepareSavedContacts clears mismatched link snapshot and starts fresh handshake`() = test {
        startForegroundWithSharingEnabled()
        whenever(keychain.loadString(Keychain.Key.PRIVATE_PAYKIT_SECRET_STATE.name))
            .thenReturn(secretStateJson())
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(SECRET_KEY_HEX)
        whenever(pubkyService.currentPublicKey()).thenReturn(OWN_KEY)
        whenever(pubkyService.fetchFileString(any())).thenAnswer { throw PrivatePaykitTestError("not found") }
        whenever(pubkyService.encryptedLinkSnapshotRecipient(LINK_SNAPSHOT)).thenReturn(OWN_KEY)
        stubPendingFreshHandshake()

        sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()

        verify(pubkyService, never()).restoreEncryptedLink(SECRET_KEY_HEX, LINK_SNAPSHOT)
        verify(pubkyService).initiateEncryptedLink(SECRET_KEY_HEX, CONTACT_KEY)
        val snapshot = sut.backupSnapshot().getOrThrow()?.get(CONTACT_KEY)
        assertNotNull(snapshot)
        assertNull(snapshot.linkSnapshotHex)
        assertEquals(UPDATED_HANDSHAKE_SNAPSHOT, snapshot.handshakeSnapshotHex)
    }

    @Test
    fun `prepareSavedContacts clears mismatched handshake snapshot and starts fresh handshake`() = test {
        startForegroundWithSharingEnabled()
        whenever(keychain.loadString(Keychain.Key.PRIVATE_PAYKIT_SECRET_STATE.name))
            .thenReturn(secretStateJson(linkSnapshotHex = null, handshakeSnapshotHex = HANDSHAKE_SNAPSHOT))
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(SECRET_KEY_HEX)
        whenever(pubkyService.currentPublicKey()).thenReturn(OWN_KEY)
        whenever(pubkyService.fetchFileString(any())).thenAnswer { throw PrivatePaykitTestError("not found") }
        whenever(pubkyService.encryptedLinkHandshakeSnapshotRecipient(HANDSHAKE_SNAPSHOT)).thenReturn(OWN_KEY)
        stubPendingFreshHandshake()

        sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()

        verify(pubkyService, never()).restoreEncryptedLinkHandshake(SECRET_KEY_HEX, HANDSHAKE_SNAPSHOT)
        verify(pubkyService).initiateEncryptedLink(SECRET_KEY_HEX, CONTACT_KEY)
        val snapshot = sut.backupSnapshot().getOrThrow()?.get(CONTACT_KEY)
        assertNotNull(snapshot)
        assertEquals(UPDATED_HANDSHAKE_SNAPSHOT, snapshot.handshakeSnapshotHex)
    }

    @Test
    fun `prepareSavedContacts publishes after fetching empty remote endpoints for fresh initiator link`() = test {
        startForegroundWithSharingEnabled()
        cacheData.value = PrivatePaykitCacheData(
            contacts = mapOf(
                CONTACT_KEY to PrivatePaykitContactCacheData(
                    linkCompletedAt = NOW_SECONDS,
                ),
            ),
        )
        whenever(keychain.loadString(Keychain.Key.PRIVATE_PAYKIT_SECRET_STATE.name))
            .thenReturn(secretStateJson())
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(SECRET_KEY_HEX)
        whenever(pubkyService.currentPublicKey()).thenReturn(OWN_KEY)
        whenever(pubkyService.encryptedLinkSnapshotRecipient(LINK_SNAPSHOT)).thenReturn(CONTACT_KEY)
        whenever(pubkyService.restoreEncryptedLink(SECRET_KEY_HEX, LINK_SNAPSHOT)).thenReturn(LINK_ID)
        whenever(pubkyService.getPrivatePayments(LINK_ID)).thenReturn(emptyList())
        whenever(pubkyService.serializeEncryptedLink(LINK_ID)).thenReturn(UPDATED_LINK_SNAPSHOT)
        whenever(addressReservationRepo.currentOrRotatedAddress(CONTACT_KEY)).thenReturn(
            Result.success("bcrt1qprivate"),
        )

        sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()

        verify(pubkyService).getPrivatePayments(LINK_ID)
        verify(pubkyService).setPrivatePayments(
            eq(LINK_ID),
            argThat<List<FfiPaymentEntry>> {
                any { it.endpointData == PublicPaykitRepo.serializePayload("bcrt1qprivate") }
            },
        )
    }

    @Test
    fun `prepareSavedContacts returns NoSupportedEndpoint when immediate publish has no endpoint`() = test {
        startForegroundWithSharingEnabled()
        settingsData.value = settingsData.value.copy(publicPaykitOnchainEnabled = false)
        cacheData.value = PrivatePaykitCacheData(
            contacts = mapOf(
                CONTACT_KEY to PrivatePaykitContactCacheData(
                    linkCompletedAt = NOW_SECONDS,
                ),
            ),
        )
        whenever(keychain.loadString(Keychain.Key.PRIVATE_PAYKIT_SECRET_STATE.name))
            .thenReturn(secretStateJson())
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(SECRET_KEY_HEX)
        whenever(pubkyService.currentPublicKey()).thenReturn(OWN_KEY)
        whenever(pubkyService.encryptedLinkSnapshotRecipient(LINK_SNAPSHOT)).thenReturn(CONTACT_KEY)
        whenever(pubkyService.restoreEncryptedLink(SECRET_KEY_HEX, LINK_SNAPSHOT)).thenReturn(LINK_ID)
        whenever(pubkyService.getPrivatePayments(LINK_ID)).thenReturn(emptyList())
        whenever(lightningRepo.canReceive()).thenReturn(false)

        val error = sut.prepareSavedContacts(
            publicKeys = listOf(CONTACT_KEY),
            requireImmediatePublication = true,
        ).exceptionOrNull()

        assertEquals(PublicPaykitError.NoSupportedEndpoint, error)
        verify(pubkyService, never()).setPrivatePayments(eq(LINK_ID), any())
    }

    @Test
    fun `prepareSavedContacts defers fresh link when immediate publication is requested`() = test {
        startForegroundWithSharingEnabled()
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(SECRET_KEY_HEX)
        whenever(pubkyService.currentPublicKey()).thenReturn(OWN_KEY)
        stubPendingFreshHandshake()

        val result = sut.prepareSavedContacts(
            publicKeys = listOf(CONTACT_KEY),
            requireImmediatePublication = true,
        )

        assertTrue(result.isSuccess)
        verify(pubkyService, never()).setPrivatePayments(any(), any())
    }

    @Test
    fun `prepareSavedContacts fails immediate publish when stale fetch defers existing endpoint update`() = test {
        val retryLinkId = "retry-link-id"
        startForegroundWithSharingEnabled()
        settingsData.value = settingsData.value.copy(publicPaykitOnchainEnabled = false)
        cacheData.value = PrivatePaykitCacheData(
            contacts = mapOf(
                CONTACT_KEY to PrivatePaykitContactCacheData(
                    lastLocalPayloadHash = LOCAL_PAYLOAD_HASH,
                    linkCompletedAt = NOW_SECONDS - 60,
                ),
            ),
        )
        whenever(keychain.loadString(Keychain.Key.PRIVATE_PAYKIT_SECRET_STATE.name))
            .thenReturn(secretStateJson())
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(SECRET_KEY_HEX)
        whenever(pubkyService.currentPublicKey()).thenReturn(OWN_KEY)
        whenever(pubkyService.encryptedLinkSnapshotRecipient(LINK_SNAPSHOT)).thenReturn(CONTACT_KEY)
        whenever(pubkyService.restoreEncryptedLink(SECRET_KEY_HEX, LINK_SNAPSHOT))
            .thenReturn(LINK_ID)
            .thenReturn(retryLinkId)
        whenever(pubkyService.getPrivatePayments(LINK_ID))
            .thenAnswer { throw PaykitFfiException.InvalidData("bad mac while decrypting payload") }
        whenever(pubkyService.getPrivatePayments(retryLinkId))
            .thenAnswer { throw PaykitFfiException.InvalidData("bad mac while decrypting payload") }
        whenever(lightningRepo.canReceive()).thenReturn(false)

        val error = sut.prepareSavedContacts(
            publicKeys = listOf(CONTACT_KEY),
            requireImmediatePublication = true,
        ).exceptionOrNull()

        assertEquals(PrivatePaykitError.PrivateUnavailable, error)
        verify(pubkyService, never()).setPrivatePayments(any(), any())
    }

    @Test
    fun `prepareSavedContacts skips publish when eligibility changes after endpoint build`() = test {
        startForegroundWithSharingEnabled()
        whenever(walletRepo.walletExists()).thenReturn(true, true, false)
        cacheData.value = PrivatePaykitCacheData(
            contacts = mapOf(
                CONTACT_KEY to PrivatePaykitContactCacheData(
                    linkCompletedAt = NOW_SECONDS,
                ),
            ),
        )
        whenever(keychain.loadString(Keychain.Key.PRIVATE_PAYKIT_SECRET_STATE.name))
            .thenReturn(secretStateJson())
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(SECRET_KEY_HEX)
        whenever(pubkyService.currentPublicKey()).thenReturn(OWN_KEY)
        whenever(pubkyService.encryptedLinkSnapshotRecipient(LINK_SNAPSHOT)).thenReturn(CONTACT_KEY)
        whenever(pubkyService.restoreEncryptedLink(SECRET_KEY_HEX, LINK_SNAPSHOT)).thenReturn(LINK_ID)
        whenever(pubkyService.getPrivatePayments(LINK_ID)).thenReturn(emptyList())
        whenever(pubkyService.serializeEncryptedLink(LINK_ID)).thenReturn(UPDATED_LINK_SNAPSHOT)
        whenever(addressReservationRepo.currentOrRotatedAddress(CONTACT_KEY)).thenReturn(
            Result.success("bcrt1qprivate"),
        )

        sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()

        verify(pubkyService).getPrivatePayments(LINK_ID)
        verify(pubkyService, never()).setPrivatePayments(eq(LINK_ID), any())
    }

    @Test
    fun `prepareSavedContacts measures private endpoint map with compact payload json`() = test {
        val bolt11 = "l".repeat(PAYLOAD_LIMIT_BOLT11_LENGTH)
        startForegroundWithSharingEnabled()
        cacheData.value = PrivatePaykitCacheData(
            contacts = mapOf(
                CONTACT_KEY to PrivatePaykitContactCacheData(
                    linkCompletedAt = NOW_SECONDS,
                ),
            ),
        )
        whenever(keychain.loadString(Keychain.Key.PRIVATE_PAYKIT_SECRET_STATE.name))
            .thenReturn(secretStateJson())
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(SECRET_KEY_HEX)
        whenever(pubkyService.currentPublicKey()).thenReturn(OWN_KEY)
        whenever(pubkyService.encryptedLinkSnapshotRecipient(LINK_SNAPSHOT)).thenReturn(CONTACT_KEY)
        whenever(pubkyService.restoreEncryptedLink(SECRET_KEY_HEX, LINK_SNAPSHOT)).thenReturn(LINK_ID)
        whenever(pubkyService.getPrivatePayments(LINK_ID)).thenReturn(emptyList())
        whenever(pubkyService.serializeEncryptedLink(LINK_ID)).thenReturn(UPDATED_LINK_SNAPSHOT)
        whenever(addressReservationRepo.currentOrRotatedAddress(CONTACT_KEY)).thenReturn(
            Result.success("bcrt1qprivate"),
        )
        whenever(lightningRepo.canReceive()).thenReturn(true)
        whenever(lightningRepo.createInvoice(anyOrNull(), any(), any())).thenReturn(Result.success(bolt11))
        whenever(coreService.decode(bolt11)).thenReturn(
            Scanner.Lightning(lightningInvoice(bolt11, byteArrayOf(1, 2, 3))),
        )

        sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()

        verify(pubkyService).setPrivatePayments(
            eq(LINK_ID),
            argThat<List<FfiPaymentEntry>> {
                any {
                    it.methodId == MethodId.Bolt11.rawValue &&
                        it.endpointData == PublicPaykitRepo.serializePayload(bolt11)
                }
            },
        )
    }

    @Test
    fun `restoreBackup reports private state persistence failures`() = test {
        whenever(pubkyService.encryptedLinkSnapshotRecipient(LINK_SNAPSHOT)).thenReturn(CONTACT_KEY)
        whenever(cacheStore.update(any())).thenAnswer { throw PrivatePaykitTestError("disk failed") }

        val result = sut.restoreBackup(
            mapOf(
                CONTACT_KEY to PrivatePaykitContactLinkBackupV1(
                    publicKey = CONTACT_KEY,
                    linkSnapshotHex = LINK_SNAPSHOT,
                    linkCompletedAt = NOW_SECONDS,
                ),
            ),
        )

        assertTrue(result.exceptionOrNull() is PrivatePaykitError.StatePersistenceFailed)
    }

    @Test
    fun `stale private link failures clear cached endpoints and start recovery`() = test {
        prepareStaleLinkFailure(PrivatePaykitTestError("decrypt failed"))

        repeat(3) {
            assertEquals(PublicPaykitPaymentResult.NoEndpoint, sut.beginSavedContactPayment(CONTACT_KEY).getOrThrow())
        }

        assertStaleLinkRecoveryStarted()
    }

    @Test
    fun `wrapped stale Paykit failures clear cached endpoints and start recovery`() = test {
        prepareStaleLinkFailure(
            PrivatePaykitTestError(
                message = "service queue failed",
                cause = PaykitFfiException.InvalidData("bad mac while decrypting payload"),
            ),
        )

        repeat(3) {
            assertEquals(PublicPaykitPaymentResult.NoEndpoint, sut.beginSavedContactPayment(CONTACT_KEY).getOrThrow())
        }

        assertStaleLinkRecoveryStarted()
    }

    @Test
    fun `beginSavedContactPayment discards attempted private lightning invoices`() = test {
        cacheData.value = PrivatePaykitCacheData(
            contacts = mapOf(
                CONTACT_KEY to PrivatePaykitContactCacheData(
                    remoteEndpoints = listOf(
                        PrivatePaykitStoredPaymentEntryData(
                            methodId = MethodId.Bolt11.rawValue,
                            endpointData = PublicPaykitRepo.serializePayload(PRIVATE_BOLT11),
                        ),
                    ),
                    lastLocalPayloadHash = LOCAL_PAYLOAD_HASH,
                    linkCompletedAt = NOW_SECONDS - 60,
                ),
            ),
        )
        whenever(keychain.loadString(Keychain.Key.PRIVATE_PAYKIT_SECRET_STATE.name))
            .thenReturn(secretStateJson())
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(SECRET_KEY_HEX)
        whenever(pubkyService.currentPublicKey()).thenReturn(OWN_KEY)
        whenever(pubkyService.encryptedLinkSnapshotRecipient(LINK_SNAPSHOT)).thenReturn(CONTACT_KEY)
        whenever(pubkyService.restoreEncryptedLink(SECRET_KEY_HEX, LINK_SNAPSHOT)).thenReturn(LINK_ID)
        whenever(pubkyService.getPrivatePayments(LINK_ID)).thenReturn(emptyList())
        whenever(pubkyService.serializeEncryptedLink(LINK_ID)).thenReturn(UPDATED_LINK_SNAPSHOT)
        whenever(publicPaykitRepo.payableEndpoints(any())).thenAnswer { it.getArgument<List<Endpoint>>(0) }
        whenever(publicPaykitRepo.beginPayment(CONTACT_KEY))
            .thenReturn(Result.success(PublicPaykitPaymentResult.NoEndpoint))
        whenever(coreService.decode(PRIVATE_BOLT11)).thenReturn(
            Scanner.Lightning(lightningInvoice(PRIVATE_BOLT11, byteArrayOf(1, 2, 3))),
        )
        whenever(lightningRepo.getPayments()).thenReturn(
            Result.success(
                listOf(
                    paymentDetails(
                        id = PRIVATE_PAYMENT_HASH,
                        status = PaymentStatus.SUCCEEDED,
                    ),
                ),
            ),
        )
        rememberSavedContact()

        val result = sut.beginSavedContactPayment(CONTACT_KEY).getOrThrow()
        val snapshot = sut.backupSnapshot().getOrThrow()?.get(CONTACT_KEY)

        assertEquals(PublicPaykitPaymentResult.NoEndpoint, result)
        assertNotNull(snapshot)
        assertEquals(emptyMap(), snapshot.remoteEndpoints)
    }

    @Test
    fun `discardRemoteOnchainEndpoints removes attempted private address from cache`() = test {
        restoreContactBackup()

        sut.discardRemoteOnchainEndpoints(CONTACT_KEY, setOf("bcrt1qprivate")).getOrThrow()

        val snapshot = sut.backupSnapshot().getOrThrow()?.get(CONTACT_KEY)
        assertNotNull(snapshot)
        assertEquals(emptyMap(), snapshot.remoteEndpoints)
    }

    @Test
    fun `stale private endpoint fetch restores link snapshot and retries once`() = test {
        val retryLinkId = "retry-link-id"
        cacheData.value = PrivatePaykitCacheData(
            contacts = mapOf(
                CONTACT_KEY to PrivatePaykitContactCacheData(
                    lastLocalPayloadHash = LOCAL_PAYLOAD_HASH,
                    linkCompletedAt = NOW_SECONDS - 60,
                ),
            ),
        )
        whenever(keychain.loadString(Keychain.Key.PRIVATE_PAYKIT_SECRET_STATE.name))
            .thenReturn(secretStateJson())
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(SECRET_KEY_HEX)
        whenever(pubkyService.currentPublicKey()).thenReturn(OWN_KEY)
        whenever(pubkyService.encryptedLinkSnapshotRecipient(LINK_SNAPSHOT)).thenReturn(CONTACT_KEY)
        whenever(pubkyService.restoreEncryptedLink(SECRET_KEY_HEX, LINK_SNAPSHOT))
            .thenReturn(LINK_ID)
            .thenReturn(retryLinkId)
        whenever(pubkyService.getPrivatePayments(LINK_ID))
            .thenAnswer { throw PaykitFfiException.InvalidData("bad mac while decrypting payload") }
        whenever(pubkyService.getPrivatePayments(retryLinkId)).thenReturn(
            listOf(
                FfiPaymentEntry(
                    methodId = MethodId.P2wpkh.rawValue,
                    endpointData = PublicPaykitRepo.serializePayload("bcrt1qprivate"),
                ),
            ),
        )
        whenever(pubkyService.serializeEncryptedLink(retryLinkId)).thenReturn(UPDATED_LINK_SNAPSHOT)
        whenever(publicPaykitRepo.payableEndpoints(any())).thenAnswer { it.getArgument<List<Endpoint>>(0) }
        whenever(coreService.isAddressUsed("bcrt1qprivate")).thenReturn(false)
        whenever(lightningRepo.getPayments()).thenReturn(Result.success(emptyList()))
        rememberSavedContact()

        val result = sut.beginSavedContactPayment(CONTACT_KEY).getOrThrow()
        val snapshot = sut.backupSnapshot().getOrThrow()?.get(CONTACT_KEY)

        assertTrue(result is PublicPaykitPaymentResult.Opened)
        assertNotNull(snapshot)
        assertEquals(
            mapOf(MethodId.P2wpkh.rawValue to PublicPaykitRepo.serializePayload("bcrt1qprivate")),
            snapshot.remoteEndpoints,
        )
        verify(pubkyService).closeEncryptedLink(LINK_ID)
        verify(pubkyService).getPrivatePayments(retryLinkId)
    }

    @Test
    fun `isDuplicatePaymentError detects wrapped duplicate payment messages`() {
        val error = PrivatePaykitTestError("service queue failed", cause = AppError("Duplicate payment."))

        assertTrue(PrivatePaykitRepo.isDuplicatePaymentError(error))
    }

    private suspend fun prepareStaleLinkFailure(error: Throwable) {
        cacheData.value = PrivatePaykitCacheData(
            contacts = mapOf(
                CONTACT_KEY to PrivatePaykitContactCacheData(
                    remoteEndpoints = listOf(
                        PrivatePaykitStoredPaymentEntryData(
                            methodId = MethodId.P2wpkh.rawValue,
                            endpointData = PublicPaykitRepo.serializePayload("bcrt1qprivate"),
                        ),
                    ),
                    lastLocalPayloadHash = LOCAL_PAYLOAD_HASH,
                    linkCompletedAt = NOW_SECONDS - 60,
                ),
            ),
        )
        whenever(keychain.loadString(Keychain.Key.PRIVATE_PAYKIT_SECRET_STATE.name))
            .thenReturn(secretStateJson())
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(SECRET_KEY_HEX)
        whenever(pubkyService.currentPublicKey()).thenReturn(OWN_KEY)
        whenever(pubkyService.fetchFileString(any())).thenAnswer { throw PrivatePaykitTestError("not found") }
        whenever(pubkyService.encryptedLinkSnapshotRecipient(LINK_SNAPSHOT)).thenReturn(CONTACT_KEY)
        whenever(pubkyService.restoreEncryptedLink(SECRET_KEY_HEX, LINK_SNAPSHOT)).thenReturn(LINK_ID)
        whenever(pubkyService.getPrivatePayments(LINK_ID)).thenAnswer { throw error }
        whenever(publicPaykitRepo.beginPayment(CONTACT_KEY))
            .thenReturn(Result.success(PublicPaykitPaymentResult.NoEndpoint))
        rememberSavedContact()
    }

    private suspend fun assertStaleLinkRecoveryStarted() {
        val snapshot = sut.backupSnapshot().getOrThrow()?.get(CONTACT_KEY)

        assertNotNull(snapshot)
        assertNull(snapshot.linkSnapshotHex)
        assertEquals(emptyMap(), snapshot.remoteEndpoints)
        assertEquals(NOW_SECONDS, snapshot.recoveryStartedAt)
    }

    private fun createSut() = PrivatePaykitRepo(
        ioDispatcher = testDispatcher,
        pubkyService = pubkyService,
        keychain = keychain,
        cacheStore = cacheStore,
        settingsStore = settingsStore,
        addressReservationRepo = addressReservationRepo,
        lightningRepo = lightningRepo,
        walletRepo = walletRepo,
        publicPaykitRepo = publicPaykitRepo,
        coreService = coreService,
        clock = clock,
    )

    private suspend fun restoreContactBackup() {
        whenever(pubkyService.encryptedLinkSnapshotRecipient(LINK_SNAPSHOT)).thenReturn(CONTACT_KEY)
        val remoteEndpoints = mapOf(
            MethodId.P2wpkh.rawValue to PublicPaykitRepo.serializePayload("bcrt1qprivate"),
        )
        sut.restoreBackup(
            mapOf(
                CONTACT_KEY to PrivatePaykitContactLinkBackupV1(
                    publicKey = CONTACT_KEY,
                    linkSnapshotHex = LINK_SNAPSHOT,
                    remoteEndpoints = remoteEndpoints,
                    linkCompletedAt = NOW_SECONDS - 60,
                ),
            ),
        ).getOrThrow()
    }

    private suspend fun rememberSavedContact() {
        sut.prepareSavedContacts(listOf(CONTACT_KEY)).getOrThrow()
    }

    private suspend fun stubPendingFreshHandshake() {
        whenever(pubkyService.initiateEncryptedLink(SECRET_KEY_HEX, CONTACT_KEY)).thenReturn(HANDSHAKE_ID)
        whenever(pubkyService.advanceHandshake(HANDSHAKE_ID))
            .thenAnswer { throw PrivatePaykitTestError("transition_transport failed isHandshake") }
        whenever(pubkyService.serializeEncryptedLinkHandshake(HANDSHAKE_ID)).thenReturn(UPDATED_HANDSHAKE_SNAPSHOT)
    }

    private fun startForegroundWithSharingEnabled() {
        settingsData.value = SettingsData(
            sharesPublicPaykitEndpoints = true,
            sharesPrivatePaykitEndpoints = true,
        )
        whenever(walletRepo.walletExists()).thenReturn(true)
        App.currentActivity = CurrentActivity().also { it.onActivityStarted(mock<Activity>()) }
    }

    private fun paymentDetails(
        id: String,
        status: PaymentStatus,
    ) = PaymentDetails(
        id = id,
        kind = PaymentKind.Bolt11(
            hash = id,
            preimage = null,
            secret = null,
            description = "",
            bolt11 = PRIVATE_BOLT11,
        ),
        amountMsat = 1000uL,
        feePaidMsat = 0uL,
        direction = PaymentDirection.OUTBOUND,
        status = status,
        latestUpdateTimestamp = NOW_SECONDS.toULong(),
    )

    private fun lightningInvoice(bolt11: String, paymentHash: ByteArray) = LightningInvoice(
        bolt11 = bolt11,
        paymentHash = paymentHash,
        amountSatoshis = 0uL,
        timestampSeconds = 0u,
        expirySeconds = 86_400u,
        isExpired = false,
        description = "",
        networkType = NetworkType.REGTEST,
        payeeNodeId = null,
    )

    private fun secretStateJson(
        linkSnapshotHex: String? = LINK_SNAPSHOT,
        handshakeSnapshotHex: String? = null,
    ): String {
        val linkSnapshot = linkSnapshotHex?.let { "\"$it\"" } ?: "null"
        val handshakeSnapshot = handshakeSnapshotHex?.let { "\"$it\"" } ?: "null"
        return """
            {"contacts":{"$CONTACT_KEY":{"linkSnapshotHex":$linkSnapshot,"handshakeSnapshotHex":$handshakeSnapshot}}}
        """.trimIndent()
    }
}

private class PrivatePaykitTestError(
    message: String,
    cause: Throwable? = null,
) : AppError(message, cause)

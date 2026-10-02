package to.bitkit.services

import com.synonym.paykit.ContactRecord
import com.synonym.paykit.IdentityStatus
import com.synonym.paykit.LinkedPeerHandshakeReport
import com.synonym.paykit.LinkedPeerRecord
import com.synonym.paykit.LinkedPeerState
import com.synonym.paykit.PaykitApp
import com.synonym.paykit.PaykitAppCapabilities
import com.synonym.paykit.PaykitAppRegistry
import com.synonym.paykit.PaykitException
import com.synonym.paykit.PaykitIdentitySecretKey
import com.synonym.paykit.PaykitSdk
import com.synonym.paykit.PaymentRequestLifecycleState
import com.synonym.paykit.PaymentRequestLocalRole
import com.synonym.paykit.PaymentRequestRecord
import com.synonym.paykit.PaymentRequestRecurrence
import com.synonym.paykit.PaymentRequestTerms
import com.synonym.paykit.PrivatePaymentListDeliveryReport
import com.synonym.paykit.PubkyAuthCompanionClaim
import com.synonym.paykit.PubkyClientConfig
import com.synonym.paykit.PubkyIdentityCapability
import com.synonym.paykit.PubkyLocalSecretKey
import com.synonym.paykit.PubkySessionAccess
import com.synonym.paykit.PubkySessionBootstrap
import com.synonym.paykit.PubkySessionBootstrapResult
import com.synonym.paykit.PublicContactSharingPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import to.bitkit.data.PubkyStore
import to.bitkit.data.PubkyStoreData
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.keychain.KeychainError
import to.bitkit.data.sharedpubky.SharedPubkyClient
import to.bitkit.ext.runSuspendCatching
import to.bitkit.ext.toHex
import to.bitkit.models.PubkyAuthClaim
import to.bitkit.models.PubkyAuthClaim.Item
import to.bitkit.models.PubkyAuthRequestError
import to.bitkit.models.PubkyProfileData
import to.bitkit.repositories.PubkyContactError
import to.bitkit.utils.AppError
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PaykitSdkServiceTest {
    companion object {
        private const val RING_PUBKY = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `request discovery timeout excludes time queued for the SDK`() = runTest {
        val sdk = mock<PaykitSdk>()
        val releaseContacts = CompletableDeferred<Unit>()
        whenever { sdk.contactRecords() }.doSuspendableAnswer {
            releaseContacts.await()
            emptyList()
        }
        whenever { sdk.paykitAppRegistry(RING_PUBKY) }.thenReturn(
            PaykitAppRegistry(
                1u,
                null,
                listOf(PaykitApp("bitkit", "Bitkit", PaykitAppCapabilities(true, true, false, true))),
                null,
                emptyMap(),
            ),
        )
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
        val contacts = async { service.contactRecords() }
        runCurrent()
        val discovery = async { service.canReceivePaymentRequests(RING_PUBKY) }
        advanceTimeBy(6.seconds.inWholeMilliseconds)
        runCurrent()

        assertFalse(discovery.isCompleted)
        verify(sdk, never()).paykitAppRegistry(any())
        releaseContacts.complete(Unit)
        contacts.await()
        assertEquals(true, discovery.await())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `request discovery bounds registry lookup and propagates cancellation`() = runTest {
        val sdk = mock<PaykitSdk>()
        whenever { sdk.paykitAppRegistry(RING_PUBKY) }.doSuspendableAnswer { awaitCancellation() }
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
        val discovery = async { service.canReceivePaymentRequests(RING_PUBKY) }
        advanceTimeBy(5.seconds.inWholeMilliseconds)
        runCurrent()
        assertNull(discovery.await())

        val cancelled = async { service.canReceivePaymentRequests(RING_PUBKY) }
        runCurrent()
        cancelled.cancel()
        assertFailsWith<CancellationException> { cancelled.await() }
        doReturn(null).whenever(sdk).paykitAppRegistry(RING_PUBKY)
        assertEquals(false, service.canReceivePaymentRequests(RING_PUBKY))
    }

    @Test
    fun `private link calls advance once and allow pending handshakes to resume`() = runTest {
        val sdk = mock<PaykitSdk>()
        whenever(sdk.stateRevision()).thenReturn("state")
        whenever(sdk.backupStateRevision()).thenReturn("backup")
        whenever(sdk.ensureLinkWithPeer(any(), any())).thenReturn(
            LinkedPeerHandshakeReport(RING_PUBKY, LinkedPeerState.LINKING, 1uL, null),
            LinkedPeerHandshakeReport(RING_PUBKY, LinkedPeerState.LINKED, 1uL, null),
        )
        val pending = PaykitException.RecoveryRequired("recovery_required", "Handshake pending")
        whenever(sdk.prepareAndResolvePrivateContactPayment(RING_PUBKY, null, null, 1u)).thenThrow(pending)
        whenever(sdk.prepareAndResolvePrivatePaymentRequest(RING_PUBKY, "request", null, 1u)).thenThrow(pending)
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }

        assertEquals(LinkedPeerState.LINKING, service.ensureLinkWithPeer(RING_PUBKY).state)
        assertEquals(LinkedPeerState.LINKED, service.ensureLinkWithPeer(RING_PUBKY).state)
        assertSame(
            pending,
            assertFailsWith<PaykitException.RecoveryRequired> {
                service.prepareAndResolvePrivateContactPayment(RING_PUBKY, null)
            },
        )
        assertSame(
            pending,
            assertFailsWith<PaykitException.RecoveryRequired> {
                service.prepareAndResolvePrivatePaymentRequest(RING_PUBKY, "request", null)
            },
        )
        verify(sdk, times(2)).ensureLinkWithPeer(RING_PUBKY, 1u)
        verify(sdk).prepareAndResolvePrivateContactPayment(RING_PUBKY, null, null, 1u)
        verify(sdk).prepareAndResolvePrivatePaymentRequest(RING_PUBKY, "request", null, 1u)
    }

    @Test
    fun `initialization can be retried after shared state contention`() = runTest {
        val failure = PaykitException.ConcurrentUpdate("concurrent_update", "Resource locked")
        val sdk = mock<PaykitSdk>()
        whenever(sdk.initialize()).thenThrow(failure).thenReturn(mock())
        whenever(sdk.contactRecords()).thenReturn(emptyList())
        val service = PaykitSdkService(
            mock(),
            mock(),
            mock(),
            ioDispatcher = StandardTestDispatcher(testScheduler),
            settingsStore = mock(),
            platformInitializer = {},
            sdkFactory = { sdk },
        )

        assertSame(failure, assertFailsWith<PaykitException.ConcurrentUpdate> { service.initialize() })
        assertSame(failure, assertFailsWith<PaykitException.ConcurrentUpdate> { service.contactRecords() })
        service.initialize()
        assertEquals(emptyList(), service.contactRecords())
    }

    @Test
    fun `wallet wipe drains initialization before cleanup and allows fresh work`() = runTest {
        val sdk = mock<PaykitSdk>()
        whenever(sdk.contactRecords()).thenReturn(emptyList())
        val releaseWipe = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        lateinit var service: PaykitSdkService
        lateinit var wipe: Deferred<Unit>
        service = PaykitSdkService(
            mock(),
            mock(),
            mock(),
            ioDispatcher = StandardTestDispatcher(testScheduler),
            settingsStore = mock(),
            platformInitializer = {
                wipe = async(start = CoroutineStart.UNDISPATCHED) {
                    service.withWalletWipe {
                        events.add("cleanup")
                        releaseWipe.await()
                    }
                }
            },
            sdkFactory = { sdk },
        )
        service.initialize()
        events.add("initialized")
        releaseWipe.complete(Unit)
        wipe.await()
        assertEquals(listOf("initialized", "cleanup"), events)
        assertEquals(emptyList(), service.contactRecords())
    }

    @Test
    fun `wallet wipe discards handles and backup fingerprints even when cleanup fails`() = runTest {
        val sdk = mock<PaykitSdk>()
        whenever(sdk.processPendingPrivateMessages()).thenReturn(emptyList())
        whenever(sdk.stateRevision()).thenReturn("state")
        whenever(sdk.backupStateRevision()).thenReturn("backup")
        var handlesCreated = 0
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) {
            handlesCreated++
            sdk
        }
        repeat(2) { service.processPendingPrivateMessages() }
        assertFailsWith<AppError> {
            service.withWalletWipe {
                service.processPendingPrivateMessages()
                assertEquals(2, handlesCreated)
                throw AppError("Cleanup failed")
            }
        }
        service.processPendingPrivateMessages()
        assertEquals(3, handlesCreated)
        verify(sdk, times(3)).backupStateRevision()
    }

    @Test
    fun `storage callbacks translate platform errors and preserve sdk errors`() {
        for (error in listOf(KeychainError.FailedToLoad("state"), KeychainError.FailedToSave("state"))) {
            val mapped = assertFailsWith<PaykitException.Storage> {
                paykitStorageCallback("state_save_failed") { throw error }
            }
            assertEquals("state_save_failed", mapped.code)
        }
        val conflict = PaykitException.Storage("revision_conflict", "State changed")
        assertSame(
            conflict,
            assertFailsWith<PaykitException.Storage> {
                paykitStorageCallback("state_save_failed") { throw conflict }
            },
        )
        assertEquals("healthy", paykitStorageCallback("state_save_failed") { "healthy" })
    }

    @Test
    fun `payer ownership follows execution claims and released actionable work`() {
        data class Case(
            val state: PaymentRequestLifecycleState,
            val payer: String?,
            val claim: String?,
            val hasProof: Boolean = false,
            val visible: Boolean,
        )
        val cases = listOf(
            Case(PaymentRequestLifecycleState.PROPOSED, null, null, visible = true),
            Case(PaymentRequestLifecycleState.ACCEPTED, "other", "bitkit", visible = true),
            Case(PaymentRequestLifecycleState.ACCEPTED, "bitkit", "other", visible = false),
            Case(PaymentRequestLifecycleState.ACCEPTED, "other", null, visible = true),
            Case(PaymentRequestLifecycleState.ACCEPTED, "other", null, hasProof = true, visible = false),
            Case(PaymentRequestLifecycleState.ACTIVE_RECURRING, "other", null, hasProof = true, visible = true),
            Case(PaymentRequestLifecycleState.ACTIVE_RECURRING, "bitkit", "other", visible = false),
            Case(PaymentRequestLifecycleState.PROOF_SUBMITTED, "other", "bitkit", hasProof = true, visible = true),
            Case(PaymentRequestLifecycleState.PROOF_SUBMITTED, "other", null, hasProof = true, visible = false),
            Case(PaymentRequestLifecycleState.CANCELED, "other", null, visible = false),
            Case(PaymentRequestLifecycleState.CANCELED, "bitkit", null, visible = true),
            Case(PaymentRequestLifecycleState.PROPOSAL_EXPIRED, null, null, visible = true),
        )
        cases.forEach { case ->
            val record = mock<PaymentRequestRecord>()
            whenever(record.localRole).thenReturn(PaymentRequestLocalRole.PAYER)
            whenever(record.state).thenReturn(case.state)
            whenever(record.payerAppId).thenReturn(case.payer)
            whenever(record.executionClaimAppId).thenReturn(case.claim)
            whenever(record.paymentProofs).thenReturn(if (case.hasProof) listOf(mock()) else emptyList())

            assertEquals(case.visible, isBitkitPaymentRequest(record), case.toString())
        }
    }

    @Test
    fun `all payment requests retain server payee records while payment API remains app scoped`() = runTest {
        val server = mock<PaymentRequestRecord> {
            on { localRole }.thenReturn(PaymentRequestLocalRole.PAYEE)
            on { proposalAppId }.thenReturn("marketplace")
        }
        val bitkit = mock<PaymentRequestRecord> {
            on { localRole }.thenReturn(PaymentRequestLocalRole.PAYEE)
            on { proposalAppId }.thenReturn("bitkit")
        }
        val sdk = mock<PaykitSdk>()
        whenever(
            sdk.identityStatus()
        ).thenReturn(IdentityStatus(RING_PUBKY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(sdk.listPaymentRequests(any())).thenReturn(listOf(server, bitkit))
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }

        assertEquals(listOf(server, bitkit), service.allPaymentRequests(RING_PUBKY))
        assertEquals(listOf(bitkit), service.paymentRequests())
    }

    @Test
    fun `all payment requests reject a different active identity before listing`() = runTest {
        val sdk = mock<PaykitSdk>()
        whenever(
            sdk.identityStatus()
        ).thenReturn(IdentityStatus(RING_PUBKY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }

        assertFailsWith<IllegalStateException> {
            service.allPaymentRequests("a3mduedw686dysw8ndr5c1dyry5h8k6i8hzbbmx9gf9k43zq4s9o")
        }
        verify(sdk, never()).listPaymentRequests(any())
    }

    @Test
    fun `companion approval rejects mismatched requests before accessing keys or transport`() = runTest {
        val watchOnly = PubkyAuthClaim(Item.WATCH_ONLY_ACCOUNT_V1)
        val claim = PubkyAuthCompanionClaim(
            queryParameter = PubkyAuthClaim.QUERY_PARAMETER,
            claimType = watchOnly.wireValue,
            unsignedPayload = ByteArray(84).apply { this[0] = 1 },
        )
        val url = "pubkyauth://signin?x-bitkit-claim=${watchOnly.wireValue}"
        val invalidRequests = listOf(
            "pubkyauth://signin" to claim,
            "$url&x-bitkit-claim=${watchOnly.wireValue}" to claim,
            "pubkyauth://signin?x-bitkit-claim=unknown" to claim,
            "$url." to claim,
            "$url.${watchOnly.wireValue}" to claim,
            "pubkyauth://signin?x-bitkit-claim=paykit-access-and-watch-only-account-v1" to claim,
            "pubkyauth://signin?x-bitkit-claim=paykit-access-v1.watch-only-account-v1" to
                claim.copy(claimType = "watch-only-account-v1.paykit-access-v1"),
            "pubkyauth://signin?x-bitkit-claim=watch-only-account-v1.paykit-access-v1" to
                claim.copy(claimType = "paykit-access-v1.watch-only-account-v1"),
            url to claim.copy(queryParameter = "other-claim"),
            url to claim.copy(claimType = PubkyAuthClaim(Item.PAYKIT_ACCESS_V1).wireValue),
            url to claim.copy(unsignedPayload = ByteArray(124)),
            "pubkyauth://signin?x-bitkit-claim=paykit-access-v1" to
                claim.copy(claimType = PubkyAuthClaim(Item.PAYKIT_ACCESS_V1).wireValue),
        )
        for ((authUrl, companion) in invalidRequests) {
            val keychain = mock<Keychain>()
            val sdk = mock<PaykitSdk>()
            val service = PaykitSdkService(mock(), keychain, mock(), settingsStore = mock()) { sdk }
            assertTrue(
                runSuspendCatching {
                    service.approveAuthWithCompanionClaim(
                        authUrl,
                        PubkyAuthClaim.REQUIRED_CAPABILITIES,
                        "test",
                        "secret",
                        companion,
                    )
                }.isFailure,
            )
            verifyNoInteractions(keychain, sdk)
        }
    }

    @Test
    fun `registered identity activation persists credentials or clears partial activation`() = runTest {
        for (failure in listOf(null, "session", "secret", "initialize", "cancel")) {
            val keychain = mock<Keychain>()
            val blocking = mock<Keychain.BlockingAccess>()
            whenever(keychain.accessBlocking<Any?>(any())).doAnswer {
                it.getArgument<Keychain.BlockingAccess.() -> Any?>(0).invoke(blocking)
            }
            val bytes = ByteArray(32) { 1 }
            val sdk = mock<PaykitSdk>()
            whenever(sdk.contactRecords()).thenReturn(emptyList())
            val access = mock<PubkySessionAccess>()
            val secret = mock<PubkyLocalSecretKey>()
            val noise = mock<PaykitIdentitySecretKey>()
            whenever(secret.exportBytes()).thenReturn(bytes)
            whenever(noise.exportBytes()).thenReturn(bytes)
            whenever(access.exportSessionSecret()).thenReturn("new-session")
            whenever(access.exportLocalSecretKey()).thenReturn(secret)
            whenever(access.exportPaykitIdentitySecretKey()).thenReturn(noise)
            val error = if (failure == "cancel") {
                CancellationException("cancelled")
            } else {
                IllegalStateException("activation failed")
            }
            when (failure) {
                "session" -> whenever(keychain.upsertString(Keychain.Key.PAYKIT_SESSION.name, "new-session"))
                    .thenThrow(error)
                "secret" -> whenever(keychain.upsertString(Keychain.Key.PUBKY_SECRET_KEY.name, bytes.toHex()))
                    .thenThrow(error)
                "initialize", "cancel" -> whenever(sdk.initialize()).thenThrow(error)
            }
            var handlesCreated = 0
            val store = mock<PubkyStore>()
            whenever(store.data).thenReturn(flowOf(PubkyStoreData()))
            val service = PaykitSdkService(mock(), keychain, store, settingsStore = mock()) {
                handlesCreated++
                sdk
            }
            val result = PubkySessionBootstrapResult(access, "pubky_test", PubkyIdentityCapability.PRIVATE_LINK_CAPABLE)

            if (failure == null) {
                service.activateRegisteredIdentity(result)
                inOrder(keychain, sdk) {
                    verify(keychain).upsertString(Keychain.Key.PAYKIT_SESSION.name, "new-session")
                    verify(keychain).upsertString(Keychain.Key.PUBKY_SECRET_KEY.name, bytes.toHex())
                    verify(sdk).initialize()
                }
                verify(blocking, never()).delete(any())
            } else {
                val thrown = assertFailsWith(error::class) { service.activateRegisteredIdentity(result) }
                assertEquals(error, thrown)
                verify(blocking).delete(Keychain.Key.PAYKIT_SESSION.name)
                verify(blocking).delete(Keychain.Key.PUBKY_SECRET_KEY.name)
                val handlesBeforeReload = handlesCreated
                service.contactRecords()
                assertEquals(handlesBeforeReload + 1, handlesCreated)
            }
        }
    }

    @Test
    fun `deletion blocks the identity before removing the contact`() = runTest {
        for (failBlock in listOf(false, true)) {
            val sdk = mock<PaykitSdk>()
            whenever(sdk.paymentRequests()).thenReturn(emptyList())
            val contact = mock<ContactRecord>()
            whenever(sdk.contactRecord(RING_PUBKY)).thenReturn(contact)
            val peer = contactPeer(LinkedPeerState.LINKED)
            whenever(sdk.linkedPeers()).thenReturn(listOf(peer))
            if (failBlock) {
                whenever(sdk.blockPeer(RING_PUBKY))
                    .thenThrow(IllegalStateException("storage failure"))
            }
            val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
            if (failBlock) {
                assertFailsWith<IllegalStateException> { service.removeContact(RING_PUBKY) }
                verify(sdk, never()).removeContact(any())
            } else {
                service.removeContact(RING_PUBKY)
                inOrder(sdk) {
                    verify(sdk).blockPeer(RING_PUBKY)
                    verify(sdk).removeContact(RING_PUBKY)
                }
            }
        }
    }

    @Test
    fun `active subscription prevents deletion until it ends`() = runTest {
        for ((role, endsNaturally) in listOf(
            PaymentRequestLocalRole.PAYER to false,
            PaymentRequestLocalRole.PAYER to true,
            PaymentRequestLocalRole.PAYEE to false,
        )) {
            val sdk = mock<PaykitSdk>()
            val recurrence = mock<PaymentRequestRecurrence>()
            val requestTerms = mock<PaymentRequestTerms> { on { this.recurrence } doReturn recurrence }
            val request = mock<PaymentRequestRecord> {
                on { counterparty } doReturn RING_PUBKY
                on { localRole } doReturn role
                on { state } doReturn PaymentRequestLifecycleState.ACTIVE_RECURRING
                on { terms } doReturn requestTerms
            }
            whenever(sdk.linkedPeers()).thenReturn(emptyList())
            whenever(sdk.paymentRequests()).thenReturn(listOf(request))
            val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
            assertFailsWith<PubkyContactError.ActiveSubscription> { service.removeContact(RING_PUBKY) }
            verify(sdk, never()).blockPeer(any())
            verify(sdk, never()).removeContact(any())
            if (endsNaturally) {
                whenever(recurrence.endsAt).thenReturn("2026-02-01T00:00:00Z")
            } else {
                whenever(request.state).thenReturn(PaymentRequestLifecycleState.CANCELED)
            }
            service.removeContact(RING_PUBKY)
            verify(sdk).removeContact(RING_PUBKY)
        }
    }

    @Test
    fun `unreadable profile cache stops activation`() = runTest {
        for (error in listOf(
            PaykitException.Identity("identity_error", "restore Pubky grant session from platform provider"),
            PaykitException.Storage("storage_error", "unavailable"),
        )) {
            val keychain = mock<Keychain>()
            val sdk = mock<PaykitSdk>()
            val store = mock<PubkyStore>()
            whenever(store.data).thenReturn(flow { throw error })
            val access = mock<PubkySessionAccess>()
            whenever(access.exportSessionSecret()).thenReturn("new-session")
            val service = PaykitSdkService(mock(), keychain, store, settingsStore = mock()) { sdk }

            val thrown = assertFailsWith<PaykitException> {
                service.activateRegisteredIdentity(
                    PubkySessionBootstrapResult(
                        access,
                        "pubky_test",
                        PubkyIdentityCapability.PRIVATE_LINK_CAPABLE
                    )
                )
            }

            assertEquals(error, thrown)
            verify(sdk, never()).initialize()
        }
    }

    @Test
    fun `deletion withdraws private endpoints before blocking even when withdrawal fails`() = runTest {
        for (failWithdrawal in listOf(false, true)) {
            val sdk = mock<PaykitSdk>()
            whenever(sdk.paymentRequests()).thenReturn(emptyList())
            whenever(sdk.linkedPeers()).thenReturn(
                listOf(contactPeer(LinkedPeerState.LINKED)),
            )
            val withdrawal = whenever(
                sdk.clearPrivatePaymentListAndProcessOutbound(RING_PUBKY),
            )
            if (failWithdrawal) {
                withdrawal.thenThrow(IllegalStateException("network unavailable"))
            } else {
                withdrawal.thenReturn(
                    PrivatePaymentListDeliveryReport(emptyList(), emptyList(), emptyList(), emptyList()),
                )
            }
            val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
            service.removeContact(RING_PUBKY)
            inOrder(sdk) {
                verify(sdk).clearPrivatePaymentListAndProcessOutbound(RING_PUBKY)
                verify(sdk).blockPeer(RING_PUBKY)
                verify(sdk).removeContact(RING_PUBKY)
            }
        }
    }

    @Test
    fun `activation isolates cached identity data by sdk or cache owner`() = runTest {
        val originalKey = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        val differentKey = "5" + originalKey.drop(1)
        for ((previousKey, cachedOwner, resetFails) in identityCacheCases(originalKey, differentKey)) {
            val keychain = mock<Keychain>()
            val sdk = mock<PaykitSdk>()
            whenever(
                sdk.identityStatus()
            ).thenReturn(IdentityStatus(previousKey, PubkyIdentityCapability.PUBLIC_ONLY))
            val originalCache = PubkyStoreData(
                ownerPublicKey = cachedOwner,
                cachedName = "Original profile",
                cachedImageUri = "pubky://original/avatar",
                contactProfileOverrides = mapOf(originalKey to PubkyProfileData("Private label", "")),
            )
            var cache = originalCache
            val store = mock<PubkyStore>()
            whenever(store.data).thenReturn(flowOf(cache))
            val resetError = AppError("Cache unavailable")
            whenever(store.reset()).thenAnswer {
                if (resetFails) throw resetError
                cache = PubkyStoreData()
            }
            val bootstrap = mock<PubkySessionBootstrap>()
            whenever(bootstrap.republishIdentity(any())).thenReturn(true)
            val access = mock<PubkySessionAccess>()
            val noise = mock<PaykitIdentitySecretKey>()
            whenever(noise.exportBytes()).thenReturn(ByteArray(32) { 1 })
            whenever(access.exportSessionSecret()).thenReturn("new-session")
            whenever(access.exportPaykitIdentitySecretKey()).thenReturn(noise)
            val service = PaykitSdkService(mock(), keychain, store, { bootstrap }, settingsStore = mock()) { sdk }

            val result =
                PubkySessionBootstrapResult(
                    access,
                    "pubky$originalKey",
                    PubkyIdentityCapability.PRIVATE_LINK_CAPABLE
                )
            if (resetFails) {
                assertEquals(resetError, assertFailsWith<AppError> { service.activateRegisteredIdentity(result) })
                verify(sdk, never()).initialize()
                assertEquals(originalCache, cache)
                continue
            }
            service.activateRegisteredIdentity(result)

            if (cachedOwner == differentKey) {
                assertEquals(PubkyStoreData(), cache)
                inOrder(store, sdk) {
                    verify(store).reset()
                    verify(sdk).initialize()
                }
            } else {
                assertEquals(originalCache, cache)
                verify(store, never()).reset()
            }
        }
    }

    @Test
    fun `only explicit readd unblocks the contact`() = runTest {
        for (restoreConnection in listOf(false, true)) {
            val sdk = mock<PaykitSdk>()
            whenever(sdk.saveContact(any())).thenReturn(mock())
            whenever(sdk.linkedPeers()).thenReturn(
                listOf(
                    contactPeer(LinkedPeerState.BLOCKED),
                ),
            )
            val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
            if (restoreConnection) {
                service.saveContact(RING_PUBKY, "Contact", restorePrivateConnection = true)
                verify(sdk).unblockPeer(RING_PUBKY)
            } else {
                assertFailsWith<IllegalStateException> { service.saveContact(RING_PUBKY, "Contact") }
                verify(sdk, never()).saveContact(any())
                verify(sdk, never()).unblockPeer(any())
            }
        }
    }

    @Test
    fun `failed private connection restoration leaves contact creation retryable`() = runTest {
        for (failurePoint in listOf("lookup", "unblock", "save", "cancel")) {
            val sdk = mock<PaykitSdk>()
            val peers = listOf(
                contactPeer(LinkedPeerState.BLOCKED),
            )
            val failure = if (failurePoint == "cancel") {
                CancellationException("cancelled")
            } else {
                IllegalStateException("storage failure")
            }
            whenever(sdk.linkedPeers()).thenReturn(peers)
            whenever(sdk.saveContact(any())).thenReturn(mock())
            when (failurePoint) {
                "lookup" -> whenever(sdk.linkedPeers()).thenThrow(failure).thenReturn(peers)
                "unblock", "cancel" -> {
                    whenever(sdk.unblockPeer(RING_PUBKY))
                        .thenThrow(failure).thenReturn(peers.last().copy(state = LinkedPeerState.NOT_LINKED))
                }
                "save" -> whenever(sdk.saveContact(any())).thenThrow(failure).thenReturn(mock())
            }
            val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
            val thrown = assertFailsWith<Throwable> {
                service.saveContact(RING_PUBKY, "Contact", restorePrivateConnection = true)
            }
            assertSame(failure, thrown)
            if (failurePoint == "lookup") {
                verify(sdk, never()).blockPeer(any())
            } else {
                verify(sdk).blockPeer(RING_PUBKY)
            }
            service.saveContact(RING_PUBKY, "Contact", restorePrivateConnection = true)
            verify(sdk, atLeastOnce()).saveContact(any())
        }
    }

    @Test
    fun `private connection restoration preserves failures from rollback`() = runTest {
        val sdk = mock<PaykitSdk>()
        val peers = listOf(
            contactPeer(LinkedPeerState.BLOCKED),
        )
        val restorationFailure = IllegalStateException("save failed")
        val rollbackFailure = IllegalStateException("block failed")
        whenever(sdk.linkedPeers()).thenReturn(peers)
        whenever(sdk.saveContact(any())).thenThrow(restorationFailure)
        whenever(sdk.blockPeer(RING_PUBKY)).thenThrow(rollbackFailure)
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }

        val thrown = assertFailsWith<IllegalStateException> {
            service.saveContact(RING_PUBKY, "Contact", restorePrivateConnection = true)
        }

        assertSame(restorationFailure, thrown)
        assertEquals(listOf(rollbackFailure), thrown.suppressed.toList())
        verify(sdk).blockPeer(RING_PUBKY)
    }

    @Test
    fun `blocked peer cleanup does not attempt network delivery`() = runTest {
        val sdk = mock<PaykitSdk>()
        whenever(sdk.linkedPeers()).thenReturn(listOf(contactPeer(LinkedPeerState.BLOCKED)))
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }
        assertNull(service.clearPrivatePaymentList(RING_PUBKY))
        verify(sdk, never()).clearPrivatePaymentListAndProcessOutbound(any())
    }

    @Test
    fun `disabled private capability does not queue withdrawal`() = runTest {
        val sdk = mock<PaykitSdk>()
        val capabilities = PaykitAppCapabilities(false, true, false, true)
        whenever(sdk.linkedPeers()).thenReturn(listOf(contactPeer(LinkedPeerState.LINKED)))
        whenever(sdk.identityStatus()).thenReturn(
            IdentityStatus(RING_PUBKY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE),
        )
        whenever(sdk.paykitAppRegistry(RING_PUBKY)).thenReturn(
            PaykitAppRegistry(1u, null, listOf(PaykitApp("bitkit", "Bitkit", capabilities)), null, emptyMap()),
        )
        val service = PaykitSdkService(mock(), mock(), mock(), settingsStore = mock()) { sdk }

        assertNull(service.clearPrivatePaymentList(RING_PUBKY))

        verify(sdk, never()).clearPrivatePaymentListAndProcessOutbound(any())
        verify(sdk, never()).publishPaykitApp(any(), any())
    }

    @Test
    fun `initialization publishes the saved private sharing preference`() = runTest {
        for (enabled in listOf(false, true)) {
            val sdk = mock<PaykitSdk>()
            val settingsStore = mock<SettingsStore>()
            whenever(settingsStore.data).thenReturn(flowOf(SettingsData(sharesPrivatePaykitEndpoints = enabled)))
            whenever(sdk.identityStatus()).thenReturn(
                IdentityStatus(RING_PUBKY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE),
            )
            val service = PaykitSdkService(
                mock(),
                mock(),
                mock(),
                ioDispatcher = StandardTestDispatcher(testScheduler),
                platformInitializer = {},
                settingsStore = settingsStore,
            ) { sdk }

            service.initialize()

            verify(sdk).publishPaykitApp("Bitkit", PaykitAppCapabilities(enabled, true, false, true))
        }
    }

    private fun contactPeer(state: LinkedPeerState) = LinkedPeerRecord(
        counterparty = RING_PUBKY,
        state = state,
        lastSyncAt = null,
        lastPrivateReceiveAt = null,
        failureCount = 0u,
        localRecoveryAttemptId = null,
        localRecoveryMarkerCreatedAt = null,
        localRecoveryMarkerLastError = null,
        remoteRecoveryAttemptId = null,
        remoteRecoveryMarkerObservedAt = null,
    )

    private val basePubkyClientConfig = PubkyClientConfig(
        requestTimeoutSecs = 30uL,
        localTestnetHost = null,
        authRelayUrl = null,
    )

    @Test
    fun `config keeps contacts private`() {
        assertEquals("staging.bitkit.to", BitkitPaykitSdkConfig.clientId)
        assertEquals(PublicContactSharingPolicy.PRIVATE_ONLY, BitkitPaykitSdkConfig.publicContactSharing)
    }

    @Test
    fun `production preserves Pubky client config`() {
        val config = paykitPubkyClientConfig(
            isLocalE2eBackend = false,
            baseConfig = basePubkyClientConfig,
        )

        assertEquals(basePubkyClientConfig, config)
    }

    @Test
    fun `local E2E uses configured host for Pubky testnet`() {
        val config = paykitPubkyClientConfig(
            isLocalE2eBackend = true,
            localTestnetHost = "192.0.2.1",
            baseConfig = basePubkyClientConfig,
        )

        assertEquals("192.0.2.1", config.localTestnetHost)
        assertEquals(basePubkyClientConfig.requestTimeoutSecs, config.requestTimeoutSecs)
    }

    @Test
    fun `approval uses external requester client id`() {
        assertEquals("paykit.test", validatedApprovalClientId("paykit.test", "paykit.test"))
    }

    @Test
    fun `approval rejects a mismatched client id`() {
        assertFailsWith<PubkyAuthRequestError.RequesterChanged> {
            validatedApprovalClientId("paykit.test", "different.test")
        }
    }

    @Test
    fun `external session retains private payment access`() {
        val keychain = mock<Keychain>()
        val sessionSecret = "external-session"
        val provider = PaykitSdkSessionProvider(keychain, mock())
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(sessionSecret)
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(null)

        assertTrue(provider.hasSessionAccess())
        assertNull(provider.loadLocalSecretKey())
    }

    @Test
    fun `adopted identity loads its secret key from the ring provider`() {
        val keychain = mock<Keychain>()
        val sharedPubky = mock<SharedPubkyClient>()
        val provider = PaykitSdkSessionProvider(keychain, sharedPubky)
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(null)
        whenever(keychain.loadString(Keychain.Key.SHARED_PUBKY_SOURCE.name)).thenReturn("to.pubky.ring:$RING_PUBKY")
        whenever(sharedPubky.readCredential(RING_PUBKY)).thenReturn(null)

        assertEquals(RING_PUBKY, provider.adoptedPubky())
        assertNull(provider.loadLocalSecretKey())
        verify(sharedPubky).readCredential(RING_PUBKY)
    }

    @Test
    fun `stale session can be deferred until sdk initialization completes`() {
        val keychain = mock<Keychain>()
        val provider = PaykitSdkSessionProvider(keychain, mock())
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn("saved-session")

        assertTrue(provider.canDeferStaleSession("restore Pubky grant session from platform provider"))
        assertTrue(!provider.canDeferStaleSession("local Pubky secret key does not match session public key"))
        provider.suspendStoredSessionAccess()
        assertNull(provider.loadSessionAccess())
    }

    @Test
    fun `missing session or unrelated identity failures are not deferred`() {
        val keychain = mock<Keychain>()
        val provider = PaykitSdkSessionProvider(keychain, mock())
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(null)

        assertTrue(!provider.canDeferStaleSession("restore Pubky grant session from platform provider"))
        assertTrue(!provider.canDeferStaleSession("local Pubky secret key does not match session public key"))
    }

    @Test
    fun `session teardown attempts both credentials with session first`() {
        val attemptedKeys = mutableListOf<String>()

        assertFailsWith<AppError> {
            clearPubkySessionCredentials {
                attemptedKeys += it
                if (it == Keychain.Key.PAYKIT_SESSION.name) throw AppError("Delete failed")
            }
        }

        assertEquals(
            listOf(Keychain.Key.PAYKIT_SESSION.name, Keychain.Key.PUBKY_SECRET_KEY.name),
            attemptedKeys,
        )
    }

    private fun identityCacheCases(originalKey: String, differentKey: String) = listOf(
        Triple(originalKey, null, false),
        Triple("pubky$originalKey", null, false),
        Triple(differentKey, null, false),
        Triple(null, null, false),
        Triple(null, originalKey, false),
        Triple(null, differentKey, false),
        Triple(originalKey, differentKey, false),
        Triple(originalKey, differentKey, true),
    )
}

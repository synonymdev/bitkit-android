package to.bitkit.services

import com.synonym.paykit.ContactRecord
import com.synonym.paykit.EncryptedLinkRecoveryMarkerPolicy
import com.synonym.paykit.EndpointManagementScope
import com.synonym.paykit.LinkedPeerRecord
import com.synonym.paykit.LinkedPeerState
import com.synonym.paykit.PaykitSdk
import com.synonym.paykit.PaymentRequestLifecycleState
import com.synonym.paykit.PaymentRequestLocalRole
import com.synonym.paykit.PaymentRequestRecord
import com.synonym.paykit.PaymentRequestRecurrence
import com.synonym.paykit.PaymentRequestTerms
import com.synonym.paykit.PrivatePaymentListDeliveryReport
import com.synonym.paykit.PubkyClientConfig
import com.synonym.paykit.PubkyLocalSecretKey
import com.synonym.paykit.PubkySessionAccess
import com.synonym.paykit.PubkySessionBootstrapResult
import com.synonym.paykit.PublicContactSharingPolicy
import com.synonym.paykit.ReceiverNoiseSecretKey
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.sharedpubky.SharedPubkyClient
import to.bitkit.ext.fromHex
import to.bitkit.ext.toHex
import to.bitkit.models.PubkyAuthRequestError
import to.bitkit.repositories.PubkyContactError
import to.bitkit.utils.AppError
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaykitSdkServiceTest {
    companion object {
        private const val RING_PUBKY = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
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
            whenever(blocking.load(Keychain.Key.PAYKIT_RECEIVER_NOISE_SECRET_KEY.name)).thenReturn(bytes)
            val sdk = mock<PaykitSdk>()
            whenever(sdk.contactRecords()).thenReturn(emptyList())
            val access = mock<PubkySessionAccess>()
            val secret = mock<PubkyLocalSecretKey>()
            val noise = mock<ReceiverNoiseSecretKey>()
            whenever(secret.exportBytes()).thenReturn(bytes)
            whenever(noise.exportBytes()).thenReturn(bytes)
            whenever(access.exportSessionSecret()).thenReturn("new-session")
            whenever(access.exportLocalSecretKey()).thenReturn(secret)
            whenever(access.exportReceiverNoiseSecretKey()).thenReturn(noise)
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
            val service = PaykitSdkService(mock(), keychain) {
                handlesCreated++
                sdk
            }
            val result = PubkySessionBootstrapResult(access, "pubky_test")

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
                verify(keychain, atLeastOnce()).delete(Keychain.Key.PAYKIT_SDK_STATE.name)
                val handlesBeforeReload = handlesCreated
                service.contactRecords()
                assertEquals(handlesBeforeReload + 1, handlesCreated)
            }
        }
    }

    @Test
    fun `deletion blocks all known receivers before removing the contact`() = runTest {
        for (failBlock in listOf(false, true)) {
            val sdk = mock<PaykitSdk>()
            whenever(sdk.paymentRequests()).thenReturn(emptyList())
            val contact = mock<ContactRecord> { on { receiverPaths } doReturn listOf(PaykitReceiverPaths.WALLET) }
            whenever(sdk.contactRecord(RING_PUBKY)).thenReturn(contact)
            val peer = contactPeer(PaykitReceiverPaths.SERVER, LinkedPeerState.LINKED)
            whenever(sdk.linkedPeers()).thenReturn(listOf(peer))
            if (failBlock) {
                whenever(sdk.blockPeer(RING_PUBKY, PaykitReceiverPaths.SERVER))
                    .thenThrow(IllegalStateException("storage failure"))
            }
            val service = PaykitSdkService(mock(), mock()) { sdk }
            if (failBlock) {
                assertFailsWith<IllegalStateException> { service.removeContact(RING_PUBKY) }
                verify(sdk, never()).removeContact(any())
            } else {
                service.removeContact(RING_PUBKY)
                inOrder(sdk) {
                    verify(sdk).blockPeer(RING_PUBKY, PaykitReceiverPaths.WALLET)
                    verify(sdk).blockPeer(RING_PUBKY, PaykitReceiverPaths.SERVER)
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
            val service = PaykitSdkService(mock(), mock()) { sdk }
            assertFailsWith<PubkyContactError.ActiveSubscription> { service.removeContact(RING_PUBKY) }
            verify(sdk, never()).blockPeer(any(), any())
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
    fun `deletion withdraws private endpoints before blocking even when withdrawal fails`() = runTest {
        for (failWithdrawal in listOf(false, true)) {
            val sdk = mock<PaykitSdk>()
            whenever(sdk.paymentRequests()).thenReturn(emptyList())
            whenever(sdk.linkedPeers()).thenReturn(
                listOf(contactPeer(PaykitReceiverPaths.SERVER, LinkedPeerState.LINKED)),
            )
            val withdrawal = whenever(
                sdk.clearPrivatePaymentListAndProcessOutbound(RING_PUBKY, PaykitReceiverPaths.SERVER),
            )
            if (failWithdrawal) {
                withdrawal.thenThrow(IllegalStateException("network unavailable"))
            } else {
                withdrawal.thenReturn(
                    PrivatePaymentListDeliveryReport(emptyList(), emptyList(), emptyList(), emptyList()),
                )
            }
            val service = PaykitSdkService(mock(), mock()) { sdk }
            service.removeContact(RING_PUBKY)
            inOrder(sdk) {
                verify(sdk).clearPrivatePaymentListAndProcessOutbound(RING_PUBKY, PaykitReceiverPaths.SERVER)
                verify(sdk).blockPeer(RING_PUBKY, PaykitReceiverPaths.SERVER)
                verify(sdk).removeContact(RING_PUBKY)
            }
        }
    }

    @Test
    fun `only explicit readd unblocks every saved private receiver`() = runTest {
        for (restoreConnection in listOf(false, true)) {
            val sdk = mock<PaykitSdk>()
            whenever(sdk.saveContact(any())).thenReturn(mock())
            whenever(sdk.linkedPeers()).thenReturn(
                listOf(
                    contactPeer(PaykitReceiverPaths.WALLET, LinkedPeerState.BLOCKED),
                    contactPeer(PaykitReceiverPaths.SERVER, LinkedPeerState.BLOCKED),
                ),
            )
            val service = PaykitSdkService(mock(), mock()) { sdk }
            if (restoreConnection) {
                service.saveContact(RING_PUBKY, "Contact", restorePrivateConnection = true)
                verify(sdk).unblockPeer(RING_PUBKY, PaykitReceiverPaths.WALLET)
                verify(sdk).unblockPeer(RING_PUBKY, PaykitReceiverPaths.SERVER)
            } else {
                assertFailsWith<IllegalStateException> { service.saveContact(RING_PUBKY, "Contact") }
                verify(sdk, never()).saveContact(any())
                verify(sdk, never()).unblockPeer(any(), any())
            }
        }
    }

    @Test
    fun `failed private connection restoration leaves contact creation retryable`() = runTest {
        for (failPeerLookup in listOf(true, false)) {
            val sdk = mock<PaykitSdk>()
            val peers = listOf(
                contactPeer(PaykitReceiverPaths.WALLET, LinkedPeerState.BLOCKED),
                contactPeer(PaykitReceiverPaths.SERVER, LinkedPeerState.BLOCKED),
            )
            val failure = IllegalStateException("storage failure")
            if (failPeerLookup) {
                whenever(sdk.linkedPeers()).thenThrow(failure).thenReturn(peers)
            } else {
                whenever(sdk.linkedPeers()).thenReturn(peers)
                whenever(sdk.unblockPeer(RING_PUBKY, PaykitReceiverPaths.SERVER))
                    .thenThrow(failure).thenReturn(peers.last().copy(state = LinkedPeerState.NOT_LINKED))
            }
            whenever(sdk.saveContact(any())).thenReturn(mock())
            val service = PaykitSdkService(mock(), mock()) { sdk }
            assertFailsWith<IllegalStateException> {
                service.saveContact(RING_PUBKY, "Contact", restorePrivateConnection = true)
            }
            verify(sdk, never()).saveContact(any())
            service.saveContact(RING_PUBKY, "Contact", restorePrivateConnection = true)
            verify(sdk).saveContact(any())
        }
    }

    @Test
    fun `blocked peer cleanup does not attempt network delivery`() = runTest {
        val sdk = mock<PaykitSdk>()
        whenever(sdk.linkedPeers()).thenReturn(listOf(contactPeer(PaykitReceiverPaths.SERVER, LinkedPeerState.BLOCKED)))
        val service = PaykitSdkService(mock(), mock()) { sdk }
        assertNull(service.clearPrivatePaymentList(RING_PUBKY, PaykitReceiverPaths.SERVER))
        verify(sdk, never()).clearPrivatePaymentListAndProcessOutbound(any(), any())
    }

    private fun contactPeer(path: String, state: LinkedPeerState) = LinkedPeerRecord(
        counterparty = RING_PUBKY,
        counterpartyReceiverPath = path,
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
    fun `config scopes public endpoint sync to Bitkit managed endpoints`() {
        assertEquals(BitkitPaykitSdkConfig.profileNamespace, BitkitPaykitSdkConfig.clientId)
        assertEquals(EndpointManagementScope.MANAGED_ONLY, BitkitPaykitSdkConfig.endpointManagementScope)
        assertEquals(PublicContactSharingPolicy.LOCAL_ONLY, BitkitPaykitSdkConfig.publicContactSharing)
        assertEquals(EncryptedLinkRecoveryMarkerPolicy.ENABLED, BitkitPaykitSdkConfig.encryptedLinkRecoveryMarkers)
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
    fun `receiver noise derivation matches cross platform vector`() {
        val seed = (
            "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e534955" +
                "31f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04"
            ).fromHex()

        val key = PaykitReceiverNoiseKeyDerivation.derive(
            seed = seed,
            network = "bitcoin",
            receiverPath = "bitkit/wallet",
        )

        assertEquals("500f4799bbb2d02103e3b74b365ddb478a3187333c053fa9eb62f4052ba6a327", key.toHex())
    }

    @Test
    fun `receiver noise key is persisted and reused`() {
        var persistedBytes: ByteArray? = null
        val derivedBytes = ByteArray(32) { 7 }
        val store = keyStore(
            loadBytes = { persistedBytes },
            upsertBytes = { persistedBytes = it.copyOf() },
            deriveBytes = { derivedBytes },
        )

        val first = store.loadOrDeriveBytes()
        val second = store.loadOrDeriveBytes()
        val restored = keyStore(
            loadBytes = { persistedBytes },
            deriveBytes = { derivedBytes },
        ).loadOrDeriveBytes()

        assertContentEquals(first, persistedBytes)
        assertContentEquals(first, second)
        assertContentEquals(first, restored)
        assertEquals("PAYKIT_RECEIVER_NOISE_SECRET_KEY", Keychain.Key.PAYKIT_RECEIVER_NOISE_SECRET_KEY.name)
    }

    @Test
    fun `receiver noise key loads for external session without wallet seed`() {
        val persistedBytes = ByteArray(32) { 7 }
        val store = keyStore(
            loadBytes = { persistedBytes },
            deriveBytes = { throw AppError("wallet seed unavailable") },
        )

        assertContentEquals(persistedBytes, store.loadOrDeriveBytes())
    }

    @Test
    fun `receiver noise key cannot be replaced`() {
        val store = keyStore(
            loadBytes = { ByteArray(32) { 1 } },
            deriveBytes = { ByteArray(32) { 1 } },
        )

        assertFailsWith<AppError> {
            store.persistBytes(ByteArray(32) { 2 })
        }
    }

    @Test
    fun `receiver noise key follows wallet replacement after keychain wipe`() {
        var persistedBytes: ByteArray? = null
        var derivedBytes = ByteArray(32) { 1 }
        val store = keyStore(
            loadBytes = { persistedBytes },
            upsertBytes = { persistedBytes = it.copyOf() },
            deriveBytes = { derivedBytes },
        )
        store.loadOrDeriveBytes()

        persistedBytes = null
        derivedBytes = ByteArray(32) { 2 }

        assertContentEquals(derivedBytes, store.loadOrDeriveBytes())
        assertContentEquals(derivedBytes, persistedBytes)
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
        whenever(keychain.loadString(Keychain.Key.SHARED_PUBKY_SOURCE.name)).thenReturn("app.pubkyring:$RING_PUBKY")
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

    private fun keyStore(
        loadBytes: () -> ByteArray?,
        upsertBytes: (ByteArray) -> Unit = {},
        deriveBytes: () -> ByteArray,
    ) = PaykitReceiverNoiseKeyStore(loadBytes, upsertBytes, deriveBytes)
}

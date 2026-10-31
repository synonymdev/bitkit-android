package to.bitkit.services

import com.synonym.paykit.PubkyGrantLogout
import com.synonym.paykit.PubkyGrantLogoutReceipt
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.sharedpubky.SharedPubkyClient
import to.bitkit.data.sharedpubky.SharedPubkyContract
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileLogoutSignerTest {
    private val owner = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"

    @Test
    fun `managed signer must match original owner and does not consult an unused Ring reference`() {
        val keychain = mock<Keychain>()
        val shared = mock<SharedPubkyClient>()
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn("managed")
        var selectedOwner = "different"
        val provider = PaykitSdkSessionProvider(keychain, shared, derivePublicKey = { selectedOwner })
        assertFailsWith<IllegalStateException> { provider.captureOriginalSigner(owner) }
        selectedOwner = owner
        provider.captureOriginalSigner(owner).invoke()
        verifyNoInteractions(shared)
    }

    @Test
    fun `adopted reference and selected key must both belong to original owner`() {
        val keychain = mock<Keychain>()
        val shared = mock<SharedPubkyClient>()
        var reference = "different"
        whenever(keychain.loadString(Keychain.Key.SHARED_PUBKY_SOURCE.name)).thenAnswer {
            SharedPubkyContract.RING_SOURCE_PREFIX + reference
        }
        val provider = PaykitSdkSessionProvider(keychain, shared, derivePublicKey = { "different" })
        assertFailsWith<IllegalStateException> { provider.captureOriginalSigner(owner) }
        verifyNoInteractions(shared)
        reference = owner
        assertFailsWith<IllegalStateException> { provider.captureOriginalSigner(owner) }
        whenever(shared.readCredential(owner)).thenReturn("invalid-owner-key")
        assertFailsWith<IllegalStateException> { provider.captureOriginalSigner(owner) }
    }

    @Test
    fun `adopted credential loss revokes remote authority permanently but permits owned local erase`() = runTest {
        val keychain = mock<Keychain>()
        val shared = mock<SharedPubkyClient>()
        whenever(keychain.loadString(Keychain.Key.SHARED_PUBKY_SOURCE.name))
            .thenReturn(SharedPubkyContract.RING_SOURCE_PREFIX + owner)
        var credential: String? = "original-shared-key"
        whenever(shared.readCredential(owner)).thenAnswer { credential }
        val provider = PaykitSdkSessionProvider(keychain, shared, derivePublicKey = { owner })
        val receipt = PubkyGrantLogoutReceipt(owner, "homeserver", "original-grant", "bitkit")
        val native = mock<PubkyGrantLogout>()
        whenever(native.binding()).thenReturn(receipt)
        val logout = PaykitProfileLogout(native, mock(), provider.captureOriginalSigner(owner)) { true }
        val service = PaykitSdkService(mock(), keychain, mock(), settingsStore = mock()) { mock() }
        service.withProfileRemoval({ true }) { service.validateProfileLogout(logout) }
        credential = null
        service.withProfileRemoval({ true }) {
            assertFailsWith<IllegalStateException> { service.validateProfileLogout(logout) }
        }
        credential = "original-shared-key"
        assertFalse(logout.isCurrent())
        service.withProfileRemoval({ true }) {
            assertFailsWith<IllegalStateException> { service.executeProfileLogout(logout) }
        }
        logout.retainAcknowledgement(receipt)
        service.withProfileRemoval({ true }) { service.eraseProfileLogoutCredentials(logout, localOnly = true) }
        assertTrue(logout.reconcileAcknowledgement())
        assertTrue(logout.credentialsErased.get())
        verify(native, never()).prepareAttempt()
        verify(shared, times(3)).readCredential(owner)
        verify(keychain).eraseProfileCredentials(any(), any())
    }

    @Test
    fun `live delegate without a local root does not acquire one during validation`() {
        val keychain = mock<Keychain>()
        val shared = mock<SharedPubkyClient>()
        val provider = PaykitSdkSessionProvider(keychain, shared, derivePublicKey = { error("No root expected") })
        provider.captureOriginalSigner(owner).invoke()
        verifyNoInteractions(shared)
    }
}

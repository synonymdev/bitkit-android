package to.bitkit.services

import com.synonym.paykit.PaykitSdk
import com.synonym.paykit.PubkySessionBootstrap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PubkyIdentityRepublishTest {
    private val publicKey = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"

    @Test
    fun `successful publication is throttled and reuses bootstrap`() = runTest {
        val bootstrap = mock<PubkySessionBootstrap>()
        whenever(bootstrap.republishIdentity(any())).thenReturn(true)
        var factories = 0
        val service = PaykitSdkService(
            context = mock(),
            keychain = mock(),
            bootstrapFactory = {
                factories++
                bootstrap
            },
            sdkFactory = { mock() },
        )

        service.republishIdentityIfNeeded(publicKey, now = 0)
        service.republishIdentityIfNeeded("pubky$publicKey", now = 1_799_000)
        service.republishIdentityIfNeeded(publicKey, now = 1_800_000)

        verify(bootstrap, times(2)).republishIdentity("pubky$publicKey")
        assertEquals(1, factories)
    }

    @Test
    fun `missing records and failures retry before success interval`() = runTest {
        for (fails in listOf(false, true)) {
            val bootstrap = mock<PubkySessionBootstrap>()
            if (fails) {
                whenever(bootstrap.republishIdentity(any())).thenThrow(IllegalStateException("offline"))
            } else {
                whenever(bootstrap.republishIdentity(any())).thenReturn(false)
            }
            val service = PaykitSdkService(mock(), mock(), { bootstrap }) { mock() }

            service.republishIdentityIfNeeded(publicKey, now = 0)
            service.republishIdentityIfNeeded(publicKey, now = 59_000)
            service.republishIdentityIfNeeded(publicKey, now = 60_000)

            verify(bootstrap, times(2)).republishIdentity("pubky$publicKey")
        }
    }

    @Test
    fun `new identity has separate throttle without restoring a session`() = runTest {
        val bootstrap = mock<PubkySessionBootstrap>()
        whenever(bootstrap.republishIdentity(any())).thenReturn(true)
        val sdk = mock<PaykitSdk>()
        val service = PaykitSdkService(mock(), mock(), { bootstrap }) { sdk }
        val otherKey = publicKey.dropLast(1) + "y"

        service.republishIdentityIfNeeded(publicKey, now = 0)
        service.republishIdentityIfNeeded(otherKey, now = 0)

        verify(bootstrap).republishIdentity("pubky$publicKey")
        verify(bootstrap).republishIdentity("pubky$otherKey")
        verify(sdk, never()).initialize()
        verify(sdk, never()).identityStatus()
    }

    @Test
    fun `concurrent triggers do not overlap publication`() = runTest {
        val gate = CompletableDeferred<Boolean>()
        val bootstrap = mock<PubkySessionBootstrap>()
        whenever(bootstrap.republishIdentity(any())).doSuspendableAnswer { gate.await() }
        val service = PaykitSdkService(mock(), mock(), { bootstrap }) { mock() }
        val first = async { service.republishIdentityIfNeeded(publicKey, now = 0) }
        runCurrent()

        service.republishIdentityIfNeeded(publicKey, now = 3_600_000)
        verify(bootstrap).republishIdentity("pubky$publicKey")

        gate.complete(true)
        first.await()
    }

    @Test
    fun `timeout and cancellation release publication for retry`() = runTest {
        for (cancel in listOf(false, true)) {
            val bootstrap = mock<PubkySessionBootstrap>()
            whenever(bootstrap.republishIdentity(any())).doSuspendableAnswer { awaitCancellation() }
            val service = PaykitSdkService(mock(), mock(), { bootstrap }) { mock() }
            val start = currentTime
            val caller = async { service.republishIdentityIfNeeded(publicKey, now = 0) }

            if (cancel) {
                runCurrent()
                caller.cancelAndJoin()
                assertTrue(caller.isCancelled)
            } else {
                caller.await()
                assertEquals(5_000L, currentTime - start)
            }

            whenever(bootstrap.republishIdentity(any())).thenReturn(true)
            service.republishIdentityIfNeeded(publicKey, now = 60_000)
            verify(bootstrap, times(2)).republishIdentity("pubky$publicKey")
        }
    }
}

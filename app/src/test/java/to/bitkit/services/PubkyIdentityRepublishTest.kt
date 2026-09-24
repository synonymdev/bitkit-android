package to.bitkit.services

import com.synonym.paykit.PaykitSdk
import com.synonym.paykit.PubkySessionBootstrap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PubkyIdentityRepublishTest {
    private val publicKey = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"

    @Test
    fun `clock rollback retries publication and then resumes throttling`() = runTest {
        for (published in listOf(true, false)) {
            val bootstrap = mock<PubkySessionBootstrap>()
            whenever(bootstrap.republishIdentity(any())).thenReturn(published)
            val service = PaykitSdkService(
                mock(),
                mock(),
                { bootstrap },
                StandardTestDispatcher(testScheduler),
            ) { mock() }

            service.republishIdentityIfNeeded(publicKey, now = 2_592_000_000)
            service.republishIdentityIfNeeded(publicKey, now = 0)
            service.republishIdentityIfNeeded(publicKey, now = 1_000)

            verify(bootstrap, times(2)).republishIdentity("pubky$publicKey")
        }
    }

    @Test
    fun `slow publication survives caller deadline and establishes success throttle`() = runTest {
        val bootstrap = mock<PubkySessionBootstrap>()
        var published = false
        var cancelled = false
        whenever(bootstrap.republishIdentity(any())).doSuspendableAnswer {
            try {
                delay(10_100)
                published = true
                true
            } finally {
                cancelled = !currentCoroutineContext().isActive
            }
        }
        val service = PaykitSdkService(mock(), mock(), { bootstrap }, StandardTestDispatcher(testScheduler)) { mock() }

        service.republishIdentityIfNeeded(publicKey, now = 0)

        assertEquals(5_000L, currentTime)
        assertFalse(cancelled)
        assertFalse(published)
        service.republishIdentityIfNeeded(publicKey, now = 60_000)
        verify(bootstrap).republishIdentity("pubky$publicKey")

        advanceTimeBy(5_100)
        runCurrent()
        assertTrue(published)
        assertFalse(cancelled)
        service.republishIdentityIfNeeded(publicKey, now = 60_000)
        verify(bootstrap).republishIdentity("pubky$publicKey")
    }

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
            ioDispatcher = StandardTestDispatcher(testScheduler),
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
            val service = PaykitSdkService(
                context = mock(),
                keychain = mock(),
                bootstrapFactory = { bootstrap },
                ioDispatcher = StandardTestDispatcher(testScheduler),
                sdkFactory = { mock() },
            )

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
        val service = PaykitSdkService(mock(), mock(), { bootstrap }, StandardTestDispatcher(testScheduler)) { sdk }
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
        val service = PaykitSdkService(mock(), mock(), { bootstrap }, StandardTestDispatcher(testScheduler)) { mock() }
        val first = async { service.republishIdentityIfNeeded(publicKey, now = 0) }
        runCurrent()

        service.republishIdentityIfNeeded(publicKey, now = 3_600_000)
        verify(bootstrap).republishIdentity("pubky$publicKey")

        gate.complete(true)
        first.await()
    }

    @Test
    fun `publication timeout releases single flight for a throttled retry`() = runTest {
        val bootstrap = mock<PubkySessionBootstrap>()
        var cancelled = false
        whenever(bootstrap.republishIdentity(any())).doSuspendableAnswer {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val service = PaykitSdkService(mock(), mock(), { bootstrap }, StandardTestDispatcher(testScheduler)) { mock() }

        service.republishIdentityIfNeeded(publicKey, now = 0)
        assertEquals(5_000L, currentTime)
        assertFalse(cancelled)
        service.republishIdentityIfNeeded(publicKey, now = 60_000)
        verify(bootstrap).republishIdentity("pubky$publicKey")

        advanceTimeBy(24_999)
        runCurrent()
        assertFalse(cancelled)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(cancelled)

        whenever(bootstrap.republishIdentity(any())).thenReturn(true)
        service.republishIdentityIfNeeded(publicKey, now = 59_000)
        verify(bootstrap).republishIdentity("pubky$publicKey")
        service.republishIdentityIfNeeded(publicKey, now = 60_000)
        verify(bootstrap, times(2)).republishIdentity("pubky$publicKey")
    }

    @Test
    fun `caller cancellation stops its continuation but preserves ongoing publication`() = runTest {
        val gate = CompletableDeferred<Boolean>()
        val bootstrap = mock<PubkySessionBootstrap>()
        whenever(bootstrap.republishIdentity(any())).doSuspendableAnswer { gate.await() }
        val service = PaykitSdkService(mock(), mock(), { bootstrap }, StandardTestDispatcher(testScheduler)) { mock() }
        var continued = false
        val cancelledCaller = async {
            cancel()
            service.republishIdentityIfNeeded(publicKey, now = 0)
            continued = true
        }
        cancelledCaller.join()
        assertFalse(continued)
        verify(bootstrap, never()).republishIdentity(any())

        val caller = async {
            service.republishIdentityIfNeeded(publicKey, now = 0)
            continued = true
        }
        runCurrent()

        caller.cancelAndJoin()

        assertTrue(caller.isCancelled)
        assertFalse(continued)
        service.republishIdentityIfNeeded(publicKey, now = 60_000)
        verify(bootstrap).republishIdentity("pubky$publicKey")
        gate.complete(true)
        runCurrent()
        service.republishIdentityIfNeeded(publicKey, now = 60_000)
        verify(bootstrap).republishIdentity("pubky$publicKey")
    }
}

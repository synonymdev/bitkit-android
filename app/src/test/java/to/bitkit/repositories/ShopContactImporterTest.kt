package to.bitkit.repositories

import com.synonym.paykit.ContactRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
import to.bitkit.ext.runSuspendCatching
import to.bitkit.services.PubkyService
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ShopContactImporterTest {
    private val buyer = "y".repeat(52)
    private val seller = "b".repeat(51) + "y"
    private val other = "n".repeat(51) + "y"
    private val root = "pubky://$buyer${ShopContactImporter.MARKER_PATH}"
    private val signIn = PubkySignIn("pubky$buyer", 1)

    @Test
    fun `pages revisit the directory but do not fetch imported sellers again`() = runTest {
        val service = mock<PubkyService>()
        val first = "$root$seller.json"
        val second = "$root$other.json"
        whenever(service.listPublicDirectoryPage(root, null, 10u)).thenReturn(listOf(first))
        whenever(service.listPublicDirectoryPage(root, first, 10u)).thenReturn(listOf(second))
        whenever(service.listPublicDirectoryPage(root, second, 10u)).thenReturn(emptyList())
        for (key in listOf(seller, other)) stubSeller(service, key)
        whenever(service.importShopContacts(any(), any(), any())).thenReturn(listOf(mock<ContactRecord>()))
        val importer = ShopContactImporter(service)

        repeat(4) { importer.importPage(signIn) { true } }

        verify(service).fetchPublicFile(first, ShopContactImporter.MARKER_MAX_BYTES)
        verify(service).fetchPublicFile(second, ShopContactImporter.MARKER_MAX_BYTES)
        verify(service, times(2)).listPublicDirectoryPage(root, null, 10u)
    }

    @Test
    fun `malformed markers self and mismatched storefronts are skipped and failed reads can retry`() = runTest {
        val service = mock<PubkyService>()
        val uri = "$root$seller.json"
        val shopUri = "pubky://$seller${ShopContactImporter.SHOP_PATH}"
        val paths = listOf("pubky://$other${ShopContactImporter.MARKER_PATH}$seller.json", "$root$buyer.json", uri)
        whenever(service.listPublicDirectoryPage(root, null, 10u)).thenReturn(paths)
        whenever(service.listPublicDirectoryPage(root, uri, 10u)).thenReturn(emptyList())
        whenever(service.importShopContacts(any(), any(), any())).thenReturn(emptyList())
        val importer = ShopContactImporter(service)
        for (body in listOf(
            "{}",
            "{\"seller\":\"$other\",\"completedAt\":1}",
            "{\"seller\":\"$seller\",\"completedAt\":0}",
        )) {
            whenever(service.fetchPublicFile(uri, ShopContactImporter.MARKER_MAX_BYTES)).thenReturn(body.toByteArray())
            assertFalse(importer.importPage(signIn) { true })
            importer.importPage(signIn) { true }
        }
        verify(service, never()).fetchPublicFile(shopUri, ShopContactImporter.SHOP_MAX_BYTES)
        stubSeller(service, seller)
        for (body in listOf(
            "{}",
            "{\"schemaVersion\":2,\"recordType\":\"shop\",\"ownerPubky\":\"$seller\"}",
            "{\"schemaVersion\":1,\"recordType\":\"listing\",\"ownerPubky\":\"$seller\"}",
            "{\"schemaVersion\":1,\"recordType\":\"shop\",\"ownerPubky\":\"$other\"}",
        )) {
            whenever(service.fetchPublicFile(shopUri, ShopContactImporter.SHOP_MAX_BYTES))
                .thenReturn(body.toByteArray())
            assertFalse(importer.importPage(signIn) { true })
            importer.importPage(signIn) { true }
        }
        whenever(service.fetchPublicFile(shopUri, ShopContactImporter.SHOP_MAX_BYTES))
            .thenThrow(IllegalStateException("offline"))
            .thenReturn("{\"schemaVersion\":1,\"recordType\":\"shop\",\"ownerPubky\":\"$seller\"}".toByteArray())
        assertFalse(importer.importPage(signIn) { true })
        importer.importPage(signIn) { true }
        whenever(service.importShopContacts(any(), any(), any())).thenReturn(listOf(mock()))
        assertTrue(importer.importPage(signIn) { true })
    }

    @Test
    fun `overlapping polls coalesce and cancellation or identity change cannot import`() = runTest {
        val service = mock<PubkyService>()
        val gate = CompletableDeferred<Unit>()
        whenever(service.listPublicDirectoryPage(root, null, 10u)).doSuspendableAnswer {
            gate.await()
            listOf("$root$seller.json")
        }
        val importer = ShopContactImporter(service)
        val first = async { importer.importPage(signIn) { true } }
        runCurrent()
        assertFalse(importer.importPage(signIn) { true })
        first.cancel()
        runCurrent()
        var current = true
        val next = async { runSuspendCatching { importer.importPage(signIn) { current } } }
        runCurrent()
        current = false
        gate.complete(Unit)
        assertEquals(PubkyContactError.SignInChanged, next.await().exceptionOrNull())
        verify(service, never()).importShopContacts(any(), any(), any())
        assertFailsWith<PubkyContactError.SignInChanged> { importer.importPage(signIn) { false } }
    }

    @Test
    fun `committed import refresh retries before discovery after interruption`() = runTest {
        for (interruptedSave in listOf(false, true)) {
            val service = mock<PubkyService>()
            var offline = false
            whenever(service.listPublicDirectoryPage(root, null, 10u)).doSuspendableAnswer {
                if (offline) throw IOException("offline")
                listOf("$root$seller.json")
            }
            stubSeller(service, seller)
            var interrupted = false
            whenever(service.importShopContacts(any(), any(), any())).doSuspendableAnswer {
                if (interruptedSave && !interrupted) {
                    interrupted = true
                    throw CancellationException("Write completed before cancellation")
                }
                emptyList()
            }
            var refreshes = 0
            val refresh: suspend () -> Boolean = {
                refreshes++
                if (!interruptedSave && !interrupted) {
                    interrupted = true
                    false
                } else {
                    true
                }
            }
            val importer = ShopContactImporter(service)
            if (interruptedSave) {
                assertFailsWith<CancellationException> { importer.importPage(signIn, refresh) { true } }
            } else {
                assertFalse(importer.importPage(signIn, refresh) { true })
            }
            val before = refreshes
            offline = true
            assertFailsWith<IOException> { importer.importPage(signIn, refresh) { true } }
            assertEquals(before + 1, refreshes)
            verify(service, times(1)).importShopContacts(any(), any(), any())
            offline = false
            importer.importPage(signIn, refresh) { true }
            assertEquals(before + 2, refreshes)
        }
    }

    private suspend fun stubSeller(service: PubkyService, key: String) {
        whenever(service.fetchPublicFile("$root$key.json", ShopContactImporter.MARKER_MAX_BYTES))
            .thenReturn("{\"seller\":\"$key\",\"completedAt\":1}".toByteArray())
        whenever(
            service.fetchPublicFile("pubky://$key${ShopContactImporter.SHOP_PATH}", ShopContactImporter.SHOP_MAX_BYTES),
        ).thenReturn("{\"schemaVersion\":1,\"recordType\":\"shop\",\"ownerPubky\":\"$key\"}".toByteArray())
    }
}

package to.bitkit.data

import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.fetch.FetchResult
import coil3.fetch.SourceFetchResult
import coil3.request.CachePolicy
import coil3.request.Options
import coil3.size.Size
import coil3.toUri
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.description
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.services.PubkyService
import to.bitkit.test.BaseUnitTest
import to.bitkit.test.forEachCase
import to.bitkit.utils.AppError
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PubkyImageFetcherTest : BaseUnitTest() {
    companion object {
        private const val IMAGE_URI = "pubky://image"
        private const val BLOB_URI = "pubky://blob_uri"
        private val IMAGE_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) // PNG header
        private val BLOB_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) // JPEG header
        private val DESCRIPTOR_BYTES = """{"src": "$BLOB_URI"}""".toByteArray()
    }

    @get:Rule(order = 1)
    val tempFolder = TemporaryFolder()

    private val pubkyService = mock<PubkyService>()
    private val cacheEpoch = PubkyImageCacheEpoch()
    private val factory = PubkyImageFetcher.Factory(pubkyService, cacheEpoch)
    private val options = Options(ApplicationProvider.getApplicationContext(), size = Size.ORIGINAL)

    @Test
    fun `factory should return fetcher for pubky uris`() = test {
        val fetcher = factory.create("pubky://image_uri".toUri(), options, mock())

        assertNotNull(fetcher)
    }

    @Test
    fun `factory should return null for non-pubky uris`() = test {
        val fetcher = factory.create("https://example.com/image.png".toUri(), options, mock())

        assertNull(fetcher)
    }

    @Test
    fun `factory should read from the image loader disk cache`() = test {
        val diskCache = createDiskCache()
        diskCache.put(IMAGE_URI, IMAGE_BYTES)
        val imageLoader = mock<ImageLoader>()
        whenever(imageLoader.diskCache).thenReturn(diskCache)

        val result = checkNotNull(factory.create(IMAGE_URI.toUri(), options, imageLoader)).fetch()

        assertEquals(DataSource.DISK, result.dataSource())
        verifyNoInteractions(pubkyService)
    }

    @Test
    fun `fetch should return raw data when response is not json`() = test {
        whenever(pubkyService.fetchFile(IMAGE_URI, PUBKY_IMAGE_MAX_BYTES)).thenReturn(IMAGE_BYTES)
        val fetcher = createFetcher()

        val result = fetcher.fetch()

        assertContentEquals(IMAGE_BYTES, result.bytes())
        verify(pubkyService).fetchFile(IMAGE_URI, PUBKY_IMAGE_MAX_BYTES)
    }

    @Test
    fun `fetch should follow json file descriptor with pubky src`() = test {
        whenever(pubkyService.fetchFile(IMAGE_URI, PUBKY_IMAGE_MAX_BYTES)).thenReturn(DESCRIPTOR_BYTES)
        whenever(pubkyService.fetchFile(BLOB_URI, PUBKY_IMAGE_MAX_BYTES)).thenReturn(BLOB_BYTES)
        val fetcher = createFetcher()

        val result = fetcher.fetch()

        assertContentEquals(BLOB_BYTES, result.bytes())
        verify(pubkyService).fetchFile(BLOB_URI, PUBKY_IMAGE_MAX_BYTES)
    }

    @Test
    fun `fetch should preserve cancellation when following file descriptor`() = test {
        whenever(pubkyService.fetchFile(IMAGE_URI, PUBKY_IMAGE_MAX_BYTES)).thenReturn(DESCRIPTOR_BYTES)
        whenever(pubkyService.fetchFile(BLOB_URI, PUBKY_IMAGE_MAX_BYTES))
            .thenThrow(CancellationException())
        val fetcher = createFetcher()

        assertFailsWith<CancellationException> { fetcher.fetch() }
    }

    @Test
    fun `fetch should not follow json src with non-pubky scheme`() = test {
        val descriptor = """{"src": "https://example.com/image.png"}""".toByteArray()
        whenever(pubkyService.fetchFile(IMAGE_URI, PUBKY_IMAGE_MAX_BYTES)).thenReturn(descriptor)
        val fetcher = createFetcher()

        fetcher.fetch()

        verify(pubkyService, never()).fetchFile("https://example.com/image.png", PUBKY_IMAGE_MAX_BYTES)
    }

    @Test
    fun `fetch should not follow json without src field`() = test {
        val json = """{"name": "test"}""".toByteArray()
        whenever(pubkyService.fetchFile(IMAGE_URI, PUBKY_IMAGE_MAX_BYTES)).thenReturn(json)
        val fetcher = createFetcher()

        val result = fetcher.fetch()

        assertNotNull(result)
    }

    @Test
    fun `fetch should return a disk cache hit without fetching the file`() = test {
        val diskCache = createDiskCache()
        diskCache.put(IMAGE_URI, IMAGE_BYTES)

        val result = createFetcher(diskCache).fetch()

        assertEquals(DataSource.DISK, result.dataSource())
        assertContentEquals(IMAGE_BYTES, result.bytes())
        verifyNoInteractions(pubkyService)
    }

    @Test
    fun `fetch should write the network bytes to the disk cache only when the request allows it`() = test {
        val httpsDescriptor = """{"src": "https://example.com/image.png"}""".toByteArray()
        listOf(
            DiskCacheWriteCase("fetched image", stored = mapOf(IMAGE_URI to IMAGE_BYTES)),
            DiskCacheWriteCase(
                "followed descriptor blob",
                image = DESCRIPTOR_BYTES,
                blob = BLOB_BYTES,
                bytes = BLOB_BYTES,
                stored = mapOf(IMAGE_URI to BLOB_BYTES, BLOB_URI to null),
            ),
            DiskCacheWriteCase(
                "descriptor whose blob fetch fails",
                image = DESCRIPTOR_BYTES,
                bytes = DESCRIPTOR_BYTES,
                stored = mapOf(IMAGE_URI to null),
            ),
            DiskCacheWriteCase(
                "json without a pubky src",
                image = httpsDescriptor,
                bytes = httpsDescriptor,
                stored = mapOf(IMAGE_URI to null),
            ),
            DiskCacheWriteCase(
                "read policy off",
                policy = CachePolicy.WRITE_ONLY,
                cached = BLOB_BYTES,
                stored = mapOf(IMAGE_URI to IMAGE_BYTES),
            ),
            DiskCacheWriteCase("cleared during the fetch", clearsCache = true, stored = mapOf(IMAGE_URI to null)),
            DiskCacheWriteCase("write policy off", policy = CachePolicy.READ_ONLY, stored = mapOf(IMAGE_URI to null)),
            DiskCacheWriteCase(
                "request disk cache key",
                key = "custom-key",
                stored = mapOf("custom-key" to IMAGE_BYTES, IMAGE_URI to null),
            ),
        ).forEachCase({ it.name }) { case ->
            reset(pubkyService)
            val epoch = PubkyImageCacheEpoch()
            val diskCache = createDiskCache()
            case.cached?.let { diskCache.put(IMAGE_URI, it) }
            whenever(pubkyService.fetchFile(IMAGE_URI, PUBKY_IMAGE_MAX_BYTES)).thenAnswer {
                if (case.clearsCache) {
                    epoch.advance()
                    diskCache.clear()
                }
                case.image
            }
            whenever(pubkyService.fetchFile(BLOB_URI, PUBKY_IMAGE_MAX_BYTES))
                .thenAnswer { case.blob ?: throw FetcherTestError("blob fetch failed") }
            val requestOptions = options.copy(diskCachePolicy = case.policy, diskCacheKey = case.key)

            val result = createFetcher(diskCache, requestOptions, epoch).fetch()

            assertEquals(DataSource.NETWORK, result.dataSource(), case.name)
            assertContentEquals(case.bytes, result.bytes(), case.name)
            case.stored.forEach { assertContentEquals(it.value, diskCache.read(it.key), "${case.name}: ${it.key}") }
        }
    }

    @Test
    fun `fetch should abort the disk cache editor when writing fails`() = test {
        val diskCache = mock<DiskCache>()
        val editor = mock<DiskCache.Editor>()
        whenever(diskCache.fileSystem).thenReturn(FileSystem.SYSTEM)
        whenever(diskCache.openEditor(IMAGE_URI)).thenReturn(editor)
        whenever(editor.data).thenReturn(tempFolder.root.toOkioPath() / "missing" / "data")
        whenever(pubkyService.fetchFile(IMAGE_URI, PUBKY_IMAGE_MAX_BYTES)).thenReturn(IMAGE_BYTES)

        val result = createFetcher(diskCache).fetch()

        assertContentEquals(IMAGE_BYTES, result.bytes())
        verify(editor).abort()
        verify(editor, never()).commit()
    }

    @Test
    fun `fetch should return the network bytes when the disk cache fails`() = test {
        listOf<Pair<String, (DiskCache) -> Unit>>(
            "read" to { whenever(it.openSnapshot(IMAGE_URI)).thenAnswer { throw IOException("journal unreadable") } },
            "editor" to { whenever(it.openEditor(IMAGE_URI)).thenAnswer { throw IOException("journal write failed") } },
        ).forEachCase({ it.first }) { (name, fail) ->
            reset(pubkyService)
            val diskCache = mock<DiskCache>()
            fail(diskCache)
            whenever(pubkyService.fetchFile(IMAGE_URI, PUBKY_IMAGE_MAX_BYTES)).thenReturn(IMAGE_BYTES)

            val result = createFetcher(diskCache).fetch()

            assertEquals(DataSource.NETWORK, result.dataSource(), name)
            assertContentEquals(IMAGE_BYTES, result.bytes(), name)
            verify(pubkyService, description(name)).fetchFile(IMAGE_URI, PUBKY_IMAGE_MAX_BYTES)
            verify(diskCache, description(name)).openEditor(IMAGE_URI)
        }
    }

    private fun createFetcher(
        diskCache: DiskCache? = null,
        options: Options = this.options,
        epoch: PubkyImageCacheEpoch = cacheEpoch,
    ) = PubkyImageFetcher(IMAGE_URI, options, pubkyService, diskCache, epoch)

    private fun createDiskCache() = DiskCache.Builder()
        .directory(tempFolder.newFolder())
        .build()

    private fun DiskCache.put(key: String, bytes: ByteArray) {
        val editor = checkNotNull(openEditor(key))
        fileSystem.write(editor.data) { write(bytes) }
        editor.commit()
    }

    private fun DiskCache.read(key: String): ByteArray? = openSnapshot(key)?.use {
        fileSystem.read(it.data) { readByteArray() }
    }

    private fun FetchResult?.dataSource() = (this as SourceFetchResult).dataSource

    private fun FetchResult?.bytes() = (this as SourceFetchResult).source.use { it.source().readByteArray() }

    @Suppress("LongParameterList")
    private class DiskCacheWriteCase(
        val name: String,
        val image: ByteArray = IMAGE_BYTES,
        val blob: ByteArray? = null,
        val bytes: ByteArray = IMAGE_BYTES,
        val policy: CachePolicy = CachePolicy.ENABLED,
        val key: String? = null,
        val cached: ByteArray? = null,
        val clearsCache: Boolean = false,
        val stored: Map<String, ByteArray?>,
    )
}

private class FetcherTestError(message: String) : AppError(message)

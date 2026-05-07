package to.bitkit.data

import androidx.test.core.app.ApplicationProvider
import coil3.request.Options
import coil3.size.Size
import coil3.toUri
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.services.PubkyService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PubkyImageFetcherTest : BaseUnitTest() {

    private val pubkyService = mock<PubkyService>()
    private val factory = PubkyImageFetcher.Factory(pubkyService)
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
    fun `fetch should return raw data when response is not json`() = test {
        val imageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) // PNG header
        whenever(pubkyService.fetchFile("pubky://image")).thenReturn(imageBytes)
        val fetcher = PubkyImageFetcher("pubky://image", options, pubkyService)

        val result = fetcher.fetch()

        assertNotNull(result)
        verify(pubkyService).fetchFile("pubky://image")
    }

    @Test
    fun `fetch should follow json file descriptor with pubky src`() = test {
        val descriptor = """{"src": "pubky://blob_uri"}""".toByteArray()
        val blobBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) // JPEG header
        whenever(pubkyService.fetchFile("pubky://image")).thenReturn(descriptor)
        whenever(pubkyService.fetchFile("pubky://blob_uri")).thenReturn(blobBytes)
        val fetcher = PubkyImageFetcher("pubky://image", options, pubkyService)

        fetcher.fetch()

        verify(pubkyService).fetchFile("pubky://blob_uri")
    }

    @Test
    fun `fetch should not follow json src with non-pubky scheme`() = test {
        val descriptor = """{"src": "https://example.com/image.png"}""".toByteArray()
        whenever(pubkyService.fetchFile("pubky://image")).thenReturn(descriptor)
        val fetcher = PubkyImageFetcher("pubky://image", options, pubkyService)

        fetcher.fetch()

        verify(pubkyService, never()).fetchFile("https://example.com/image.png")
    }

    @Test
    fun `fetch should not follow json without src field`() = test {
        val json = """{"name": "test"}""".toByteArray()
        whenever(pubkyService.fetchFile("pubky://image")).thenReturn(json)
        val fetcher = PubkyImageFetcher("pubky://image", options, pubkyService)

        val result = fetcher.fetch()

        assertNotNull(result)
    }
}

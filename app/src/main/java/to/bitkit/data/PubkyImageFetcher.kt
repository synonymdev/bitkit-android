package to.bitkit.data

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.Buffer
import org.json.JSONObject
import to.bitkit.ext.runSuspendCatching
import to.bitkit.services.PubkyService
import to.bitkit.utils.Logger

private const val TAG = "PubkyImageFetcher"
private const val PUBKY_SCHEME = "pubky://"

/** Maximum successful response body accepted for a displayed Pubky image. */
internal const val PUBKY_IMAGE_MAX_BYTES = 1_048_576uL

class PubkyImageFetcher(
    private val uri: String,
    private val options: Options,
    private val pubkyService: PubkyService,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val data = pubkyService.fetchFile(uri, PUBKY_IMAGE_MAX_BYTES)
        val blobData = resolveImageData(data)
        val source = ImageSource(Buffer().apply { write(blobData) }, options.fileSystem)
        return SourceFetchResult(source, null, dataSource = DataSource.NETWORK)
    }

    private suspend fun resolveImageData(data: ByteArray): ByteArray = runSuspendCatching {
        val json = JSONObject(String(data))
        val src = json.optString("src", "")
        if (src.isNotEmpty() && src.startsWith(PUBKY_SCHEME)) {
            Logger.debug("Found file descriptor, fetching blob from '$src'", context = TAG)
            pubkyService.fetchFile(src, PUBKY_IMAGE_MAX_BYTES)
        } else {
            data
        }
    }.getOrDefault(data)

    class Factory(private val pubkyService: PubkyService) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val uri = data.toString()
            if (!uri.startsWith(PUBKY_SCHEME)) return null
            return PubkyImageFetcher(uri, options, pubkyService)
        }
    }
}

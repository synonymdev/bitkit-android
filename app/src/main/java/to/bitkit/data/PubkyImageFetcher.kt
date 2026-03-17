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
import to.bitkit.services.PubkyService
import to.bitkit.utils.Logger

private const val TAG = "PubkyImageFetcher"
private const val PUBKY_SCHEME = "pubky://"

class PubkyImageFetcher(
    private val uri: String,
    private val options: Options,
    private val pubkyService: PubkyService,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val data = pubkyService.fetchFile(uri)
        val blobData = resolveImageData(data)
        val source = ImageSource(Buffer().apply { write(blobData) }, options.fileSystem)
        return SourceFetchResult(source, null, dataSource = DataSource.NETWORK)
    }

    private suspend fun resolveImageData(data: ByteArray): ByteArray = runCatching {
        val json = JSONObject(String(data))
        val src = json.optString("src", "")
        if (src.isNotEmpty() && src.startsWith(PUBKY_SCHEME)) {
            Logger.debug("File descriptor found, fetching blob from '$src'", context = TAG)
            pubkyService.fetchFile(src)
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

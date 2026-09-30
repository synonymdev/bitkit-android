package to.bitkit.data

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.Buffer
import org.json.JSONObject
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PubkyPublicKeyFormat
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
    private val diskCache: DiskCache?,
) : Fetcher {
    private val diskCacheKey = options.diskCacheKey ?: uri

    override suspend fun fetch(): FetchResult {
        readFromDiskCache()?.let { return it }

        val image = resolveImageData(pubkyService.fetchFile(uri, PUBKY_IMAGE_MAX_BYTES))
        if (image.isCacheable) writeToDiskCache(image.bytes)
        val source = ImageSource(Buffer().apply { write(image.bytes) }, options.fileSystem)
        return SourceFetchResult(source, null, dataSource = DataSource.NETWORK)
    }

    private fun readFromDiskCache(): FetchResult? {
        if (!options.diskCachePolicy.readEnabled) return null
        val cache = diskCache ?: return null
        val snapshot = cache.openSnapshot(diskCacheKey) ?: return null
        val source = ImageSource(snapshot.data, cache.fileSystem, diskCacheKey, snapshot)
        return SourceFetchResult(source, null, dataSource = DataSource.DISK)
    }

    private fun writeToDiskCache(bytes: ByteArray) {
        if (!options.diskCachePolicy.writeEnabled) return
        val cache = diskCache ?: return
        val editor = cache.openEditor(diskCacheKey) ?: return
        runCatching {
            cache.fileSystem.write(editor.data) { write(bytes) }
            editor.commit()
        }.onFailure {
            runCatching { editor.abort() }
            Logger.warn("Failed to cache pubky image", it, context = TAG)
        }
    }

    private suspend fun resolveImageData(data: ByteArray): PubkyImageData {
        val descriptor = runCatching { JSONObject(String(data)) }.getOrNull()
            ?: return PubkyImageData(data, isCacheable = true)
        val src = descriptor.optString("src", "")
        if (!src.startsWith(PUBKY_SCHEME)) return PubkyImageData(data, isCacheable = false)

        Logger.debug("Found file descriptor, fetching blob from '${PubkyPublicKeyFormat.redacted(src)}'", context = TAG)
        return runSuspendCatching { pubkyService.fetchFile(src, PUBKY_IMAGE_MAX_BYTES) }
            .map { PubkyImageData(it, isCacheable = true) }
            .onFailure { Logger.warn("Failed to fetch pubky image blob", it, context = TAG) }
            .getOrDefault(PubkyImageData(data, isCacheable = false))
    }

    class Factory(private val pubkyService: PubkyService) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val uri = data.toString()
            if (!uri.startsWith(PUBKY_SCHEME)) return null
            return PubkyImageFetcher(uri, options, pubkyService, imageLoader.diskCache)
        }
    }
}

private class PubkyImageData(val bytes: ByteArray, val isCacheable: Boolean)

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
import com.synonym.paykit.PaykitException
import okio.Buffer
import org.json.JSONObject
import to.bitkit.ext.nowMs
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.services.PubkyFileNotFoundError
import to.bitkit.services.PubkyService
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

private const val TAG = "PubkyImageFetcher"
private const val PUBKY_SCHEME = "pubky://"

/** Maximum successful response body accepted for a displayed Pubky image. */
internal const val PUBKY_IMAGE_MAX_BYTES = 1_048_576uL

/** How long a Pubky image whose fetch failed for a reason that can pass, such as a network error, is not fetched. */
internal val PUBKY_IMAGE_TRANSIENT_FAILURE_TTL = 60.seconds

class PubkyImageFetcher(
    private val uri: String,
    private val options: Options,
    private val pubkyService: PubkyService,
    private val diskCache: DiskCache?,
    private val cacheEpoch: PubkyImageCacheEpoch,
    private val failures: PubkyImageFailureCache,
) : Fetcher {
    private val diskCacheKey = options.diskCacheKey ?: uri

    override suspend fun fetch(): FetchResult {
        readFromDiskCache()?.let { return it }
        failures.recentFailure(uri)?.let { throw PubkyImageRecentlyFailedError(it) }

        val epoch = cacheEpoch.current()
        val data = runSuspendCatching { pubkyService.fetchFile(uri, PUBKY_IMAGE_MAX_BYTES) }
            .onFailure { failures.remember(uri, it, epoch) }
            .getOrThrow()
        val image = resolveImageData(data, epoch)
        if (image.isCacheable) writeToDiskCache(image.bytes, epoch)
        val source = ImageSource(Buffer().apply { write(image.bytes) }, options.fileSystem)
        return SourceFetchResult(source, null, dataSource = DataSource.NETWORK)
    }

    private suspend fun readFromDiskCache(): FetchResult? {
        if (!options.diskCachePolicy.readEnabled) return null
        val cache = diskCache ?: return null
        val snapshot = runSuspendCatching { cache.openSnapshot(diskCacheKey) }
            .onFailure { Logger.warn("Failed to read pubky image from disk cache", it, context = TAG) }
            .getOrNull()
            ?: return null
        val source = ImageSource(snapshot.data, cache.fileSystem, diskCacheKey, snapshot)
        return SourceFetchResult(source, null, dataSource = DataSource.DISK)
    }

    private suspend fun writeToDiskCache(bytes: ByteArray, epoch: Long) {
        if (!options.diskCachePolicy.writeEnabled) return
        val cache = diskCache ?: return
        val editor = runSuspendCatching { cache.openEditor(diskCacheKey) }
            .onFailure { Logger.warn("Failed to open pubky image disk cache editor", it, context = TAG) }
            .getOrNull()
            ?: return
        runCatching {
            cache.fileSystem.write(editor.data) { write(bytes) }
            // The editor is open, so a clear after this check makes evictAll() zombie the entry and drop the commit.
            if (cacheEpoch.current() == epoch) editor.commit() else editor.abort()
        }.onFailure {
            runCatching { editor.abort() }
            if (it is CancellationException) throw it
            Logger.warn("Failed to cache pubky image", it, context = TAG)
        }
    }

    private suspend fun resolveImageData(data: ByteArray, epoch: Long): PubkyImageData {
        val descriptor = runSuspendCatching { JSONObject(String(data)) }.getOrNull()
            ?: return PubkyImageData(data, isCacheable = true)
        val src = descriptor.optString("src", "")
        if (!src.startsWith(PUBKY_SCHEME)) return PubkyImageData(data, isCacheable = false)

        Logger.debug("Found file descriptor, fetching blob from '${PubkyPublicKeyFormat.redacted(src)}'", context = TAG)
        return runSuspendCatching { pubkyService.fetchFile(src, PUBKY_IMAGE_MAX_BYTES) }
            .map { PubkyImageData(it, isCacheable = true) }
            .onFailure {
                Logger.warn("Failed to fetch pubky image blob", it, context = TAG)
                failures.remember(uri, it, epoch)
            }
            .getOrDefault(PubkyImageData(data, isCacheable = false))
    }

    class Factory(
        private val pubkyService: PubkyService,
        private val cacheEpoch: PubkyImageCacheEpoch,
        clock: Clock,
    ) : Fetcher.Factory<Uri> {
        private val failures = PubkyImageFailureCache(cacheEpoch, clock)

        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val uri = data.toString()
            if (!uri.startsWith(PUBKY_SCHEME)) return null
            return PubkyImageFetcher(uri, options, pubkyService, imageLoader.diskCache, cacheEpoch, failures)
        }
    }
}

/**
 * Remembers failed Pubky image fetches by URI, so an image that cannot load is not fetched again each time it is
 * shown. A missing file, a file over [PUBKY_IMAGE_MAX_BYTES] or another invalid response is remembered until the
 * image cache is next cleared, and any other failure for [PUBKY_IMAGE_TRANSIENT_FAILURE_TTL]. A clear of the image
 * cache, which advances [PubkyImageCacheEpoch] on sign-out and identity change, forgets every failure, and a failure
 * of a fetch that a clear overtook is not remembered. For a file descriptor, a failed blob fetch counts as a failure
 * of the descriptor URI.
 */
class PubkyImageFailureCache(
    private val cacheEpoch: PubkyImageCacheEpoch,
    private val clock: Clock,
) {
    private val lock = Any()
    private val failures = mutableMapOf<String, Failure>()
    private var failuresEpoch = cacheEpoch.current()

    fun recentFailure(uri: String): Throwable? = synchronized(lock) {
        forgetIfCleared()
        val failure = failures[uri] ?: return null
        if (failure.expiresAtMillis != null && clock.nowMs() >= failure.expiresAtMillis) {
            failures.remove(uri)
            return null
        }
        failure.error
    }

    fun remember(uri: String, error: Throwable, epoch: Long) {
        synchronized(lock) {
            forgetIfCleared()
            if (epoch != failuresEpoch) return
            val expiresAtMillis = if (error.isPermanentImageFailure()) {
                null
            } else {
                clock.nowMs() + PUBKY_IMAGE_TRANSIENT_FAILURE_TTL.inWholeMilliseconds
            }
            failures[uri] = Failure(error, expiresAtMillis)
        }
    }

    private fun forgetIfCleared() {
        val epoch = cacheEpoch.current()
        if (epoch == failuresEpoch) return
        failures.clear()
        failuresEpoch = epoch
    }

    private fun Throwable.isPermanentImageFailure(): Boolean = generateSequence(this) { it.cause }.any {
        it is PubkyFileNotFoundError || it is PaykitException.NotFound || it is PaykitException.Protocol
    }

    private class Failure(val error: Throwable, val expiresAtMillis: Long?)
}

class PubkyImageRecentlyFailedError(cause: Throwable) : AppError("Pubky image fetch failed recently", cause)

private class PubkyImageData(val bytes: ByteArray, val isCacheable: Boolean)

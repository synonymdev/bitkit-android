package to.bitkit.data

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Counts clears of the Pubky image disk cache. [PubkyImageFetcher] reads it before a network fetch and commits
 * the fetched image only while it is unchanged, so a fetch in flight across a clear cannot re-populate the
 * cleared directory, and [PubkyImageFailureCache] forgets every failed fetch once it changes. Advance it before
 * clearing the directory.
 */
@Singleton
class PubkyImageCacheEpoch @Inject constructor() {
    private val epoch = AtomicLong(0L)

    fun current(): Long = epoch.get()

    fun advance() {
        epoch.incrementAndGet()
    }
}

package to.bitkit.repositories

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.services.PubkyService

internal class ShopContactImporter(private val service: PubkyService) {
    companion object {
        /** Shop's permanent per-seller interaction markers on the buyer's homeserver. */
        const val MARKER_PATH = "/pub/pubky.app/migrations/seller-auto-follow/v1/"

        /** The seller's public storefront, not proof of which app wrote a marker. */
        const val SHOP_PATH = "/pub/pubky.app/marketplace/v1/shop.json"

        /** Maximum markers processed by one existing payment-request poll. */
        const val PAGE_SIZE: UShort = 10u

        /** Maximum accepted marker JSON size. */
        const val MARKER_MAX_BYTES: ULong = 4096u

        /** Maximum accepted storefront JSON size. */
        const val SHOP_MAX_BYTES: ULong = 65_536u
    }

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var owner: PubkySignIn? = null
    private var cursor: String? = null
    private val handled = mutableSetOf<String>()
    private var refreshPending = false

    suspend fun importPage(
        signIn: PubkySignIn,
        refreshContacts: suspend () -> Boolean = { true },
        isStillCurrent: () -> Boolean,
    ): Boolean {
        if (!mutex.tryLock()) return false
        try {
            requireCurrent(isStillCurrent)
            if (owner?.publicKey != signIn.publicKey || owner?.generation != signIn.generation) {
                owner = signIn
                cursor = null
                handled.clear()
                refreshPending = false
            }
            if (refreshPending) {
                if (!refreshContacts()) return false
                requireCurrent(isStillCurrent)
                refreshPending = false
            }
            val buyer = requireNotNull(PubkyPublicKeyFormat.normalized(signIn.publicKey)).removePrefix("pubky")
            val root = "pubky://$buyer$MARKER_PATH"
            val page = service.listPublicDirectoryPage(root, cursor, PAGE_SIZE)
            requireCurrent(isStillCurrent)
            val sellers = page.mapNotNull { verifiedSeller(it, root, buyer, isStillCurrent) }.toSet()
            requireCurrent(isStillCurrent)
            // A cancelled write may have committed; refresh before further discovery on retry.
            refreshPending = sellers.isNotEmpty()
            val saved = service.importShopContacts(sellers.map { "pubky$it" }, signIn.publicKey, isStillCurrent)
            requireCurrent(isStillCurrent)
            if (refreshPending && refreshContacts()) {
                requireCurrent(isStillCurrent)
                refreshPending = false
            }
            if (!refreshPending) {
                handled.addAll(sellers)
                cursor = page.lastOrNull()
            }
            return saved.isNotEmpty()
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun verifiedSeller(
        uri: String,
        root: String,
        buyer: String,
        isStillCurrent: () -> Boolean,
    ): String? {
        requireCurrent(isStillCurrent)
        val seller = uri.removePrefix(root).removeSuffix(".json")
        if (uri != "$root$seller.json" || !isRawKey(seller)) return null
        if (seller == buyer || seller in handled) return null
        return runSuspendCatching {
            val marker = json.decodeFromString<SellerMarker>(
                service.fetchPublicFile(uri, MARKER_MAX_BYTES).decodeToString(),
            )
            requireCurrent(isStillCurrent)
            if (marker.seller != seller || marker.completedAt <= 0) return@runSuspendCatching null
            val shop = json.decodeFromString<Storefront>(
                service.fetchPublicFile("pubky://$seller$SHOP_PATH", SHOP_MAX_BYTES).decodeToString(),
            )
            seller.takeIf { shop.schemaVersion == 1 && shop.recordType == "shop" && shop.ownerPubky == seller }
        }.getOrNull()
    }

    private suspend fun requireCurrent(isStillCurrent: () -> Boolean) {
        currentCoroutineContext().ensureActive()
        if (!isStillCurrent()) throw PubkyContactError.SignInChanged
    }

    private fun isRawKey(value: String): Boolean =
        PubkyPublicKeyFormat.normalized(value) == "pubky$value"
}

@Serializable
private data class SellerMarker(val seller: String, val completedAt: Long)

@Serializable
private data class Storefront(val schemaVersion: Int, val recordType: String, val ownerPubky: String)

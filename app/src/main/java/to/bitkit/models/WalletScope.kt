package to.bitkit.models

import androidx.annotation.VisibleForTesting
import com.synonym.bitkitcore.getDefaultWalletId

object WalletScope {
    private var testOverride: String? = null

    @VisibleForTesting
    internal fun pushTestOverride(walletId: String): AutoCloseable {
        val previous = testOverride
        testOverride = walletId
        return AutoCloseable { testOverride = previous }
    }

    val default: String
        get() = testOverride ?: lazyDefault

    private val lazyDefault: String by lazy { getDefaultWalletId() }
}

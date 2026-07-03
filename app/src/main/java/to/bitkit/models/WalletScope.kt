package to.bitkit.models

import androidx.annotation.VisibleForTesting
import com.synonym.bitkitcore.getDefaultWalletId

object WalletScope {
    @VisibleForTesting
    internal var testOverride: String? = null

    val default: String
        get() = testOverride ?: lazyDefault

    private val lazyDefault: String by lazy { getDefaultWalletId() }
}

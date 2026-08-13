package to.bitkit.ui.utils

import android.net.Uri
import to.bitkit.ui.components.Sheet

object SheetDeepLinks {
    val sheetIds: Set<String>
        get() = ScreenDeepLinkRuntime.sheetIds

    fun sheetFor(uri: Uri): Sheet? = ScreenDeepLinkRuntime.sheetFor(uri)
}

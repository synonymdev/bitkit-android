package to.bitkit.models

import android.net.Uri

object PubkyContactLink {
    fun matches(uri: Uri): Boolean =
        uri.scheme.equals("bitkit", ignoreCase = true) && uri.host.equals("contact", ignoreCase = true)

    fun publicKey(uri: Uri): String? {
        if (!matches(uri)) return null
        if (!uri.encodedAuthority.equals("contact", ignoreCase = true) ||
            !uri.path.isNullOrEmpty() || uri.fragment != null
        ) {
            return null
        }
        if (uri.queryParameterNames != setOf("pubky") || uri.encodedQuery.orEmpty().contains('&')) return null
        val key = uri.getQueryParameters("pubky").singleOrNull()
            ?.takeIf { it.length <= PubkyPublicKeyFormat.maximumInputLength } ?: return null

        return PubkyPublicKeyFormat.normalized(key)
    }
}

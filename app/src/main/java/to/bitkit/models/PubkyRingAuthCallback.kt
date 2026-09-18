package to.bitkit.models

import android.net.Uri

private const val NONCE_PARAM = "nonce"

sealed interface PubkyRingAuthCallback {
    companion object {
        private const val BITKIT_SCHEME = "bitkit"
        private const val PUBKY_AUTH_HOST = "pubky-auth"
        private const val SUCCESS_PATH = "/success"
        private const val CANCEL_PATH = "/cancel"
        private const val ERROR_PATH = "/error"
        private const val ERROR_MESSAGE_PARAM = "errorMessage"

        fun parse(uri: Uri): PubkyRingAuthCallback? {
            if (uri.scheme != BITKIT_SCHEME || uri.host != PUBKY_AUTH_HOST) return null

            val nonce = uri.getQueryParameter(NONCE_PARAM)?.takeIf { it.isNotBlank() }
            return when (uri.path) {
                SUCCESS_PATH -> Success(nonce)
                CANCEL_PATH -> Cancel(nonce)
                ERROR_PATH -> Error(uri.getQueryParameter(ERROR_MESSAGE_PARAM), nonce)
                else -> null
            }
        }
    }

    val nonce: String?

    data class Success(override val nonce: String?) : PubkyRingAuthCallback
    data class Cancel(override val nonce: String?) : PubkyRingAuthCallback
    data class Error(val message: String?, override val nonce: String?) : PubkyRingAuthCallback
}

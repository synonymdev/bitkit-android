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

sealed interface PubkyRingAuthCallbackHandlingResult {
    data object Ignored : PubkyRingAuthCallbackHandlingResult
    data object Handled : PubkyRingAuthCallbackHandlingResult
    data class TrustedError(val message: String?) : PubkyRingAuthCallbackHandlingResult
}

object PubkyRingAuthUrlBuilder {
    const val SUCCESS_CALLBACK = "bitkit://pubky-auth/success"
    const val CANCEL_CALLBACK = "bitkit://pubky-auth/cancel"
    const val ERROR_CALLBACK = "bitkit://pubky-auth/error"
    const val SOURCE = "Bitkit"

    fun addCallbacks(authUrl: String, nonce: String? = null): String? {
        val uri = Uri.parse(authUrl)
        if (uri.scheme.isNullOrBlank()) return null

        return uri.buildUpon()
            .appendQueryParameter("x-success", callbackUrl(SUCCESS_CALLBACK, nonce))
            .appendQueryParameter("x-cancel", callbackUrl(CANCEL_CALLBACK, nonce))
            .appendQueryParameter("x-error", callbackUrl(ERROR_CALLBACK, nonce))
            .appendQueryParameter("x-source", SOURCE)
            .build()
            .toString()
    }

    private fun callbackUrl(baseUrl: String, nonce: String?): String {
        if (nonce.isNullOrBlank()) return baseUrl

        return Uri.parse(baseUrl)
            .buildUpon()
            .appendQueryParameter(NONCE_PARAM, nonce)
            .build()
            .toString()
    }
}

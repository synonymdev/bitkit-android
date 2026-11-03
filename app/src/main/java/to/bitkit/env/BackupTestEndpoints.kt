package to.bitkit.env

import java.net.URI

internal data class BackupTestEndpoints(val vss: String, val auth: String) {
    companion object {
        fun resolve(enabled: Boolean, vss: String?, auth: String?): BackupTestEndpoints? {
            if (!enabled) return null
            val vssUrl = vss.orEmpty().trim()
            val authUrl = auth.orEmpty().trim()
            if (vssUrl.isEmpty() && authUrl.isEmpty()) return null
            require(valid(vssUrl) && valid(authUrl)) {
                "E2E backup endpoints must be a valid pair of localhost URLs with explicit ports"
            }
            return BackupTestEndpoints(vssUrl, authUrl)
        }

        private fun valid(value: String): Boolean = runCatching {
            val url = URI(value)
            url.scheme in listOf("http", "https") && url.host == "localhost" && url.port in 1..65535 &&
                url.userInfo == null && url.query == null && url.fragment == null && !url.path.isNullOrEmpty()
        }.getOrDefault(false)
    }
}

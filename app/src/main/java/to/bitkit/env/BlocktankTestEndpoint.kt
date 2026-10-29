package to.bitkit.env

import java.net.URI

internal object BlocktankTestEndpoint {
    fun resolve(enabled: Boolean, value: String?): String? {
        if (!enabled) return null
        val endpoint = value.orEmpty().trim().takeIf { it.isNotEmpty() } ?: return null
        val valid = runCatching {
            val url = URI(endpoint)
            url.scheme in listOf("http", "https") && url.host == "localhost" && url.port in 1..65535 &&
                url.userInfo == null && url.query == null && url.fragment == null && !url.path.isNullOrEmpty()
        }.getOrDefault(false)
        require(valid) { "E2E Blocktank endpoint must be a localhost URL with an explicit port and path" }
        return endpoint
    }
}

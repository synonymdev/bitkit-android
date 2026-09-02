package to.bitkit.models

import androidx.compose.runtime.Immutable
import to.bitkit.utils.AppError
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class PubkyAuthClaim(val wireValue: String) {
    WATCH_ONLY_ACCOUNT_V1("watch-only-account-v1"),
    ;

    companion object {
        /** Query parameter used for Bitkit-specific Pubky auth claims. */
        const val QUERY_PARAMETER = "x-bitkit-claim"

        /** Both public and private Paykit Server capabilities required by the watch-only setup flow. */
        const val WATCH_ONLY_ACCOUNT_CAPABILITIES =
            "/pub/paykit/v0/bitkit/server/:rw,/pub/paykit/v0/private/bitkit/server/:rw"

        private val watchOnlyAccountCapabilitySet = WATCH_ONLY_ACCOUNT_CAPABILITIES.split(",").toSet()

        /**
         * Matches exactly the required public and private capabilities regardless of ordering or surrounding spaces.
         */
        fun matchesWatchOnlyAccountCapabilities(capabilities: String) =
            capabilitySet(capabilities) == watchOnlyAccountCapabilitySet

        private fun capabilitySet(capabilities: String): Set<String>? {
            val entries = capabilities.split(",").map { it.trim() }
            if (entries.any { it.isEmpty() }) return null
            return entries.toSet()
        }

        fun fromWireValue(value: String) = entries.firstOrNull { it.wireValue == value }
    }
}

sealed class PubkyAuthRequestError(cause: Throwable? = null) : AppError(cause = cause) {
    class InvalidUrl(cause: Throwable) : PubkyAuthRequestError(cause)
    data object RequesterChanged : PubkyAuthRequestError()
    data object MissingBitkitClaim : PubkyAuthRequestError()
    data object DuplicateBitkitClaim : PubkyAuthRequestError()
    data class UnsupportedBitkitClaim(val value: String) : PubkyAuthRequestError()
    data object InvalidBitkitClaimCapabilities : PubkyAuthRequestError()
}

@Immutable
data class PubkyAuthPermission(
    val path: String,
    val accessLevel: String,
) {
    val displayPath: String
        get() = if (path.length > 1) path.removeSuffix("/") else path

    val displayAccess: String
        get() = accessLevel.map { char ->
            when (char) {
                'r' -> "READ"
                'w' -> "WRITE"
                else -> ""
            }
        }.filter { it.isNotEmpty() }.joinToString(", ")
}

data class PubkyAuthRequest(
    val rawUrl: String,
    val clientId: String,
    val relay: String,
    val capabilities: String,
    val permissions: List<PubkyAuthPermission>,
    val serviceNames: List<String>,
    val bitkitClaim: PubkyAuthClaim?,
    val homeserverPublicKey: String? = null,
    val signupToken: String? = null,
    val authorizationUrl: String = rawUrl,
) {
    val isRingSignup: Boolean
        get() = isRingSignupUrl(rawUrl)

    companion object {
        @Suppress("LongParameterList")
        fun parse(
            rawUrl: String,
            clientId: String,
            relay: String,
            capabilities: String,
            homeserverPublicKey: String? = null,
            signupToken: String? = null,
            authorizationUrl: String = rawUrl,
        ): Result<PubkyAuthRequest> = parseBitkitClaim(rawUrl, capabilities).map { bitkitClaim ->
            val permissions = parseCapabilities(capabilities)
            PubkyAuthRequest(
                rawUrl = rawUrl,
                clientId = clientId,
                relay = relay,
                capabilities = capabilities,
                permissions = permissions,
                serviceNames = permissions.mapNotNull { extractServiceName(it.path) }.distinct(),
                bitkitClaim = bitkitClaim,
                homeserverPublicKey = homeserverPublicKey,
                signupToken = signupToken,
                authorizationUrl = authorizationUrl,
            )
        }

        fun isProtocolUrl(rawUrl: String): Boolean = runCatching {
            val uri = URI(rawUrl)
            when (uri.scheme?.lowercase()) {
                "pubkyauth" -> true
                "pubkyring" -> uri.host.equals("signup", ignoreCase = true)
                else -> false
            }
        }.getOrDefault(false)

        fun isRingSignupUrl(rawUrl: String): Boolean = runCatching {
            val uri = URI(rawUrl)
            uri.scheme.equals("pubkyring", ignoreCase = true) &&
                uri.host.equals("signup", ignoreCase = true)
        }.getOrDefault(false)

        fun parseRingSignup(rawUrl: String): Result<PubkyAuthRequest> = runCatching {
            val uri = URI(rawUrl)
            require(
                uri.scheme.equals("pubkyring", ignoreCase = true) &&
                    uri.host.equals("signup", ignoreCase = true),
            ) { "Unsupported Pubky signup URL" }
            val query = parseQuery(uri)
            val relay = query.requiredSingle("relay")
            val secret = query.requiredSingle("secret")
            val capabilities = query.requiredSingle("caps")
            val homeserver = query.requiredSingle("hs")
            val authorizationUrl = ringAuthorizationUrl(relay, secret, capabilities)

            parse(
                rawUrl = rawUrl,
                clientId = "",
                relay = relay,
                capabilities = capabilities,
                homeserverPublicKey = homeserver,
                signupToken = query.optionalSingle("st"),
                authorizationUrl = authorizationUrl,
            ).getOrThrow().also {
                require(it.bitkitClaim == null) { "Ring signup does not support Bitkit companion claims" }
            }
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(PubkyAuthRequestError.InvalidUrl(it)) },
        )

        fun parseBitkitClaim(rawUrl: String, capabilities: String): Result<PubkyAuthClaim?> =
            parseBitkitClaimValues(rawUrl).fold(
                onSuccess = { claimValues -> validateBitkitClaim(claimValues, capabilities) },
                onFailure = { Result.failure(it) },
            )

        private fun parseBitkitClaimValues(rawUrl: String): Result<List<String>> = runCatching {
            URI(rawUrl).rawQuery.orEmpty()
                .split("&")
                .filter { it.isNotEmpty() }
                .map { it.split("=", limit = 2) }
                .filter { decodeQueryComponent(it.first()) == PubkyAuthClaim.QUERY_PARAMETER }
                .map { decodeQueryComponent(it.getOrElse(1) { "" }) }
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(PubkyAuthRequestError.InvalidUrl(it)) },
        )

        private fun validateBitkitClaim(
            claimValues: List<String>,
            capabilities: String,
        ): Result<PubkyAuthClaim?> = when {
            claimValues.size > 1 -> Result.failure(PubkyAuthRequestError.DuplicateBitkitClaim)
            claimValues.isEmpty() && PubkyAuthClaim.matchesWatchOnlyAccountCapabilities(capabilities) ->
                Result.failure(PubkyAuthRequestError.MissingBitkitClaim)
            claimValues.isEmpty() -> Result.success(null)
            else -> validateBitkitClaimValue(claimValues.first(), capabilities)
        }

        private fun validateBitkitClaimValue(
            claimValue: String,
            capabilities: String,
        ): Result<PubkyAuthClaim?> {
            val claim = PubkyAuthClaim.fromWireValue(claimValue)
                ?: return Result.failure(PubkyAuthRequestError.UnsupportedBitkitClaim(claimValue))

            return if (PubkyAuthClaim.matchesWatchOnlyAccountCapabilities(capabilities)) {
                Result.success(claim)
            } else {
                Result.failure(PubkyAuthRequestError.InvalidBitkitClaimCapabilities)
            }
        }

        fun parseCapabilities(caps: String): List<PubkyAuthPermission> =
            caps.split(",")
                .filter { it.isNotBlank() }
                .mapNotNull { segment ->
                    val lastColon = segment.lastIndexOf(':')
                    if (lastColon <= 0) return@mapNotNull null
                    val path = segment.substring(0, lastColon)
                    val access = segment.substring(lastColon + 1)
                    PubkyAuthPermission(path = path, accessLevel = access)
                }

        fun extractServiceName(path: String): String? {
            val parts = path.trimStart('/').split("/")
            val pubIndex = parts.indexOf("pub")
            return if (pubIndex >= 0 && pubIndex + 1 < parts.size) parts[pubIndex + 1] else null
        }

        private fun decodeQueryComponent(value: String) = URLDecoder.decode(value, StandardCharsets.UTF_8.name())

        private fun ringAuthorizationUrl(relay: String, secret: String, capabilities: String): String =
            "pubkyauth:///?relay=${encodeQueryComponent(relay)}" +
                "&secret=${encodeQueryComponent(secret)}&caps=${encodeQueryComponent(capabilities)}"

        private fun encodeQueryComponent(value: String) =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

        private fun parseQuery(uri: URI): Map<String, List<String>> = uri.rawQuery.orEmpty()
            .split("&")
            .filter { it.isNotEmpty() }
            .map { it.split("=", limit = 2) }
            .groupBy(
                keySelector = { decodeQueryComponent(it.first()) },
                valueTransform = { decodeQueryComponent(it.getOrElse(1) { "" }) },
            )

        private fun Map<String, List<String>>.requiredSingle(name: String): String =
            optionalSingle(name)?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Missing Pubky signup parameter: $name")

        private fun Map<String, List<String>>.optionalSingle(name: String): String? {
            val values = this[name].orEmpty()
            require(values.size <= 1) { "Duplicate Pubky signup parameter: $name" }
            return values.singleOrNull()?.takeIf { it.isNotBlank() }
        }
    }
}

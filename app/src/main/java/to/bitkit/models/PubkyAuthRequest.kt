package to.bitkit.models

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import to.bitkit.repositories.Endpoint
import to.bitkit.repositories.MethodId
import to.bitkit.utils.AppError
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Immutable
@JvmInline
value class PubkyAuthClaim private constructor(val items: ImmutableList<Item>) {
    constructor(vararg items: Item) : this(items.sortedBy { it.ordinal }.toImmutableList())

    init {
        require(items.isNotEmpty() && items.distinct().size == items.size)
    }

    enum class Item(val wireValue: String) {
        PAYKIT_ACCESS_V1("paykit-access-v1"),
        WATCH_ONLY_ACCOUNT_V1("watch-only-account-v1"),
        USDT_ADDRESS_V1("usdt-address-v1"),
        PAYMENT_DETAILS_V1("payment-details-v1"),
    }

    val wireValue: String get() = items.joinToString(".") { it.wireValue }
    val includesWatchOnlyAccount: Boolean
        get() = Item.WATCH_ONLY_ACCOUNT_V1 in items || Item.PAYMENT_DETAILS_V1 in items
    val includesPaykitAccess: Boolean get() = Item.PAYKIT_ACCESS_V1 in items

    val sharesBitcoin: Boolean get() = includesWatchOnlyAccount
    val sharesUsdt: Boolean get() = Item.USDT_ADDRESS_V1 in items || Item.PAYMENT_DETAILS_V1 in items
    val sharesReceivingDetails: Boolean get() = sharesBitcoin || sharesUsdt

    fun unsignedPayload(bitcoin: PreparedWatchOnlyAccountClaim?, usdt: Endpoint?): ByteArray {
        if (sharesBitcoin && bitcoin == null) throw PubkyAuthRequestError.InvalidPaymentDetails
        if (!sharesUsdt) return bitcoin?.payload ?: byteArrayOf()
        if (usdt == null || usdt.methodId != MethodId.UsdtArbitrum ||
            PaykitUsdt.address(usdt.rawPayload) != usdt.value
        ) {
            throw PubkyAuthRequestError.InvalidPaymentDetails
        }
        return buildJsonObject {
            put(MethodId.UsdtArbitrum.rawValue, Json.parseToJsonElement(usdt.rawPayload).jsonObject)
            if (sharesBitcoin) {
                val account = checkNotNull(bitcoin).account
                put(
                    "bitcoin_account",
                    buildJsonObject {
                        put("account_index", account.accountIndex)
                        put("address_type", account.addressType)
                        put("xpub", account.xpub)
                    }
                )
            }
        }.toString().encodeToByteArray()
    }

    companion object {
        val USDT_ADDRESS_V1 = PubkyAuthClaim(Item.USDT_ADDRESS_V1)
        val PAYMENT_DETAILS_V1 = PubkyAuthClaim(Item.PAYMENT_DETAILS_V1)

        /** Query parameter used for Bitkit-specific Pubky auth claims. */
        const val QUERY_PARAMETER = "x-bitkit-claim"

        /** Exact Pubky storage scope required by Bitkit companion claims. */
        const val REQUIRED_CAPABILITIES = "/pub/paykit/:rw"

        /** Matches the required storage scope, allowing surrounding whitespace but no duplicate capabilities. */
        fun matchesRequiredCapabilities(capabilities: String) =
            capabilities.trim() == REQUIRED_CAPABILITIES

        /** Preserves the received item order because the SDK signs and routes using this exact string. */
        fun fromWireValue(value: String): PubkyAuthClaim? {
            val items = value.split(".").map { token ->
                Item.entries.firstOrNull { it.wireValue == token } ?: return null
            }
            if (items.distinct().size != items.size) return null
            val hasStandaloneClaim = items.any { it == Item.USDT_ADDRESS_V1 || it == Item.PAYMENT_DETAILS_V1 }
            if (items.size != 1 && hasStandaloneClaim) return null
            return PubkyAuthClaim(items.toImmutableList())
        }
    }
}

sealed class PubkyAuthRequestError(cause: Throwable? = null) : AppError(cause = cause) {
    class InvalidUrl(cause: Throwable) : PubkyAuthRequestError(cause)
    data object InvalidPaymentDetails : PubkyAuthRequestError()
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
    val authorizationUrl: String? = rawUrl,
) {
    val isSignup: Boolean
        get() = isSignupUrl(rawUrl)

    val isGrantSignup: Boolean
        get() = isGrantSignupUrl(rawUrl)

    fun requiresIdentityCreation(hasIdentity: Boolean): Boolean = isSignup && (!isGrantSignup || !hasIdentity)

    companion object {
        @Suppress("LongParameterList")
        fun parse(
            rawUrl: String,
            clientId: String,
            relay: String,
            capabilities: String,
            homeserverPublicKey: String? = null,
            signupToken: String? = null,
            authorizationUrl: String? = rawUrl,
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

        fun isSignupUrl(rawUrl: String): Boolean = runCatching { URI(rawUrl).isSignupRequest() }.getOrDefault(false)

        fun isGrantSignupUrl(rawUrl: String): Boolean = runCatching {
            val uri = URI(rawUrl)
            uri.scheme.equals("pubkyauth", ignoreCase = true) &&
                (uri.host ?: uri.rawAuthority).equals("signup_grant", ignoreCase = true)
        }.getOrDefault(false)

        fun parseGrantSignup(
            rawUrl: String,
            clientId: String,
            relay: String,
            capabilities: String,
            homeserverPublicKey: String?,
        ): Result<PubkyAuthRequest> = runCatching {
            require(isGrantSignupUrl(rawUrl)) { "Not a Pubky grant signup request" }
            val query = parseQuery(URI(rawUrl))
            query.requiredSingle("hs")
            parse(
                rawUrl = rawUrl,
                clientId = clientId,
                relay = relay,
                capabilities = capabilities,
                homeserverPublicKey = requireNotNull(homeserverPublicKey),
                signupToken = query.optionalSingle("st"),
            ).getOrThrow()
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(PubkyAuthRequestError.InvalidUrl(it)) },
        )

        fun parseSignup(rawUrl: String): Result<PubkyAuthRequest> = runCatching {
            val uri = URI(rawUrl)
            require(uri.isSignupRequest() && !isGrantSignupUrl(rawUrl)) { "Unsupported Pubky signup URL" }
            val query = parseQuery(uri)
            val homeserver = query.requiredSingle("hs")
            val authorizesApp = uri.authorizesApp(query)
            val relay = if (authorizesApp) query.requiredSingle("relay") else ""
            val secret = if (authorizesApp) query.requiredSingle("secret") else ""
            val capabilities = if (authorizesApp) query.requiredSingle("caps") else ""
            val authorizationUrl = if (authorizesApp) {
                ringAuthorizationUrl(relay, secret, capabilities)
            } else {
                null
            }

            parse(
                rawUrl = rawUrl,
                clientId = "",
                relay = relay,
                capabilities = capabilities,
                homeserverPublicKey = homeserver,
                signupToken = query.optionalSingle("st"),
                authorizationUrl = authorizationUrl,
            ).getOrThrow().also {
                require(it.bitkitClaim == null) { "Pubky signup does not support Bitkit companion claims" }
            }
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(PubkyAuthRequestError.InvalidUrl(it)) },
        )

        private fun URI.isSignupRequest(): Boolean = when (scheme?.lowercase()) {
            "pubkyring" -> host.equals("signup", ignoreCase = true)
            "pubkyauth" -> isDirectSignupRequest() || (host ?: rawAuthority).equals("signup_grant", ignoreCase = true)
            else -> false
        }

        private fun URI.isDirectSignupRequest(): Boolean =
            scheme.equals("pubkyauth", ignoreCase = true) && (host ?: rawAuthority).let {
                it.equals("direct_signup", ignoreCase = true) || it.equals("signup", ignoreCase = true)
            }

        private fun URI.authorizesApp(query: Map<String, List<String>>): Boolean =
            scheme.equals("pubkyring", ignoreCase = true) ||
                (
                    scheme.equals("pubkyauth", ignoreCase = true) &&
                        (host ?: rawAuthority).equals("signup", ignoreCase = true) &&
                        listOf("relay", "secret", "caps").any(query::containsKey)
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
            claimValues.isEmpty() -> Result.success(null)
            else -> validateBitkitClaimValue(claimValues.first(), capabilities)
        }

        private fun validateBitkitClaimValue(
            claimValue: String,
            capabilities: String,
        ): Result<PubkyAuthClaim?> {
            val claim = PubkyAuthClaim.fromWireValue(claimValue)
                ?: return Result.failure(PubkyAuthRequestError.UnsupportedBitkitClaim(claimValue))

            return if (PubkyAuthClaim.matchesRequiredCapabilities(capabilities)) {
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

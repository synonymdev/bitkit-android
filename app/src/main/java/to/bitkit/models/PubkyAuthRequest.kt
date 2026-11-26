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
    }

    val wireValue: String get() = items.joinToString(".") { it.wireValue }
    val includesWatchOnlyAccount: Boolean
        get() = Item.WATCH_ONLY_ACCOUNT_V1 in items
    val includesPaykitAccess: Boolean get() = Item.PAYKIT_ACCESS_V1 in items

    val sharesBitcoin: Boolean get() = includesWatchOnlyAccount
    val sharesUsdt: Boolean get() = Item.USDT_ADDRESS_V1 in items
    val sharesReceivingDetails: Boolean get() = sharesBitcoin || sharesUsdt

    fun unsignedPayload(bitcoin: PreparedWatchOnlyAccountClaim?, usdt: Endpoint?): ByteArray {
        if (sharesBitcoin && bitcoin == null) throw PubkyAuthRequestError.InvalidPaymentDetails
        if (!sharesUsdt) return bitcoin?.payload ?: byteArrayOf()
        if (
            usdt != null && (
                usdt.methodId != MethodId.UsdtArbitrum ||
                    PaykitUsdt.address(usdt.rawPayload) != usdt.value
                )
        ) {
            throw PubkyAuthRequestError.InvalidPaymentDetails
        }
        return buildJsonObject {
            usdt?.let { put(MethodId.UsdtArbitrum.rawValue, Json.parseToJsonElement(it.rawPayload).jsonObject) }
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
        val BITCOIN_AND_USDT = PubkyAuthClaim(Item.WATCH_ONLY_ACCOUNT_V1, Item.USDT_ADDRESS_V1)

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

    /**
     * The relay URL that receives the authorization, without user info or fragment so the host cannot be disguised.
     * This is a delivery destination, not a service identity. Null only when the request has no relay.
     */
    val relayDisplayUrl: String? get() = displayRelay(relay)

    companion object {
        private val RELAY_SCHEMES = setOf("http", "https")

        /** A relay host as the Pubky SDK's URL parser would keep it, with an optional port. */
        private val RELAY_HOST_PORT = Regex("""(\[[0-9a-f:.]+]|[a-z0-9._~-]+)(:[0-9]{1,5})?""")

        /** A host label the SDK's URL parser reads as a number, which makes the whole host an IPv4 address. */
        private val NUMERIC_HOST_LABEL = Regex("[0-9]+|0x[0-9a-f]*")

        /** An IPv4 address in the only form the SDK's URL parser leaves unchanged. */
        private val CANONICAL_IPV4 = "(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])".let { Regex("""($it\.){3}$it""") }

        /** Characters the SDK's URL parser rewrites or percent-encodes, so a relay holding one is not shown as sent. */
        private const val RELAY_REWRITTEN_CHARACTERS = "\\\"<>`{}^'"

        /** Ports the SDK's URL parser drops because they are the scheme's default. */
        private val RELAY_DEFAULT_PORTS = mapOf("http" to 80, "https" to 443)

        /** Highest TCP port. */
        private const val MAX_PORT = 65535

        /** Number of 16-bit groups in an IPv6 address. */
        private const val IPV6_GROUPS = 8

        /** Highest value of one IPv6 group. */
        private const val MAX_IPV6_GROUP = 0xFFFF

        /** First code point past printable ASCII. */
        private const val DEL = 0x7F

        /**
         * The relay as shown on the approval sheet: scheme, host, port, path and query, exactly as the Pubky SDK's URL
         * parser keeps them. Null when [relay] is not already in that form apart from letter case, user info, the
         * fragment and a missing root path: a backslash, a space, control or non-ASCII characters, characters the
         * parser percent-encodes, a missing, percent-encoded or non-canonical host, a default, padded or out-of-range
         * port, or a `.` or `..` path segment.
         */
        fun displayRelay(relay: String): String? {
            if (relay.any { it.code <= ' '.code || it.code >= DEL || it in RELAY_REWRITTEN_CHARACTERS }) return null
            val schemeEnd = relay.indexOf("://")
            if (schemeEnd <= 0) return null
            val scheme = relay.substring(0, schemeEnd).lowercase().takeIf { it in RELAY_SCHEMES } ?: return null
            val rest = relay.substring(schemeEnd + "://".length)
            val authorityEnd = rest.indexOfAny(charArrayOf('/', '?', '#')).takeIf { it >= 0 } ?: rest.length
            val hostPort = rest.substring(0, authorityEnd).substringAfterLast('@').lowercase()
            val pathAndQuery = rest.substring(authorityEnd).substringBefore('#')
            val hasDotSegment = pathAndQuery.substringBefore('?').split('/')
                .any { it.lowercase().replace("%2e", ".") in setOf(".", "..") }
            if (hasDotSegment || !isUnchangedRelayHostPort(scheme, hostPort)) return null
            val rootPath = if (pathAndQuery.startsWith("/")) "" else "/"
            return "$scheme://$hostPort$rootPath$pathAndQuery"
        }

        private fun isUnchangedRelayHostPort(scheme: String, hostPort: String): Boolean {
            val (host, portSuffix) = RELAY_HOST_PORT.matchEntire(hostPort)?.destructured ?: return false
            val port = portSuffix.removePrefix(":")
            val isUnchangedPort = port.isEmpty() || port.toInt().let {
                port == it.toString() && it <= MAX_PORT && it != RELAY_DEFAULT_PORTS[scheme]
            }
            if (!isUnchangedPort) return false
            if (host.startsWith("[")) return isCanonicalIpv6(host.removeSurrounding("[", "]"))
            val readsAsNumber = NUMERIC_HOST_LABEL.matches(host.removeSuffix(".").substringAfterLast('.'))
            return !readsAsNumber || CANONICAL_IPV4.matches(host)
        }

        private fun isCanonicalIpv6(address: String): Boolean {
            val halves = address.split("::")
            val head = halves[0].split(':').filter { it.isNotEmpty() }
            val tail = halves.getOrNull(1)?.split(':')?.filter { it.isNotEmpty() }.orEmpty()
            val missing = IPV6_GROUPS - head.size - tail.size
            val isComplete = if (halves.size == 1) missing == 0 else halves.size == 2 && missing >= 1
            if (!isComplete || '.' in address) return false
            val groups = (head + List(missing) { "0" } + tail).map { group ->
                group.toIntOrNull(radix = 16)?.takeIf { it <= MAX_IPV6_GROUP } ?: return false
            }
            var runStart = -1
            var runLength = 0
            var index = 0
            while (index < groups.size) {
                val end = (index until groups.size).firstOrNull { groups[it] != 0 } ?: groups.size
                if (end - index > runLength) {
                    runStart = index
                    runLength = end - index
                }
                index = maxOf(end, index + 1)
            }
            val hex = { part: List<Int> -> part.joinToString(":") { it.toString(radix = 16) } }
            val canonical = if (runLength < 2) {
                hex(groups)
            } else {
                "${hex(groups.take(runStart))}::${hex(groups.drop(runStart + runLength))}"
            }
            return canonical == address
        }

        fun parse(
            rawUrl: String,
            clientId: String,
            relay: String,
            capabilities: String,
            homeserverPublicKey: String? = null,
            signupToken: String? = null,
            authorizationUrl: String? = rawUrl,
        ): Result<PubkyAuthRequest> = parseBitkitClaim(rawUrl, capabilities).mapCatching { bitkitClaim ->
            if (relay.isNotEmpty() && displayRelay(relay) == null) {
                throw PubkyAuthRequestError.InvalidUrl(IllegalArgumentException("Unreadable Pubky auth relay"))
            }
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

package to.bitkit.models

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

data class SamRockSetupRequest(
    val postUrl: String,
    val storeId: String,
    val otp: String,
    val requestedMethods: Set<SamRockPaymentMethod>,
    val hasUnknownMethods: Boolean,
    val hostDisplayName: String,
    val logDescription: String,
) {
    companion object {
        @Suppress("CyclomaticComplexMethod", "ReturnCount")
        fun parse(raw: String): SamRockSetupRequest? {
            val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
            val host = uri.host ?: return null
            if (uri.rawUserInfo != null) return null

            val isAllowedScheme = scheme == HTTPS_SCHEME || scheme == HTTP_SCHEME && isLocalOrPrivateHost(host)
            if (!isAllowedScheme) return null

            val pathComponents = uri.decodedPathComponents()
                ?: return null

            if (pathComponents.size != EXPECTED_PATH_COMPONENTS) return null
            if (pathComponents[0] != PLUGINS_PATH_COMPONENT) return null
            if (!pathComponents[2].equals(SAMROCK_PATH_COMPONENT, ignoreCase = true)) return null
            if (!pathComponents[3].equals(PROTOCOL_PATH_COMPONENT, ignoreCase = true)) return null

            val queryItems = runCatching { parseQuery(uri.rawQuery ?: return null) }.getOrNull() ?: return null
            val otp = queryItems.firstValue(OTP_QUERY_KEY)?.takeIf { it.isNotBlank() } ?: return null
            val setup = queryItems.firstValue(SETUP_QUERY_KEY)
            val parsedMethods = parseMethods(setup)

            return SamRockSetupRequest(
                postUrl = buildPostUrl(uri, setup, otp),
                storeId = pathComponents[1],
                otp = otp,
                requestedMethods = parsedMethods.methods,
                hasUnknownMethods = parsedMethods.hasUnknownMethods,
                hostDisplayName = uri.hostDisplayName(),
                logDescription = uri.logDescription(),
            )
        }

        fun sanitizedDescription(raw: String): String? {
            val trimmed = raw.trim()
            val uri = runCatching { URI(trimmed) }.getOrNull()
            if (uri?.hasAuthority() == true && uri.isSamRockProtocolPath()) return uri.logDescription()

            return trimmed.sanitizedSamRockLikeDescription()
        }

        fun sanitizedLaunchKey(raw: String): String? {
            return sanitizedDescription(raw)?.let { "$it#${raw.sha256Prefix()}" }
        }

        fun isProtocolUrl(raw: String): Boolean {
            return sanitizedDescription(raw) != null
        }

        fun isPublicHttpProtocolUrl(raw: String): Boolean {
            val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
            val host = uri.host ?: return false

            return scheme == HTTP_SCHEME &&
                uri.isSamRockProtocolPath() &&
                !isLocalOrPrivateHost(host)
        }

        private fun parseMethods(setup: String?): ParsedSamRockMethods {
            val values = setup
                ?.split(SETUP_METHOD_SEPARATOR)
                ?.map { it.trim().lowercase(Locale.US) }
                ?.filter { it.isNotEmpty() }
                .orEmpty()

            if (values.isEmpty()) {
                return ParsedSamRockMethods(
                    methods = setOf(SamRockPaymentMethod.ALL),
                    hasUnknownMethods = false,
                )
            }

            val methods = values.mapNotNull(SamRockPaymentMethod::fromProtocolValue).toSet()
            return ParsedSamRockMethods(
                methods = methods,
                hasUnknownMethods = values.any { SamRockPaymentMethod.fromProtocolValue(it) == null },
            )
        }

        private fun buildPostUrl(
            uri: URI,
            setup: String?,
            otp: String,
        ): String {
            val query = buildList {
                setup?.let { add("$SETUP_QUERY_KEY=${encodeQueryValue(it)}") }
                add("$OTP_QUERY_KEY=${encodeQueryValue(otp)}")
            }.joinToString("&")

            val authority = uri.rawAuthority ?: uri.host
            return "${uri.scheme}://$authority${uri.rawPath}?$query"
        }

        private fun parseQuery(rawQuery: String): List<Pair<String, String>> {
            return rawQuery
                .split("&")
                .filter { it.isNotEmpty() }
                .map {
                    val parts = it.split("=", limit = 2)
                    decode(parts[0]) to decode(parts.getOrElse(1) { "" })
                }
        }

        private fun List<Pair<String, String>>.firstValue(key: String): String? {
            return firstOrNull { it.first.equals(key, ignoreCase = true) }?.second
        }

        private fun URI.hostDisplayName(): String {
            val portSuffix = port.takeIf { it != NO_PORT }?.let { ":$it" }.orEmpty()
            return "$host$portSuffix"
        }

        private fun URI.logDescription(): String {
            return "$scheme://${hostWithPort()}$rawPath"
        }

        private fun URI.hostWithPort(): String {
            val formattedHost = host?.takeIf { ":" in it }?.let { "[$it]" } ?: host.orEmpty()
            val portSuffix = port.takeIf { it != NO_PORT }?.let { ":$it" }.orEmpty()
            return "$formattedHost$portSuffix"
        }

        private fun URI.isSamRockProtocolPath(): Boolean {
            val pathComponents = decodedPathComponents()
                ?: return false

            return pathComponents.size == EXPECTED_PATH_COMPONENTS &&
                pathComponents[0] == PLUGINS_PATH_COMPONENT &&
                pathComponents[2].equals(SAMROCK_PATH_COMPONENT, ignoreCase = true) &&
                pathComponents[3].equals(PROTOCOL_PATH_COMPONENT, ignoreCase = true)
        }

        private fun URI.hasAuthority(): Boolean {
            return scheme != null && host != null
        }

        private fun String.sanitizedSamRockLikeDescription(): String? {
            val queryStart = indexOfAny(SENSITIVE_URL_DELIMITERS)
            val withoutQuery = if (queryStart == NOT_FOUND) this else substring(0, queryStart)
            val normalized = withoutQuery.lowercase(Locale.US)
            val isSamRockLikePath = PLUGINS_PATH_MARKER in normalized &&
                SAMROCK_PROTOCOL_PATH_MARKER in normalized

            if (!isSamRockLikePath) return null

            return runCatching { URI(withoutQuery) }
                .getOrNull()
                ?.takeIf { it.hasAuthority() }
                ?.logDescription()
                ?: withoutQuery.stripUserInfoFromAuthority()
        }

        private fun String.stripUserInfoFromAuthority(): String {
            val schemeEnd = indexOf(SCHEME_SEPARATOR)
            if (schemeEnd == NOT_FOUND) return this

            val authorityStart = schemeEnd + SCHEME_SEPARATOR.length
            val pathStart = indexOf(PATH_SEPARATOR, startIndex = authorityStart)
                .takeUnless { it == NOT_FOUND }
                ?: length
            val authority = substring(authorityStart, pathStart)
            val safeAuthority = authority.substringAfterLast(USER_INFO_SEPARATOR)

            return substring(0, authorityStart) + safeAuthority + substring(pathStart)
        }

        private fun URI.decodedPathComponents(): List<String>? {
            return runCatching {
                rawPath
                    ?.split(PATH_SEPARATOR)
                    ?.filter { it.isNotBlank() }
                    ?.map(::decode)
            }.getOrNull()
        }

        private fun decode(value: String): String {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        }

        private fun encodeQueryValue(value: String): String {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
        }

        private fun String.sha256Prefix(): String {
            val bytes = MessageDigest.getInstance(SHA_256_ALGORITHM).digest(trim().toByteArray(StandardCharsets.UTF_8))
            return bytes.take(LAUNCH_KEY_HASH_BYTES).joinToString("") { "%02x".format(it) }
        }

        private fun isLocalOrPrivateHost(host: String): Boolean {
            val normalized = host
                .removePrefix("[")
                .removeSuffix("]")
                .lowercase(Locale.US)

            return normalized.isLocalHost() ||
                normalized.isPrivateIpv6Literal() ||
                normalized.isPrivateIpv4Literal()
        }

        private fun String.isLocalHost(): Boolean {
            return this == LOCALHOST || endsWith(LOCAL_HOST_SUFFIX) || this == IPV6_LOOPBACK
        }

        private fun String.isPrivateIpv6Literal(): Boolean {
            return isIpv6Literal() && IPV6_PRIVATE_PREFIXES.any(::startsWith)
        }

        @Suppress("MagicNumber")
        private fun String.isPrivateIpv4Literal(): Boolean {
            val octets = split(".").map {
                it.toIntOrNull() ?: return false
            }
            if (octets.size != IPV4_OCTET_COUNT) return false
            if (octets.any { it !in IPV4_OCTET_RANGE }) return false

            return when (octets[0]) {
                10, 127 -> true
                172 -> octets[1] in 16..31
                192 -> octets[1] == 168
                169 -> octets[1] == 254
                else -> false
            }
        }

        private fun String.isIpv6Literal(): Boolean = ":" in this

        private const val HTTP_SCHEME = "http"
        private const val HTTPS_SCHEME = "https"
        private const val LOCALHOST = "localhost"
        private const val LOCAL_HOST_SUFFIX = ".local"
        private const val IPV6_LOOPBACK = "::1"
        private const val IPV6_LINK_LOCAL_PREFIX = "fe80:"
        private const val IPV6_UNIQUE_LOCAL_FC_PREFIX = "fc"
        private const val IPV6_UNIQUE_LOCAL_FD_PREFIX = "fd"
        private val IPV6_PRIVATE_PREFIXES = listOf(
            IPV6_LINK_LOCAL_PREFIX,
            IPV6_UNIQUE_LOCAL_FC_PREFIX,
            IPV6_UNIQUE_LOCAL_FD_PREFIX,
        )
        private const val IPV4_OCTET_COUNT = 4
        private val IPV4_OCTET_RANGE = 0..255
        private const val NO_PORT = -1
        private const val NOT_FOUND = -1
        private const val PATH_SEPARATOR = '/'
        private const val SCHEME_SEPARATOR = "://"
        private const val USER_INFO_SEPARATOR = '@'
        private const val EXPECTED_PATH_COMPONENTS = 4
        private const val PLUGINS_PATH_COMPONENT = "plugins"
        private const val SAMROCK_PATH_COMPONENT = "samrock"
        private const val PROTOCOL_PATH_COMPONENT = "protocol"
        private const val PLUGINS_PATH_MARKER = "/plugins/"
        private const val SAMROCK_PROTOCOL_PATH_MARKER = "/samrock/protocol"
        private const val OTP_QUERY_KEY = "otp"
        private const val SETUP_QUERY_KEY = "setup"
        private const val SETUP_METHOD_SEPARATOR = ","
        private const val SHA_256_ALGORITHM = "SHA-256"
        private const val LAUNCH_KEY_HASH_BYTES = 8
        private val SENSITIVE_URL_DELIMITERS = charArrayOf('?', '#')
    }

    val requestsBitcoinOnchain: Boolean
        get() = requestedMethods.any {
            it == SamRockPaymentMethod.ALL ||
                it == SamRockPaymentMethod.BTC ||
                it == SamRockPaymentMethod.BTC_ONCHAIN
        }

    val requestsUnsupportedMethods: Boolean
        get() = hasUnknownMethods || requestedMethods.any {
            it == SamRockPaymentMethod.ALL ||
                it == SamRockPaymentMethod.LIQUID ||
                it == SamRockPaymentMethod.LIQUID_ONCHAIN ||
                it == SamRockPaymentMethod.BTC_LIGHTNING
        }
}

enum class SamRockPaymentMethod(
    private val protocolValues: Set<String>,
) {
    ALL(setOf("all")),
    BTC(setOf("btc")),
    BTC_ONCHAIN(setOf("btc-chain")),
    LIQUID(setOf("lbtc")),
    LIQUID_ONCHAIN(setOf("liquid-chain")),
    BTC_LIGHTNING(setOf("btcln", "btc-ln"));

    companion object {
        fun fromProtocolValue(value: String): SamRockPaymentMethod? {
            return entries.firstOrNull { value in it.protocolValues }
        }
    }
}

private data class ParsedSamRockMethods(
    val methods: Set<SamRockPaymentMethod>,
    val hasUnknownMethods: Boolean,
)

fun String.sanitizedQrLogValue(): String {
    return SamRockSetupRequest.sanitizedDescription(this) ?: redactedLogValue()
}

fun String.sanitizedDeeplinkLogValue(): String {
    SamRockSetupRequest.sanitizedDescription(this)?.let { return it }

    val uri = runCatching { URI(trim()) }.getOrNull() ?: return redactedLogValue()
    val scheme = uri.scheme ?: return redactedLogValue()
    val host = uri.host ?: return scheme
    val formattedHost = if (":" in host) "[$host]" else host
    val portSuffix = uri.port.takeIf { it != LOG_NO_PORT }?.let { ":$it" }.orEmpty()

    return "$scheme://$formattedHost$portSuffix${uri.rawPath.orEmpty()}"
}

private fun String.redactedLogValue(): String {
    return "redacted#${sha256LogPrefix()}"
}

private fun String.sha256LogPrefix(): String {
    val bytes = MessageDigest.getInstance(LOG_SHA_256_ALGORITHM).digest(trim().toByteArray(StandardCharsets.UTF_8))
    return bytes.take(LOG_HASH_BYTES).joinToString("") { "%02x".format(it) }
}

private const val LOG_HASH_BYTES = 8
private const val LOG_NO_PORT = -1
private const val LOG_SHA_256_ALGORITHM = "SHA-256"

package to.bitkit.models

import to.bitkit.ext.URI_SCHEME
import to.bitkit.ext.isWebLink

/** pubky.app link limits from pubky-app-specs, as Paykit checks them when it publishes a profile. */
const val PUBKY_APP_LINK_TITLE_MAX_LENGTH = 100
const val PUBKY_APP_LINK_URL_MAX_LENGTH = 300

/** First printable ASCII code; lower codes are control characters. */
private const val ASCII_SPACE = 0x20

/** The ASCII delete control character. */
private const val ASCII_DELETE = 0x7F

/** Highest port `url::Url::parse` accepts. */
private const val MAX_PORT = 65_535

/** Characters `url::Url::parse` refuses in the host of a special URL, as WHATWG's forbidden domain code points. */
private const val FORBIDDEN_HOST_CHARS = " #%/:<>?@[\\]^|"

private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")
private val SPECIAL_SCHEMES = setOf("http", "https", "ws", "wss", "ftp", "file")
private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

/** Why pubky.app would refuse a profile link, checked on the label and URL a profile save sends. */
enum class PubkyAppLinkError {
    LABEL_EMPTY,
    LABEL_TOO_LONG,
    URL_TOO_LONG,
    URL_INVALID;

    val isLabelError: Boolean get() = this == LABEL_EMPTY || this == LABEL_TOO_LONG
}

/**
 * The first rule pubky.app's publisher would refuse [label] and [url] for, after the normalization a profile save
 * applies, or null when it accepts them. A blank URL is left to the caller; a label that is empty once on one line is
 * reported last, so a URL error shows while the label is still blank. [webOnly] also requires an http(s) link, as a
 * social v1 profile does.
 */
fun pubkyAppLinkError(label: String, url: String, webOnly: Boolean): PubkyAppLinkError? {
    val title = label.singleLine()
    if (title.codePointCount(0, title.length) > PUBKY_APP_LINK_TITLE_MAX_LENGTH) return PubkyAppLinkError.LABEL_TOO_LONG
    if (url.isNotBlank()) {
        val normalized = url.normalizedLinkUrl()?.takeIf { it.linkUrlParts() != null }
            ?: return PubkyAppLinkError.URL_INVALID
        if (webOnly && !url.isWebLink()) return PubkyAppLinkError.URL_INVALID
        if (normalized.codePointCount(0, normalized.length) > PUBKY_APP_LINK_URL_MAX_LENGTH) {
            return PubkyAppLinkError.URL_TOO_LONG
        }
    }
    if (title.isEmpty()) return PubkyAppLinkError.LABEL_EMPTY
    return null
}

/** One line of text as pubky.app keeps it: control characters become spaces and the ends are trimmed. */
fun String.singleLine() = map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()

/**
 * This link as the full URL pubky.app needs, or null when pubky.app cannot hold it. Bitkit accepted links without a
 * scheme (a site, an email address, a phone number), which gain one here.
 */
fun String.toPubkyAppLinkUrl(): String? = normalizedLinkUrl()?.takeIf {
    it.codePointCount(0, it.length) <= PUBKY_APP_LINK_URL_MAX_LENGTH && it.linkUrlParts() != null
}

/**
 * Paykit's social v1 link rule (`is_social_v1_web_uri`): a lowercase `http://` or `https://` prefix, no control or
 * whitespace character, and a non-empty host, read up to the first `/`, `?` or `#`, after the last `@`, and up to `:`
 * unless it starts with `[`. Social v1 drops other links on migration too.
 */
fun String.isSocialV1WebUri(): Boolean {
    val rest = removePrefix("http://").takeIf { it != this } ?: removePrefix("https://").takeIf { it != this }
        ?: return false
    if (any { it.code < ASCII_SPACE || it.code == ASCII_DELETE || it.isWhitespace() }) return false
    val hostAndPort = rest.takeWhile { it !in "/?#" }.substringAfterLast('@')
    val host = if (hostAndPort.startsWith('[')) hostAndPort else hostAndPort.substringBefore(':')
    return host.isNotEmpty()
}

/** The lowercase scheme and the host of a URL; the host is empty for a scheme without one, such as `mailto`. */
class LinkUrlParts(val scheme: String, val host: String)

/**
 * The scheme and host `url::Url::parse`, which Paykit validates links with, reads from this URL, or null when it
 * would refuse it. A WHATWG-style check of what that parser rejects: a control character, a special URL (http, https,
 * ws, wss, ftp) with an empty host, a forbidden host character or a port over 65535, and a host ending in an
 * all-digit label that is not an IPv4 address (one to four numbers, as in `127.1`). Paths, queries and other
 * schemes are accepted, as the parser accepts them.
 */
fun String.linkUrlParts(): LinkUrlParts? {
    if (any { it.isISOControl() }) return null
    val scheme = URI_SCHEME.find(this)?.value?.dropLast(1)?.lowercase() ?: return null
    if (scheme !in SPECIAL_SCHEMES) return LinkUrlParts(scheme, host = "")
    val authority = substring(scheme.length + 1).trimStart('/', '\\').takeWhile { it !in "/?#\\" }
    val hostAndPort = authority.substringAfterLast('@')
    val host = hostAndPort.hostPart() ?: return null
    if (!hostAndPort.substring(host.length).isValidPort()) return null
    if (scheme == "file") return LinkUrlParts(scheme, host)
    if (host.isEmpty() || (!host.startsWith('[') && host.any { it in FORBIDDEN_HOST_CHARS })) return null
    if (!host.isValidIpv4Ending()) return null
    return LinkUrlParts(scheme, host.lowercase())
}

private fun String.hostPart(): String? {
    if (!startsWith('[')) return substringBefore(':')
    val end = indexOf(']').takeIf { it > 1 } ?: return null
    val address = substring(1, end)
    if (!address.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }) return null
    return substring(0, end + 1)
}

private fun String.isValidPort(): Boolean {
    if (isEmpty()) return true
    val port = removePrefix(":").takeIf { startsWith(':') } ?: return false
    if (!port.all { it in '0'..'9' }) return false
    val digits = port.trimStart('0')
    return digits.length <= MAX_PORT.toString().length && (digits.toIntOrNull() ?: 0) <= MAX_PORT
}

private fun String.isValidIpv4Ending(): Boolean {
    val name = removeSuffix(".")
    if (startsWith('[') || !name.substringAfterLast('.').all { it.isDigit() }) return true
    val numbers = name.split('.').map { it.toLongOrNull() ?: return false }
    if (numbers.size !in 1..4) return false
    val lastLimit = (1L shl (8 * (5 - numbers.size))) - 1
    return numbers.dropLast(1).all { it <= 255 } && numbers.last() <= lastLimit
}

private fun String.normalizedLinkUrl(): String? {
    val value = trim().takeIf { it.isNotEmpty() } ?: return null
    val isPhone = value.isPhoneNumber()
    if (!isPhone && value.any { it.isWhitespace() }) return null
    val scheme = if (isPhone) null else URI_SCHEME.find(value)?.value?.dropLast(1)
    return when {
        scheme != null -> scheme.lowercase() + value.substring(scheme.length)
        value.isEmailAddress() -> "mailto:$value"
        isPhone -> "tel:" + value.filterNot { it in " -()." }
        '.' in value && !value.startsWith('.') -> "https://$value"
        else -> null
    }
}

private fun String.isEmailAddress(): Boolean {
    val parts = split('@')
    return parts.size == 2 && parts[0].isNotEmpty() && '.' in parts[1] && '/' !in this
}

private fun String.isPhoneNumber(): Boolean {
    val digits = count { it.isDigit() }
    return (startsWith('+') || digits >= 7) && digits >= 5 && all { it in "+0123456789 -()." } && '+' !in drop(1) &&
        !ISO_DATE.matches(this) && !IPV4.matches(this)
}

package to.bitkit.models

import to.bitkit.ext.isWebLink
import java.net.URI

/** pubky.app link limits from pubky-app-specs, as Paykit checks them when it publishes a profile. */
const val PUBKY_APP_LINK_TITLE_MAX_LENGTH = 100
const val PUBKY_APP_LINK_URL_MAX_LENGTH = 300

private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")
private val WEB_SCHEMES = setOf("http", "https", "ws", "wss", "ftp")
private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

/** Why pubky.app would refuse a profile link, checked on the label and URL a profile save sends. */
enum class PubkyAppLinkError { LABEL_TOO_LONG, URL_TOO_LONG, URL_INVALID }

/**
 * The first rule pubky.app's publisher would refuse [label] and [url] for, after the normalization a profile save
 * applies, or null when it accepts them. A blank label or URL is left to the caller. [webOnly] also requires an
 * http(s) link, as a social v1 profile does.
 */
fun pubkyAppLinkError(label: String, url: String, webOnly: Boolean): PubkyAppLinkError? {
    val title = label.singleLine()
    if (title.codePointCount(0, title.length) > PUBKY_APP_LINK_TITLE_MAX_LENGTH) return PubkyAppLinkError.LABEL_TOO_LONG
    if (url.isBlank()) return null
    val normalized = url.normalizedLinkUrl()?.takeIf { it.isParsableLinkUrl() } ?: return PubkyAppLinkError.URL_INVALID
    if (webOnly && !url.isWebLink()) return PubkyAppLinkError.URL_INVALID
    if (normalized.codePointCount(0, normalized.length) > PUBKY_APP_LINK_URL_MAX_LENGTH) {
        return PubkyAppLinkError.URL_TOO_LONG
    }
    return null
}

/** One line of text as pubky.app keeps it: control characters become spaces and the ends are trimmed. */
fun String.singleLine() = map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()

/**
 * This link as the full URL pubky.app needs, or null when pubky.app cannot hold it. Bitkit accepted links without a
 * scheme (a site, an email address, a phone number), which gain one here.
 */
fun String.toPubkyAppLinkUrl(): String? = normalizedLinkUrl()?.takeIf {
    it.codePointCount(0, it.length) <= PUBKY_APP_LINK_URL_MAX_LENGTH && it.isParsableLinkUrl()
}

private fun String.normalizedLinkUrl(): String? {
    val value = trim().takeIf { it.isNotEmpty() } ?: return null
    val isPhone = value.isPhoneNumber()
    if (!isPhone && value.any { it.isWhitespace() }) return null
    val scheme = if (isPhone) null else runCatching { URI(value).scheme }.getOrNull()
    return when {
        scheme != null -> scheme.lowercase() + value.substring(scheme.length)
        value.isEmailAddress() -> "mailto:$value"
        isPhone -> "tel:" + value.filterNot { it in " -()." }
        '.' in value && !value.startsWith('.') -> "https://$value"
        else -> null
    }
}

private fun String.isParsableLinkUrl(): Boolean {
    val parsed = runCatching { URI(this) }.getOrNull() ?: return false
    return parsed.scheme != null && parsed.hasValidHost()
}

/**
 * Paykit's URL parser reads a web URL's host that ends in an all-digit label as an IPv4 address (one to four
 * numbers, as in `127.1`) and rejects it when it is not one; other schemes keep such hosts as they are.
 */
private fun URI.hasValidHost(): Boolean {
    if (scheme?.lowercase() !in WEB_SCHEMES) return true
    // java.net.URI leaves the host null when the name is not a valid hostname, so fall back to the authority.
    val host = host ?: rawAuthority?.substringAfterLast('@')?.substringBefore(':') ?: return true
    if (!host.substringAfterLast('.').all { it.isDigit() }) return true
    val numbers = host.split('.').map { it.toLongOrNull() ?: return false }
    if (numbers.size !in 1..4) return false
    val lastLimit = (1L shl (8 * (5 - numbers.size))) - 1
    return numbers.dropLast(1).all { it <= 255 } && numbers.last() <= lastLimit
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

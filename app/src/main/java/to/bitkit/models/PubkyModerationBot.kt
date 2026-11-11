package to.bitkit.models

/**
 * The pubky.app moderation accounts. pubky.app makes every new profile follow one of them, so it shows up among the
 * follows of a fresh profile without being anyone the user knows.
 */
object PubkyModerationBot {
    private val publicKeys = listOf(
        // pubky.app production
        "euarj183st16psbzusb44usk8i1k5foubnw7zt3n3j8h9zxg6hro",
        // pubky.app staging
        "nto4u7kkagk5hfjk4wgueemzy61nssic811hid1ty9u81uatmqzy",
    )

    fun matches(publicKey: String): Boolean {
        val canonicalKey = PubkyPublicKeyFormat.canonicalized(publicKey) ?: return false
        return publicKeys.any { PubkyPublicKeyFormat.canonicalized(it) == canonicalKey }
    }
}

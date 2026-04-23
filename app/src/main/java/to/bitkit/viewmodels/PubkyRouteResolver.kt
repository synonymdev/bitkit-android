package to.bitkit.viewmodels

import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.ui.Routes

internal fun resolvePastedPubkyRoute(
    input: String,
    ownPublicKey: String?,
    contacts: List<PubkyProfile>,
): Routes? {
    val normalizedKey = PubkyPublicKeyFormat.normalized(input) ?: return null

    if (PubkyPublicKeyFormat.matches(normalizedKey, ownPublicKey)) {
        return Routes.Profile
    }

    if (contacts.any { PubkyPublicKeyFormat.matches(it.publicKey, normalizedKey) }) {
        return Routes.ContactDetail(normalizedKey)
    }

    return Routes.AddContact(normalizedKey)
}

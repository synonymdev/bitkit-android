package to.bitkit.ui.screens.contacts

import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.ui.Routes

internal fun hasPendingImport(profile: PubkyProfile?, contacts: List<PubkyProfile>): Boolean =
    profile != null && contacts.isNotEmpty()

internal sealed interface AddContactValidationResult {
    data object Empty : AddContactValidationResult
    data class ExistingContact(val normalizedKey: String) : AddContactValidationResult
    data object InvalidKey : AddContactValidationResult
    data object OwnKey : AddContactValidationResult
    data class Valid(val normalizedKey: String) : AddContactValidationResult
}

internal fun resolveAddContactValidation(
    input: String,
    ownPublicKey: String?,
    contacts: List<PubkyProfile> = emptyList(),
): AddContactValidationResult {
    val trimmedInput = input.trim()

    if (trimmedInput.isEmpty()) {
        return AddContactValidationResult.Empty
    }

    if (PubkyPublicKeyFormat.matches(trimmedInput, ownPublicKey)) {
        return AddContactValidationResult.OwnKey
    }

    val normalizedKey = PubkyPublicKeyFormat.normalized(trimmedInput)
        ?: return AddContactValidationResult.InvalidKey

    if (contacts.any { PubkyPublicKeyFormat.matches(it.publicKey, normalizedKey) }) {
        return AddContactValidationResult.ExistingContact(normalizedKey)
    }

    return AddContactValidationResult.Valid(normalizedKey = normalizedKey)
}

internal fun shouldDiscardPendingImport(currentDestination: NavDestination?, destination: Routes?): Boolean {
    if (!currentDestination.isContactImportRoute()) {
        return false
    }

    return !destination.isContactImportRoute()
}

private fun NavDestination?.isContactImportRoute(): Boolean =
    this?.hasRoute<Routes.ContactImportOverview>() == true ||
        this?.hasRoute<Routes.ContactImportSelect>() == true

private fun Routes?.isContactImportRoute(): Boolean =
    this == Routes.ContactImportOverview || this == Routes.ContactImportSelect

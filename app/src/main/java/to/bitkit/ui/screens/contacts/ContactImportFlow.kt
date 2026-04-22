package to.bitkit.ui.screens.contacts

import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import to.bitkit.models.PubkyProfile
import to.bitkit.ui.Routes

internal fun hasPendingImport(profile: PubkyProfile?, contacts: List<PubkyProfile>): Boolean =
    profile != null && contacts.isNotEmpty()

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

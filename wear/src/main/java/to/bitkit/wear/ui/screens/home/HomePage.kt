package to.bitkit.wear.ui.screens.home

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** The pages of the home pager. */
enum class HomePage { Balance, Receive, Activity, Settings }

/** The home pages in swipe order; the pager opens on the first. Add a page by listing it here. */
val HOME_PAGES: ImmutableList<HomePage> = persistentListOf(
    HomePage.Balance,
    HomePage.Receive,
    HomePage.Activity,
    HomePage.Settings,
)

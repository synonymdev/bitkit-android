package to.bitkit.ui.utils

import androidx.navigation.NavOptionsBuilder

fun NavOptionsBuilder.clearBackStack() = popUpTo(id = 0)

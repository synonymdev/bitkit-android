package to.bitkit.ui

import androidx.compose.runtime.compositionLocalOf
import to.bitkit.models.BalanceState

val LocalBalances = compositionLocalOf { BalanceState() }

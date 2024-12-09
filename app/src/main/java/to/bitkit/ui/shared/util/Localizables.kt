package to.bitkit.ui.shared.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import to.bitkit.R
import to.bitkit.models.BitcoinDisplayUnit

val BitcoinDisplayUnit.display: String
    @Composable
    get() {
        return when (this) {
            BitcoinDisplayUnit.MODERN -> stringResource(R.string.bitcoin_display_modern)
            BitcoinDisplayUnit.CLASSIC -> stringResource(R.string.bitcoin_display_classic)
        }
    }

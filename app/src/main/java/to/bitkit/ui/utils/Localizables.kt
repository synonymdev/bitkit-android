package to.bitkit.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import to.bitkit.R
import to.bitkit.models.TransactionSpeed

val TransactionSpeed.displayText: String
    @Composable
    get() {
        return when (this) {
            is TransactionSpeed.Fast -> stringResource(R.string.settings__fee__fast__value)
            is TransactionSpeed.Medium -> stringResource(R.string.settings__fee__normal__value)
            is TransactionSpeed.Slow -> stringResource(R.string.settings__fee__slow__value)
            is TransactionSpeed.Custom -> stringResource(R.string.settings__fee__custom__value)
        }
    }

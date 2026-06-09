package to.bitkit.ui.screens.trezor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.synonym.bitkitcore.AccountType
import to.bitkit.ui.components.Caption
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.theme.Colors

internal fun AccountType?.accountTypeLabel(): String = when (this) {
    null -> "Auto"
    AccountType.LEGACY -> "Legacy"
    AccountType.WRAPPED_SEGWIT -> "Wrapped"
    AccountType.NATIVE_SEGWIT -> "Native"
    AccountType.TAPROOT -> "Taproot"
}

@Composable
internal fun AccountTypeSelectorRow(
    selectedAccountType: AccountType?,
    onAccountTypeChange: (AccountType?) -> Unit,
) {
    Column {
        Caption("Account type (Auto = detect from key prefix)", color = Colors.White50)
        VerticalSpacer(8.dp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val options = listOf(null) + AccountType.entries
            options.forEach { type ->
                TagButton(
                    text = type.accountTypeLabel(),
                    onClick = { onAccountTypeChange(type) },
                    isSelected = type == selectedAccountType,
                )
            }
        }
    }
}

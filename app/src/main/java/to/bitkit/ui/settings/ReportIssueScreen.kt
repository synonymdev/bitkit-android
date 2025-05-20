package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ext.removeSpaces
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.AppTextFieldDefaults
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors


@Composable
fun ReportIssueScreen(
    onBack: () -> Unit,
    onClose: () -> Unit,
) {

    var emailInput: String by rememberSaveable { mutableStateOf("") }

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__support__report),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClick = onClose) },
        )

        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            BodyM(text = stringResource(R.string.settings__support__report_text), color = Colors.White64)

            Spacer(modifier = Modifier.height(32.dp))

            BodyM(text = stringResource(R.string.settings__support__label_address), color = Colors.White64)

            Spacer(modifier = Modifier.height(8.dp))

            TextField(
                placeholder = { Text(stringResource(R.string.settings__support__placeholder_address)) },
                value = emailInput,
                onValueChange = { emailInput = emailInput.removeSpaces() },
                maxLines = 1,
                singleLine = true,
                colors = AppTextFieldDefaults.semiTransparent,
                shape = AppShapes.small,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth()
            )


            Spacer(modifier = Modifier.height(32.dp))

            BodyM(text = stringResource(R.string.settings__support__label_message), color = Colors.White64)

            Spacer(modifier = Modifier.height(8.dp))

        }

    }
}


@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        ReportIssueScreen(
            onBack = {},
            onClose = {}
        )
    }
}

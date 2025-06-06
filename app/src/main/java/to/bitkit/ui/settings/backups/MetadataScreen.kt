package to.bitkit.ui.settings.backups

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withBold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MetadataScreen(
    uiState: BackupContract.UiState,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
) {
    MetadataContent(
        lastBackupTimeMs = uiState.lastBackupTimeMs,
        onDismiss = onDismiss,
        onBack = onBack,
    )
}

@Composable
private fun MetadataContent(
    lastBackupTimeMs: Long,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
) {
    val latestBackupTime = remember(lastBackupTimeMs) {
        val formatter = SimpleDateFormat("MMMM d, yyyy 'at' h:mm a", Locale.getDefault())
        formatter.format(Date(lastBackupTimeMs))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("backup_metadata_screen")
    ) {
        SheetTopBar(stringResource(R.string.security__mnemonic_data_header), onBack = onBack)
        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            BodyM(
                text = stringResource(R.string.security__mnemonic_data_text),
                color = Colors.White64,
            )

            Image(
                painter = painterResource(R.drawable.card),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            BodyS(
                text = stringResource(R.string.security__mnemonic_latest_backup)
                    .replace("{time}", latestBackupTime)
                    .withBold(),
                modifier = Modifier.testTag("backup_time_text")
            )

            Spacer(modifier = Modifier.height(16.dp))

            PrimaryButton(
                text = stringResource(R.string.common__ok),
                onClick = onDismiss,
                modifier = Modifier.testTag("backup_metadata_ok_button")
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        MetadataContent(
            lastBackupTimeMs = System.currentTimeMillis(),
            onDismiss = {},
            onBack = {},
        )
    }
}

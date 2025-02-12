package to.bitkit.ui.screens.transfer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun TransferIntroScreen() {
    Box(modifier = Modifier.fillMaxSize()) {
        Text("TODO: Transfer Intro", modifier = Modifier.align(Alignment.Center))
    }
}

@Preview(showSystemUi = true, showBackground = true)
@Composable
private fun TransferIntroScreenPreview() {
    AppThemeSurface {
        TransferIntroScreen()
    }
}

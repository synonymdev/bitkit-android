package to.bitkit.wear.ui.components

import androidx.compose.runtime.Composable
import androidx.wear.compose.foundation.AmbientMode
import androidx.wear.compose.foundation.LocalAmbientModeManager

/** Whether the watch shows this screen dimmed in ambient (always-on) mode, where balances stay hidden. */
@Composable
fun isAmbient(): Boolean = LocalAmbientModeManager.current?.currentAmbientMode is AmbientMode.Ambient

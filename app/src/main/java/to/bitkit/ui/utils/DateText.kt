package to.bitkit.ui.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import to.bitkit.ext.UiDateStyle
import to.bitkit.ext.formatted
import to.bitkit.ext.is24HourTimeFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

@Composable
fun uiDateText(
    timestamp: ULong,
    style: UiDateStyle,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
): String = Instant.ofEpochSecond(timestamp.toLong())
    .formatted(style.pattern(rememberIs24HourFormat()), locale, zone)

@Composable
fun rememberIs24HourFormat(context: Context = LocalContext.current): Boolean {
    var is24Hour by remember(context) { mutableStateOf(context.is24HourTimeFormat) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) = run {
                is24Hour = context.is24HourTimeFormat
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_TIME_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        onDispose { context.unregisterReceiver(receiver) }
    }

    return is24Hour
}

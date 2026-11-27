package to.bitkit.wear.ext

import android.app.KeyguardManager
import android.content.Context
import android.provider.Settings

val Context.keyguardManager: KeyguardManager
    get() = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

/** Whether the wearer turned animations off, so motion that only decorates should stay still. */
val Context.areAnimationsDisabled: Boolean
    get() = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

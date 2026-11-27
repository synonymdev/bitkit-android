package to.bitkit.wear.ext

import android.app.KeyguardManager
import android.content.Context

val Context.keyguardManager: KeyguardManager
    get() = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

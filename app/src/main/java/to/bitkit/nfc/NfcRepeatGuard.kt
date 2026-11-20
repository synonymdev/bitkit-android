package to.bitkit.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Handler
import android.os.Looper
import to.bitkit.utils.Logger
import java.lang.ref.WeakReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
    NfcAdapter.FLAG_READER_NFC_B or
    NfcAdapter.FLAG_READER_NFC_F or
    NfcAdapter.FLAG_READER_NFC_V

/**
 * Reader mode is activity-wide, so only its latest owner may switch it off. A scanning screen registers how to
 * take it back in [resumeScanner], so the repeat guard can hand reader mode back instead of switching it off.
 */
internal object ReaderModeOwner {
    var current: Any? = null
    var resumeScanner: (() -> Unit)? = null
}

/**
 * Stops Android from delivering a payment tag again while the phone stays on it after a read.
 *
 * Outside reader mode, Android dispatches every activation of a tag as a new NDEF_DISCOVERED intent, and a phone
 * emulating a tag activates again whenever the field drops. A read followed by the scanner closing, or a tap that
 * launched the app, then opens the app chooser or the app again. The guard holds reader mode with a callback that
 * reads nothing until the tag has left the field, then hands reader mode back to a scanning screen or switches it
 * off.
 */
internal object NfcRepeatGuard {
    private const val TAG = "NfcRepeatGuard"

    /** How long the guard holds when no tag answers, e.g. after a launch from a tag already removed. */
    private val HOLD_WITHOUT_TAG: Duration = 3.seconds

    /** How long a tag must stay out of range before Android may deliver it again. */
    private val REMOVAL_DEBOUNCE: Duration = 1.seconds

    /** Covers the gap while an emulating phone re-activates its tag after a removal is reported. */
    private val RELEASE_AFTER_REMOVAL: Duration = 500.milliseconds

    private val handler = Handler(Looper.getMainLooper())
    private val release = Runnable { release() }
    private var activity = WeakReference<Activity>(null)

    /** Holds reader mode on [activity], which must be resumed. Pass the [tag] just read so its removal ends it. */
    fun engage(activity: Activity, tag: Tag? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { engage(activity, tag) }
            return
        }
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
        val enabled = runCatching {
            adapter.enableReaderMode(activity, { onTag(adapter, it) }, READER_FLAGS, null)
        }.onFailure { Logger.warn("Failed to hold NFC reader mode", it, context = TAG) }.isSuccess
        if (!enabled) return
        ReaderModeOwner.current = this
        this.activity = WeakReference(activity)
        schedule(HOLD_WITHOUT_TAG)
        tag?.let { onTag(adapter, it) }
    }

    private fun onTag(adapter: NfcAdapter, tag: Tag) {
        handler.post { handler.removeCallbacks(release) }
        val ignored = runCatching {
            adapter.ignore(
                tag,
                REMOVAL_DEBOUNCE.inWholeMilliseconds.toInt(),
                { schedule(RELEASE_AFTER_REMOVAL) },
                handler,
            )
        }.getOrDefault(false)
        if (!ignored) schedule(HOLD_WITHOUT_TAG)
    }

    private fun schedule(delay: Duration) {
        handler.post {
            handler.removeCallbacks(release)
            handler.postDelayed(release, delay.inWholeMilliseconds)
        }
    }

    private fun release() {
        if (ReaderModeOwner.current !== this) return
        ReaderModeOwner.current = null
        ReaderModeOwner.resumeScanner?.let { return it() }
        val activity = activity.get() ?: return
        runCatching { NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity) }
            .onFailure { Logger.warn("Failed to release NFC reader mode", it, context = TAG) }
    }
}

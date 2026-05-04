package to.bitkit.appwidget.ui.headlines

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import to.bitkit.models.widget.safeBrowserUri
import to.bitkit.utils.Logger

private const val TAG = "OpenUrlAction"

class OpenUrlAction : ActionCallback {
    companion object {
        val linkKey = ActionParameters.Key<String>("article_link")
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val link = parameters[linkKey] ?: return
        val uri = safeBrowserUri(link) ?: return
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Logger.error("Failed to open url '$link'", it, context = TAG) }
    }
}

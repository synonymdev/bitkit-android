package to.bitkit.models.widget

import kotlinx.serialization.Serializable

@Serializable
data class FactsPreferences(
    val showSource: Boolean = false
)

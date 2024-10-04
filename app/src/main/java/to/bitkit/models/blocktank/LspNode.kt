package to.bitkit.models.blocktank

import kotlinx.serialization.Serializable

@Serializable
data class LspNode(
    val alias: String,
    val pubkey: String,
    val connectionStrings: List<String>,
    val readonly: Boolean? = null,
)

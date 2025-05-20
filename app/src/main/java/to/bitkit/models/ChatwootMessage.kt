package to.bitkit.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatwootMessage(
    val email: String,
    val message: String,
    val platform: String,
    val version: String,
    @SerialName("ldkVersion")
    val ldkVersion: String,
    @SerialName("ldkNodeId")
    val ldkNodeId: String,
    val logs: String,
    val logsFileName: String
)

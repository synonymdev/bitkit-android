package to.bitkit.data.dto

data class VssObjectDto(
    val key: String,
    val version: Long,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VssObjectDto

        if (key != other.key) return false
        if (version != other.version) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

data class VssListDto(
    val objects: List<VssObjectDto>,
    val nextPageToken: String?,
    val globalVersion: Long?,
)

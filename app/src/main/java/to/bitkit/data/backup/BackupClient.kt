package to.bitkit.data.backup

import to.bitkit.data.dto.VssListDto
import to.bitkit.data.dto.VssObjectDto
import to.bitkit.models.BackupCategory

interface BackupClient {
    fun setup() = Unit
    suspend fun putObject(category: BackupCategory, data: ByteArray): Result<VssObjectDto>
    suspend fun getObject(category: BackupCategory): Result<VssObjectDto>
    suspend fun deleteObject(category: BackupCategory, version: Long = -1): Result<Unit>
    suspend fun listObjects(
        keyPrefix: String? = null,
        pageSize: Int? = null,
        pageToken: String? = null,
    ): Result<VssListDto>
}

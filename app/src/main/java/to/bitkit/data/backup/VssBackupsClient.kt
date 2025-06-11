package to.bitkit.data.backup

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.vss.DeleteObjectRequest
import org.vss.ErrorResponse
import org.vss.GetObjectRequest
import org.vss.GetObjectResponse
import org.vss.KeyValue
import org.vss.ListKeyVersionsRequest
import org.vss.ListKeyVersionsResponse
import org.vss.PutObjectRequest
import to.bitkit.di.ProtoClient
import to.bitkit.env.Env
import to.bitkit.models.BackupCategory
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VssBackupsClient @Inject constructor(
    @ProtoClient private val httpClient: HttpClient,
    private val vssStoreIdProvider: VssStoreIdProvider,
) {
    private val vssStoreId: String get() = vssStoreIdProvider.getVssStoreId()

    suspend fun putObject(
        category: BackupCategory,
        data: ByteArray,
        version: Long? = null,
    ): Result<VssObjectInfo> =
        runCatching {
            Logger.debug("Storing object for category: $category", context = TAG)

            val key = category.name.lowercase()

            // If no version is specified, get the current version or use 0 for new objects
            val useVersion = version ?: run {
                val existingObject = getObject(category)
                if (existingObject.isSuccess) {
                    existingObject.getOrThrow().version
                } else {
                    when (val error = existingObject.exceptionOrNull()) {
                        is VssError.NotFoundError -> 0L // New object starts at version 0
                        else -> throw error ?: Exception("Failed to get current version")
                    }
                }
            }

            val dataToBackup = ByteString.copyFrom(data)

            val keyValue = KeyValue.newBuilder()
                .setKey(key)
                .setValue(dataToBackup)
                .setVersion(useVersion)
                .build()

            val request = PutObjectRequest.newBuilder()
                .setStoreId(vssStoreId)
                .addTransactionItems(keyValue)
                .build()

            post("/putObjects", request)

            VssObjectInfo(
                key = key,
                version = useVersion + 1,
                data = data,
            )
        }

    suspend fun getObject(category: BackupCategory): Result<VssObjectInfo> = runCatching {
        Logger.debug("Retrieving object for category: $category", context = TAG)

        val key = category.name.lowercase()
        val request = GetObjectRequest.newBuilder()
            .setStoreId(vssStoreId)
            .setKey(key)
            .build()

        val response = post("/getObject", request)
        val responseBytes = response.readRawBytes()
        val getResponse = GetObjectResponse.parseFrom(responseBytes)

        VssObjectInfo(
            key = getResponse.value.key,
            version = getResponse.value.version,
            data = getResponse.value.value.toByteArray()
        )
    }

    suspend fun deleteObject(category: BackupCategory, version: Long = -1): Result<Unit> = runCatching {
        Logger.debug("Deleting object for category: $category", context = TAG)

        val key = category.name.lowercase()

        val keyValue = KeyValue.newBuilder()
            .setKey(key)
            .setVersion(version) // Use -1 for non-conditional delete
            .build()

        val request = DeleteObjectRequest.newBuilder()
            .setStoreId(vssStoreId)
            .setKeyValue(keyValue)
            .build()

        try {
            post("/deleteObject", request)
        } catch (_: VssError.NotFoundError) {
            // Object doesn't exist - that's fine for delete (idempotent)
        }
    }

    suspend fun listObjects(
        keyPrefix: String? = null,
        pageSize: Int? = null,
        pageToken: String? = null,
    ): Result<VssListResult> = runCatching {
        Logger.debug("Listing objects with prefix: $keyPrefix", context = TAG)

        val requestBuilder = ListKeyVersionsRequest.newBuilder()
            .setStoreId(vssStoreId)

        keyPrefix?.let { requestBuilder.setKeyPrefix(it) }
        pageSize?.let { requestBuilder.setPageSize(it) }
        pageToken?.let { requestBuilder.setPageToken(it) }

        val request = requestBuilder.build()
        val response = post("/listKeyVersions", request)
        val responseBytes = response.readRawBytes()
        val listResponse = ListKeyVersionsResponse.parseFrom(responseBytes)

        val objects = listResponse.keyVersionsList.map { keyValue ->
            VssObjectInfo(
                key = keyValue.key,
                version = keyValue.version,
                data = ByteArray(0) // List doesn't include data
            )
        }

        VssListResult(
            objects = objects,
            nextPageToken = if (listResponse.hasNextPageToken()) listResponse.nextPageToken else null,
            globalVersion = if (listResponse.hasGlobalVersion()) listResponse.globalVersion else null
        )
    }

    private suspend fun post(endpoint: String, request: MessageLite): HttpResponse {
        val response = httpClient.post("${Env.vssServerUrl}$endpoint") {
            contentType(ContentType.Application.OctetStream)
            setBody(request.toByteArray())
        }

        // Handle common error responses
        when (response.status.value) {
            in 200..299 -> return response
            400 -> {
                val errorResponse = parseErrorResponse(response)
                throw VssError.InvalidRequestError(errorResponse?.message ?: "Invalid request")
            }

            401 -> {
                throw VssError.AuthError("Authentication failed")
            }

            404 -> {
                throw VssError.NotFoundError("Resource not found")
            }

            409 -> {
                val errorResponse = parseErrorResponse(response)
                throw VssError.ConflictError("Version conflict: ${errorResponse?.message ?: "Unknown conflict"}")
            }

            else -> {
                val errorResponse = parseErrorResponse(response)
                throw VssError.ServerError("Request failed with status: ${response.status}, message: ${errorResponse?.message}")
            }
        }
    }

    private suspend fun parseErrorResponse(response: HttpResponse): ErrorResponse? {
        return try {
            val contentType = response.contentType()

            if (contentType?.contentType == "application" &&
                (contentType.contentSubtype == "x-protobuf" || contentType.contentSubtype == "octet-stream")
            ) {
                val responseBytes = response.readRawBytes()
                if (responseBytes.isNotEmpty()) {
                    try {
                        ErrorResponse.parseFrom(responseBytes)
                    } catch (_: Throwable) {
                        null
                    }
                } else null
            } else {
                // Handle plain text or other error response formats
                val responseBytes = response.readRawBytes()
                val responseText = String(responseBytes)

                if (responseText.isNotBlank()) {
                    ErrorResponse.newBuilder()
                        .setMessage(responseText.trim())
                        .build()
                } else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    companion object Companion {
        private const val TAG = "VssBackupClient"
    }
}

data class VssObjectInfo(
    val key: String,
    val version: Long,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VssObjectInfo

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

data class VssListResult(
    val objects: List<VssObjectInfo>,
    val nextPageToken: String?,
    val globalVersion: Long?,
)

sealed class VssError(message: String) : AppError(message) {
    class ServerError(message: String) : VssError(message)
    class AuthError(message: String) : VssError(message)
    class ConflictError(message: String) : VssError(message)
    class InvalidRequestError(message: String) : VssError(message)
    class NotFoundError(message: String) : VssError(message)

}

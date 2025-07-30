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
import to.bitkit.data.dto.VssListDto
import to.bitkit.data.dto.VssObjectDto
import to.bitkit.di.ProtoClient
import to.bitkit.env.Env
import to.bitkit.models.BackupCategory
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VssBackupClientHttp @Inject constructor(
    @ProtoClient private val httpClient: HttpClient,
    private val vssStoreIdProvider: VssStoreIdProvider,
) : VssBackupClient {
    private fun getVssStoreId(): String = vssStoreIdProvider.getVssStoreId()

    override suspend fun putObject(
        category: BackupCategory,
        data: ByteArray,
    ): Result<VssObjectDto> =
        runCatching {
            Logger.debug("Storing object for category: $category", context = TAG)

            val key = category.name.lowercase()
            val dataToBackup = ByteString.copyFrom(data)
            val useVersion = getCurrentVersionForKey(key)

            val keyValue = KeyValue.newBuilder()
                .setKey(key)
                .setValue(dataToBackup)
                .setVersion(useVersion)
                .build()

            val request = PutObjectRequest.newBuilder()
                .setStoreId(getVssStoreId())
                .addTransactionItems(keyValue)
                .build()

            post("/putObjects", request)

            // VSS uses optimistic concurrency control: when you specify a version, you're saying
            // "update this object only if the current version matches". If successful, VSS
            // increments the version and returns the new version (useVersion + 1)
            VssObjectDto(
                key = key,
                version = useVersion + 1,
                data = data,
            )
        }

    override suspend fun getObject(category: BackupCategory): Result<VssObjectDto> = runCatching {
        Logger.debug("Retrieving object for category: $category", context = TAG)

        val key = category.name.lowercase()
        val request = GetObjectRequest.newBuilder()
            .setStoreId(getVssStoreId())
            .setKey(key)
            .build()

        val response = post("/getObject", request)
        val responseBytes = response.readRawBytes()
        val getResponse = GetObjectResponse.parseFrom(responseBytes)

        VssObjectDto(
            key = getResponse.value.key,
            version = getResponse.value.version,
            data = getResponse.value.value.toByteArray()
        )
    }

    override suspend fun deleteObject(category: BackupCategory, version: Long): Result<Unit> = runCatching {
        Logger.debug("Deleting object for category: $category", context = TAG)

        val key = category.name.lowercase()

        val keyValue = KeyValue.newBuilder()
            .setKey(key)
            .setVersion(version) // Use -1 for non-conditional delete
            .build()

        val request = DeleteObjectRequest.newBuilder()
            .setStoreId(getVssStoreId())
            .setKeyValue(keyValue)
            .build()

        post("/deleteObject", request)
    }

    override suspend fun listObjects(
        keyPrefix: String?,
        pageSize: Int?,
        pageToken: String?,
    ): Result<VssListDto> = runCatching {
        Logger.debug("Listing objects with prefix: $keyPrefix", context = TAG)

        val requestBuilder = ListKeyVersionsRequest.newBuilder()
            .setStoreId(getVssStoreId())

        keyPrefix?.let { requestBuilder.setKeyPrefix(it) }
        pageSize?.let { requestBuilder.setPageSize(it) }
        pageToken?.let { requestBuilder.setPageToken(it) }

        val request = requestBuilder.build()
        val response = post("/listKeyVersions", request)
        val responseBytes = response.readRawBytes()
        val listResponse = ListKeyVersionsResponse.parseFrom(responseBytes)

        val objects = listResponse.keyVersionsList.map { keyValue ->
            VssObjectDto(
                key = keyValue.key,
                version = keyValue.version,
                data = ByteArray(0) // List doesn't include data
            )
        }

        VssListDto(
            objects = objects,
            nextPageToken = if (listResponse.hasNextPageToken()) listResponse.nextPageToken else null,
            globalVersion = if (listResponse.hasGlobalVersion()) listResponse.globalVersion else null
        )
    }

    private suspend fun post(endpoint: String, request: MessageLite): HttpResponse {
        val baseUrl = Env.vssServerUrl

        val response = httpClient.post("$baseUrl$endpoint") {
            contentType(ContentType.Application.OctetStream)
            setBody(request.toByteArray())
        }

        // Handle common error responses
        when (response.status.value) {
            in 200..299 -> return response
            else -> {
                val errorResponse = parseErrorResponse(response)
                throw Exception("Request failed with status: ${response.status}, message: ${errorResponse?.message}")
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
                    runCatching { ErrorResponse.parseFrom(responseBytes) }.getOrNull()
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

    private suspend fun getCurrentVersionForKey(key: String): Long {
        val currentVersionResult = listObjects(keyPrefix = key, pageSize = 1)

        return if (currentVersionResult.isSuccess) {
            currentVersionResult.getOrThrow().objects
                .firstOrNull { it.key == key }
                ?.version ?: 0L // New object starts at version 0
        } else {
            val error = currentVersionResult.exceptionOrNull()
            throw error ?: Exception("Failed to get current version")
        }
    }

    companion object {
        private const val TAG = "VssBackupClientHttp"
    }
}

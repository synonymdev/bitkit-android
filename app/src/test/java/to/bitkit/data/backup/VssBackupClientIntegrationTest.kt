package to.bitkit.data.backup

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.logging.EMPTY
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import to.bitkit.models.BackupCategory
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Ignore("Integration test - use during dev only")
class VssBackupClientIntegrationTest : BaseUnitTest() {

    private lateinit var httpClient: HttpClient
    private lateinit var vssClient: BackupClientHttp

    @Before
    fun setUp() {
        httpClient = HttpClient(OkHttp) {
            install(Logging) {
                logger = Logger.EMPTY
                level = LogLevel.BODY
            }
        }

        val vssStoreIdProvider = mock<VssStoreIdProvider> {
            on { getVssStoreId() } doReturn "test_vss_storeId"
        }

        vssClient = BackupClientHttp(
            httpClient = httpClient,
            vssStoreIdProvider = vssStoreIdProvider,
        )
    }

    @After
    fun tearDown() {
        httpClient.close()
    }

    @Test
    fun `putObject and getObject should store and retrieve data successfully`() = test {
        val testData = "test-wallet-data-${System.currentTimeMillis()}".toByteArray()
        val category = BackupCategory.WALLET
        println("Test data: ${String(testData)}")
        println("Category: $category")

        // Store object
        println("Storing object...")
        val putResult = vssClient.putObject(category, testData)
        assertTrue(putResult.isSuccess, "Put operation should succeed")

        val putInfo = putResult.getOrNull()
        assertNotNull(putInfo, "Put should return object info")
        assertEquals(category.name.lowercase(), putInfo.key)
        assertTrue(putInfo.version > 0, "Version should be positive")
        println("Object stored successfully. Key: ${putInfo.key}, Version: ${putInfo.version}")

        // Retrieve object
        println("Retrieving object...")
        val getResult = vssClient.getObject(category)
        assertTrue(getResult.isSuccess, "Get operation should succeed")

        val getInfo = getResult.getOrNull()
        assertNotNull(getInfo, "Get should return object info")
        assertEquals(category.name.lowercase(), getInfo.key)
        assertEquals(testData.contentToString(), getInfo.data.contentToString())
        println("Object retrieved successfully. Data matches: ${String(getInfo.data) == String(testData)}")
    }

    @Test
    fun `deleteObject should remove data successfully`() = test {
        val allCategories: List<BackupCategory> = BackupCategory.entries

        val timestamp = System.currentTimeMillis()
        println("Testing deletion across all ${allCategories.size} categories")

        // Store objects for all categories first
        println("Storing objects for all categories...")
        allCategories.forEach { category ->
            val testData = "test-delete-data-${category.name}-$timestamp".toByteArray()
            val putResult = vssClient.putObject(category, testData)
            assertTrue(putResult.isSuccess, "Put operation should succeed for $category")
            println("  ✓ Stored object for $category")
        }

        // Delete all objects
        println("Deleting all objects...")
        allCategories.forEach { category ->
            val deleteResult = vssClient.deleteObject(category)
            assertTrue(deleteResult.isSuccess, "Delete operation should succeed for $category")
            println("  ✓ Deleted object for $category")
        }

        // Verify all objects are deleted
        println("Verifying all objects were deleted...")
        allCategories.forEach { category ->
            val getResult = vssClient.getObject(category)
            assertTrue(getResult.isFailure, "Get after delete should fail for $category")
            println("  ✓ Confirmed $category no longer exists")
        }

        println("Successfully deleted all ${allCategories.size} category objects")
    }

    @Test
    fun `deleteObject on non-existent object should be idempotent`() = test {
        val category = BackupCategory.WIDGETS
        println("Category: $category")

        // Delete non-existent object - should not fail
        println("Attempting to delete non-existent object...")
        val deleteResult = vssClient.deleteObject(category)
        assertTrue(deleteResult.isSuccess, "Delete of non-existent object should be idempotent")
        println("Delete operation succeeded (idempotent behavior confirmed)")
    }

    @Test
    fun `listObjects should return stored objects`() = test {
        val testData1 = "test-list-data1-${System.currentTimeMillis()}".toByteArray()
        val testData2 = "test-list-data2-${System.currentTimeMillis()}".toByteArray()
        println("Test data 1: ${String(testData1)}")
        println("Test data 2: ${String(testData2)}")

        // Store multiple objects
        println("Storing multiple objects...")
        val putResult1 = vssClient.putObject(BackupCategory.METADATA, testData1)
        val putResult2 = vssClient.putObject(BackupCategory.BLOCKTANK, testData2)
        println("Object 1 stored: ${putResult1.isSuccess}")
        println("Object 2 stored: ${putResult2.isSuccess}")

        // List objects
        println("Listing all objects...")
        val listResult = vssClient.listObjects()
        println("List result: $listResult")
        assertTrue(listResult.isSuccess, "List operation should succeed")

        val listInfo = listResult.getOrNull()
        assertNotNull(listInfo, "List should return result")
        assertTrue(listInfo.objects.isNotEmpty(), "Should have at least some objects")
        println("Found ${listInfo.objects.size} objects in total")

        // Check if our objects are in the list
        val keys = listInfo.objects.map { it.key }
        println("Object keys: $keys")
        assertTrue(
            keys.any { it.contains("metadata") || it.contains("blocktank") },
            "List should contain our test objects"
        )
        println("Confirmed: Our test objects are present in the list")
    }

    @Test
    fun `listObjects with pagination should work`() = test {
        println("Requesting list with page size: 2")

        val listResult = vssClient.listObjects(pageSize = 2)
        assertTrue(listResult.isSuccess, "List with pagination should succeed")

        val listInfo = listResult.getOrNull()
        assertNotNull(listInfo, "List should return result")
        println("Pagination result: ${listInfo.objects.size} objects returned")
        println("Next page token: ${listInfo.nextPageToken}")
        // Note: We can't guarantee the page size will be respected if there are fewer objects
    }
}

package to.bitkit.services

import com.synonym.bitkitcore.UsdtException
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.repositories.BackupRepo
import to.bitkit.test.BaseUnitTest
import javax.inject.Provider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UsdtServiceTest : BaseUnitTest() {
    @Test
    fun `recovery generation advances only after the actual backup callback succeeds`() = test {
        val backups: BackupRepo = mock()
        val service = UsdtService(mock(), Provider { backups })
        whenever(backups.persistUsdtRecoveryBackup("original"))
            .thenReturn(Result.failure(UsdtException.NetworkUnavailable()), Result.success(Unit))

        assertFailsWith<UsdtException.BackupUnavailable> { service.recoveryBackup.persist("original") }
        assertEquals(0L, service.recoveryGeneration)
        service.recoveryBackup.persist("original")
        assertEquals(1L, service.recoveryGeneration)
        verify(backups, times(2)).persistUsdtRecoveryBackup("original")
    }
}

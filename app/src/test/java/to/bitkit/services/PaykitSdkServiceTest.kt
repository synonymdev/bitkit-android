package to.bitkit.services

import com.synonym.paykit.EncryptedLinkRecoveryMarkerPolicy
import com.synonym.paykit.EndpointManagementScope
import com.synonym.paykit.PublicContactSharingPolicy
import org.junit.Test
import kotlin.test.assertEquals

class PaykitSdkServiceTest {
    @Test
    fun `config scopes public endpoint sync to Bitkit managed endpoints`() {
        assertEquals(EndpointManagementScope.MANAGED_ONLY, BitkitPaykitSdkConfig.endpointManagementScope)
        assertEquals(PublicContactSharingPolicy.LOCAL_ONLY, BitkitPaykitSdkConfig.publicContactSharing)
        assertEquals(EncryptedLinkRecoveryMarkerPolicy.ENABLED, BitkitPaykitSdkConfig.encryptedLinkRecoveryMarkers)
    }
}

package to.bitkit

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import to.bitkit.utils.AppError
import java.security.Provider
import java.security.Security
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class AppTest {
    private companion object {
        const val BC = BouncyCastleProvider.PROVIDER_NAME
    }

    private var baselineProvider: Provider? = null
    private var baselinePosition = 0

    @Before
    fun setUp() {
        baselineProvider = Security.getProvider(BC)
        baselinePosition = Security.getProviders().indexOfFirst { it === baselineProvider } + 1
    }

    @After
    fun tearDown() {
        // The provider list is shared by the whole JVM, so restore BC as it was before this test started
        Security.removeProvider(BC)
        baselineProvider?.let { Security.insertProviderAt(it, baselinePosition) }
    }

    @Test
    fun `onCreate installs the security provider before Hilt injects the app`() {
        Security.removeProvider(BC)
        Security.addProvider(OutdatedBcProvider())
        val app = InjectionProbeApp()

        // The Hilt Gradle plugin rewrites App to extend the generated Hilt_App, whose onCreate() injects App through
        // hiltInternalInject(). The probe's method of that name overrides it at runtime and stops onCreate() there.
        assertFailsWith<InjectionReached> { app.onCreate() }

        assertIs<BouncyCastleProvider>(app.providerAtInjection)
    }

    private class InjectionProbeApp : App() {
        var providerAtInjection: Provider? = null

        fun hiltInternalInject() {
            providerAtInjection = Security.getProvider(BC)
            throw InjectionReached()
        }
    }

    private class InjectionReached : AppError("Reached Hilt injection")

    private class OutdatedBcProvider : Provider(BC, 1.0, "Stub for the BC provider that Android registers")
}

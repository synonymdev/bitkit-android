package to.bitkit.build

import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class NativeReleaseConfigTest {

    private val repoRoot = generateSequence(
        Path(requireNotNull(System.getProperty("user.dir")) { "user.dir is required" }),
    ) { it.parent }
        .first { it.resolve("gradle/libs.versions.toml").exists() }

    @Test
    fun `release build keeps full native debug symbols`() {
        val buildFile = repoRoot.resolve("app/build.gradle.kts").readText()

        assertTrue(
            buildFile.contains("""debugSymbolLevel = "FULL""""),
            "Release builds must keep full native debug symbols for Play crash symbolication.",
        )
    }

    @Test
    fun `release recipe verifies native debug symbols archive`() {
        val justfile = repoRoot.resolve("Justfile").readText()

        assertTrue(
            justfile.contains(
                """symbols="app/build/outputs/native-debug-symbols/mainnetRelease/native-debug-symbols.zip"""",
            ),
            "Release builds must verify the native debug symbols archive before publishing.",
        )
        assertTrue(
            justfile.contains("Attach this exact file to GitHub releases"),
            "Release builds must tell the releaser to attach native debug symbols.",
        )
    }

    @Test
    fun `release command uploads native debug symbols archive`() {
        val releaseCommand = repoRoot.resolve(".agents/commands/release.md").readText()

        assertTrue(
            releaseCommand.contains(
                "app/build/outputs/native-debug-symbols/mainnetRelease/native-debug-symbols.zip",
            ),
            "Release command must include the native debug symbols archive path.",
        )
        assertTrue(
            releaseCommand.contains("Native debug symbols uploaded: native-debug-symbols.zip"),
            "Release command summary must report the native debug symbols archive.",
        )
    }
}

package to.bitkit.build

import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeReleaseConfigTest {

    private val repoRoot = generateSequence(
        Path(requireNotNull(System.getProperty("user.dir")) { "user.dir is required" }),
    ) { it.parent }
        .first { it.resolve("gradle/libs.versions.toml").exists() }

    @Test
    fun `release build requests full native debug symbols`() {
        val buildFile = repoRoot.resolve("app/build.gradle.kts").readText()

        assertTrue(
            buildFile.contains("""debugSymbolLevel = "FULL""""),
            "Release builds must request full native debug symbols for Play crash symbolication.",
        )
    }

    @Test
    fun `release recipe verifies native debug symbols archive`() {
        val justfile = repoRoot.resolve("Justfile").readText()

        assertTrue(
            justfile.contains(
                """rm -f "${'$'}symbols"""",
            ),
            "Release builds must remove stale native debug symbols before rebuilding.",
        )
        assertTrue(
            justfile.contains("scripts/create-native-debug-symbols.sh"),
            "Release builds must create the native debug symbols archive before publishing.",
        )
        assertTrue(
            justfile.contains("Attach this exact file to GitHub releases"),
            "Release builds must tell the releaser to attach native debug symbols.",
        )
        assertTrue(
            justfile.contains("upload it to Play Console for this release"),
            "Release builds must tell the releaser to upload native debug symbols to Play.",
        )
        assertFalse(
            justfile.contains("download"),
            "Release builds should keep native debug symbols in release storage.",
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
        assertFalse(
            releaseCommand.contains("Play " + "did not"),
            "Release command should use current Play native symbol wording.",
        )
        assertTrue(
            releaseCommand.contains("fails instead of creating a placeholder zip from stripped `.so` files"),
            "Release command must fail instead of publishing fake native debug symbols.",
        )
        assertTrue(
            releaseCommand.contains("Play Console may only show delete/replace controls"),
            "Release command must document the verified Play Console behavior.",
        )
    }

    @Test
    fun `native debug symbols script rejects stripped release libraries`() {
        val symbolsScript = repoRoot.resolve("scripts/create-native-debug-symbols.sh").readText()

        assertTrue(
            symbolsScript.contains(
                "app/build/outputs/native-debug-symbols/${'$'}variant/native-debug-symbols.zip",
            ),
            "Native debug symbols script must write the canonical archive path.",
        )
        assertTrue(
            symbolsScript.contains("arm64-v8a armeabi-v7a"),
            "Native debug symbols script must archive Play release ABIs.",
        )
        assertTrue(
            symbolsScript.contains("zip -qr"),
            "Native debug symbols script must create a zip archive.",
        )
        assertTrue(
            symbolsScript.contains(
                """required_libs="libbitkitcore.so libldk_node.so libpaykit.so libvss_rust_client_ffi.so"""",
            ),
            "Native debug symbols script must validate release-critical native libraries.",
        )
        assertTrue(
            symbolsScript.contains("""archive_symbol_suffixes=".dbg .sym""""),
            "Native debug symbols script must accept AGP native debug symbol entry suffixes.",
        )
        assertTrue(
            symbolsScript.contains("""grep -Eq '\.debug_info'"""),
            "Native debug symbols script must validate full DWARF debug metadata before zipping.",
        )
        assertFalse(
            symbolsScript.contains("symtab|debug_|gnu_debugdata"),
            "Native debug symbols script must not accept symbol-table-only metadata for FULL symbols.",
        )
        assertTrue(
            symbolsScript.contains("Refusing to create '${'$'}output' from stripped native libraries."),
            "Native debug symbols script must refuse placeholder archives.",
        )
    }
}

package to.bitkit.wear.services

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The watch must never write to VSS. That is kept by structure, which these tests pin: the reader's interface has
 * one call, a read, and the only source file that names the VSS library imports nothing of it that writes.
 */
class PhoneBackupReaderTest {
    private companion object {
        const val VSS_PACKAGE = "com.synonym.vssclient"
        const val READER_FILE = "PhoneBackupReader.kt"

        /**
         * Everything of vss-client the watch may name: the calls that derive the store id, sign in, read one object
         * and shut down, and the library's error type, which calls nothing.
         */
        val ALLOWED_IMPORTS = setOf(
            "$VSS_PACKAGE.VssException",
            "$VSS_PACKAGE.vssDeriveStoreId",
            "$VSS_PACKAGE.vssGet",
            "$VSS_PACKAGE.vssNewClientWithLnurlAuth",
            "$VSS_PACKAGE.vssShutdownClient",
        )
    }

    private val sources: List<File> by lazy {
        val module = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { if (it.name == "wear") it else File(it, "wear") }
            .first { File(it, "src/main/java").isDirectory }
        File(module, "src/main").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
    }

    @Test
    fun `the reader can only read`() {
        assertEquals(listOf("read"), PhoneBackupReader::class.java.declaredMethods.map { it.name })
    }

    @Test
    fun `only the reader's file names the vss library`() {
        val naming = sources.filter { VSS_PACKAGE in it.readText() }.map { it.name }

        assertEquals(listOf(READER_FILE), naming)
    }

    @Test
    fun `the reader imports only the error type and the calls that derive, sign in, read and shut down`() {
        val reader = sources.single { it.name == READER_FILE }.readText()
        val imports = reader.lines().filter { it.startsWith("import $VSS_PACKAGE") }.map { it.removePrefix("import ") }

        assertEquals(ALLOWED_IMPORTS, imports.toSet())
        // Named only through its imports: no wildcard, no alias and no fully qualified call slips past them.
        assertEquals(ALLOWED_IMPORTS.size, Regex(Regex.escape(VSS_PACKAGE)).findAll(reader).count())
        assertTrue(imports.none { it.endsWith("*") || " as " in it })
    }

    @Test
    fun `no source of the watch app names a vss call that writes, deletes or lists`() {
        val writes = Regex("\\bvss(Store|Delete|Put\\w*|Ldk\\w*|NewLdk\\w*|NewClient|List\\w*)\\b")
        val offenders = sources.filter { writes.containsMatchIn(it.readText()) }.map { it.name }

        assertEquals(emptyList(), offenders)
    }
}

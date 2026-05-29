package org.iotsplab.akiba.tests.llm

import org.iotsplab.akiba.llm.tool.GhidraDocsManager
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Functional tests for [GhidraDocsManager] / `QueryGhidraAPITool`.
 *
 * The test exercises the real download + extraction path:
 *   - On the first run, the Ghidra release (~400 MB) is downloaded into
 *     `~/.akiba/ghidra/` and the API javadoc zip is unpacked into
 *     `~/.akiba/docs/`. This may take several minutes.
 *   - On subsequent runs everything is reused, so the test completes in
 *     well under a second.
 *
 * To skip these tests in environments without network access, set the
 * system property `-Dakiba.test.skipGhidraDownload=true` (the test is
 * silently passed in that case).
 */
class QueryGhidraAPIToolTest {

    private val docsRoot: Path get() = GhidraDocsManager.resolveDocsRoot()

    private fun skipIfDisabled(): Boolean {
        val skip = System.getProperty("akiba.test.skipGhidraDownload")?.toBoolean() ?: false
        if (skip) {
            println("[QueryGhidraAPIToolTest] Skipped (akiba.test.skipGhidraDownload=true).")
        }
        return skip
    }

    @Test
    fun testResolveDocsRootPointsToAkibaHome() {
        val expected = Path.of(System.getProperty("user.home"), ".akiba", "docs", "ghidra_api_12.1")
        assertEquals(expected.toAbsolutePath(), docsRoot.toAbsolutePath(),
            "resolveDocsRoot() should return ~/.akiba/docs/ghidra_api_12.1")
    }

    @Test
    fun testEnsureDocsAvailableDownloadsAndExtracts() {
        if (skipIfDisabled()) return

        val err = GhidraDocsManager.ensureDocsAvailable()
        assertTrue(err == null, "ensureDocsAvailable() reported error: $err")
        assertTrue(GhidraDocsManager.isDocsAvailable(),
            "Docs should be available after ensureDocsAvailable()")

        // Sanity-check: the standard javadoc index files must exist.
        assertTrue(docsRoot.resolve("type-search-index.js").exists(),
            "Missing type-search-index.js under $docsRoot")
        assertTrue(docsRoot.resolve("member-search-index.js").exists(),
            "Missing member-search-index.js under $docsRoot")
        assertTrue(docsRoot.resolve("index.html").exists(),
            "Missing index.html under $docsRoot")
    }

    @Test
    fun testLoadTypeIndexAndMemberIndex() {
        if (skipIfDisabled()) return
        GhidraDocsManager.ensureDocsAvailable()?.let { error("Setup failed: $it") }

        val typeIndex = GhidraDocsManager.loadTypeIndex()
        assertTrue(typeIndex.isNotEmpty(), "Type index should not be empty")
        assertTrue(typeIndex.any { it.className == "FlatProgramAPI" },
            "Type index should contain FlatProgramAPI")

        val memberIndex = GhidraDocsManager.loadMemberIndex()
        assertTrue(memberIndex.isNotEmpty(), "Member index should not be empty")
    }

    @Test
    fun testSearchAPIFindsKnownClass() {
        if (skipIfDisabled()) return
        GhidraDocsManager.ensureDocsAvailable()?.let { error("Setup failed: $it") }

        val result = GhidraDocsManager.searchAPI("FlatProgramAPI", maxResults = 30)
        assertNotNull(result)
        assertTrue(result.isNotBlank(), "Search result should not be empty")
        assertTrue(result.contains("FlatProgramAPI"),
            "Search for 'FlatProgramAPI' should mention the class. Got:\n$result")
        assertTrue(!result.startsWith("Error:"),
            "Search returned an error: $result")
    }

    @Test
    fun testSearchAPICaseInsensitive() {
        if (skipIfDisabled()) return
        GhidraDocsManager.ensureDocsAvailable()?.let { error("Setup failed: $it") }

        val result = GhidraDocsManager.searchAPI("decompinterface", maxResults = 10)
        assertTrue(result.contains("DecompInterface", ignoreCase = true),
            "Case-insensitive search should locate DecompInterface. Got:\n$result")
    }

    @Test
    fun testReadClassDocBySimpleName() {
        if (skipIfDisabled()) return
        GhidraDocsManager.ensureDocsAvailable()?.let { error("Setup failed: $it") }

        val doc = GhidraDocsManager.readClassDoc("FlatProgramAPI", maxChars = 20000)
        assertNotNull(doc)
        assertTrue(!doc.startsWith("Error:"),
            "readClassDoc returned an error: $doc")
        assertTrue(doc.contains("class FlatProgramAPI"),
            "readClassDoc should produce a 'class FlatProgramAPI' header. Got:\n${doc.take(400)}")
        // Method section header is included only if at least one non-<init> method is present.
        assertTrue(doc.contains("== Methods =="),
            "readClassDoc should include a Methods section. Got:\n${doc.take(800)}")
    }

    @Test
    fun testReadClassDocByFullyQualifiedName() {
        if (skipIfDisabled()) return
        GhidraDocsManager.ensureDocsAvailable()?.let { error("Setup failed: $it") }

        val doc = GhidraDocsManager.readClassDoc(
            "ghidra.program.flatapi.FlatProgramAPI",
            maxChars = 20000,
        )
        assertTrue(!doc.startsWith("Error:"),
            "readClassDoc returned an error: $doc")
        assertTrue(doc.contains("FlatProgramAPI"),
            "Output should mention FlatProgramAPI. Got:\n${doc.take(400)}")
    }

    @Test
    fun testReadClassDocUnknownClass() {
        if (skipIfDisabled()) return
        GhidraDocsManager.ensureDocsAvailable()?.let { error("Setup failed: $it") }

        val doc = GhidraDocsManager.readClassDoc("ThisClassDefinitelyDoesNotExist_xyz123")
        assertTrue(doc.contains("No documentation found", ignoreCase = true),
            "Unknown class should produce a 'No documentation found' message. Got:\n$doc")
    }
}

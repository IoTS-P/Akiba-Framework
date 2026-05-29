package org.iotsplab.akiba.llm.tool

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.iotsplab.akiba.llm.agent.Tool
import org.iotsplab.akiba.llm.agent.ToolParameter
import java.io.BufferedInputStream
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Manages the Ghidra API documentation on the local filesystem.
 *
 * The documentation directory (`ghidra_api_12.1/`) contains the standard
 * Javadoc output where HTML and JSON files for each class live side by side:
 *
 * ```
 * ghidra_api_12.1/
 * ├── ghidra/
 * │   ├── program/
 * │   │   ├── flatapi/
 * │   │   │   ├── FlatProgramAPI.html
 * │   │   │   └── FlatProgramAPI.json    ← structured class info
 * │   │   └── ...
 * │   └── ...
 * ├── type-search-index.js    ← {p:"pkg", l:"ClassName"} index
 * ├── member-search-index.js  ← {p:"pkg", c:"Class", l:"member"} index
 * ├── package-search-index.js
 * ├── index.html
 * └── ...
 * ```
 *
 * Each `.json` file has the following structure:
 * ```json
 * {
 *   "name": "FlatProgramAPI",
 *   "comment": "plain-text description",
 *   "javadoc": "HTML description",
 *   "static": false,
 *   "implements": [],
 *   "extends": "java.lang.Object",
 *   "fields": [{ "name", "comment", "static", "type_long", "type_short", "constant_value" }],
 *   "methods": [{ "name", "comment", "javadoc", "static", "params", "return", "throws" }]
 * }
 * ```
 */
object GhidraDocsManager {

    private const val GHIDRA_VERSION = "12.1"

    /** URL of the official Ghidra release zip (pinned, since version cadence is low). */
    private const val GHIDRA_RELEASE_URL =
        "https://github.com/NationalSecurityAgency/ghidra/releases/download/" +
        "Ghidra_12.1_build/ghidra_12.1_PUBLIC_20260513.zip"

    /** Base directory under user home for Akiba data. */
    private val AKIBA_HOME: Path = Path.of(System.getProperty("user.home"), ".akiba")

    /** Where the full Ghidra release is unpacked. */
    private val GHIDRA_DIR: Path = AKIBA_HOME.resolve("ghidra")

    /** Where the Ghidra API javadoc is unpacked (resolveDocsRoot returns this). */
    private val DOCS_DIR: Path = AKIBA_HOME.resolve("docs")

    /** Top-level directory name inside the release zip. */
    private const val RELEASE_TOP_DIR = "ghidra_12.1_PUBLIC"

    /**
     * Name of the directory under [DOCS_DIR] that holds the Ghidra API
     * javadoc. The javadoc zip wraps everything in a top-level `api/`
     * directory; we rename it to this versioned name so the docs root can
     * coexist with other documentation in the future.
     */
    private const val GHIDRA_API_DIR_NAME = "ghidra_api_$GHIDRA_VERSION"

    private val mapper: ObjectMapper = ObjectMapper()

    /** Lazy-loaded type search index: package → list of (package, simpleClassName). */
    @Volatile
    private var typeIndex: List<TypeEntry>? = null

    /** Lazy-loaded member search index. */
    @Volatile
    private var memberIndex: List<MemberEntry>? = null

    /** Whether we already attempted to download/extract the docs in this process. */
    @Volatile
    private var setupAttempted: Boolean = false

    data class TypeEntry(val pkg: String, val className: String)
    data class MemberEntry(val pkg: String, val className: String, val memberName: String)

    /**
     * Resolve the root directory for Ghidra API docs.
     * Points to `~/.akiba/docs/ghidra_api_<version>`. The directory is
     * created and populated on demand by [ensureDocsAvailable].
     */
    fun resolveDocsRoot(): Path = DOCS_DIR.resolve(GHIDRA_API_DIR_NAME)

    /** Check whether the docs directory exists and contains content. */
    fun isDocsAvailable(): Boolean {
        val root = resolveDocsRoot()
        return root.exists() && root.isDirectory()
            && root.resolve("type-search-index.js").exists()
    }

    // ============================================================
    //  Auto-download & extraction
    // ============================================================

    /**
     * Make sure the Ghidra API docs are available on disk.
     *
     * Steps performed (idempotent, executed only when needed):
     *   1. Download the pinned Ghidra release zip into `~/.akiba/ghidra/`
     *      (skipped if the release directory already exists).
     *   2. Extract the release zip into `~/.akiba/ghidra/`.
     *   3. Locate the API javadoc zip under
     *      `~/.akiba/ghidra/ghidra_12.1_PUBLIC/docs/` and extract it
     *      into `~/.akiba/docs/`. The javadoc's top-level directory `api/`
     *      is renamed to `ghidra_api_<version>/` so other documentation can
     *      live under `~/.akiba/docs/` alongside it.
     *
     * @return null on success, or an error message describing the failure.
     */
    @Synchronized
    fun ensureDocsAvailable(): String? {
        if (isDocsAvailable()) return null
        if (setupAttempted && !isDocsAvailable()) {
            // Avoid endless retries within one process if a previous attempt failed.
            return "Ghidra API docs are not available at '${resolveDocsRoot()}' " +
                "(automatic setup previously failed in this process)."
        }
        setupAttempted = true

        return try {
            Files.createDirectories(GHIDRA_DIR)
            Files.createDirectories(DOCS_DIR)

            val releaseRoot = GHIDRA_DIR.resolve(RELEASE_TOP_DIR)
            if (!releaseRoot.exists()) {
                val zipPath = GHIDRA_DIR.resolve("ghidra_release.zip")
                if (!zipPath.exists()) {
                    downloadFile(GHIDRA_RELEASE_URL, zipPath)
                }
                extractZip(zipPath, GHIDRA_DIR)
                // Best-effort cleanup of the (large) release zip after extraction.
                try { Files.deleteIfExists(zipPath) } catch (_: Exception) {}
            }

            if (!releaseRoot.exists() || !releaseRoot.isDirectory()) {
                return "Ghidra release was extracted, but expected directory not found: $releaseRoot"
            }

            // Locate the API javadoc zip under <release>/docs/
            val docsSrc = releaseRoot.resolve("docs")
            if (!docsSrc.exists() || !docsSrc.isDirectory()) {
                return "Ghidra release does not contain a 'docs' directory at $docsSrc"
            }

            val apiZip = docsSrc.resolve("GhidraAPI_javadoc.zip").let {
                if (it.exists()) it else return "Could not locate the Ghidra API javadoc zip under $docsSrc"
            }

            // The javadoc zip wraps everything in a top-level `api/` directory;
            // rewrite it to `ghidra_api_<version>/` so DOCS_DIR can host
            // additional doc sets in the future without colliding.
            extractZip(apiZip, DOCS_DIR, renameTopLevelDir = GHIDRA_API_DIR_NAME)

            if (!isDocsAvailable()) {
                "Extracted API docs but '${resolveDocsRoot().resolve("type-search-index.js")}' is still missing."
            } else {
                null
            }
        } catch (e: Exception) {
            "Failed to set up Ghidra API docs: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Download a file from [url] to [target], following redirects. */
    private fun downloadFile(url: String, target: Path) {
        var current: String = url
        var redirects = 0
        while (true) {
            val conn = (URI(current).toURL().openConnection() as java.net.HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 120_000
                setRequestProperty("User-Agent", "Akiba-GhidraDocsManager")
            }
            val code = conn.responseCode
            if (code in 300..399 && redirects < 5) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw IOException("Redirect with no Location header (HTTP $code)")
                current = loc
                redirects++
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IOException("HTTP $code while downloading $current")
            }
            BufferedInputStream(conn.inputStream).use { input ->
                Files.createDirectories(target.parent)
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
            }
            conn.disconnect()
            return
        }
    }

    /**
     * Extract a zip file [zipFile] into [destDir]. Includes basic
     * Zip-Slip protection by verifying every entry stays within destDir.
     *
     * @param renameTopLevelDir  When non-null, the leading path component
     *   of each entry is replaced with this value (e.g. with `"foo"`,
     *   `api/x/y.html` → `foo/x/y.html`). Useful for archives that wrap
     *   their contents in a single top-level directory whose name we want
     *   to control.
     */
    private fun extractZip(zipFile: Path, destDir: Path, renameTopLevelDir: String? = null) {
        Files.createDirectories(destDir)
        val destNorm = destDir.toAbsolutePath().normalize()

        Files.newInputStream(zipFile).use { fis ->
            ZipInputStream(BufferedInputStream(fis)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val rawName = entry.name
                    val relName = if (renameTopLevelDir != null)
                        replaceFirstSegment(rawName, renameTopLevelDir)
                    else
                        rawName
                    if (relName.isNullOrBlank()) {
                        // Top-level dir entry itself when renaming had no
                        // remainder — skip; we'll create the dir via children.
                        zis.closeEntry()
                        continue
                    }
                    val outPath = destDir.resolve(relName).toAbsolutePath().normalize()
                    if (!outPath.startsWith(destNorm)) {
                        throw IOException("Refusing to extract entry outside target dir: $rawName")
                    }
                    if (entry.isDirectory) {
                        Files.createDirectories(outPath)
                    } else {
                        Files.createDirectories(outPath.parent)
                        Files.newOutputStream(outPath).use { os ->
                            zis.copyTo(os)
                        }
                    }
                    zis.closeEntry()
                }
            }
        }
    }

    /**
     * Replace the first path segment of a forward-slash zip entry with
     * [newFirstSegment].
     *
     * Examples (with `newFirstSegment = "ghidra_api_12.1"`):
     *   `api/`                 → `ghidra_api_12.1/`
     *   `api/index.html`       → `ghidra_api_12.1/index.html`
     *   `api/foo/bar.html`     → `ghidra_api_12.1/foo/bar.html`
     *   `top_level_file_only`  → `ghidra_api_12.1/top_level_file_only`
     */
    private fun replaceFirstSegment(path: String, newFirstSegment: String): String? {
        val normalized = path.trimStart('/')
        if (normalized.isEmpty()) return null
        val slashIdx = normalized.indexOf('/')
        return if (slashIdx < 0) {
            // No directory separator — treat the whole entry as a file at
            // the top level and place it under the renamed directory.
            "$newFirstSegment/$normalized"
        } else {
            val rest = normalized.substring(slashIdx + 1)
            if (rest.isBlank()) "$newFirstSegment/" else "$newFirstSegment/$rest"
        }
    }

    // ============================================================
    //  Index loading
    // ============================================================

    /**
     * Parse the `type-search-index.js` file.
     * Format: `typeSearchIndex = [{p:"pkg",l:"ClassName"}, ...];updateSearchResults();`
     */
    @Synchronized
    fun loadTypeIndex(): List<TypeEntry> {
        typeIndex?.let { return it }

        val indexFile = resolveDocsRoot().resolve("type-search-index.js")
        if (!indexFile.exists()) return emptyList()

        val content = Files.readString(indexFile)
        val jsonStr = extractJsonArray(content) ?: return emptyList()

        val entries = mapper.readTree(jsonStr)
        val result = mutableListOf<TypeEntry>()
        for (node in entries) {
            val pkg = node["p"]?.asText() ?: continue
            val className = node["l"]?.asText() ?: continue
            result.add(TypeEntry(pkg, className))
        }

        typeIndex = result
        return result
    }

    /**
     * Parse the `member-search-index.js` file.
     * Format: `memberSearchIndex = [{p:"pkg",c:"Class",l:"memberName",u:"..."}, ...];...`
     */
    @Synchronized
    fun loadMemberIndex(): List<MemberEntry> {
        memberIndex?.let { return it }

        val indexFile = resolveDocsRoot().resolve("member-search-index.js")
        if (!indexFile.exists()) return emptyList()

        val content = Files.readString(indexFile)
        val jsonStr = extractJsonArray(content) ?: return emptyList()

        val entries = mapper.readTree(jsonStr)
        val result = mutableListOf<MemberEntry>()
        for (node in entries) {
            val pkg = node["p"]?.asText() ?: continue
            val className = node["c"]?.asText() ?: continue
            val memberName = node["l"]?.asText() ?: continue
            result.add(MemberEntry(pkg, className, memberName))
        }

        memberIndex = result
        return result
    }

    /**
     * Extract JSON array string from a javadoc JS file.
     * Input:  `typeSearchIndex = [{...},...];updateSearchResults();`
     * Output: `[{...},...]`
     */
    private fun extractJsonArray(jsContent: String): String? {
        val eqIdx = jsContent.indexOf('=')
        if (eqIdx < 0) return null
        val afterEq = jsContent.substring(eqIdx + 1).trim()
        // Find the closing '];' or ';' of the array
        val endIdx = afterEq.indexOf("];")
        if (endIdx >= 0) return afterEq.substring(0, endIdx + 1)
        // Fallback: find the first ';' after the array starts
        val semiIdx = afterEq.indexOf(';')
        if (semiIdx >= 0) return afterEq.substring(0, semiIdx).trimEnd()
        return null
    }

    // ============================================================
    //  Search operations
    // ============================================================

    /**
     * Search for Ghidra types (classes/interfaces/enums) matching a keyword.
     * Uses the pre-built `type-search-index.js` for fast lookup.
     *
     * @param keyword  Simple class name or part of it (case-insensitive)
     * @param maxResults  Maximum number of results
     * @return Formatted search results
     */
    fun searchAPI(keyword: String, maxResults: Int = 30): String {
        ensureDocsAvailable()?.let { err ->
            return "Error: $err"
        }
        if (!isDocsAvailable()) {
            return "Error: Ghidra API docs not found at '${resolveDocsRoot()}'."
        }

        val results = mutableListOf<String>()
        val lowerKeyword = keyword.lowercase()

        // 1. Search type index for matching class names
        val typeEntries = loadTypeIndex()
        val typeMatches = typeEntries.filter {
            it.className.lowercase().contains(lowerKeyword)
        }

        // Partition into exact match, prefix match, and contains match
        val exactClassMatches = typeMatches.filter { it.className.equals(keyword, ignoreCase = true) }
        val prefixClassMatches = typeMatches.filter {
            !it.className.equals(keyword, ignoreCase = true) &&
                it.className.lowercase().startsWith(lowerKeyword)
        }
        val containsClassMatches = typeMatches.filter {
            !it.className.equals(keyword, ignoreCase = true) &&
                !it.className.lowercase().startsWith(lowerKeyword)
        }

        // 2. Search member index
        val memberEntries = loadMemberIndex()

        // For exact class matches: show the class and ALL its members immediately below
        if (exactClassMatches.isNotEmpty()) {
            results.add("=== Exact class match ===")
            for (entry in exactClassMatches) {
                val fqn = "${entry.pkg}.${entry.className}"
                results.add("  $fqn")

                // Find all members belonging to this exact class
                val classMembers = memberEntries.filter {
                    it.className == entry.className && it.pkg == entry.pkg
                }
                if (classMembers.isNotEmpty()) {
                    results.add("    Members (${classMembers.size}):")
                    classMembers.take(50).forEach { m ->
                        results.add("      .${m.memberName}")
                    }
                    if (classMembers.size > 50) {
                        results.add("      ... and ${classMembers.size - 50} more members")
                    }
                }
            }
        }

        // 3. Similar class names (prefix match first, then contains)
        val otherClasses = prefixClassMatches + containsClassMatches
        if (otherClasses.isNotEmpty()) {
            val remaining = maxResults - exactClassMatches.size
            if (remaining > 0) {
                results.add("=== Similar classes ===")
                otherClasses.take(remaining).forEach { entry ->
                    val fqn = "${entry.pkg}.${entry.className}"
                    results.add("  $fqn")
                }
                if (otherClasses.size > remaining) {
                    results.add("  ... and ${otherClasses.size - remaining} more")
                }
            }
        }

        // 4. Members matching the keyword that do NOT belong to an exact-matched class
        //    (e.g. methods in other classes that happen to have this name)
        val exactClassFqns = exactClassMatches.map { "${it.pkg}.${it.className}" }.toSet()
        val otherMemberMatches = memberEntries.filter {
            it.memberName.lowercase().contains(lowerKeyword) &&
                "${it.pkg}.${it.className}" !in exactClassFqns
        }

        if (otherMemberMatches.isNotEmpty()) {
            // Prioritize: exact member name match first, then contains
            val exactMembers = otherMemberMatches.filter {
                it.memberName.equals(keyword, ignoreCase = true)
            }
            val otherMembers = otherMemberMatches.filter {
                !it.memberName.equals(keyword, ignoreCase = true)
            }
            val sortedMembers = exactMembers + otherMembers

            val memberLimit = maxResults.coerceAtMost(20)
            results.add("=== Members in other classes matching '$keyword' ===")
            sortedMembers.take(memberLimit).forEach { entry ->
                results.add("  ${entry.pkg}.${entry.className}.${entry.memberName}")
            }
            if (sortedMembers.size > memberLimit) {
                results.add("  ... and ${sortedMembers.size - memberLimit} more")
            }
        }

        if (results.isEmpty()) {
            // Fallback: grep the JSON files directly
            val grepResults = grepJsonDocs(keyword, maxLines = maxResults)
            if (grepResults.isNotEmpty()) {
                results.add("=== Grep results in JSON docs ===")
                results.addAll(grepResults)
            }
        }

        if (results.isEmpty()) {
            return "No results found for '$keyword' in Ghidra API documentation."
        }

        return results.joinToString("\n")
    }

    /**
     * Read the structured JSON documentation for a Ghidra class.
     *
     * Parses the `.json` file and formats it into a readable summary
     * including class description, fields, and method signatures.
     *
     * @param className  Simple name (e.g. "FlatProgramAPI") or
     *   fully-qualified name (e.g. "ghidra.program.flatapi.FlatProgramAPI")
     * @param maxChars  Maximum characters in the output
     */
    fun readClassDoc(className: String, maxChars: Int = 15000): String {
        ensureDocsAvailable()?.let { err ->
            return "Error: $err"
        }
        if (!isDocsAvailable()) {
            return "Error: Ghidra API docs not found at '${resolveDocsRoot()}'."
        }

        val jsonFile = resolveClassJsonFile(className)
        if (jsonFile == null || !jsonFile.exists()) {
            // Try fuzzy search in the type index
            val typeEntries = loadTypeIndex()
            val matches = typeEntries.filter { it.className.equals(className, ignoreCase = true) }
            if (matches.size == 1) {
                val fqn = "${matches[0].pkg}.${matches[0].className}"
                val resolved = resolveClassJsonFile(fqn)
                if (resolved != null && resolved.exists()) {
                    return formatClassJson(resolved, maxChars)
                }
            } else if (matches.size > 1) {
                return "Multiple classes match '$className':\n" +
                    matches.take(10).joinToString("\n") { "  ${it.pkg}.${it.className}" } +
                    if (matches.size > 10) "\n  ... and ${matches.size - 10} more" else "" +
                    "\nPlease specify the fully-qualified name."
            }

            return "No documentation found for class '$className'. " +
                "Try the 'search' action first to find the correct class name."
        }

        return formatClassJson(jsonFile, maxChars)
    }

    /**
     * Resolve the `.json` file path for a given class name.
     *
     * @param className  Simple name or fully-qualified name
     * @return Path to the JSON file, or null if not found
     */
    private fun resolveClassJsonFile(className: String): Path? {
        val root = resolveDocsRoot()
        val classPath = className.replace('.', '/')

        // Try fully-qualified path first: ghidra/program/flatapi/FlatProgramAPI.json
        val exactPath = root.resolve("$classPath.json")
        if (exactPath.exists()) return exactPath

        // Try finding by simple name using type index
        val simpleName = className.substringAfterLast('.')
        if (simpleName != className) {
            // Already tried fully-qualified, no more to do
            return null
        }

        // Search type index for matching simple names
        val typeEntries = loadTypeIndex()
        val match = typeEntries.firstOrNull { it.className == simpleName }
        if (match != null) {
            val fqnPath = root.resolve("${match.pkg.replace('.', '/')}/$simpleName.json")
            if (fqnPath.exists()) return fqnPath
        }

        // Fallback: find using system command
        return findJsonFile(root, simpleName)
    }

    /**
     * Find a JSON file by simple class name using `find` command.
     */
    private fun findJsonFile(root: Path, simpleName: String): Path? {
        return try {
            val process = ProcessBuilder(
                "find", root.toAbsolutePath().toString(),
                "-name", "$simpleName.json"
            )
                .redirectErrorStream(true)
                .start()

            val line = process.inputStream.bufferedReader()
                .lines()
                .findFirst()
                .orElse(null)

            process.waitFor()

            if (line != null) Path.of(line) else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Format a Ghidra class JSON file into a readable text summary.
     *
     * Extracts: class name, package, description, extends, implements,
     * fields, and method signatures.
     */
    private fun formatClassJson(jsonFile: Path, maxChars: Int): String {
        return try {
            val node = mapper.readTree(jsonFile.toFile())
            val sb = StringBuilder()

            // Header
            val name = node["name"]?.asText() ?: "Unknown"
            val pkg = jsonFile.parent?.let { parent ->
                rootRelativePath(parent)
            }?.replace('/', '.') ?: ""

            sb.appendLine("class $name" + if (pkg.isNotBlank()) "  // package: $pkg" else "")

            node["extends"]?.asText()?.let { ext ->
                if (ext != "java.lang.Object") sb.appendLine("extends $ext")
            }

            val implements = node["implements"]
            if (implements != null && implements.isArray && implements.size() > 0) {
                val ifaceList = implements.mapNotNull { it.asText() }
                if (ifaceList.isNotEmpty()) sb.appendLine("implements ${ifaceList.joinToString(", ")}")
            }

            // Description
            node["comment"]?.asText()?.let { comment ->
                if (comment.isNotBlank()) {
                    sb.appendLine()
                    sb.appendLine(comment.take(500))
                }
            }

            // Fields
            val fields = node["fields"]
            if (fields != null && fields.isArray && fields.size() > 0) {
                sb.appendLine()
                sb.appendLine("== Fields ==")
                for (field in fields) {
                    val fName = field["name"]?.asText() ?: continue
                    val fType = field["type_short"]?.asText() ?: field["type_long"]?.asText() ?: "?"
                    val isStatic = field["static"]?.asBoolean() ?: false
                    val constVal = field["constant_value"]?.asText()
                    val modifier = if (isStatic) "static " else ""
                    val valuePart = if (constVal != null) " = $constVal" else ""
                    sb.appendLine("  ${modifier}$fType $fName$valuePart")
                }
            }

            // Methods
            val methods = node["methods"]
            if (methods != null && methods.isArray && methods.size() > 0) {
                sb.appendLine()
                sb.appendLine("== Methods ==")
                for (method in methods) {
                    val mName = method["name"]?.asText() ?: continue
                    if (mName == "<init>") continue  // skip constructors for brevity

                    val isStatic = method["static"]?.asBoolean() ?: false
                    val retType = method["return"]?.get("type_short")?.asText()
                        ?: method["return"]?.get("type_long")?.asText() ?: "void"

                    val params = method["params"]?.mapNotNull { param ->
                        val pType = param["type_short"]?.asText() ?: param["type_long"]?.asText() ?: "?"
                        val pName = param["name"]?.asText() ?: ""
                        "$pType $pName"
                    }?.joinToString(", ") ?: ""

                    val modifier = if (isStatic) "static " else ""
                    sb.appendLine("  ${modifier}$retType $mName($params)")

                    // Add method comment as inline description (first line only)
                    method["comment"]?.asText()?.let { c ->
                        val firstLine = c.lines().firstOrNull()?.take(120)
                        if (!firstLine.isNullOrBlank()) {
                            sb.appendLine("    → $firstLine")
                        }
                    }
                }

                // Constructors (after methods, more compact)
                val constructors = methods.filter { it["name"]?.asText() == "<init>" }
                if (constructors.isNotEmpty()) {
                    sb.appendLine()
                    sb.appendLine("== Constructors ==")
                    for (ctor in constructors) {
                        val params = ctor["params"]?.mapNotNull { param ->
                            val pType = param["type_short"]?.asText() ?: param["type_long"]?.asText() ?: "?"
                            val pName = param["name"]?.asText() ?: ""
                            "$pType $pName"
                        }?.joinToString(", ") ?: ""
                        sb.appendLine("  $name($params)")
                    }
                }
            }

            val result = sb.toString().trimEnd()
            if (result.length > maxChars) result.take(maxChars) + "\n... (truncated, ${result.length - maxChars} chars omitted)" else result
        } catch (e: Exception) {
            "Error reading class documentation: ${e.message}"
        }
    }

    /**
     * Get the relative path of a path under the docs root.
     */
    private fun rootRelativePath(path: Path): String {
        val root = resolveDocsRoot().toAbsolutePath()
        val absPath = path.toAbsolutePath()
        return if (absPath.startsWith(root)) {
            root.relativize(absPath).toString()
        } else {
            path.toString()
        }
    }

    /**
     * Grep for a keyword in the JSON API docs using system commands.
     * Fallback search when the index doesn't yield results.
     */
    fun grepJsonDocs(keyword: String, maxLines: Int = 50): List<String> {
        val root = resolveDocsRoot()
        if (!root.exists() || !root.isDirectory()) return emptyList()

        return try {
            val process = ProcessBuilder(
                "grep", "-ri", "--include=*.json",
                "--max-count=3", "-n", keyword,
                root.toAbsolutePath().toString()
            )
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader()
                .lines()
                .limit(maxLines.toLong())
                .toList()

            process.waitFor()
            output
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * Create a tool that allows the agent to search and read Ghidra API documentation.
 *
 * The Ghidra API has thousands of classes and methods. Before writing a script
 * that uses Ghidra APIs, the agent should query the documentation to find the
 * correct class names, method signatures, and usage patterns.
 *
 * ### Supported actions
 *
 * - **search**: Find classes and members matching a keyword using the built-in
 *   search index. Returns matching class names (with packages) and member names.
 * - **read_class**: Read the full documentation for a specific Ghidra class.
 *   Provide the simple class name (e.g. "FlatProgramAPI") or fully-qualified name
 *   (e.g. "ghidra.program.flatapi.FlatProgramAPI"). Returns class description,
 *   fields, method signatures, and constructors.
 *
 * ### Typical workflow
 *
 * 1. `search "decompile"` → discover `DecompInterface`, `DecompileOptions`, etc.
 * 2. `read_class "DecompInterface"` → get method signatures and descriptions
 * 3. Use the discovered API in a `run_script` call
 */
fun QueryGhidraAPITool(): Tool = Tool(
    name = "query_ghidra_api",
    description = buildString {
        appendLine("Search and read Ghidra API documentation.")
        appendLine("Ghidra has thousands of API classes. Use this tool to look up class names, method signatures,")
        appendLine("and usage patterns BEFORE writing scripts that call Ghidra APIs.")
        appendLine()
        appendLine("Actions:")
        appendLine("  search <keyword> — find classes and members matching a keyword (case-insensitive)")
        appendLine("  read_class <className> — read full documentation for a class (fields, methods, constructors)")
        appendLine()
        appendLine("IMPORTANT: Always query the API docs before writing a script if you are unsure about:")
        appendLine("  - The correct class name (e.g. is it `FunctionManager` or `FunctionDB`?)")
        appendLine("  - Method signatures and parameter types")
        appendLine("  - How to get an iterator/collection from a manager class")
        appendLine()
        appendLine("=== Common useful classes ===")
        appendLine("  Program — the top-level binary object (get via `currentProgram`)")
        appendLine("  FunctionManager — enumerate/find functions: `currentProgram.functionManager`")
        appendLine("  Listing — code units, instructions, data: `currentProgram.listing`")
        appendLine("  ReferenceManager — cross-references: `currentProgram.referenceManager`")
        appendLine("  SymbolTable — symbols/labels: `currentProgram.symbolTable`")
        appendLine("  Memory — raw memory access: `currentProgram.memory`")
        appendLine("  DecompInterface — decompile functions to C pseudocode")
        appendLine("  FlatProgramAPI — simplified utility methods for common operations")
        appendLine()
        appendLine("=== Example workflow ===")
        appendLine("1. query_ghidra_api {\"action\":\"search\", \"keyword\":\"decompile\"}")
        appendLine("   → finds DecompInterface, DecompileResults, etc.")
        appendLine("2. query_ghidra_api {\"action\":\"read_class\", \"keyword\":\"DecompInterface\"}")
        appendLine("   → shows openProgram(), decompileFunction() signatures")
        appendLine("3. run_script with a script using the discovered API")
    },
    parameters = listOf(
        ToolParameter(
            "action", "string",
            "Action to perform: 'search' or 'read_class'",
            required = true,
            enum = listOf("search", "read_class")
        ),
        ToolParameter(
            "keyword", "string",
            "For 'search': keyword to search for. For 'read_class': class name (simple or fully-qualified).",
            required = true
        ),
        ToolParameter(
            "maxResults", "integer",
            "Maximum number of results to return (default 30 for search).",
            required = false
        )
    )
) { args ->
    val action = args["action"] as? String
        ?: return@Tool "Error: 'action' parameter is required ('search' or 'read_class')"
    val keyword = args["keyword"] as? String
        ?: return@Tool "Error: 'keyword' parameter is required"
    val maxResults = (args["maxResults"] as? Number)?.toInt() ?: 30

    when (action) {
        "search" -> GhidraDocsManager.searchAPI(keyword, maxResults)
        "read_class" -> GhidraDocsManager.readClassDoc(keyword)
        else -> "Error: unknown action '$action'. Use 'search' or 'read_class'."
    }
}

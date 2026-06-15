package org.iotsplab.akiba.llm.skill

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

object SkillManager {
    private val mapper = jacksonObjectMapper()
    private val root: Path = Path.of(System.getProperty("user.home"), ".akiba", "skills")

    private const val MAX_ZIP_BYTES = 50L * 1024 * 1024
    private const val MAX_TOTAL_UNZIPPED_BYTES = 50L * 1024 * 1024
    private const val MAX_FILE_BYTES = 5L * 1024 * 1024
    private const val MAX_FILES = 300

    private val allowedExtensions = setOf(
        "md", "markdown", "json", "txt", "yaml", "yml",
        "kt", "kts", "py", "js", "ts", "sh"
    )

    data class SkillFileInfo(
        val path: String,
        val size: Long,
        val description: String? = null,
    )

    data class SkillInfo(
        val id: String,
        val name: String,
        val version: String? = null,
        val description: String,
        val entry: String,
        val tags: List<String> = emptyList(),
        val triggers: List<String> = emptyList(),
        val files: List<SkillFileInfo> = emptyList(),
        val path: String? = null,
    )

    data class SkillReadResult(
        val skill: SkillInfo,
        val content: String,
        val truncated: Boolean = false,
        val path: String? = null,
    )

    fun skillsRootForUser(username: String): Path = root.resolve(safeSegment(username))

    fun listSkills(username: String): List<SkillInfo> {
        val merged = linkedMapOf<String, SkillInfo>()
        // Built-in/default skills are installed under the service user `akiba`.
        // User-specific skills override built-ins with the same id.
        for (root in listOf(skillsRootForUser("akiba"), skillsRootForUser(username)).distinct()) {
            if (!root.exists() || !root.isDirectory()) continue
            Files.list(root).use { stream ->
                stream.filter { it.isDirectory() }
                    .map { runCatching { loadSkillInfo(it) }.getOrNull() }
                    .filter { it != null }
                    .map { it!! }
                    .forEach { merged[it.id] = it }
            }
        }
        return merged.values.sortedBy { it.id }
    }

    fun readSkill(username: String, skillId: String, maxChars: Int = 20_000): SkillReadResult {
        val dir = resolveSkillDir(username, skillId)
        val info = loadSkillInfo(dir)
        return readSkillFile(username, skillId, info.entry, maxChars)
    }

    fun readSkillFile(username: String, skillId: String, relativePath: String, maxChars: Int = 20_000): SkillReadResult {
        val dir = resolveSkillDir(username, skillId)
        val info = loadSkillInfo(dir)
        val normalizedPath = validateRelativePath(relativePath)
        val file = resolveInside(dir, normalizedPath)
        if (!file.exists() || !file.isRegularFile()) {
            throw IllegalArgumentException("Skill file '$normalizedPath' not found in skill '$skillId'")
        }
        if (Files.size(file) > MAX_FILE_BYTES) {
            throw IllegalArgumentException("Skill file '$normalizedPath' is too large")
        }
        val text = Files.readString(file)
        val truncated = text.length > maxChars
        val content = if (truncated) text.take(maxChars) + "\n... (truncated, ${text.length - maxChars} chars omitted)" else text
        return SkillReadResult(info, content, truncated, normalizedPath)
    }

    fun deleteSkill(username: String, skillId: String) {
        val dir = resolveSkillDir(username, skillId)
        deleteRecursively(dir)
    }

    fun installSkillZip(username: String, zipFile: Path): SkillInfo {
        if (!zipFile.exists() || !zipFile.isRegularFile()) {
            throw IllegalArgumentException("Uploaded skill zip not found")
        }
        if (Files.size(zipFile) > MAX_ZIP_BYTES) {
            throw IllegalArgumentException("Skill zip exceeds ${MAX_ZIP_BYTES / (1024 * 1024)} MiB")
        }

        val tmp = Files.createTempDirectory("akiba_skill_upload_")
        try {
            extractZipSafely(zipFile, tmp)
            val skillRoot = locateSkillRoot(tmp)
            return installSkillDirectory(username, skillRoot)
        } finally {
            deleteRecursively(tmp)
        }
    }

    fun installSkillDirectory(username: String, skillRoot: Path): SkillInfo {
        val info = loadSkillInfo(skillRoot)
        val targetRoot = skillsRootForUser(username)
        Files.createDirectories(targetRoot)
        val target = targetRoot.resolve(info.id).normalize()
        if (!target.startsWith(targetRoot.toAbsolutePath().normalize())) {
            throw IllegalArgumentException("Invalid skill id '${info.id}'")
        }
        if (target.exists()) deleteRecursively(target)
        Files.createDirectories(target.parent)
        copyRecursively(skillRoot, target)
        return loadSkillInfo(target)
    }

    private fun loadSkillInfo(dir: Path): SkillInfo {
        validateSkillDir(dir)
        val json = dir.resolve("skill.json")
        val skillMd = dir.resolve("SKILL.md")
        val instructions = dir.resolve("instructions.md")

        val info = when {
            json.exists() -> loadJsonSkillInfo(dir, json)
            skillMd.exists() -> loadMarkdownSkillInfo(dir, skillMd)
            instructions.exists() -> SkillInfo(
                id = safeSkillId(dir.fileName.toString()),
                name = dir.fileName.toString(),
                description = "Skill instructions from instructions.md",
                entry = "instructions.md",
            )
            else -> throw IllegalArgumentException("Skill must contain skill.json, SKILL.md, or instructions.md")
        }
        val entry = resolveInside(dir, info.entry)
        if (!entry.exists() || !entry.isRegularFile()) {
            throw IllegalArgumentException("Skill '${info.id}' entry file '${info.entry}' does not exist")
        }
        return info.copy(files = collectFiles(dir, info), path = dir.toString())
    }

    private fun loadJsonSkillInfo(dir: Path, json: Path): SkillInfo {
        val node = mapper.readTree(json.toFile())
        val id = safeSkillId(text(node, "id") ?: dir.fileName.toString())
        val entry = text(node, "entry") ?: text(node, "main") ?: text(node, "mainFile") ?: "instructions.md"
        return SkillInfo(
            id = id,
            name = text(node, "name") ?: id,
            version = text(node, "version"),
            description = text(node, "description") ?: "",
            entry = validateRelativePath(entry),
            tags = stringList(node, "tags"),
            triggers = stringList(node, "triggers"),
        )
    }

    private fun loadMarkdownSkillInfo(dir: Path, file: Path): SkillInfo {
        val text = Files.readString(file).take(16_000)
        val frontmatter = parseFrontmatter(text)
        val id = safeSkillId(frontmatter["id"] ?: frontmatter["name"] ?: dir.fileName.toString())
        return SkillInfo(
            id = id,
            name = frontmatter["name"] ?: id,
            version = frontmatter["version"],
            description = frontmatter["description"] ?: firstNonEmptyLine(text) ?: "Claude-compatible skill",
            entry = "SKILL.md",
            tags = splitCsv(frontmatter["tags"]),
            triggers = splitCsv(frontmatter["triggers"]),
        )
    }

    private fun collectFiles(dir: Path, info: SkillInfo): List<SkillFileInfo> {
        val declared = mutableMapOf<String, String?>()
        runCatching {
            val json = dir.resolve("skill.json")
            if (json.exists()) {
                val files = mapper.readTree(json.toFile()).get("files")
                if (files != null && files.isArray) {
                    for (item in files) {
                        val path = text(item, "path") ?: continue
                        declared[validateRelativePath(path)] = text(item, "description")
                    }
                }
            }
        }
        return Files.walk(dir).use { stream ->
            stream.filter { it.isRegularFile() }
                .map { file ->
                    val rel = dir.relativize(file).toString().replace('\\', '/')
                    SkillFileInfo(rel, Files.size(file), declared[rel])
                }
                .sorted(Comparator.comparing<SkillFileInfo, String> { it.path })
                .toList()
        }
    }

    private fun validateSkillDir(dir: Path) {
        if (!dir.exists() || !dir.isDirectory()) throw IllegalArgumentException("Skill directory not found: $dir")
        var count = 0
        var total = 0L
        Files.walk(dir).use { stream ->
            stream.forEach { path ->
                if (Files.isSymbolicLink(path)) throw IllegalArgumentException("Skill contains symlink: ${dir.relativize(path)}")
                if (path.isRegularFile()) {
                    count++
                    if (count > MAX_FILES) throw IllegalArgumentException("Skill contains too many files")
                    val size = Files.size(path)
                    if (size > MAX_FILE_BYTES) throw IllegalArgumentException("Skill file too large: ${dir.relativize(path)}")
                    total += size
                    if (total > MAX_TOTAL_UNZIPPED_BYTES) throw IllegalArgumentException("Skill total size too large")
                    val rel = dir.relativize(path).toString().replace('\\', '/')
                    validateSkillFileName(rel)
                }
            }
        }
    }

    private fun extractZipSafely(zipFile: Path, destDir: Path) {
        var count = 0
        var total = 0L
        val destNorm = destDir.toAbsolutePath().normalize()
        Files.newInputStream(zipFile).use { input ->
            ZipInputStream(input.buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name
                    validateZipEntryName(name)
                    val out = destDir.resolve(name).toAbsolutePath().normalize()
                    if (!out.startsWith(destNorm)) throw IllegalArgumentException("Zip entry escapes target dir: $name")
                    if (entry.isDirectory) {
                        Files.createDirectories(out)
                    } else {
                        validateSkillFileName(name)
                        count++
                        if (count > MAX_FILES) throw IllegalArgumentException("Skill zip contains too many files")
                        Files.createDirectories(out.parent)
                        var written = 0L
                        Files.newOutputStream(out).use { output ->
                            val buf = ByteArray(8192)
                            while (true) {
                                val n = zis.read(buf)
                                if (n < 0) break
                                written += n
                                total += n
                                if (written > MAX_FILE_BYTES) throw IllegalArgumentException("Zip entry too large: $name")
                                if (total > MAX_TOTAL_UNZIPPED_BYTES) throw IllegalArgumentException("Skill zip expands too large")
                                output.write(buf, 0, n)
                            }
                        }
                    }
                    zis.closeEntry()
                }
            }
        }
    }

    private fun locateSkillRoot(tmp: Path): Path {
        if (tmp.resolve("skill.json").exists() || tmp.resolve("SKILL.md").exists() || tmp.resolve("instructions.md").exists()) return tmp
        val dirs = Files.list(tmp).use { stream -> stream.filter { it.isDirectory() }.toList() }
        if (dirs.size == 1) return dirs[0]
        throw IllegalArgumentException("Skill zip must contain skill.json, SKILL.md, or instructions.md at root or inside one top-level directory")
    }

    private fun resolveSkillDir(username: String, skillId: String): Path {
        val id = safeSkillId(skillId)
        val roots = listOf(skillsRootForUser(username), skillsRootForUser("akiba")).distinct()
        for (candidateRoot in roots) {
            val root = candidateRoot.toAbsolutePath().normalize()
            val dir = root.resolve(id).normalize()
            if (!dir.startsWith(root)) throw IllegalArgumentException("Invalid skill id")
            if (dir.exists() && dir.isDirectory()) return dir
        }
        throw IllegalArgumentException("Skill '$skillId' not found for user '$username' or default skills")
    }

    private fun resolveInside(root: Path, relativePath: String): Path {
        val rel = validateRelativePath(relativePath)
        val rootNorm = root.toAbsolutePath().normalize()
        val path = rootNorm.resolve(rel).normalize()
        if (!path.startsWith(rootNorm)) throw IllegalArgumentException("Path escapes skill directory")
        return path
    }

    private fun validateZipEntryName(name: String) {
        validateRelativePath(name)
        if (name.endsWith('/')) return
    }

    private fun validateRelativePath(path: String): String {
        require(path.isNotBlank()) { "Path must not be blank" }
        require(!path.contains('\u0000')) { "Path contains NUL byte" }
        require(!path.startsWith("/") && !path.startsWith("\\")) { "Absolute paths are not allowed" }
        require(!Regex("^[A-Za-z]:").containsMatchIn(path)) { "Drive-letter paths are not allowed" }
        val normalized = path.replace('\\', '/')
        require(normalized.split('/').none { it == ".." || it.isBlank() }) { "Invalid relative path: $path" }
        return normalized
    }

    private fun validateSkillFileName(path: String) {
        val normalized = validateRelativePath(path)
        if (normalized.endsWith("/")) return
        val fileName = normalized.substringAfterLast('/')
        require(!fileName.startsWith('.')) { "Hidden files are not allowed: $path" }
        val ext = fileName.substringAfterLast('.', "").lowercase()
        require(ext in allowedExtensions) { "File extension '.$ext' is not allowed in skill zip: $path" }
    }

    private fun safeSkillId(raw: String): String {
        val id = raw.trim().lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-')
        require(id.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}"))) { "Invalid skill id '$raw'" }
        return id
    }

    private fun safeSegment(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(64).ifBlank { "akiba" }

    private fun text(node: JsonNode, field: String): String? =
        node.get(field)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }

    private fun stringList(node: JsonNode, field: String): List<String> {
        val value = node.get(field) ?: return emptyList()
        return when {
            value.isArray -> value.mapNotNull { it.asText(null) }.filter { it.isNotBlank() }.take(32)
            value.isTextual -> splitCsv(value.asText())
            else -> emptyList()
        }
    }

    private fun splitCsv(value: String?): List<String> =
        value?.split(',', ';', '|')?.map { it.trim() }?.filter { it.isNotBlank() }?.take(32) ?: emptyList()

    private fun parseFrontmatter(text: String): Map<String, String> {
        if (!text.startsWith("---")) return emptyMap()
        val end = text.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap()
        return text.substring(3, end).lines().mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim().trim('"', '\'')
        }.toMap()
    }

    private fun firstNonEmptyLine(text: String): String? =
        text.lines().firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("---") }?.trim()?.take(200)

    private fun copyRecursively(source: Path, target: Path) {
        Files.walk(source).use { stream ->
            stream.forEach { src ->
                val dst = target.resolve(source.relativize(src).toString()).normalize()
                if (src.isDirectory()) Files.createDirectories(dst)
                else Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!path.exists()) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}

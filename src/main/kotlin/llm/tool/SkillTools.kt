package org.iotsplab.akiba.llm.tool

import org.iotsplab.akiba.llm.skill.SkillManager

fun SearchSkillTool(username: String? = null): Tool = Tool(
    name = "search_skill",
    description = buildString {
        appendLine("Search or list installed user skills without reading file contents.")
        appendLine("Use this first when you do not know the skill id or need to see available skill files.")
        appendLine("Then call read_skill with skillId and an optional file/path/fileName parameter to read a file.")
        appendLine("Skills are user-provided guidance packages stored under ~/.akiba/skills/<username>/<skill_id>.")
    },
    parameters = listOf(
        ToolParameter("query", "string", "Optional search text matched against skill id, name, description, triggers, tags, and file paths. If omitted, lists all skills.", required = false),
        ToolParameter("skillId", "string", "Optional exact skill id to inspect metadata and file list for one skill.", required = false)
    )
) { args ->
    val user = username ?: "akiba"
    val query = firstStringArg(args, "query", "keyword", "q")
    val exactSkillId = firstStringArg(args, "skillId", "skill", "id")
    val skills = SkillManager.listSkills(user)
    val filtered = skills.filter { skill ->
        val matchesExact = exactSkillId == null || skill.id.equals(exactSkillId, ignoreCase = true)
        val matchesQuery = query == null || skill.matchesSkillQuery(query)
        matchesExact && matchesQuery
    }

    if (skills.isEmpty()) {
        "No skills installed for user '$user'. Upload a skill zip or install one under ~/.akiba/skills/$user/."
    } else if (filtered.isEmpty()) {
        "No skills matched query '${query ?: exactSkillId}'. Call search_skill without query to list all available skills."
    } else {
        buildString {
            appendLine("=== Available skills for $user (${filtered.size}/${skills.size}) ===")
            for (skill in filtered) {
                appendLine("- ${skill.id}: ${skill.name}")
                appendLine("  Description: ${skill.description.ifBlank { "(none)" }}")
                appendLine("  Entry: ${skill.entry}")
                skill.version?.let { appendLine("  Version: $it") }
                if (skill.tags.isNotEmpty()) appendLine("  Tags: ${skill.tags.joinToString(", ")}")
                if (skill.triggers.isNotEmpty()) appendLine("  Triggers: ${skill.triggers.joinToString(", ")}")
                if (skill.files.isNotEmpty()) {
                    appendLine("  Files:")
                    skill.files.take(50).forEach { file ->
                        appendLine("    - ${file.path} (${file.size} bytes)" + (file.description?.let { ": $it" } ?: ""))
                    }
                    if (skill.files.size > 50) appendLine("    ... and ${skill.files.size - 50} more")
                }
            }
            appendLine()
            appendLine("To read a file, call read_skill with skillId and one of: path, file, fileName, filename. If no file name is provided, read_skill defaults to instructions.md.")
        }
    }
}

fun ReadSkillTool(username: String? = null): Tool = Tool(
    name = "read_skill",
    description = buildString {
        appendLine("Read a file from an installed skill package.")
        appendLine("Use search_skill to find available skills and file paths before calling this tool.")
        appendLine("The skill id is required. The file name may be supplied as path, file, fileName, or filename.")
        appendLine("If no file name is supplied, this tool defaults to instructions.md and the first output line explicitly says no file name was received.")
        appendLine("Path must be relative to the skill root; path traversal and absolute paths are rejected.")
    },
    parameters = listOf(
        ToolParameter("skillId", "string", "Skill id to read. Required; use search_skill first if unknown.", required = true),
        ToolParameter("path", "string", "Optional relative file path inside the skill, e.g. coverage.md or files/checklist.md.", required = false),
        ToolParameter("file", "string", "Alias for path. Optional relative file path inside the skill.", required = false),
        ToolParameter("fileName", "string", "Alias for path. Optional relative file path inside the skill.", required = false),
        ToolParameter("filename", "string", "Alias for path. Optional relative file path inside the skill.", required = false),
        ToolParameter("maxChars", "integer", "Maximum characters to return. Default 20000.", required = false)
    )
) { args ->
    val user = username ?: "akiba"
    val skillId = firstStringArg(args, "skillId", "skill", "id")
        ?: return@Tool "Tool argument error for 'read_skill': missing required parameter 'skillId'. Use search_skill to find skill ids."
    val requestedPath = firstStringArg(args, "path", "file", "fileName", "filename")
    val defaultedToInstructions = requestedPath == null
    val path = requestedPath ?: "instructions.md"
    val maxChars = intArg(args, "maxChars", "max_chars") ?: 20_000
    val result = SkillManager.readSkillFile(user, skillId, path, maxChars)
    formatSkillReadResult(result, path, defaultedToInstructions)
}

private fun SkillManager.SkillInfo.matchesSkillQuery(query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    return id.lowercase().contains(q) ||
        name.lowercase().contains(q) ||
        description.lowercase().contains(q) ||
        entry.lowercase().contains(q) ||
        tags.any { it.lowercase().contains(q) } ||
        triggers.any { it.lowercase().contains(q) } ||
        files.any { file -> file.path.lowercase().contains(q) || file.description?.lowercase()?.contains(q) == true }
}

private fun firstStringArg(args: Map<String, Any?>, vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key -> (args[key] as? String)?.trim()?.takeIf { it.isNotEmpty() } }

private fun intArg(args: Map<String, Any?>, vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { key ->
        when (val value = args[key]) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
    }

private fun formatSkillReadResult(
    result: SkillManager.SkillReadResult,
    requestedPath: String,
    defaultedToInstructions: Boolean = false,
): String = buildString {
    if (defaultedToInstructions) {
        appendLine("Notice: read_skill did not receive a file name parameter; defaulting to instructions.md. Pass path, file, fileName, or filename to read a different skill file.")
    }
    val skill = result.skill
    appendLine("=== Skill: ${skill.id} — ${skill.name} ===")
    appendLine("Description: ${skill.description.ifBlank { "(none)" }}")
    skill.version?.let { appendLine("Version: $it") }
    if (skill.tags.isNotEmpty()) appendLine("Tags: ${skill.tags.joinToString(", ")}")
    if (skill.triggers.isNotEmpty()) appendLine("Triggers: ${skill.triggers.joinToString(", ")}")
    appendLine("Entry: ${skill.entry}")
    if (skill.files.isNotEmpty()) {
        appendLine("Files:")
        skill.files.take(50).forEach { file ->
            appendLine("  - ${file.path} (${file.size} bytes)" + (file.description?.let { ": $it" } ?: ""))
        }
        if (skill.files.size > 50) appendLine("  ... and ${skill.files.size - 50} more")
    }
    val displayPath = result.path ?: requestedPath
    appendLine()
    appendLine("--- BEGIN $displayPath ---")
    append(result.content)
    if (!result.content.endsWith("\n")) appendLine()
    appendLine("--- END $displayPath ---")
    if (result.truncated) appendLine("Note: content was truncated; call again with a larger maxChars if needed.")
}

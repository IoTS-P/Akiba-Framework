package org.iotsplab.akiba.llm.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.client.AkibaLLMClient
import org.iotsplab.akiba.llm.client.ChatCompletion
import org.iotsplab.akiba.llm.memory.ChatMemory
import org.iotsplab.akiba.llm.memory.MemoryManager
import org.iotsplab.akiba.llm.memory.MemoryType
import org.iotsplab.akiba.llm.memory.MemoryScope
import kotlin.system.measureTimeMillis

// ============================================================
//  Agent Strategy — interface
// ============================================================

/**
 * Strategy that controls how an [AkibaAgent] iterates through the
 * LLM → tool-call → observe cycle.
 *
 * Built-in implementations:
 * - [ReActStrategy] — explicit Thought → Action → Observation loop
 * - [PlanExecuteStrategy] — plan first, then execute each step
 *
 * Custom strategies can be created by implementing this interface.
 */
interface AgentStrategy {

    /** Human-readable name for logging. */
    val name: String

    /**
     * Run the agent loop to completion.
     *
     * @param ctx Fully-wired [StrategyContext] providing all dependencies.
     * @return The final [AgentResult].
     */
    fun execute(ctx: StrategyContext): AgentResult
}

/**
 * Bundle of everything a strategy needs, provided by [AkibaAgent].
 * This avoids strategies needing a direct reference to the agent itself.
 */
class StrategyContext(
    val client: AkibaLLMClient,
    val memory: ChatMemory,
    val memoryManager: MemoryManager?,
    val toolRegistry: ToolRegistry,
    val sessionId: String?,
    val systemPrompt: String?,
    val maxIterations: Int,
    val enrichSystemPromptWithMemory: Boolean,
    val auditToolCalls: Boolean,
    val logger: org.apache.logging.log4j.Logger = LogManager.getLogger(StrategyContext::class.java),
    val transcript: AgentTranscriptWriter? = null
) {
    /** Accumulated counters during the loop. Mutable, shared across the strategy. */
    @Suppress("LeakingThis")
    val stats: LoopStats = LoopStats()

    /** Build the effective system prompt with optional memory enrichment. */
    fun buildEffectiveSystemPrompt(extraInject: String? = null): String {
        var base = systemPrompt ?: ""

        if (enrichSystemPromptWithMemory && memoryManager != null) {
            val memoryContext = memoryManager.contextSummary()
            if (memoryContext.isNotBlank()) {
                base = "$base\n\n## Relevant Memories\n$memoryContext"
            }
        }

        if (!extraInject.isNullOrBlank()) {
            base = "$base\n\n$extraInject"
        }

        return base
    }

    /**
     * Call the LLM with the current conversation history.
     *
     * @return the completion, or null on error (error is logged).
     */
    fun callLLM(systemPrompt: String): ChatCompletion? {
        return try {
            client.chat(
                systemPrompt = systemPrompt,
                messages = memory.messages(),
                tools = if (toolRegistry.isEmpty()) null else toolRegistry.toJsonSchemas()
            )
        } catch (e: Exception) {
            stats.lastError = e.message
            null
        }
    }

    /**
     * Execute a tool call and return the result string.
     */
    fun executeTool(toolCall: ParsedToolCall): String {
        val tool = toolRegistry.get(toolCall.name)
        if (tool == null) {
            val errMsg = "Unknown tool: ${toolCall.name}. Available: ${toolRegistry.names()}"
            transcript?.writeToolResult(toolCall.name, errMsg)
            return errMsg
        }

        // Write tool call to transcript
        transcript?.writeToolCall(
            toolCall.name, toolCall.argumentsJson, toolCall.arguments, stats.iterations
        )

        var result: String
        val durationMs = measureTimeMillis {
            result = tool.safeExecute(toolCall.arguments)
        }

        stats.toolCallsMade++

        // Write tool result to transcript
        transcript?.writeToolResult(toolCall.name, result, durationMs)

        // Audit to database
        if (auditToolCalls && sessionId != null) {
            try {
                AgentDatabaseClient.recordToolCall(
                    sessionId = sessionId,
                    toolName = toolCall.name,
                    toolArgs = toolCall.argumentsJson,
                    resultSummary = result.take(2000),
                    success = !result.startsWith("Tool '${toolCall.name}' execution error"),
                    durationMs = durationMs
                )
            } catch (_: Exception) {}
        }

        // Truncate very long results
        if (result.length > AkibaAgent.MAX_TOOL_RESULT_LENGTH) {
            result = result.substring(0, AkibaAgent.MAX_TOOL_RESULT_LENGTH) +
                "\n... [truncated, ${result.length} chars total]"
        }

        // Auto-remember significant tool results
        if (memoryManager != null && auditToolCalls &&
            result.length >= 100 &&
            !result.startsWith("Tool '${toolCall.name}' execution error")
        ) {
            memoryManager.remember(
                content = "[${toolCall.name}] ${result.take(500)}",
                type = MemoryType.FINDING,
                scope = MemoryScope.SESSION,
                key = "tool:${toolCall.name}",
                importance = 0.6
            )
        }

        return result
    }

    /** Update session status in the database. */
    fun updateSessionStatus(status: String) {
        if (sessionId != null) {
            try {
                AgentDatabaseClient.updateSession(sessionId, status = status)
            } catch (_: Exception) {}
        }
    }
}

/** Mutable counters shared across a strategy execution. */
class LoopStats {
    var iterations: Int = 0
    var toolCallsMade: Int = 0
    var totalInputTokens: Int = 0
    var totalOutputTokens: Int = 0
    var lastError: String? = null
}

// ============================================================
//  ParsedToolCall — unified tool-call representation
// ============================================================

/**
 * A tool call parsed from an LLM response, regardless of how the
 * provider encodes it (native function_call, JSON in text, etc.).
 */
data class ParsedToolCall(
    val callId: String,
    val name: String,
    val arguments: Map<String, Any?>,
    val argumentsJson: String
)

// ============================================================
//  Tool call parsing — shared across strategies
// ============================================================

object ToolCallParser {
    private val mapper = jacksonObjectMapper()

    /** Pattern to detect the start of a json code block containing tool_call. */
    private val codeBlockStart = Regex("""```(?:json|tool_call)\s*""")

    /**
     * Try to parse a tool call from the assistant's text response.
     *
     * Returns null if no tool call is found.
     */
    fun parse(response: String): ParsedToolCall? {
        // 1. Try code block extraction: find ```json or ```tool_call blocks,
        //    then use brace-balancing to extract the JSON inside.
        var cbMatch = codeBlockStart.find(response)
        while (cbMatch != null) {
            val jsonStart = cbMatch.range.last + 1
            // Find the first '{' after the code block marker
            val braceStart = response.indexOf('{', jsonStart)
            if (braceStart >= 0) {
                val jsonStr = extractBalancedJson(response, braceStart)
                if (jsonStr != null) {
                    val result = tryParseToolCallJson(jsonStr)
                    if (result != null) return result
                }
            }
            cbMatch = codeBlockStart.find(response, cbMatch.range.last + 1)
        }

        // 2. Find bare JSON containing "tool_call" using brace-balancing.
        //    Look for the keyword and walk backwards to the enclosing '{'.
        val toolCallKeyword = "\"tool_call\""
        var searchFrom = 0
        while (searchFrom < response.length) {
            val keyIdx = response.indexOf(toolCallKeyword, searchFrom)
            if (keyIdx < 0) break

            val braceStart = findOpeningBrace(response, keyIdx)
            if (braceStart >= 0) {
                val jsonStr = extractBalancedJson(response, braceStart)
                if (jsonStr != null) {
                    val result = tryParseToolCallJson(jsonStr)
                    if (result != null) return result
                }
            }
            searchFrom = keyIdx + toolCallKeyword.length
        }

        return null
    }

    /**
     * Walk backwards from [fromIndex] to find the nearest '{'.
     * Skips whitespace and colon characters.
     */
    private fun findOpeningBrace(text: String, fromIndex: Int): Int {
        var i = fromIndex - 1
        while (i >= 0) {
            when {
                text[i] == '{' -> return i
                text[i].isWhitespace() || text[i] == ':' -> i--
                else -> break
            }
        }
        return -1
    }

    /**
     * Parse a JSON string as a tool_call object.
     * Returns null if parsing fails or the structure is invalid.
     */
    private fun tryParseToolCallJson(jsonStr: String): ParsedToolCall? {
        return try {
            val parsed = mapper.readValue<Map<String, Any?>>(jsonStr)
            val toolCallObj = parsed["tool_call"] as? Map<*, *> ?: return null
            val name = toolCallObj["name"] as? String ?: return null
            @Suppress("UNCHECKED_CAST")
            val args = toolCallObj["arguments"] as? Map<String, Any?> ?: emptyMap()
            ParsedToolCall(
                callId = "tc_${System.nanoTime()}",
                name = name,
                arguments = args,
                argumentsJson = mapper.writeValueAsString(args)
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extract a complete JSON object from [text] starting at [startIndex]
     * by counting balanced braces. Handles nested objects and strings
     * (with escaped quotes).
     *
     * Returns the JSON substring, or null if braces are unbalanced.
     */
    private fun extractBalancedJson(text: String, startIndex: Int): String? {
        if (startIndex >= text.length || text[startIndex] != '{') return null

        var depth = 0
        var inString = false
        var i = startIndex

        while (i < text.length) {
            val c = text[i]
            when {
                inString -> {
                    if (c == '\\') {
                        i++ // skip escaped character
                    } else if (c == '"') {
                        inString = false
                    }
                }
                c == '"' -> inString = true
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(startIndex, i + 1)
                    }
                }
            }
            i++
        }
        return null // unbalanced
    }

    /**
     * Check if the completion contains native tool calls.
     */
    fun isNativeToolCall(completion: ChatCompletion): Boolean {
        return completion.toolCalls.isNotEmpty() ||
            completion.finishReason == "tool_calls" ||
            completion.finishReason == "function_call"
    }

    /**
     * Parse tool calls from a completion.
     *
     * Priority:
     * 1. Native tool calls (from provider's function calling protocol)
     * 2. Text-embedded tool calls (JSON in assistant text)
     *
     * Returns the first tool call found (multiple tool calls in a single
     * response are supported via [parseAllFromCompletion]).
     */
    fun parseFromCompletion(completion: ChatCompletion): ParsedToolCall? {
        // 1. Check for native tool calls from the provider
        if (completion.toolCalls.isNotEmpty()) {
            val first = completion.toolCalls.first()
            val args: Map<String, Any?> = try {
                mapper.readValue(first.argumentsJson)
            } catch (_: Exception) {
                emptyMap()
            }
            return ParsedToolCall(
                callId = first.id,
                name = first.name,
                arguments = args,
                argumentsJson = first.argumentsJson
            )
        }

        // 2. Fall back to text-based parsing
        val content = completion.content
        if (content.isNotBlank()) {
            return parse(content)
        }
        return null
    }

    /**
     * Parse ALL tool calls from a completion (for providers that support
     * parallel/multi tool calling in a single response).
     */
    fun parseAllFromCompletion(completion: ChatCompletion): List<ParsedToolCall> {
        if (completion.toolCalls.isNotEmpty()) {
            return completion.toolCalls.map { tc ->
                val args: Map<String, Any?> = try {
                    mapper.readValue(tc.argumentsJson)
                } catch (_: Exception) {
                    emptyMap()
                }
                ParsedToolCall(
                    callId = tc.id,
                    name = tc.name,
                    arguments = args,
                    argumentsJson = tc.argumentsJson
                )
            }
        }
        // Fall back to single text-based parse
        val content = completion.content
        if (content.isNotBlank()) {
            val single = parse(content)
            if (single != null) return listOf(single)
        }
        return emptyList()
    }
}

// ============================================================
//  ReAct Strategy
// ============================================================

/**
 * ReAct (Reasoning + Acting) strategy.
 *
 * The agent follows an explicit **Thought → Action → Observation** cycle:
 *
 * ```
 * ┌──────────────────────────────────────────────┐
 * │  THOUGHT: I need to find the entry point.    │
 * │  ACTION:  list_functions()                   │
 * │  OBSERVATION: main @ 0x401234, foo @ 0x401256│
 * │  THOUGHT: The entry point is main...         │
 * │  ACTION:  decompile(address="0x401234")      │
 * │  OBSERVATION: int main() { return 0; }       │
 * │  THOUGHT: I now have the answer.             │
 * │  FINAL ANSWER: The binary's entry point...   │
 * └──────────────────────────────────────────────┘
 * ```
 *
 * The LLM is instructed to follow this structured format.  On each
 * iteration:
 *
 * 1. The LLM reasons about the current state (THOUGHT)
 * 2. It either calls a tool (ACTION) or gives the final answer
 * 3. If a tool was called, the result is injected as OBSERVATION
 * 4. The loop repeats until a final answer or [maxIterations]
 *
 * This strategy is ideal for tasks requiring step-by-step reasoning
 * and tool usage, such as binary analysis, debugging, or research.
 */
class ReActStrategy : AgentStrategy {

    override val name: String = "ReAct"

    companion object {
        /** The system prompt supplement that instructs the LLM to follow ReAct. */
        val REACT_INSTRUCTION: String = """
            You are a ReAct-style agent. You MUST follow this exact format in EVERY response:
            
            **Thought:** <your reasoning about the current state and what to do next>
            
            Then choose EXACTLY ONE of:
            - **Action:** followed by a JSON tool call (see format below)
            - **Final Answer:** <your final conclusion when you have enough information>
            
            For Action, you MUST include a JSON tool call in this EXACT format:
            ```json
            {"tool_call": {"name": "<tool_name>", "arguments": {<key>: <value>, ...}}}
            ```
            
            IMPORTANT RULES:
            1. ALWAYS start with **Thought:** — never skip reasoning.
            2. **Action:** MUST be followed by a JSON code block containing "tool_call". 
               Do NOT just describe what you want to do in natural language — the system 
               can only understand the JSON format above.
            3. After receiving an observation, reason again with **Thought:** before your next step.
            4. When you have enough information to answer, respond with **Final Answer:**.
            5. Do NOT make up information — use tools to verify.
            6. If a tool call fails, reason about the failure and try a different approach 
               using the correct JSON format.
            7. Never write "Action: <natural language description>". Always write 
               "Action:" followed by ```json {"tool_call": ...} ```.
        """.trimIndent()
    }

    override fun execute(ctx: StrategyContext): AgentResult {
        val logger = ctx.logger

        // Write system prompt to transcript (only on first iteration)
        ctx.transcript?.writeSystemPrompt(ctx.buildEffectiveSystemPrompt(REACT_INSTRUCTION))

        while (ctx.stats.iterations < ctx.maxIterations) {
            ctx.stats.iterations++
            logger.debug("[ReAct] iteration ${ctx.stats.iterations}/${ctx.maxIterations}")

            val systemPrompt = ctx.buildEffectiveSystemPrompt(REACT_INSTRUCTION)

            val completion = ctx.callLLM(systemPrompt) ?: return ctx.stats.toResult(
                output = "Agent error: ${ctx.stats.lastError}",
                stopReason = StopReason.ERROR
            )

            completion.tokenUsage?.let { usage ->
                ctx.stats.totalInputTokens += usage.inputTokenCount
                ctx.stats.totalOutputTokens += usage.outputTokenCount
            }

            val assistantText = completion.content
            ctx.memory.addAssistantMessage(assistantText)

            logger.info("[ReAct] Assistant (${assistantText.length} chars): ${assistantText.take(300)}...")
            ctx.transcript?.writeAssistantMessage(assistantText, ctx.stats.iterations)

            // Parse tool call (native first, then text-based fallback)
            val toolCall = ToolCallParser.parseFromCompletion(completion)
                ?: ToolCallParser.parse(assistantText)

            if (toolCall != null) {
                // Action phase — execute tool
                logger.info("[ReAct] Action: ${toolCall.name}(${toolCall.argumentsJson.take(200)})")
                val observation = ctx.executeTool(toolCall)

                // Inject as structured observation
                val obsMessage = "**Observation:** $observation"
                ctx.memory.addToolMessage(
                    toolCallId = toolCall.callId,
                    toolName = toolCall.name,
                    args = toolCall.argumentsJson,
                    result = obsMessage
                )

                logger.debug("[ReAct] Observation: ${observation.take(200)}...")
            } else {
                // No tool call detected — check if LLM explicitly gave a Final Answer
                val finalAnswer = extractFinalAnswer(assistantText)

                if (finalAnswer != null) {
                    // LLM explicitly signaled it's done
                    ctx.updateSessionStatus("completed")
                    val result = ctx.stats.toResult(
                        output = finalAnswer,
                        stopReason = StopReason.COMPLETED
                    )
                    ctx.transcript?.writeSessionEnd(result)
                    return result
                }

                // LLM did not provide a Final Answer nor a valid tool call.
                logger.warn("[ReAct] No tool call or final answer detected in response. " +
                    "Text preview: ${assistantText.take(500)}")

                val toolNames = ctx.toolRegistry.names().joinToString(", ")
                val reminder = "Your previous response did not contain a valid tool call or a **Final Answer:**.\n\n" +
                    "You MUST use this exact JSON format for tool calls:\n" +
                    "```json\n{\"tool_call\": {\"name\": \"<tool_name>\", \"arguments\": {<args>}}}\n```\n\n" +
                    "Available tools: $toolNames\n\n" +
                    "Example:\n" +
                    "```json\n{\"tool_call\": {\"name\": \"list_modules\", \"arguments\": {}}}\n```\n\n" +
                    "Please respond with **Thought:** followed by either a valid **Action:** with the JSON above, " +
                    "or a **Final Answer:** if you have enough information."
                ctx.memory.addUserMessage(reminder)
                ctx.transcript?.writeFormatReminder(reminder)
            }
        }

        // Max iterations
        logger.warn("[ReAct] reached max iterations (${ctx.maxIterations})")
        ctx.updateSessionStatus("error")
        val result = ctx.stats.toResult(
            output = extractLastAnswer(ctx.memory)
                ?: "Agent reached maximum iterations without producing a final answer.",
            stopReason = StopReason.MAX_ITERATIONS
        )
        ctx.transcript?.writeSessionEnd(result)
        return result
    }

    /** Extract the Final Answer portion from a ReAct response. */
    private fun extractFinalAnswer(text: String): String? {
        val patterns = listOf(
            Regex("""(?i)\*\*Final Answer:\*\*\s*(.*)""", RegexOption.DOT_MATCHES_ALL),
            Regex("""(?i)Final Answer:\s*(.*)""", RegexOption.DOT_MATCHES_ALL),
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val answer = match.groupValues[1].trim()
            if (answer.isNotBlank()) return answer
        }
        return null
    }

    private fun extractLastAnswer(memory: ChatMemory): String? {
        return memory.messages().lastOrNull { it.first == "assistant" }?.second
    }
}

// ============================================================
//  Plan-Execute Strategy
// ============================================================

/**
 * Plan-Execute strategy.
 *
 * The agent works in two distinct phases:
 *
 * **Phase 1 — Planning:**
 * The LLM is asked to produce a numbered plan of steps. Each step
 * describes a goal and optionally a tool to use. The plan is stored
 * in [MemoryManager] as type [MemoryType.PLAN].
 *
 * **Phase 2 — Execution:**
 * The agent iterates through each planned step, executing tools and
 * reasoning about observations. After completing all steps (or if
 * execution fails), the agent reflects and optionally **re-plans**.
 *
 * ```
 * ┌───────────────────────────────────────┐
 * │  PLAN Phase                           │
 * │  Step 1: List all functions           │
 * │  Step 2: Decompile the entry point    │
 * │  Step 3: Identify crypto patterns     │
 * ├───────────────────────────────────────┤
 * │  EXECUTE Phase                        │
 * │  ▶ Step 1: list_functions()           │
 * │    → Observation: main, foo, bar...   │
 * │  ▶ Step 2: decompile("0x401234")      │
 * │    → Observation: int main(){...}     │
 * │  ▶ Step 3: search_strings("encrypt")  │
 * │    → Observation: AES_encrypt found   │
 * ├───────────────────────────────────────┤
 * │  REFLECT Phase                        │
 * │  Final Answer: This binary is...      │
 * └───────────────────────────────────────┘
 * ```
 *
 * This strategy excels at complex, multistep tasks where upfront
 * planning reduces meandering and wasted tool calls.
 */
class PlanExecuteStrategy(
    /** Maximum number of re-planning cycles before forcing completion. */
    val maxReplanCycles: Int = 1,

    /** Whether to include step numbers in the execution prompt. */
    val includeStepNumbers: Boolean = true
) : AgentStrategy {

    override val name: String = "Plan-Execute"

    companion object {
        val PLANNING_INSTRUCTION: String = """
            You are a Plan-Execute agent. Your first task is to create a structured plan.
            
            Create a numbered plan with clear steps. For each step, specify:
            - What you want to accomplish
            - Which tool you will use (if any)
            - What information you expect to gain
            
            Format your plan as:
            ```
            ## Plan
            1. [Step description] — Tool: <tool_name> — Expected: <what you'll learn>
            2. [Step description] — Tool: <tool_name> — Expected: <what you'll learn>
            ...
            ```
            
            Rules:
            1. Be specific about which tool each step uses.
            2. Steps should be ordered by dependency — don't plan to use results you haven't gathered yet.
            3. Keep the plan concise (3-8 steps typically).
            4. If the task is simple, a 1-2 step plan is fine.
            5. Do NOT execute any tools yet — just create the plan.
        """.trimIndent()

        val EXECUTION_INSTRUCTION: String = """
            You are now in the EXECUTION phase. Follow the plan step by step.
            
            Current step: {step}
            
            For each step:
            1. Call the appropriate tool using this format:
            ```json
            {"tool_call": {"name": "<tool_name>", "arguments": {<key>: <value>, ...}}}
            ```
            2. After receiving the observation, briefly note what you learned.
            3. If you cannot complete a step, explain why and move to the next.
            4. If all steps are complete or you have enough information, respond with **Final Answer:** followed by your conclusion.
            
            If the current step's tool fails or the observation suggests the plan needs adjustment, say **Replan Needed:** and explain what changed.
        """.trimIndent()

        val REFLECTION_INSTRUCTION: String = """
            You have completed the execution phase. Now reflect on the results.
            
            Based on all observations gathered:
            1. Summarize the key findings.
            2. Determine if the original goal has been achieved.
            3. If not, suggest what additional steps would be needed.
            
            Provide your final answer starting with **Final Answer:**.
        """.trimIndent()
    }

    /** Represents a parsed plan step. */
    data class PlanStep(
        val index: Int,
        val description: String,
        val tool: String?,
        val expected: String?
    )

    override fun execute(ctx: StrategyContext): AgentResult {
        val logger = ctx.logger
        var replanCycle = 0

        // Write system prompt to transcript at start
        ctx.transcript?.writeSystemPrompt(ctx.buildEffectiveSystemPrompt(PLANNING_INSTRUCTION))

        while (replanCycle <= maxReplanCycles) {
            // ── Phase 1: Planning ──────────────────────────────────────
            val plan = if (replanCycle == 0) {
                logger.info("[PlanExec] Phase 1: Planning (cycle $replanCycle)")
                createPlan(ctx)
            } else {
                logger.info("[PlanExec] Re-planning (cycle $replanCycle)")
                replan(ctx)
            }

            if (plan.isEmpty()) {
                logger.warn("[PlanExec] Failed to create a plan, falling back to direct answer")
                ctx.updateSessionStatus("completed")
                val result = ctx.stats.toResult(
                    output = "Agent could not formulate a plan for this task.",
                    stopReason = StopReason.ERROR
                )
                ctx.transcript?.writeSessionEnd(result)
                return result
            }

            // Store plan in memory
            if (ctx.memoryManager != null) {
                val planText = plan.mapIndexed { i, s ->
                    "${i + 1}. ${s.description}" + (s.tool?.let { " [Tool: $it]" } ?: "")
                }.joinToString("\n")
                ctx.memoryManager.remember(
                    content = planText,
                    type = MemoryType.PLAN,
                    scope = MemoryScope.SESSION,
                    key = "plan:cycle$replanCycle",
                    importance = 0.8
                )
            }

            logger.info("[PlanExec] Plan created with ${plan.size} steps: " +
                plan.joinToString("; ") { it.description })

            // ── Phase 2: Execution ─────────────────────────────────────
            when (val executionResult = executePlan(ctx, plan)) {
                is ExecResult.Completed -> {
                    // ── Phase 3: Reflection ────────────────────────────
                    val finalAnswer = reflect(ctx)
                    ctx.updateSessionStatus("completed")
                    val result = ctx.stats.toResult(
                        output = finalAnswer,
                        stopReason = StopReason.COMPLETED
                    )
                    ctx.transcript?.writeSessionEnd(result)
                    return result
                }
                is ExecResult.ReplanNeeded -> {
                    replanCycle++
                    logger.info("[PlanExec] Replan requested: ${executionResult.reason}")
                    continue
                }
                is ExecResult.MaxIterations -> {
                    ctx.updateSessionStatus("error")
                    val result = ctx.stats.toResult(
                        output = extractLastAnswer(ctx.memory)
                            ?: "Agent reached maximum iterations during plan execution.",
                        stopReason = StopReason.MAX_ITERATIONS
                    )
                    ctx.transcript?.writeSessionEnd(result)
                    return result
                }
                is ExecResult.Error -> {
                    ctx.updateSessionStatus("error")
                    val result = ctx.stats.toResult(
                        output = "Agent error: ${executionResult.message}",
                        stopReason = StopReason.ERROR
                    )
                    ctx.transcript?.writeSessionEnd(result)
                    return result
                }
            }
        }

        // Exceeded max replan cycles
        logger.warn("[PlanExec] Exceeded max replan cycles ($maxReplanCycles)")
        val finalAnswer = reflect(ctx)
        ctx.updateSessionStatus("completed")
        val result = ctx.stats.toResult(
            output = finalAnswer,
            stopReason = StopReason.COMPLETED
        )
        ctx.transcript?.writeSessionEnd(result)
        return result
    }

    // ---- Phase 1: Planning ────────────────────────────────────────────

    private fun createPlan(ctx: StrategyContext): List<PlanStep> {
        return requestPlan(ctx, PLANNING_INSTRUCTION)
    }

    private fun replan(ctx: StrategyContext): List<PlanStep> {
        val replanPrompt = """
            The previous plan needs to be adjusted based on new observations.
            Create an updated plan that accounts for what we've learned so far.
            
            $PLANNING_INSTRUCTION
        """.trimIndent()
        return requestPlan(ctx, replanPrompt)
    }

    private fun requestPlan(ctx: StrategyContext, instruction: String): List<PlanStep> {
        val systemPrompt = ctx.buildEffectiveSystemPrompt(instruction)

        val completion = ctx.callLLM(systemPrompt) ?: return emptyList()
        ctx.stats.iterations++
        completion.tokenUsage?.let { usage ->
            ctx.stats.totalInputTokens += usage.inputTokenCount
            ctx.stats.totalOutputTokens += usage.outputTokenCount
        }

        val planText = completion.content
        ctx.memory.addAssistantMessage(planText)

        return parsePlan(planText)
    }

    /** Parse a numbered plan from the LLM's text. */
    internal fun parsePlan(text: String): List<PlanStep> {
        val steps = mutableListOf<PlanStep>()
        // Match patterns like "1. description — Tool: name — Expected: info"
        // or simpler "1. description"
        val stepRegex = Regex(
            """^\s*(\d+)\.\s*(.+?)(?:\s*[—–-]\s*Tool:\s*(\S+))?(?:\s*[—–-]\s*Expected:\s*(.+))?\s*$""",
            RegexOption.MULTILINE
        )
        for (match in stepRegex.findAll(text)) {
            val index = match.groupValues[1].toIntOrNull() ?: continue
            val description = match.groupValues[2].trim()
            if (description.isBlank()) continue
            steps.add(PlanStep(
                index = index,
                description = description,
                tool = match.groupValues[3].trim().ifBlank { null },
                expected = match.groupValues[4].trim().ifBlank { null }
            ))
        }
        return steps
    }

    // ---- Phase 2: Execution ───────────────────────────────────────────

    private sealed class ExecResult {
        object Completed : ExecResult()
        class ReplanNeeded(val reason: String) : ExecResult()
        object MaxIterations : ExecResult()
        class Error(val message: String) : ExecResult()
    }

    private fun executePlan(ctx: StrategyContext, plan: List<PlanStep>): ExecResult {
        val logger = ctx.logger

        for ((stepIdx, step) in plan.withIndex()) {
            if (ctx.stats.iterations >= ctx.maxIterations) {
                return ExecResult.MaxIterations
            }

            logger.info("[PlanExec] Executing step ${stepIdx + 1}/${plan.size}: ${step.description}")

            // Build the step-specific prompt
            val stepContext = buildString {
                append("## Plan\n")
                plan.forEachIndexed { i, s ->
                    val marker = when {
                        i < stepIdx -> "✓"
                        i == stepIdx -> "▶"
                        else -> "○"
                    }
                    append("$marker ${i + 1}. ${s.description}")
                    s.tool?.let { append(" [Tool: $it]") }
                    append("\n")
                }
                append("\n")
            }

            val execInstruction = EXECUTION_INSTRUCTION.replace(
                "{step}",
                "Step ${step.index}: ${step.description}" +
                    (step.tool?.let { " (suggested tool: $it)" } ?: "")
            )

            val systemPrompt = ctx.buildEffectiveSystemPrompt(
                "$stepContext\n$execInstruction"
            )

            // Execute this step (may need multiple LLM calls for one step)
            var stepIterations = 0
            val maxStepIterations = 3  // safety limit per step

            while (stepIterations < maxStepIterations && ctx.stats.iterations < ctx.maxIterations) {
                stepIterations++
                ctx.stats.iterations++

                val completion = ctx.callLLM(systemPrompt) ?: return ExecResult.Error(
                    ctx.stats.lastError ?: "LLM call failed"
                )
                completion.tokenUsage?.let { usage ->
                    ctx.stats.totalInputTokens += usage.inputTokenCount
                    ctx.stats.totalOutputTokens += usage.outputTokenCount
                }

                val assistantText = completion.content
                ctx.memory.addAssistantMessage(assistantText)
                ctx.transcript?.writeAssistantMessage(assistantText, ctx.stats.iterations)

                // Check for replan request
                if (assistantText.contains("Replan Needed:", ignoreCase = true)) {
                    val reason = assistantText.substringAfter("Replan Needed:").trim().take(500)
                    return ExecResult.ReplanNeeded(reason)
                }

                // Parse tool call (native first, then text-based fallback)
                val toolCall = ToolCallParser.parseFromCompletion(completion)
                    ?: ToolCallParser.parse(assistantText)

                if (toolCall != null) {
                    logger.info("[PlanExec]   Tool: ${toolCall.name}(${toolCall.arguments})")
                    val observation = ctx.executeTool(toolCall)
                    ctx.memory.addToolMessage(
                        toolCallId = toolCall.callId,
                        toolName = toolCall.name,
                        args = toolCall.argumentsJson,
                        result = "**Observation (Step ${step.index}):** $observation"
                    )
                    // Continue loop to let the agent process the observation
                } else {
                    // No tool call — check for final answer
                    val finalAnswer = extractFinalAnswer(assistantText)
                    if (finalAnswer != null) {
                        return ExecResult.Completed
                    }
                    // Otherwise, the step is done without a tool call; move on
                    break
                }
            }
        }

        // All steps executed
        return ExecResult.Completed
    }

    // ---- Phase 3: Reflection ──────────────────────────────────────────

    private fun reflect(ctx: StrategyContext): String {
        val systemPrompt = ctx.buildEffectiveSystemPrompt(REFLECTION_INSTRUCTION)
        ctx.stats.iterations++

        val completion = ctx.callLLM(systemPrompt) ?: return extractLastAnswer(ctx.memory)
            ?: "Agent completed but reflection failed."

        completion.tokenUsage?.let { usage ->
            ctx.stats.totalInputTokens += usage.inputTokenCount
            ctx.stats.totalOutputTokens += usage.outputTokenCount
        }

        val reflection = completion.content
        ctx.memory.addAssistantMessage(reflection)

        // Store reflection as insight
        ctx.memoryManager?.remember(
            content = reflection.take(1000),
            type = MemoryType.INSIGHT,
            scope = MemoryScope.SESSION,
            key = "reflection",
            importance = 0.9
        )

        return extractFinalAnswer(reflection) ?: reflection
    }

    // ---- Helpers ──────────────────────────────────────────────────────

    private fun extractFinalAnswer(text: String): String? {
        val patterns = listOf(
            Regex("""(?i)\*\*Final Answer:\*\*\s*(.*)""", RegexOption.DOT_MATCHES_ALL),
            Regex("""(?i)Final Answer:\s*(.*)""", RegexOption.DOT_MATCHES_ALL),
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val answer = match.groupValues[1].trim()
            if (answer.isNotBlank()) return answer
        }
        return null
    }

    private fun extractLastAnswer(memory: ChatMemory): String? {
        return memory.messages().lastOrNull { it.first == "assistant" }?.second
    }
}

// ============================================================
//  LoopStats → AgentResult convenience
// ============================================================

private fun LoopStats.toResult(output: String, stopReason: StopReason): AgentResult = AgentResult(
    output = output,
    iterations = iterations,
    toolCallsMade = toolCallsMade,
    totalInputTokens = totalInputTokens,
    totalOutputTokens = totalOutputTokens,
    stopReason = stopReason
)

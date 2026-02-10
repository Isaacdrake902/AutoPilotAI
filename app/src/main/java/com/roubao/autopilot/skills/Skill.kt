package com.roubao.autopilot.skills

import com.roubao.autopilot.tools.ToolManager

/**
 * Execution Type
 */
enum class ExecutionType {
    /** Delegation: Open App via DeepLink */
    DELEGATION,
    /** GUI Automation: Screenshot-Action Loop */
    GUI_AUTOMATION
}

/**
 * Related App Configuration
 */
data class RelatedApp(
    val packageName: String,
    val name: String,
    val type: ExecutionType,
    val deepLink: String? = null,
    val steps: List<String>? = null,
    val priority: Int = 0,
    val description: String? = null
)

/**
 * Skill Parameter Definition
 */
data class SkillParam(
    val name: String,
    val type: String,           // string, int, boolean
    val description: String,
    val required: Boolean = false,
    val defaultValue: Any? = null,
    val examples: List<String> = emptyList()
)

/**
 * Skill Configuration (Intent Definition)
 */
data class SkillConfig(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val keywords: List<String>,
    val params: List<SkillParam>,
    val relatedapp: List<RelatedApp>,
    val promptHint: String? = null  // Prompt constraint e.g. "Content must be under 100 words"
)

/**
 * Skill Execution Plan
 *
 * Execution scheme generated based on user intent and locally installed Apps
 */
data class ExecutionPlan(
    val skillId: String,
    val skillName: String,
    val app: RelatedApp,
    val params: Map<String, Any?>,
    val isInstalled: Boolean,
    val promptHint: String? = null  // Prompt constraint
) {
    /**
     * Generate context info for Agent
     */
    fun toAgentContext(): String {
        return buildString {
            append("【Task】${skillName}\n")
            append("【Target App】${app.name} (${app.packageName})\n")
            append("【Execution Mode】${if (app.type == ExecutionType.DELEGATION) "DeepLink Shortcut" else "GUI Automation"}\n")

            if (!promptHint.isNullOrBlank()) {
                append("【Important】⚠️ $promptHint\n")
            }

            if (!app.steps.isNullOrEmpty()) {
                append("【Steps】\n")
                app.steps.forEachIndexed { index, step ->
                    append("  ${index + 1}. $step\n")
                }
            }

            if (params.isNotEmpty()) {
                append("【Params】\n")
                params.forEach { (key, value) ->
                    if (key != "_raw_query" && value != null) {
                        append("  $key: $value\n")
                    }
                }
            }
        }
    }
}

/**
 * Skill Execution Result
 */
sealed class SkillResult {
    /**
     * Delegation Success: Opened via DeepLink
     */
    data class Delegated(
        val app: RelatedApp,
        val deepLink: String,
        val message: String
    ) : SkillResult()

    /**
     * GUI Automation: Return execution plan to Agent
     */
    data class NeedAutomation(
        val plan: ExecutionPlan,
        val message: String
    ) : SkillResult()

    /**
     * Failed
     */
    data class Failed(
        val error: String,
        val suggestion: String? = null
    ) : SkillResult()

    /**
     * No available app
     */
    data class NoAvailableApp(
        val skillName: String,
        val requiredapp: List<String>
    ) : SkillResult()
}

/**
 * Skill Intent Matcher
 */
class Skill(val config: SkillConfig) {

    /**
     * Calculate match score with user query
     * @return Score between 0-1
     */
    fun matchScore(query: String): Float {
        val lowerQuery = query.lowercase()

        // Exact keyword match (Highest score)
        for (keyword in config.keywords) {
            if (lowerQuery.contains(keyword.lowercase())) {
                return 0.9f
            }
        }

        // Match Skill Name
        if (lowerQuery.contains(config.name.lowercase())) {
            return 0.8f
        }

        // Fuzzy match description
        val descWords = config.description.split(" ", " ", "、", "/")
        val matchedWords = descWords.count { lowerQuery.contains(it.lowercase()) }
        if (matchedWords > 0) {
            return (0.3f + 0.3f * matchedWords / descWords.size).coerceAtMost(0.7f)
        }

        return 0f
    }

    /**
     * Extract parameters from query
     */
    fun extractParams(query: String): Map<String, Any?> {
        val params = mutableMapOf<String, Any?>()

        for (param in config.params) {
            when (param.name) {
                "food", "item", "song", "book", "keyword" -> {
                    // Extract key content (remove intent keywords)
                    var content = query
                    for (kw in config.keywords) {
                        content = content.replace(kw, "", ignoreCase = true)
                    }
                    content = content.trim()
                    if (content.isNotEmpty()) {
                        params[param.name] = content
                    }
                }
                "destination", "address", "location" -> {
                    // Extract destination
                    val patterns = listOf(
                        "to (.+?)$",
                        "go to (.+?)$",
                        "navigate to (.+?)$",
                        "how to get to (.+?)"
                    )
                    for (pattern in patterns) {
                        try {
                            val match = Regex(pattern).find(query)
                            if (match != null) {
                                params[param.name] = match.groupValues[1].trim()
                                break
                            }
                        } catch (e: Exception) {}
                    }
                    // If English patterns fail, try fallback/Chinese patterns if needed, but for now stick to simple extraction or keep original
                }
                "contact" -> {
                    // Extract contact
                    val patterns = listOf(
                        "send to (.+?)$",
                        "tell (.+?)$"
                    )
                    for (pattern in patterns) {
                        try {
                            val match = Regex(pattern).find(query)
                            if (match != null) {
                                params[param.name] = match.groupValues[1].trim()
                                break
                            }
                        } catch (e: Exception) {}
                    }
                }
                "message", "content", "prompt" -> {
                    // Save original query as content
                    params[param.name] = query
                }
                "time" -> {
                    // Extract time (Simple regex for now)
                    val patterns = listOf(
                        "(\\d{1,2}:\\d{2})",
                        "(\\d{1,2} [ap]m)"
                    )
                    for (pattern in patterns) {
                        val match = Regex(pattern).find(query)
                        if (match != null) {
                            params[param.name] = match.value
                            break
                        }
                    }
                }
            }

            // Set default value
            if (!params.containsKey(param.name) && param.defaultValue != null) {
                params[param.name] = param.defaultValue
            }
        }

        // Save raw query
        params["_raw_query"] = query

        return params
    }

    /**
     * Generate DeepLink (Param substitution)
     */
    fun generateDeepLink(app: RelatedApp, params: Map<String, Any?>): String {
        var deepLink = app.deepLink ?: return ""

        for ((key, value) in params) {
            if (value != null && key != "_raw_query") {
                deepLink = deepLink.replace("{$key}", value.toString())
            }
        }

        // Clean up unreplaced placeholders
        deepLink = deepLink.replace(Regex("\\{[^}]+\\}"), "")

        return deepLink
    }
}

/**
 * Skill Match Result
 */
data class SkillMatch(
    val skill: Skill,
    val score: Float,
    val params: Map<String, Any?>
)

/**
 * Available App Match Result
 */
data class AvailableAppMatch(
    val skill: Skill,
    val app: RelatedApp,
    val params: Map<String, Any?>,
    val score: Float
)

/**
 * LLM Intent Match Result
 */
data class LLMIntentMatch(
    val skillId: String,
    val confidence: Float,
    val reasoning: String
)

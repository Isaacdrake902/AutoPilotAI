package com.roubao.autopilot.skills

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.roubao.autopilot.controller.AppScanner
import com.roubao.autopilot.tools.ToolManager
import com.roubao.autopilot.vlm.VLMClient
import org.json.JSONObject

/**
 * Skill 管理器
 *
 * 作为 Skill 层的统一入口 负责:
 * - Initialize和加载 Skills
 * - 意图识别和 Skill 匹配（使用 LLM 语义理解）
 * - 基于已安装 App 选择最佳执行方案
 * - Skill 执行调度
 */
class SkillManager private constructor(
    private val context: Context,
    private val toolManager: ToolManager,
    private val appScanner: AppScanner
) {

    private val registry: SkillRegistry = SkillRegistry.init(context, appScanner)

    // VLM 客户端（for意图匹配）
    private var vlmClient: VLMClient? = null

    /**
     * Settings VLM 客户端（for LLM 意图匹配）
     */
    fun setVLMClient(client: VLMClient) {
        this.vlmClient = client
    }

    /**
     * Initialize:加载 Skills 配置
     */
    fun initialize() {
        val loadedCount = registry.loadFromAssets("skills.json")
        println("[SkillManager] Loaded $loadedCount items Skills")
    }

    /**
     * Refresh installed apps list
     */
    fun refreshInstalledApps() {
        registry.refreshInstalledApps()
    }

    /**
     * Handle user intent (New: return best available app)
     *
     * @param query User input
     * @return Available app match result, or null if none
     */
    fun matchAvailableApp(query: String): AvailableAppMatch? {
        return registry.getBestAvailableApp(query, minScore = 0.3f)
    }

    /**
     * Get all matching available apps
     */
    fun matchAllAvailableApps(query: String): List<AvailableAppMatch> {
        return registry.matchAvailableApps(query, minScore = 0.2f)
    }

    /**
     * 使用 LLM 进行意图匹配（异steps方法）
     *
     * @param query 用户Input
     * @return 匹配的 Skill IDe.g.果No match forreturn to null
     */
    suspend fun matchIntentWithLLM(query: String): LLMIntentMatch? {
        val client = vlmClient ?: return null

        // 构建 Skills list描述
        val skillsInfo = buildString {
            append("可用技能list:\n")
            for (skill in registry.getAll()) {
                val config = skill.config
                // 只展示有已安装app的 Skill
                val installedapp = config.relatedapp.filter { registry.isAppInstalled(it.packageName) }
                if (installedapp.isNotEmpty()) {
                    append("- ID: ${config.id}\n")
                    append("  名称: ${config.name}\n")
                    append("  描述: ${config.description}\n")
                    append("  关键词: ${config.keywords.joinToString(", ")}\n")
                    append("  可用app: ${installedapp.joinToString(", ") { it.name }}\n\n")
                }
            }
        }

        val prompt = """你是一items意图识别助手.根据用户Input 判断最匹配的技能.

$skillsInfo

用户Input: "$query"

请m析用户意图 return to JSON 格式:
{
  "skill_id": "匹配的技能IDe.g.果No match forreturn to null",
  "confidence": 0.0-1.0 的置信度,
  "reasoning": "简短的匹配理由"
}

注意:
1. 只return to JSON 不要有其他文字
2. e.g.果用户意图明确匹配某items技能 即使措辞不同也要识别
3. e.g.果确实No match for的技能 skill_id return to null
4. e.g."点items汉堡"、"帮我Order Takeout"、"想吃炸鸡" 都应该匹配 order_food
5. "附近好吃的"、"Recommended美食" 应该匹配 find_food"""

        return try {
            val result = client.predict(prompt)
            result.getOrNull()?.let { response ->
                parseIntentResponse(response)
            }
        } catch (e: Exception) {
            println("[SkillManager] LLM 意图匹配Failed: ${e.message}")
            null
        }
    }

    /**
     * 解析 LLM return to的意图匹配结果
     */
    private fun parseIntentResponse(response: String): LLMIntentMatch? {
        return try {
            // 提取 JSON（可能被 markdown 包裹）
            val jsonStr = response
                .replace("```json", "")
                .replace("```", "")
                .trim()

            val json = JSONObject(jsonStr)
            val skillId = json.optString("skill_id", null)?.takeIf { it != "null" && it.isNotEmpty() }
            val confidence = json.optDouble("confidence", 0.0).toFloat()
            val reasoning = json.optString("reasoning", "")

            if (skillId != null) {
                LLMIntentMatch(
                    skillId = skillId,
                    confidence = confidence,
                    reasoning = reasoning
                )
            } else {
                null
            }
        } catch (e: Exception) {
            println("[SkillManager] 解析意图ResponseFailed: ${e.message}")
            null
        }
    }

    /**
     * 使用 LLM 匹配意图并return to可用app（组合方法）
     */
    suspend fun matchAvailableAppWithLLM(query: String): AvailableAppMatch? {
        // 先尝试 LLM 匹配
        val llmMatch = matchIntentWithLLM(query)

        if (llmMatch != null && llmMatch.confidence >= 0.5f) {
            println("[SkillManager] LLM 匹配: ${llmMatch.skillId} (置信度: ${llmMatch.confidence})")
            println("[SkillManager] 理由: ${llmMatch.reasoning}")

            // 获取对应的 Skill 和已安装app
            val skill = registry.get(llmMatch.skillId)
            if (skill != null) {
                println("[SkillManager] find Skill: ${skill.config.name}")
                println("[SkillManager] 关联app: ${skill.config.relatedapp.map { "${it.name}(${it.packageName})" }}")

                // 检查每apps的安装Status
                for (app in skill.config.relatedapp) {
                    val installed = registry.isAppInstalled(app.packageName)
                    println("[SkillManager] ${app.name}(${app.packageName}): ${if (installed) "已安装" else "未安装"}")
                }

                val availableApp = skill.config.relatedapp
                    .filter { registry.isAppInstalled(it.packageName) }
                    .maxByOrNull { it.priority }

                if (availableApp != null) {
                    println("[SkillManager] 选中app: ${availableApp.name}")
                    val params = skill.extractParams(query)
                    return AvailableAppMatch(
                        skill = skill,
                        app = availableApp,
                        params = params,
                        score = llmMatch.confidence
                    )
                } else {
                    println("[SkillManager] 没有可用app（都未安装）")
                }
            } else {
                println("[SkillManager] 未find Skill: ${llmMatch.skillId}")
            }
        }

        // e.g.果 LLM 匹配Failed 回退到关键词匹配
        println("[SkillManager] LLM 未匹配或无可用app 回退到关键词匹配")
        return matchAvailableApp(query)
    }

    /**
     * Generate context prompt for Agent (Use LLM match)
     */
    suspend fun generateAgentContextWithLLM(query: String): String {
        // Match with LLM
        val match = matchAvailableAppWithLLM(query)

        if (match == null) {
            return "No relevant skill or available app found. Please use general GUI automation."
        }

        return buildString {
            val config = match.skill.config
            val app = match.app

            append("Matched skill based on user intent:\n\n")
            append("【${config.name}】(Confidence: ${(match.score * 100).toInt()}%)\n")
            append("Description: ${config.description}\n\n")

            // Show prompt constraints
            if (!config.promptHint.isNullOrBlank()) {
                append("⚠️ Important: ${config.promptHint}\n\n")
            }

            val typeLabel = when (app.type) {
                ExecutionType.DELEGATION -> "🚀Delegation(Fast)"
                ExecutionType.GUI_AUTOMATION -> "🤖GUI Auto"
            }

            append("Recommended App: ${app.name} $typeLabel\n")

            if (app.type == ExecutionType.DELEGATION && app.deepLink != null) {
                append("DeepLink: ${app.deepLink}\n")
            }

            if (!app.steps.isNullOrEmpty()) {
                append("Steps: ${app.steps.joinToString(" → ")}\n")
            }

            app.description?.let {
                append("Note: $it\n")
            }

            append("\nSuggestion:")
            if (app.type == ExecutionType.DELEGATION) {
                append("Use DeepLink to open ${app.name} directly for fast execution.")
            } else {
                append("Use GUI Automation to operate ${app.name}.")
            }
        }
    }

    /**
     * Execute Skill (Core Method)
     *
     * @param match Available app match result
     * @return Execution result
     */
    suspend fun execute(match: AvailableAppMatch): SkillResult {
        val skill = match.skill
        val app = match.app
        val params = match.params

        println("[SkillManager] Execute: ${skill.config.name} -> ${app.name} (${app.type})")

        return when (app.type) {
            ExecutionType.DELEGATION -> {
                // Delegation Mode: Open via DeepLink
                executeDelegation(skill, app, params)
            }
            ExecutionType.GUI_AUTOMATION -> {
                // GUI Automation Mode: Return execution plan
                executeAutomation(skill, app, params)
            }
        }
    }

    /**
     * Execute Delegation (DeepLink)
     */
    private fun executeDelegation(
        skill: Skill,
        app: RelatedApp,
        params: Map<String, Any?>
    ): SkillResult {
        val deepLink = skill.generateDeepLink(app, params)

        if (deepLink.isEmpty()) {
            return SkillResult.Failed(
                error = "Cannot generate DeepLink",
                suggestion = "Try GUI Automation"
            )
        }

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Explicitly set package name to avoid system picker
                setPackage(app.packageName)
            }
            context.startActivity(intent)

            SkillResult.Delegated(
                app = app,
                deepLink = deepLink,
                message = "Opened ${app.name}"
            )
        } catch (e: Exception) {
            // Fallback to implicit intent
            println("[SkillManager] Explicit package failed, trying implicit: ${e.message}")
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)

                SkillResult.Delegated(
                    app = app,
                    deepLink = deepLink,
                    message = "Opened ${app.name} (Implicit)"
                )
            } catch (e2: Exception) {
                SkillResult.Failed(
                    error = "Open ${app.name} Failed: ${e2.message}",
                    suggestion = "Please confirm app is installed and supports DeepLink"
                )
            }
        }
    }

    /**
     * Execute GUI Automation (Return Plan)
     */
    private fun executeAutomation(
        skill: Skill,
        app: RelatedApp,
        params: Map<String, Any?>
    ): SkillResult {
        val plan = ExecutionPlan(
            skillId = skill.config.id,
            skillName = skill.config.name,
            app = app,
            params = params,
            isInstalled = true,
            promptHint = skill.config.promptHint
        )

        return SkillResult.NeedAutomation(
            plan = plan,
            message = "Requires GUI Automation on ${app.name}"
        )
    }

    /**
     * Check if should use Fast Path
     *
     * Conditions:
     * 1. High confidence (score >= 0.8)
     * 2. Best app is Delegation type
     * 3. App is installed
     */
    fun shouldUseFastPath(query: String): AvailableAppMatch? {
        val match = matchAvailableApp(query) ?: return null

        // Only delegation + high score
        if (match.app.type == ExecutionType.DELEGATION && match.score >= 0.8f) {
            return match
        }

        return null
    }

    /**
     * Generate context prompt for Agent
     *
     * Includes: Matched intent, available apps, recommended steps
     */
    fun generateAgentContext(query: String): String {
        val matches = matchAllAvailableApps(query)

        if (matches.isEmpty()) {
            return "No relevant skill or available app found. Please use general GUI automation."
        }

        return buildString {
            append("Matched following available solutions based on user intent:\n\n")

            // Group by Skill
            val groupedBySkill = matches.groupBy { it.skill.config.id }

            for ((_, skillMatches) in groupedBySkill) {
                val firstMatch = skillMatches.first()
                val config = firstMatch.skill.config

                append("【${config.name}】(Confidence: ${(firstMatch.score * 100).toInt()}%)\n")

                for ((index, match) in skillMatches.withIndex()) {
                    val app = match.app
                    val typeLabel = when (app.type) {
                        ExecutionType.DELEGATION -> "🚀Delegation(Fast)"
                        ExecutionType.GUI_AUTOMATION -> "🤖GUI Auto"
                    }

                    append("  ${index + 1}. ${app.name} $typeLabel (Priority: ${app.priority})\n")

                    if (app.type == ExecutionType.DELEGATION && app.deepLink != null) {
                        append("     DeepLink: ${app.deepLink}\n")
                    }

                    if (!app.steps.isNullOrEmpty()) {
                        append("     Steps: ${app.steps.joinToString(" → ")}\n")
                    }

                    app.description?.let {
                        append("     Note: $it\n")
                    }
                }
                append("\n")
            }

            append("Suggestion: Prefer Delegation(🚀) for speed. If failed, use GUI Auto(🤖).")
        }
    }

    /**
     * 获取 Skill 信息
     */
    fun getSkillInfo(skillId: String): SkillConfig? {
        return registry.get(skillId)?.config
    }

    /**
     * 获取所有 Skills 描述（给 LLM）
     */
    fun getSkillsDescription(): String {
        return registry.getSkillsDescription()
    }

    /**
     * 获取所有 Skills
     */
    fun getAllSkills(): List<Skill> {
        return registry.getAll()
    }

    /**
     * 按m类获取 Skills
     */
    fun getSkillsByCategory(category: String): List<Skill> {
        return registry.getByCategory(category)
    }

    /**
     * 检查意图是否有可用app
     */
    fun hasAvailableApp(query: String): Boolean {
        return matchAvailableApp(query) != null
    }

    /**
     * 获取意图的所有关联app（不管是否安装）
     */
    fun getAllRelatedapp(query: String): List<RelatedApp> {
        val skillMatch = registry.matchBest(query) ?: return emptyList()
        return skillMatch.skill.config.relatedapp
    }

    /**
     * 获取缺失的appRecommended（用户没装但可以装的）
     */
    fun getMissingAppSuggestions(query: String): List<RelatedApp> {
        val skillMatch = registry.matchBest(query) ?: return emptyList()
        return skillMatch.skill.config.relatedapp
            .filter { !registry.isAppInstalled(it.packageName) }
            .sortedByDescending { it.priority }
    }

    companion object {
        @Volatile
        private var instance: SkillManager? = null

        fun init(context: Context, toolManager: ToolManager, appScanner: AppScanner): SkillManager {
            return instance ?: synchronized(this) {
                instance ?: SkillManager(context.applicationContext, toolManager, appScanner).also {
                    it.initialize()
                    instance = it
                }
            }
        }

        fun getInstance(): SkillManager {
            return instance ?: throw IllegalStateException("SkillManager 未Initialize 请先调用 init()")
        }

        fun isInitialized(): Boolean = instance != null
    }
}

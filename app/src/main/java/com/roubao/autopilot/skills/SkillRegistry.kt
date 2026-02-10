package com.roubao.autopilot.skills

import android.content.Context
import com.roubao.autopilot.controller.AppScanner
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Skill 注册表
 *
 * 管理所有 Skills 的注册、查找和匹配
 * 核心功能:
 * - 从 skills.json 加载意图定义
 * - 查询本地已安装 App 筛选可用app
 * - 根据优先级选择最佳执行方案
 */
class SkillRegistry private constructor(
    private val context: Context,
    private val appScanner: AppScanner
) {

    private val skills = mutableMapOf<String, Skill>()
    private val categoryIndex = mutableMapOf<String, MutableList<Skill>>()

    // Cached set of installed app package names (refreshed on start)
    private var installedPackages: Set<String> = emptySet()

    /**
     * Initialize: Refresh installed apps list
     */
    fun refreshInstalledApps() {
        val apps = appScanner.getApps()
        installedPackages = apps.map { it.packageName }.toSet()
        println("[SkillRegistry] Cached ${installedPackages.size} installed apps")

        // Debug: Check Meituan related apps
        val meituanApps = installedPackages.filter { it.contains("meituan") || it.contains("dianping") }
        println("[SkillRegistry] Meituan related apps: $meituanApps")

        // Check if Meituan DeepLink is available (indirect installation check)
        try {
            val pm = context.packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("beam://www.meituan.com/home")
            }
            val resolveInfo = pm.resolveActivity(intent, 0)
            if (resolveInfo != null) {
                val pkgName = resolveInfo.activityInfo.packageName
                println("[SkillRegistry] Meituan DeepLink available, package: $pkgName")
                if (!installedPackages.contains(pkgName)) {
                    installedPackages = installedPackages + pkgName
                    println("[SkillRegistry] Added $pkgName to installed list")
                }
            } else {
                println("[SkillRegistry] Meituan DeepLink unavailable")
            }
        } catch (e: Exception) {
            println("[SkillRegistry] Check Meituan failed: ${e.message}")
        }
    }

    /**
     * 检查包名是否已安装
     */
    fun isAppInstalled(packageName: String): Boolean {
        return installedPackages.contains(packageName)
    }

    /**
     * 从 assets/skills.json 加载 Skills
     */
    fun loadFromAssets(filename: String = "skills.json"): Int {
        try {
            val jsonString = context.assets.open(filename).bufferedReader().use { it.readText() }
            return loadFromJson(jsonString)
        } catch (e: IOException) {
            println("[SkillRegistry] 无法加载 $filename: ${e.message}")
            return 0
        }
    }

    /**
     * 从 JSON 字符串加载 Skills
     */
    fun loadFromJson(jsonString: String): Int {
        var loadedCount = 0
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val config = parseSkillConfig(obj)
                register(Skill(config))
                loadedCount++
            }
            println("[SkillRegistry] Loaded $loadedCount items Skills")
        } catch (e: Exception) {
            println("[SkillRegistry] JSON 解析Error: ${e.message}")
            e.printStackTrace()
        }
        return loadedCount
    }

    /**
     * 解析单items Skill 配置（新结构）
     */
    private fun parseSkillConfig(obj: JSONObject): SkillConfig {
        // 解析参数
        val params = mutableListOf<SkillParam>()
        val paramsArray = obj.optJSONArray("params")
        if (paramsArray != null) {
            for (i in 0 until paramsArray.length()) {
                val paramObj = paramsArray.getJSONObject(i)
                val examples = mutableListOf<String>()
                val examplesArray = paramObj.optJSONArray("examples")
                if (examplesArray != null) {
                    for (j in 0 until examplesArray.length()) {
                        examples.add(examplesArray.getString(j))
                    }
                }
                params.add(SkillParam(
                    name = paramObj.getString("name"),
                    type = paramObj.optString("type", "string"),
                    description = paramObj.optString("description", ""),
                    required = paramObj.optBoolean("required", false),
                    defaultValue = paramObj.opt("default"),
                    examples = examples
                ))
            }
        }

        // 解析关键词
        val keywords = mutableListOf<String>()
        val keywordsArray = obj.optJSONArray("keywords")
        if (keywordsArray != null) {
            for (i in 0 until keywordsArray.length()) {
                keywords.add(keywordsArray.getString(i))
            }
        }

        // 解析关联applist（新结构）
        val relatedapp = mutableListOf<RelatedApp>()
        val appsArray = obj.optJSONArray("related_apps")
        if (appsArray != null) {
            for (i in 0 until appsArray.length()) {
                val appObj = appsArray.getJSONObject(i)

                // 解析执行类型
                val typeStr = appObj.optString("type", "gui_automation")
                val type = when (typeStr.lowercase()) {
                    "delegation" -> ExecutionType.DELEGATION
                    else -> ExecutionType.GUI_AUTOMATION
                }

                // 解析操作steps
                val steps = mutableListOf<String>()
                val stepsArray = appObj.optJSONArray("steps")
                if (stepsArray != null) {
                    for (j in 0 until stepsArray.length()) {
                        steps.add(stepsArray.getString(j))
                    }
                }

                relatedapp.add(RelatedApp(
                    packageName = appObj.getString("package"),
                    name = appObj.getString("name"),
                    type = type,
                    deepLink = appObj.optString("deep_link", null)?.takeIf { it.isNotEmpty() },
                    steps = if (steps.isEmpty()) null else steps,
                    priority = appObj.optInt("priority", 0),
                    description = appObj.optString("description", null)?.takeIf { it.isNotEmpty() }
                ))
            }
        }

        return SkillConfig(
            id = obj.getString("id"),
            name = obj.getString("name"),
            description = obj.optString("description", ""),
            category = obj.optString("category", "通用"),
            keywords = keywords,
            params = params,
            relatedapp = relatedapp,
            promptHint = obj.optString("prompt_hint", null)?.takeIf { it.isNotEmpty() }
        )
    }

    /**
     * 注册 Skill
     */
    fun register(skill: Skill) {
        skills[skill.config.id] = skill

        // 更新m类索引
        val category = skill.config.category
        categoryIndex.getOrPut(category) { mutableListOf() }.add(skill)

        println("[SkillRegistry] 注册 Skill: ${skill.config.id} (${skill.config.relatedapp.size} 关联app)")
    }

    /**
     * 获取 Skill
     */
    fun get(id: String): Skill? = skills[id]

    /**
     * 获取所有 Skills
     */
    fun getAll(): List<Skill> = skills.values.toList()

    /**
     * 按m类获取 Skills
     */
    fun getByCategory(category: String): List<Skill> {
        return categoryIndex[category] ?: emptyList()
    }

    /**
     * 获取所有m类
     */
    fun getAllCategories(): List<String> = categoryIndex.keys.toList()

    /**
     * 匹配用户意图（基于关键词）
     */
    fun match(query: String, topK: Int = 3, minScore: Float = 0.3f): List<SkillMatch> {
        val matches = mutableListOf<SkillMatch>()

        for (skill in skills.values) {
            val score = skill.matchScore(query)
            if (score >= minScore) {
                val params = skill.extractParams(query)
                matches.add(SkillMatch(skill, score, params))
            }
        }

        return matches
            .sortedByDescending { it.score }
            .take(topK)
    }

    /**
     * 获取最佳匹配
     */
    fun matchBest(query: String, minScore: Float = 0.3f): SkillMatch? {
        return match(query, topK = 1, minScore = minScore).firstOrNull()
    }

    /**
     * Match intent and return available apps (Core Method)
     *
     * 1. Match user intent to Skill
     * 2. Filter installed related apps
     * 3. Sort by priority
     */
    fun matchAvailableApps(
        query: String,
        minScore: Float = 0.3f
    ): List<AvailableAppMatch> {
        val skillMatches = match(query, topK = 5, minScore = minScore)
        val results = mutableListOf<AvailableAppMatch>()

        for (skillMatch in skillMatches) {
            val skill = skillMatch.skill
            val params = skillMatch.params

            // Filter installed apps, sort by priority
            val availableApps = skill.config.relatedapp
                .filter { isAppInstalled(it.packageName) }
                .sortedByDescending { it.priority }

            for (app in availableApps) {
                results.add(AvailableAppMatch(
                    skill = skill,
                    app = app,
                    params = params,
                    score = skillMatch.score
                ))
            }
        }

        // Sort by (Match Score * 0.5 + App Priority * 0.01)
        return results.sortedByDescending { it.score * 0.5f + it.app.priority * 0.01f }
    }

    /**
     * Get best available app for intent
     */
    fun getBestAvailableApp(query: String, minScore: Float = 0.3f): AvailableAppMatch? {
        return matchAvailableApps(query, minScore).firstOrNull()
    }

    /**
     * 生成 Skills 描述（给 LLM）
     */
    fun getSkillsDescription(): String {
        return buildString {
            append("可用技能list:\n\n")
            for ((category, categorySkills) in categoryIndex) {
                append("【$category】\n")
                for (skill in categorySkills) {
                    val config = skill.config
                    append("- ${config.name}: ${config.description}\n")
                    if (config.keywords.isNotEmpty()) {
                        append("  关键词: ${config.keywords.joinToString(", ")}\n")
                    }
                    // Show已安装的app
                    val installedapp = config.relatedapp.filter { isAppInstalled(it.packageName) }
                    if (installedapp.isNotEmpty()) {
                        val appNames = installedapp.map {
                            val typeIcon = if (it.type == ExecutionType.DELEGATION) "🚀" else "🤖"
                            "$typeIcon${it.name}"
                        }
                        append("  可用app: ${appNames.joinToString(", ")}\n")
                    }
                }
                append("\n")
            }
        }
    }

    companion object {
        @Volatile
        private var instance: SkillRegistry? = null

        fun init(context: Context, appScanner: AppScanner): SkillRegistry {
            return instance ?: synchronized(this) {
                instance ?: SkillRegistry(context.applicationContext, appScanner).also {
                    it.refreshInstalledApps()
                    instance = it
                }
            }
        }

        fun getInstance(): SkillRegistry {
            return instance ?: throw IllegalStateException("SkillRegistry 未Initialize 请先调用 init()")
        }

        fun isInitialized(): Boolean = instance != null
    }
}

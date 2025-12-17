package com.roubao.autopilot.controller

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * App Scanner - Get all installed app information
 * Supports: Pre-scan caching, keyword matching, categorization, semantic search
 */
class AppScanner(private val context: Context) {

    companion object {
        private const val CACHE_FILE = "installed_apps.json"

        // Memory cache (valid within app lifecycle)
        @Volatile
        private var cachedApps: List<AppInfo>? = null

        // Pre-compiled regex (avoid recreation)
        private val CLEAN_REGEX = Regex("[^a-z0-9]")

        // App category keywords mapping
        private val CATEGORY_KEYWORDS = mapOf(
            "Social" to listOf("WeChat", "QQ", "DingTalk", "Lark", "Telegram", "WhatsApp", "Line", "Weibo", "Momo", "Tantan"),
            "Shopping" to listOf("Taobao", "JD", "Pinduoduo", "Tmall", "Suning", "Vipshop", "Dewu", "Xianyu", "Dangdang", "Amazon", "eBay"),
            "Food" to listOf("Meituan", "Ele.me", "KFC", "McDonald's", "Starbucks", "Luckin", "Xiaomei", "DoorDash", "Uber Eats"),
            "Travel" to listOf("DiDi", "Amap", "Baidu Map", "Tencent Map", "Dida", "Hello", "CaoCao", "T3", "Uber", "Lyft"),
            "Map" to listOf("Amap", "Baidu Map", "Tencent Map", "Google Maps", "Navigation", "Waze"),
            "Music" to listOf("NetEase", "QQ Music", "Kugou", "Kuwo", "Spotify", "Apple Music", "Xiami", "Pandora", "SoundCloud"),
            "Video" to listOf("TikTok", "Kuaishou", "Bilibili", "Youku", "iQIYI", "Tencent Video", "Mango TV", "YouTube", "Netflix", "Hulu", "Disney+"),
            "Payment" to listOf("Alipay", "WeChat", "UnionPay", "PayPal", "Venmo", "Cash App"),
            "Notes" to listOf("Evernote", "Youdao", "Notion", "Memo", "Notes", "OneNote", "TickTick"),
            "Camera" to listOf("Camera", "Photo", "Beauty", "Meitu"),
            "Photos" to listOf("Photos", "Gallery", "Album", "Google Photos"),
            "Browser" to listOf("Chrome", "Safari", "Firefox", "Edge", "Browser", "UC", "Quark", "Via", "Brave", "Opera"),
            "Office" to listOf("WPS", "Office", "DingTalk", "Lark", "WeCom", "Slack", "Teams", "Zoom", "Docs", "Sheets"),
            "AI" to listOf("ChatGPT", "Claude", "Doubao", "Ernie", "Tongyi", "Xunfei", "Copilot", "Jimeng", "Midjourney", "Gemini"),
            "Tools" to listOf("Calculator", "Flashlight", "Compass", "Clock", "Alarm", "Calendar", "Weather", "Files", "File Manager"),
            "Reading" to listOf("WeChat Read", "Kindle", "iReader", "Tomato Novel", "Qidian", "Zhihu", "Toutiao", "Books", "Audible"),
            "Games" to listOf("Honor of Kings", "PUBG", "Genshin", "Honkai", "Onmyoji", "Game", "Roblox", "Minecraft")
        )

        // Keyword mapping (Common apps)
        private val KEYWORD_MAP = mapOf(
            // Social
            "weixin" to "WeChat", "wechat" to "WeChat", "wx" to "WeChat",
            "qq" to "QQ",
            "dingding" to "DingTalk", "dingtalk" to "DingTalk",
            "feishu" to "Lark", "lark" to "Lark",
            "weibo" to "Weibo",

            // Shopping
            "taobao" to "Taobao", "tb" to "Taobao",
            "jingdong" to "JD", "jd" to "JD",
            "pinduoduo" to "Pinduoduo", "pdd" to "Pinduoduo",
            "xianyu" to "Xianyu",
            "amazon" to "Amazon",

            // Food
            "meituan" to "Meituan", "mt" to "Meituan",
            "eleme" to "Ele.me", "elm" to "Ele.me",
            "kfc" to "KFC",
            "mcdonald" to "McDonald's",
            "starbucks" to "Starbucks",
            "luckin" to "Luckin",

            // Travel/Map
            "didi" to "DiDi", "dd" to "DiDi",
            "gaode" to "Amap", "amap" to "Amap",
            "baidu" to "Baidu", "baidumap" to "Baidu Map",
            "googlemap" to "Google Maps",
            "map" to "Map",
            "navi" to "Navigation",

            // Payment
            "alipay" to "Alipay", "zfb" to "Alipay",
            "paypal" to "PayPal",

            // Music
            "netease" to "NetEase", "wangyiyun" to "NetEase",
            "qqmusic" to "QQ Music",
            "spotify" to "Spotify",
            "music" to "Music",

            // Video
            "tiktok" to "TikTok", "douyin" to "TikTok",
            "youtube" to "YouTube",
            "bilibili" to "Bilibili", "b" to "Bilibili",
            "video" to "Video",

            // Notes/Office
            "note" to "Notes", "memo" to "Memo",
            "wps" to "WPS",
            "office" to "Office",

            // System
            "settings" to "Settings",
            "camera" to "Camera",
            "photos" to "Photos", "gallery" to "Gallery",
            "phone" to "Phone", "dialer" to "Phone",
            "message" to "Messages", "sms" to "Messages",
            "browser" to "Browser", "chrome" to "Chrome",
            "calculator" to "Calculator",
            "clock" to "Clock",
            "alarm" to "Alarm",
            "calendar" to "Calendar",
            "weather" to "Weather",
            "file" to "Files"
        )

        // Semantic query mapping (Natural language to Category)
        private val SEMANTIC_MAP = mapOf(
            // Description -> Category
            "take photo" to "Camera", "photo" to "Camera", "selfie" to "Camera", "picture" to "Camera",
            "view photos" to "Photos", "gallery" to "Photos", "pictures" to "Photos", "images" to "Photos",
            "chat" to "Social", "message" to "Social", "communicate" to "Social", "dm" to "Social",
            "shop" to "Shopping", "buy" to "Shopping", "purchase" to "Shopping", "order" to "Shopping", "online shopping" to "Shopping",
            "food" to "Food", "takeout" to "Food", "eat" to "Food", "delivery" to "Food", "lunch" to "Food", "dinner" to "Food",
            "taxi" to "Travel", "cab" to "Travel", "ride" to "Travel", "uber" to "Travel", "lyft" to "Travel",
            "map" to "Map", "navigate" to "Map", "direction" to "Map", "location" to "Map", "gps" to "Map",
            "music" to "Music", "listen" to "Music", "song" to "Music", "audio" to "Music",
            "video" to "Video", "watch" to "Video", "movie" to "Video", "tv" to "Video", "stream" to "Video",
            "pay" to "Payment", "payment" to "Payment", "cash" to "Payment", "money" to "Payment",
            "note" to "Notes", "write" to "Notes", "record" to "Notes", "memo" to "Notes", "remember" to "Notes",
            "browse" to "Browser", "search" to "Browser", "internet" to "Browser", "web" to "Browser",
            "work" to "Office", "document" to "Office", "sheet" to "Office", "slide" to "Office",
            "ai" to "AI", "draw" to "AI", "generate" to "AI", "bot" to "AI", "assistant" to "AI",
            "read" to "Reading", "book" to "Reading", "novel" to "Reading", "story" to "Reading", "kindle" to "Reading",
            "game" to "Games", "play" to "Games", "gaming" to "Games"
        )
    }

    /**
     * App Info
     */
    data class AppInfo(
        val packageName: String,
        val appName: String,
        val normalizedName: String, // Normalized name (lowercase alphanum)
        val category: String?,      // Category
        val isSystem: Boolean,
        val keywords: List<String>  // Keywords for search
    )

    /**
     * Search Result
     */
    data class SearchResult(
        val app: AppInfo,
        val score: Float,          // Match score 0-1
        val matchType: String      // Match type: exact/contains/normalized/category/semantic
    )

    /**
     * Get apps list (priority: memory -> file -> scan)
     */
    fun getApps(): List<AppInfo> {
        cachedApps?.let { return it }

        val cacheFile = File(context.filesDir, CACHE_FILE)
        if (cacheFile.exists()) {
            val loaded = loadFromFile(cacheFile)
            if (loaded.isNotEmpty()) {
                cachedApps = loaded
                println("[AppScanner] Loaded ${loaded.size} apps from file")
                return loaded
            }
        }

        return refreshApps()
    }

    /**
     * Force refresh app list
     */
    fun refreshApps(): List<AppInfo> {
        println("[AppScanner] Scanning installed apps...")
        val apps = scanAllApps()
        cachedApps = apps

        val cacheFile = File(context.filesDir, CACHE_FILE)
        saveToFile(apps, cacheFile)
        println("[AppScanner] Cached ${apps.size} apps")

        return apps
    }

    /**
     * Scan all installed apps
     */
    private fun scanAllApps(): List<AppInfo> {
        val pm = context.packageManager
        val apps = mutableListOf<AppInfo>()

        try {
            // Use 0 flag to get all apps
            val packages = pm.getInstalledApplications(0)
            for (appInfo in packages) {
                val appName = pm.getApplicationLabel(appInfo).toString()
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val normalized = normalizeName(appName)
                val category = detectCategory(appName, appInfo.packageName)
                val keywords = generateKeywords(appName, appInfo.packageName, category)

                apps.add(AppInfo(
                    packageName = appInfo.packageName,
                    appName = appName,
                    normalizedName = normalized,
                    category = category,
                    isSystem = isSystem,
                    keywords = keywords
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return apps.sortedBy { it.appName }
    }

    /**
     * Smart Search Apps
     * @param query Search query (supports: app name, keywords, category, semantic description)
     * @param topK Return top K results
     * @param includeSystem Whether to include system apps
     */
    fun searchApps(query: String, topK: Int = 5, includeSystem: Boolean = true): List<SearchResult> {
        val apps = getApps()
        val lowerQuery = query.lowercase().trim()
        val results = mutableListOf<SearchResult>()

        // Check if semantic query translates to category
        val semanticCategory = SEMANTIC_MAP[lowerQuery]
        val keywordMapped = KEYWORD_MAP[lowerQuery]

        for (app in apps) {
            if (!includeSystem && app.isSystem) continue

            var score = 0f
            var matchType = ""

            // 1. Exact Name Match (Highest Priority)
            if (app.appName.equals(query, ignoreCase = true)) {
                score = 1.0f
                matchType = "exact"
            }
            // 2. Mapped Keyword Exact Match
            else if (keywordMapped != null && app.appName.contains(keywordMapped, ignoreCase = true)) {
                score = 0.95f
                matchType = "keyword_mapped"
            }
            // 3. Name Contains Query
            else if (app.appName.lowercase().contains(lowerQuery)) {
                score = 0.9f
                matchType = "contains"
            }
            // 4. Normalized Name Contains
            else if (app.normalizedName.contains(normalizeName(query))) {
                score = 0.8f
                matchType = "normalized"
            }
            // 5. Keyword Match
            else if (app.keywords.any { it.contains(lowerQuery, ignoreCase = true) || lowerQuery.contains(it, ignoreCase = true) }) {
                score = 0.7f
                matchType = "keyword"
            }
            // 6. Category Match (Semantic)
            else if (semanticCategory != null && app.category.equals(semanticCategory, ignoreCase = true)) {
                score = 0.6f
                matchType = "semantic"
            }
            // 7. Package Name Contains
            else if (app.packageName.lowercase().contains(lowerQuery)) {
                score = 0.5f
                matchType = "package"
            }

            if (score > 0) {
                // Bonus for non-system apps
                if (!app.isSystem) score += 0.05f
                results.add(SearchResult(app, score.coerceAtMost(1f), matchType))
            }
        }

        return results
            .sortedByDescending { it.score }
            .take(topK)
    }

    /**
     * Find package by name (Legacy compatibility)
     */
    fun findPackage(query: String): String? {
        val results = searchApps(query, topK = 1)
        return results.firstOrNull()?.app?.packageName
    }

    /**
     * Get apps by category
     */
    fun getAppsByCategory(category: String): List<AppInfo> {
        return getApps().filter { it.category == category }
    }

    /**
     * Get all categories
     */
    fun getAllCategories(): List<String> {
        return getApps().mapNotNull { it.category }.distinct().sorted()
    }

    /**
     * Format search results for LLM
     */
    fun formatSearchResultsForLLM(results: List<SearchResult>): String {
        if (results.isEmpty()) return "No matching apps found"

        return buildString {
            append("Found the following apps, please select the most appropriate one:\n")
            results.forEachIndexed { index, result ->
                val app = result.app
                val categoryStr = app.category?.let { " [$it]" } ?: ""
                append("${index + 1}. ${app.appName}$categoryStr (${app.packageName})\n")
            }
        }
    }

    // ========== Helper Methods ==========

    /**
     * Normalize string (keep lowercase alphanumeric)
     */
    private fun normalizeName(text: String): String {
        return text.lowercase()
            .replace(CLEAN_REGEX, "")
    }

    /**
     * Detect app category
     */
    private fun detectCategory(appName: String, packageName: String): String? {
        val lowerName = appName.lowercase()
        val lowerPackage = packageName.lowercase()

        for ((category, keywords) in CATEGORY_KEYWORDS) {
            for (keyword in keywords) {
                if (lowerName.contains(keyword.lowercase()) ||
                    lowerPackage.contains(keyword.lowercase())) {
                    return category
                }
            }
        }
        return null
    }

    /**
     * Generate search keywords
     */
    private fun generateKeywords(appName: String, packageName: String, category: String?): List<String> {
        val keywords = mutableListOf<String>()

        // Extract keywords from package name
        val packageParts = packageName.split(".")
        keywords.addAll(packageParts.filter { it.length > 2 })

        // Add category
        category?.let { keywords.add(it) }

        // Add from keyword map reverse lookup
        for ((key, name) in KEYWORD_MAP) {
            if (appName.contains(name, ignoreCase = true)) {
                keywords.add(key)
            }
        }

        return keywords.distinct()
    }

    // ========== Cache ==========

    private fun saveToFile(apps: List<AppInfo>, file: File) {
        try {
            val jsonArray = JSONArray()
            for (app in apps) {
                val obj = JSONObject()
                obj.put("package", app.packageName)
                obj.put("name", app.appName)
                obj.put("normalized", app.normalizedName)
                obj.put("category", app.category ?: "")
                obj.put("system", app.isSystem)
                obj.put("keywords", JSONArray(app.keywords))
                jsonArray.put(obj)
            }
            file.writeText(jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadFromFile(file: File): List<AppInfo> {
        val apps = mutableListOf<AppInfo>()
        try {
            val jsonArray = JSONArray(file.readText())
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val keywordsArray = obj.optJSONArray("keywords")
                val keywords = mutableListOf<String>()
                if (keywordsArray != null) {
                    for (j in 0 until keywordsArray.length()) {
                        keywords.add(keywordsArray.getString(j))
                    }
                }

                apps.add(AppInfo(
                    packageName = obj.getString("package"),
                    appName = obj.getString("name"),
                    normalizedName = obj.optString("normalized", ""),
                    category = obj.optString("category", null)?.takeIf { it.isNotEmpty() },
                    isSystem = obj.optBoolean("system", false),
                    keywords = keywords
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return apps
    }
}

package com.roubao.autopilot.tools

import com.roubao.autopilot.controller.AppScanner

/**
 * Search Apps Tool
 *
 * Supports:
 * - App name search
 * - Pinyin search
 * - Semantic search (e.g. "Order Takeout" matches food delivery apps)
 * - Category search
 */
class SearchAppsTool(private val appScanner: AppScanner) : Tool {

    override val name = "search_apps"
    override val displayName = "Search Apps"
    override val description = "Search in installed apps. Supports app name, Pinyin, semantic description, etc."

    override val params = listOf(
        ToolParam(
            name = "query",
            type = "string",
            description = "Search query (App name/Pinyin/Description e.g.: WeChat, weixin, Chat)",
            required = true
        ),
        ToolParam(
            name = "top_k",
            type = "int",
            description = "Number of results to return",
            required = false,
            defaultValue = 5
        ),
        ToolParam(
            name = "include_system",
            type = "boolean",
            description = "Whether to include system apps",
            required = false,
            defaultValue = true
        )
    )

    override suspend fun execute(params: Map<String, Any?>): ToolResult {
        val query = params["query"] as? String
            ?: return ToolResult.Error("Missing query parameter")

        val topK = (params["top_k"] as? Number)?.toInt() ?: 5
        val includeSystem = params["include_system"] as? Boolean ?: true

        val results = appScanner.searchApps(query, topK, includeSystem)

        if (results.isEmpty()) {
            return ToolResult.Success(
                data = emptyList<Map<String, Any>>(),
                message = "No matching apps found for \"$query\""
            )
        }

        val data = results.map { result ->
            mapOf(
                "package_name" to result.app.packageName,
                "app_name" to result.app.appName,
                "category" to (result.app.category ?: ""),
                "score" to result.score,
                "match_type" to result.matchType,
                "is_system" to result.app.isSystem
            )
        }

        return ToolResult.Success(
            data = data,
            message = "Found ${results.size} matching apps"
        )
    }

    /**
     * Shortcut: Get best match package name
     */
    fun findBestMatch(query: String): String? {
        val results = appScanner.searchApps(query, topK = 1)
        return results.firstOrNull()?.app?.packageName
    }
}

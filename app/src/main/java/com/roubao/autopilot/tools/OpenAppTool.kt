package com.roubao.autopilot.tools

import com.roubao.autopilot.controller.AppScanner
import com.roubao.autopilot.controller.DeviceController

/**
 * Openapp工具
 *
 * 支持:
 * - 通过包名Open
 * - 通过app名Open（自动Search包名）
 */
class OpenAppTool(
    private val deviceController: DeviceController,
    private val appScanner: AppScanner
) : Tool {

    override val name = "open_app"
    override val displayName = "Openapp"
    override val description = "Open指定的app程序"

    override val params = listOf(
        ToolParam(
            name = "app",
            type = "string",
            description = "app名称或包名（e.g.:微信、com.tencent.mm）",
            required = true
        )
    )

    override suspend fun execute(params: Map<String, Any?>): ToolResult {
        val app = params["app"] as? String
            ?: return ToolResult.Error("缺少 app 参数")

        // 判断是包名还是app名
        val packageName = if (app.contains(".")) {
            // 已经是包名格式
            app
        } else {
            // RequiresSearch包名
            val results = appScanner.searchApps(app, topK = 1)
            results.firstOrNull()?.app?.packageName
                ?: return ToolResult.Error("App not found: $app")
        }

        return try {
            deviceController.openApp(packageName)
            ToolResult.Success(
                data = mapOf("package_name" to packageName),
                message = "已Openapp: $app"
            )
        } catch (e: Exception) {
            ToolResult.Error("OpenappFailed: ${e.message}")
        }
    }
}

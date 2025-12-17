package com.roubao.autopilot.tools

import android.content.Context
import com.roubao.autopilot.controller.AppScanner
import com.roubao.autopilot.controller.DeviceController

/**
 * 工具管理器
 *
 * 负责Initialize、注册和管理所有 Tools
 * 作为 Tool 层的统一入口
 */
class ToolManager private constructor(
    private val context: Context,
    private val deviceController: DeviceController,
    private val appScanner: AppScanner
) {

    // 持有各tools的引用（方便直接调用）
    lateinit var searchAppsTool: SearchAppsTool
        private set
    lateinit var openAppTool: OpenAppTool
        private set
    lateinit var clipboardTool: ClipboardTool
        private set
    lateinit var deepLinkTool: DeepLinkTool
        private set
    lateinit var shellTool: ShellTool
        private set
    lateinit var httpTool: HttpTool
        private set

    /**
     * Initialize所有工具
     */
    private fun initialize() {
        // Create tool instances
        searchAppsTool = SearchAppsTool(appScanner)
        openAppTool = OpenAppTool(deviceController, appScanner)
        clipboardTool = ClipboardTool(context)
        deepLinkTool = DeepLinkTool(deviceController)
        shellTool = ShellTool(deviceController)
        httpTool = HttpTool()

        // Register to global Registry
        ToolRegistry.register(searchAppsTool)
        ToolRegistry.register(openAppTool)
        ToolRegistry.register(clipboardTool)
        ToolRegistry.register(deepLinkTool)
        ToolRegistry.register(shellTool)
        ToolRegistry.register(httpTool)

        println("[ToolManager] 已Initialize ${ToolRegistry.getAll().size} tools")
    }

    /**
     * 执行工具
     */
    suspend fun execute(toolName: String, params: Map<String, Any?>): ToolResult {
        return ToolRegistry.execute(toolName, params)
    }

    /**
     * 获取所有工具描述（给 LLM）
     */
    fun getToolDescriptions(): String {
        return ToolRegistry.getAllDescriptions()
    }

    /**
     * 获取可用工具list
     */
    fun getAvailableTools(): List<Tool> {
        return ToolRegistry.getAll()
    }

    companion object {
        @Volatile
        private var instance: ToolManager? = null

        /**
         * Initialize单例
         */
        fun init(
            context: Context,
            deviceController: DeviceController,
            appScanner: AppScanner
        ): ToolManager {
            return instance ?: synchronized(this) {
                instance ?: ToolManager(context, deviceController, appScanner).also {
                    it.initialize()
                    instance = it
                }
            }
        }

        /**
         * 获取单例
         */
        fun getInstance(): ToolManager {
            return instance ?: throw IllegalStateException("ToolManager 未Initialize 请先调用 init()")
        }

        /**
         * 检查是否已Initialize
         */
        fun isInitialized(): Boolean = instance != null
    }
}

package com.roubao.autopilot.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import com.roubao.autopilot.controller.AppScanner
import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.data.ExecutionStep
import com.roubao.autopilot.ui.OverlayService
import com.roubao.autopilot.vlm.VLMClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Mobile Agent 主循环 - 移植自 MobileAgent-v3
 */
class MobileAgent(
    private val vlmClient: VLMClient,
    private val controller: DeviceController,
    private val context: Context
) {
    // App 扫描器 (使用缓存，不每次扫描)
    private val appScanner = AppScanner(context)
    private val manager = Manager()
    private val executor = Executor()
    private val reflector = ActionReflector()
    private val notetaker = Notetaker()

    // 状态流
    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    /**
     * 执行指令
     */
    suspend fun runInstruction(
        instruction: String,
        maxSteps: Int = 25,
        useNotetaker: Boolean = false
    ): AgentResult {
        val infoPool = InfoPool(instruction = instruction)

        // 获取屏幕尺寸
        val (width, height) = controller.getScreenSize()
        infoPool.screenWidth = width
        infoPool.screenHeight = height

        log("开始执行: $instruction")
        log("屏幕尺寸: ${width}x${height}")

        // 显示悬浮窗 (带停止按钮)
        OverlayService.show(context, "🚀 开始执行...") {
            // 停止回调
            updateState { copy(isRunning = false) }
        }

        updateState { copy(isRunning = true, currentStep = 0, instruction = instruction) }

        for (step in 0 until maxSteps) {
            // 检查是否被用户停止
            if (!_state.value.isRunning) {
                log("⏹ 用户停止执行")
                OverlayService.hide(context)
                bringAppToFront()
                return AgentResult(success = false, message = "用户停止")
            }

            updateState { copy(currentStep = step + 1) }
            log("\n========== Step ${step + 1} ==========")
            OverlayService.update("Step ${step + 1}/$maxSteps")

            // 1. 截图 (先隐藏悬浮窗避免被识别)
            log("📸 截图中...")
            OverlayService.setVisible(false)
            delay(100) // 等待悬浮窗隐藏
            val screenshot = controller.screenshot()
            OverlayService.setVisible(true)
            if (screenshot == null) {
                log("❌ 截图失败")
                delay(1000)
                continue
            }

            // 2. 检查错误升级
            checkErrorEscalation(infoPool)

            // 3. 跳过 Manager 的情况
            val skipManager = !infoPool.errorFlagPlan &&
                    infoPool.actionHistory.isNotEmpty() &&
                    infoPool.actionHistory.last().type == "invalid"

            // 4. Manager 规划
            if (!skipManager) {
                log("🧠 Manager 规划中...")
                val planPrompt = manager.getPrompt(infoPool)
                val planResponse = vlmClient.predict(planPrompt, listOf(screenshot))

                if (planResponse.isFailure) {
                    log("❌ Manager 调用失败: ${planResponse.exceptionOrNull()?.message}")
                    continue
                }

                val planResult = manager.parseResponse(planResponse.getOrThrow())
                infoPool.completedPlan = planResult.completedSubgoal
                infoPool.plan = planResult.plan

                log("📋 计划: ${planResult.plan.take(100)}...")

                // 检查是否遇到敏感页面
                if (planResult.plan.contains("STOP_SENSITIVE")) {
                    log("⛔ 检测到敏感页面（支付/密码等），已停止执行")
                    OverlayService.update("⛔ 敏感页面，已停止")
                    delay(2000)
                    OverlayService.hide(context)
                    updateState { copy(isRunning = false, isCompleted = false) }
                    bringAppToFront()
                    return AgentResult(success = false, message = "检测到敏感页面（支付/密码），已安全停止")
                }

                // 检查是否完成
                if (planResult.plan.contains("Finished") && planResult.plan.length < 20) {
                    log("✅ 任务完成!")
                    OverlayService.update("✅ 完成!")
                    delay(1500)
                    OverlayService.hide(context)
                    updateState { copy(isRunning = false, isCompleted = true) }
                    bringAppToFront()
                    return AgentResult(success = true, message = "任务完成")
                }
            }

            // 5. Executor 决定动作
            log("🎯 Executor 决策中...")
            val actionPrompt = executor.getPrompt(infoPool)
            val actionResponse = vlmClient.predict(actionPrompt, listOf(screenshot))

            if (actionResponse.isFailure) {
                log("❌ Executor 调用失败: ${actionResponse.exceptionOrNull()?.message}")
                continue
            }

            val executorResult = executor.parseResponse(actionResponse.getOrThrow())
            val action = executorResult.action

            log("💭 思考: ${executorResult.thought.take(80)}...")
            log("🎬 动作: ${executorResult.actionStr}")
            log("📝 描述: ${executorResult.description}")

            infoPool.lastActionThought = executorResult.thought
            infoPool.lastSummary = executorResult.description

            if (action == null) {
                log("❌ 动作解析失败")
                infoPool.actionHistory.add(Action(type = "invalid"))
                infoPool.summaryHistory.add(executorResult.description)
                infoPool.actionOutcomes.add("C")
                infoPool.errorDescriptions.add("Invalid action format")
                continue
            }

            // 特殊处理: answer 动作
            if (action.type == "answer") {
                log("💬 回答: ${action.text}")
                OverlayService.update("💬 ${action.text?.take(20)}...")
                delay(1500)
                OverlayService.hide(context)
                updateState { copy(isRunning = false, isCompleted = true, answer = action.text) }
                bringAppToFront()
                return AgentResult(success = true, message = "回答: ${action.text}")
            }

            // 6. 执行动作
            log("⚡ 执行动作: ${action.type}")
            OverlayService.update("⚡ ${action.type}: ${executorResult.description.take(15)}...")
            executeAction(action, infoPool)
            infoPool.lastAction = action

            // 等待动作生效
            delay(if (step == 0) 5000 else 2000)

            // 7. 截图 (动作后，隐藏悬浮窗)
            OverlayService.setVisible(false)
            delay(100)
            val afterScreenshot = controller.screenshot()
            OverlayService.setVisible(true)
            if (afterScreenshot == null) {
                log("❌ 动作后截图失败")
                continue
            }

            // 8. Reflector 反思
            log("🔍 Reflector 反思中...")
            val reflectPrompt = reflector.getPrompt(infoPool)
            val reflectResponse = vlmClient.predict(reflectPrompt, listOf(screenshot, afterScreenshot))

            val reflectResult = if (reflectResponse.isSuccess) {
                reflector.parseResponse(reflectResponse.getOrThrow())
            } else {
                ReflectorResult("C", "Failed to call reflector")
            }

            log("📊 结果: ${reflectResult.outcome} - ${reflectResult.errorDescription.take(50)}")

            // 更新历史
            infoPool.actionHistory.add(action)
            infoPool.summaryHistory.add(executorResult.description)
            infoPool.actionOutcomes.add(reflectResult.outcome)
            infoPool.errorDescriptions.add(reflectResult.errorDescription)
            infoPool.progressStatus = infoPool.completedPlan

            // 记录执行步骤
            val executionStep = ExecutionStep(
                stepNumber = step + 1,
                timestamp = System.currentTimeMillis(),
                action = action.type,
                description = executorResult.description,
                thought = executorResult.thought,
                outcome = reflectResult.outcome
            )
            updateState { copy(executionSteps = executionSteps + executionStep) }

            // 9. Notetaker (可选)
            if (useNotetaker && reflectResult.outcome == "A" && action.type != "answer") {
                log("📒 Notetaker 记录中...")
                val notePrompt = notetaker.getPrompt(infoPool)
                val noteResponse = vlmClient.predict(notePrompt, listOf(afterScreenshot))
                if (noteResponse.isSuccess) {
                    infoPool.importantNotes = notetaker.parseResponse(noteResponse.getOrThrow())
                }
            }
        }

        log("⚠️ 达到最大步数限制")
        OverlayService.update("⚠️ 达到最大步数")
        delay(1500)
        OverlayService.hide(context)
        updateState { copy(isRunning = false, isCompleted = false) }
        bringAppToFront()
        return AgentResult(success = false, message = "达到最大步数限制")
    }

    /**
     * 执行具体动作 (在 IO 线程执行，避免 ANR)
     */
    private suspend fun executeAction(action: Action, infoPool: InfoPool) = withContext(Dispatchers.IO) {
        when (action.type) {
            "click" -> {
                val x = mapCoordinate(action.x ?: 0, infoPool.screenWidth)
                val y = mapCoordinate(action.y ?: 0, infoPool.screenHeight)
                controller.tap(x, y)
            }
            "long_press" -> {
                val x = mapCoordinate(action.x ?: 0, infoPool.screenWidth)
                val y = mapCoordinate(action.y ?: 0, infoPool.screenHeight)
                controller.longPress(x, y)
            }
            "swipe" -> {
                val x1 = mapCoordinate(action.x ?: 0, infoPool.screenWidth)
                val y1 = mapCoordinate(action.y ?: 0, infoPool.screenHeight)
                val x2 = mapCoordinate(action.x2 ?: 0, infoPool.screenWidth)
                val y2 = mapCoordinate(action.y2 ?: 0, infoPool.screenHeight)
                controller.swipe(x1, y1, x2, y2)
            }
            "type" -> {
                action.text?.let { controller.type(it) }
            }
            "system_button" -> {
                when (action.button) {
                    "Back", "back" -> controller.back()
                    "Home", "home" -> controller.home()
                    "Enter", "enter" -> controller.enter()
                    else -> log("⚠️ 未知系统按钮: ${action.button}")
                }
            }
            "open_app" -> {
                action.text?.let { appName ->
                    // 智能匹配包名 (客户端模糊搜索，省 token)
                    val packageName = appScanner.findPackage(appName)
                    if (packageName != null) {
                        log("📦 找到应用: $appName -> $packageName")
                        controller.openApp(packageName)
                    } else {
                        log("⚠️ 未找到应用: $appName，尝试直接打开")
                        controller.openApp(appName)
                    }
                }
            }
            else -> {
                log("⚠️ 未知动作类型: ${action.type}")
            }
        }
    }

    /**
     * 坐标映射 (0-1000 -> 实际像素)
     * 某些模型输出 0-1000 的相对坐标
     */
    private fun mapCoordinate(value: Int, max: Int): Int {
        return if (value > 1000) {
            value // 已经是绝对坐标
        } else {
            (value * max / 1000)
        }
    }

    /**
     * 检查错误升级
     */
    private fun checkErrorEscalation(infoPool: InfoPool) {
        infoPool.errorFlagPlan = false
        val thresh = infoPool.errToManagerThresh

        if (infoPool.actionOutcomes.size >= thresh) {
            val recentOutcomes = infoPool.actionOutcomes.takeLast(thresh)
            val failCount = recentOutcomes.count { it in listOf("B", "C") }
            if (failCount == thresh) {
                infoPool.errorFlagPlan = true
            }
        }
    }

    /**
     * 停止执行
     */
    fun stop() {
        OverlayService.hide(context)
        updateState { copy(isRunning = false) }
    }

    /**
     * 清空日志
     */
    fun clearLogs() {
        _logs.value = emptyList()
        updateState { copy(executionSteps = emptyList()) }
    }

    /**
     * 返回肉包App
     */
    private fun bringAppToFront() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            context.startActivity(intent)
        } catch (e: Exception) {
            log("返回App失败: ${e.message}")
        }
    }

    private fun log(message: String) {
        println("[肉包] $message")
        _logs.value = _logs.value + message
    }

    private fun updateState(update: AgentState.() -> AgentState) {
        _state.value = _state.value.update()
    }
}

data class AgentState(
    val isRunning: Boolean = false,
    val isCompleted: Boolean = false,
    val currentStep: Int = 0,
    val instruction: String = "",
    val answer: String? = null,
    val executionSteps: List<ExecutionStep> = emptyList()
)

data class AgentResult(
    val success: Boolean,
    val message: String
)

package com.roubao.autopilot.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import com.roubao.autopilot.App
import com.roubao.autopilot.controller.AppScanner
import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.data.ExecutionStep
import com.roubao.autopilot.skills.SkillManager
import com.roubao.autopilot.ui.OverlayService
import com.roubao.autopilot.vlm.VLMClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * Mobile Agent 主循环 - 移植自 MobileAgent-v3
 *
 * 新增 Skill 层支持:
 * - 快速路径:高置信度 delegation Skill 直接执行
 * - 增强Mode:GUI 自动化 Skill 提供上下文指导
 */
class MobileAgent(
    private val vlmClient: VLMClient,
    private val controller: DeviceController,
    private val context: Context
) {
    // App 扫描器 (使用 App 单例中的实例)
    private val appScanner: AppScanner = App.getInstance().appScanner
    private val manager = Manager()
    private val executor = Executor()
    private val reflector = ActionReflector()
    private val notetaker = Notetaker()

    // Skill 管理器
    private val skillManager: SkillManager? = try {
        SkillManager.getInstance().also {
            println("[Baozi] SkillManager Loaded Total ${it.getAllSkills().size} items Skills")
            // Settings VLM 客户端for意图匹配
            it.setVLMClient(vlmClient)
        }
    } catch (e: Exception) {
        println("[Baozi] SkillManager Load failed: ${e.message}")
        null
    }

    // Status流
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
        log("Start执行: $instruction")

        // 使用 LLM 匹配 Skill 生成上下文信息给 Agent（不执行任何操作）
        log("正在m析意图...")
        val skillContext = skillManager?.generateAgentContextWithLLM(instruction)

        val infoPool = InfoPool(instruction = instruction)

        // Initialize Executor 的对话记忆
        val executorSystemPrompt = buildString {
            append("You are an agent who can operate an Android phone. ")
            append("Decide the next action based on the current state.\n\n")
            append("User Request: $instruction\n")
        }
        infoPool.executorMemory = ConversationMemory.withSystemPrompt(executorSystemPrompt)
        log("已Initialize对话记忆")

        // e.g.果有 Skill 上下文 Add到 InfoPool 让 Manager 知道可用的工具
        if (!skillContext.isNullOrEmpty() && skillContext != "未find相关技能或可用app 请使用通用 GUI 自动化Done任务.") {
            infoPool.skillContext = skillContext
            log("已匹配到可用技能:\n$skillContext")
        } else {
            log("未匹配到特定技能 使用通用 GUI 自动化")
        }

        // 获取屏幕尺寸
        val (width, height) = controller.getScreenSize()
        infoPool.screenWidth = width
        infoPool.screenHeight = height
        log("屏幕尺寸: ${width}x${height}")

        // Get installed apps list (non-system only, limited count)
        val apps = appScanner.getApps()
            .filter { !it.isSystem }
            .take(50)
            .map { it.appName }
        infoPool.installedapp = apps.joinToString(", ")
        log("Loaded ${apps.size} apps")

        // Show悬浮窗 (带Stop按钮)
        OverlayService.show(context, "Start执行...") {
            // Stop回调 - SettingsStatus为Stop
            // 注意:协程CancelRequires在 MainActivity 中处理
            updateState { copy(isRunning = false) }
            // 调用 stop() 方法确保清理
            stop()
        }

        updateState { copy(isRunning = true, currentStep = 0, instruction = instruction) }

        try {
            for (step in 0 until maxSteps) {
                // 检查协程是否被Cancel
                coroutineContext.ensureActive()

                // 检查是否被用户Stop
                if (!_state.value.isRunning) {
                    log("用户Stop执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户Stop")
                }

                updateState { copy(currentStep = step + 1) }
                log("\n========== Step ${step + 1} ==========")
                OverlayService.update("Step ${step + 1}/$maxSteps")

                // 1. 截图 (先Hide悬浮窗避免被识别)
                log("截图中...")
                OverlayService.setVisible(false)
                delay(100) // Wait悬浮窗Hide
                val screenshotResult = controller.screenshotWithFallback()
                OverlayService.setVisible(true)
                val screenshot = screenshotResult.bitmap

                // 处理敏感页面（截图被系统阻止）
                if (screenshotResult.isSensitive) {
                    log("⚠️ Detected sensitive page（截图被阻止） Request人工接管")
                    val confirmed = withContext(Dispatchers.Main) {
                        waitForUserConfirm("Detected sensitive page 是否继续执行?")
                    }
                    if (!confirmed) {
                        log("用户Cancel 任务终止")
                        OverlayService.hide(context)
                        bringAppToFront()
                        return AgentResult(success = false, message = "敏感页面 用户Cancel")
                    }
                    log("用户Confirm继续（使用黑屏占位图）")
                } else if (screenshotResult.isFallback) {
                    log("⚠️ 截图Failed 使用黑屏占位图继续")
                }

                // 再次检查StopStatus（截图后）
                if (!_state.value.isRunning) {
                    log("用户Stop执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户Stop")
                }

                // 2. 检查Error升级
                checkErrorEscalation(infoPool)

                // 3. Skip Manager 的情况
                val skipManager = !infoPool.errorFlagPlan &&
                        infoPool.actionHistory.isNotEmpty() &&
                        infoPool.actionHistory.last().type == "invalid"

                // 4. Manager 规划
                if (!skipManager) {
                    log("Manager 规划中...")

                    // 检查StopStatus
                    if (!_state.value.isRunning) {
                        log("用户Stop执行")
                        OverlayService.hide(context)
                        bringAppToFront()
                        return AgentResult(success = false, message = "用户Stop")
                    }

                    val planPrompt = manager.getPrompt(infoPool)
                    val planResponse = vlmClient.predict(planPrompt, listOf(screenshot))

                    // VLM 调用后检查StopStatus
                    if (!_state.value.isRunning) {
                        log("用户Stop执行")
                        OverlayService.hide(context)
                        bringAppToFront()
                        return AgentResult(success = false, message = "用户Stop")
                    }

                    if (planResponse.isFailure) {
                        log("Manager 调用Failed: ${planResponse.exceptionOrNull()?.message}")
                        continue
                    }

                    val planResult = manager.parseResponse(planResponse.getOrThrow())
                    infoPool.completedPlan = planResult.completedSubgoal
                    infoPool.plan = planResult.plan

                    log("计划: ${planResult.plan.take(100)}...")

                    // 检查是否When encountering sensitive pages
                    if (planResult.plan.contains("STOP_SENSITIVE")) {
                        log("Detected sensitive page（payment/password等） Stopped执行")
                        OverlayService.update("敏感页面 Stopped")
                        delay(2000)
                        OverlayService.hide(context)
                        updateState { copy(isRunning = false, isCompleted = false) }
                        bringAppToFront()
                        return AgentResult(success = false, message = "Detected sensitive page（payment/password） 已安全Stop")
                    }

                    // 检查是否Done
                    if (planResult.plan.contains("Finished") && planResult.plan.length < 20) {
                        log("任务Done!")
                        OverlayService.update("Done!")
                        delay(1500)
                        OverlayService.hide(context)
                        updateState { copy(isRunning = false, isCompleted = true) }
                        bringAppToFront()
                        return AgentResult(success = true, message = "任务Done")
                    }
                }

                // 5. Executor 决定动作 (使用上下文记忆)
                log("Executor 决策中...")

                // 检查StopStatus
                if (!_state.value.isRunning) {
                    log("用户Stop执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户Stop")
                }

                val actionPrompt = executor.getPrompt(infoPool)

                // 使用上下文记忆调用 VLM
                val memory = infoPool.executorMemory
                val actionResponse = if (memory != null) {
                    // Add用户消息（带截图）
                    memory.addUserMessage(actionPrompt, screenshot)
                    log("记忆消息数: ${memory.size()}, 估算 token: ${memory.estimateTokens()}")

                    // 调用 VLM
                    val response = vlmClient.predictWithContext(memory.toMessagesJson())

                    // Delete图片节省 token
                    memory.stripLastUserImage()

                    response
                } else {
                    // 降级:使用普通方式
                    vlmClient.predict(actionPrompt, listOf(screenshot))
                }

                // VLM 调用后检查StopStatus
                if (!_state.value.isRunning) {
                    log("用户Stop执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户Stop")
                }

                if (actionResponse.isFailure) {
                    log("Executor 调用Failed: ${actionResponse.exceptionOrNull()?.message}")
                    continue
                }

                val responseText = actionResponse.getOrThrow()
                val executorResult = executor.parseResponse(responseText)

                // 将助手ResponseAdd到记忆
                memory?.addAssistantMessage(responseText)
                val action = executorResult.action

                log("思考: ${executorResult.thought.take(80)}...")
                log("动作: ${executorResult.actionStr}")
                log("描述: ${executorResult.description}")

                infoPool.lastActionThought = executorResult.thought
                infoPool.lastSummary = executorResult.description

                if (action == null) {
                    log("动作解析Failed")
                    infoPool.actionHistory.add(Action(type = "invalid"))
                    infoPool.summaryHistory.add(executorResult.description)
                    infoPool.actionOutcomes.add("C")
                    infoPool.errorDescriptions.add("Invalid action format")
                    continue
                }

                // 特殊处理: answer 动作
                if (action.type == "answer") {
                    log("回答: ${action.text}")
                    OverlayService.update("${action.text?.take(20)}...")
                    delay(1500)
                    OverlayService.hide(context)
                    updateState { copy(isRunning = false, isCompleted = true, answer = action.text) }
                    bringAppToFront()
                    return AgentResult(success = true, message = "回答: ${action.text}")
                }

                // 6. 敏感操作Confirm
                if (action.needConfirm || action.message != null && action.type in listOf("click", "double_tap", "long_press")) {
                    val confirmMessage = action.message ?: "Confirm执行此操作?"
                    log("⚠️ 敏感操作: $confirmMessage")

                    val confirmed = withContext(Dispatchers.Main) {
                        waitForUserConfirm(confirmMessage)
                    }

                    if (!confirmed) {
                        log("❌ 用户Cancel操作")
                        infoPool.actionHistory.add(action)
                        infoPool.summaryHistory.add("用户Cancel: ${executorResult.description}")
                        infoPool.actionOutcomes.add("C")
                        infoPool.errorDescriptions.add("User cancelled")
                        continue
                    }
                    log("✅ 用户Confirm 继续执行")
                }

                // 7. 执行动作
                log("执行动作: ${action.type}")
                OverlayService.update("${action.type}: ${executorResult.description.take(15)}...")
                executeAction(action, infoPool)
                infoPool.lastAction = action

                // 立即records执行steps（outcome 暂时为 "?" 表示进行中）
                val currentStepIndex = _state.value.executionSteps.size
                val executionStep = ExecutionStep(
                    stepNumber = step + 1,
                    timestamp = System.currentTimeMillis(),
                    action = action.type,
                    description = executorResult.description,
                    thought = executorResult.thought,
                    outcome = "?" // 进行中
                )
                updateState { copy(executionSteps = executionSteps + executionStep) }

                // Wait动作生效
                delay(if (step == 0) 5000 else 2000)

                // 检查StopStatus
                if (!_state.value.isRunning) {
                    log("用户Stop执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户Stop")
                }

                // 8. 截图 (动作后 Hide悬浮窗)
                OverlayService.setVisible(false)
                delay(100)
                val afterScreenshotResult = controller.screenshotWithFallback()
                OverlayService.setVisible(true)
                val afterScreenshot = afterScreenshotResult.bitmap
                if (afterScreenshotResult.isFallback) {
                    log("动作后截图Failed 使用黑屏占位图")
                }

                // 9. Reflector 反思
                log("Reflector 反思中...")

                // 检查StopStatus
                if (!_state.value.isRunning) {
                    log("用户Stop执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户Stop")
                }

                val reflectPrompt = reflector.getPrompt(infoPool)
                val reflectResponse = vlmClient.predict(reflectPrompt, listOf(screenshot, afterScreenshot))

                val reflectResult = if (reflectResponse.isSuccess) {
                    reflector.parseResponse(reflectResponse.getOrThrow())
                } else {
                    ReflectorResult("C", "Failed to call reflector")
                }

                log("结果: ${reflectResult.outcome} - ${reflectResult.errorDescription.take(50)}")

                // 更新历史
                infoPool.actionHistory.add(action)
                infoPool.summaryHistory.add(executorResult.description)
                infoPool.actionOutcomes.add(reflectResult.outcome)
                infoPool.errorDescriptions.add(reflectResult.errorDescription)
                infoPool.progressStatus = infoPool.completedPlan

                // 更新执行steps的 outcome（之前Add的steps outcome 是 "?"）
                updateState {
                    val updatedSteps = executionSteps.toMutableList()
                    if (currentStepIndex < updatedSteps.size) {
                        updatedSteps[currentStepIndex] = updatedSteps[currentStepIndex].copy(
                            outcome = reflectResult.outcome
                        )
                    }
                    copy(executionSteps = updatedSteps)
                }

                // 10. Notetaker (可选)
                if (useNotetaker && reflectResult.outcome == "A" && action.type != "answer") {
                    log("Notetaker records中...")

                    // 检查StopStatus
                    if (!_state.value.isRunning) {
                        log("用户Stop执行")
                        OverlayService.hide(context)
                        bringAppToFront()
                        return AgentResult(success = false, message = "用户Stop")
                    }

                    val notePrompt = notetaker.getPrompt(infoPool)
                    val noteResponse = vlmClient.predict(notePrompt, listOf(afterScreenshot))
                    if (noteResponse.isSuccess) {
                        infoPool.importantNotes = notetaker.parseResponse(noteResponse.getOrThrow())
                    }
                }
            }
        } catch (e: CancellationException) {
            log("任务被Cancel")
            OverlayService.hide(context)
            updateState { copy(isRunning = false) }
            bringAppToFront()
            throw e
        }

        log("达到最大steps数限制")
        OverlayService.update("达到最大steps数")
        delay(1500)
        OverlayService.hide(context)
        updateState { copy(isRunning = false, isCompleted = false) }
        bringAppToFront()
        return AgentResult(success = false, message = "达到最大steps数限制")
    }

    /**
     * 执行具体动作 (在 IO 线程执行 避免 ANR)
     */
    private suspend fun executeAction(action: Action, infoPool: InfoPool) = withContext(Dispatchers.IO) {
        // 动态获取屏幕尺寸（处理横竖屏切换）
        val (screenWidth, screenHeight) = controller.getScreenSize()

        when (action.type) {
            "click" -> {
                val x = mapCoordinate(action.x ?: 0, screenWidth)
                val y = mapCoordinate(action.y ?: 0, screenHeight)
                controller.tap(x, y)
            }
            "double_tap" -> {
                val x = mapCoordinate(action.x ?: 0, screenWidth)
                val y = mapCoordinate(action.y ?: 0, screenHeight)
                controller.doubleTap(x, y)
            }
            "long_press" -> {
                val x = mapCoordinate(action.x ?: 0, screenWidth)
                val y = mapCoordinate(action.y ?: 0, screenHeight)
                controller.longPress(x, y)
            }
            "swipe" -> {
                val x1 = mapCoordinate(action.x ?: 0, screenWidth)
                val y1 = mapCoordinate(action.y ?: 0, screenHeight)
                val x2 = mapCoordinate(action.x2 ?: 0, screenWidth)
                val y2 = mapCoordinate(action.y2 ?: 0, screenHeight)
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
                    else -> log("Unknown system button: ${action.button}")
                }
            }
            "open_app" -> {
                action.text?.let { appName ->
                    // Intelligent package name matching (client-side fuzzy search to save tokens)
                    val packageName = appScanner.findPackage(appName)
                    if (packageName != null) {
                        log("Found app: $appName -> $packageName")
                        controller.openApp(packageName)
                    } else {
                        log("App not found: $appName, trying to open directly")
                        controller.openApp(appName)
                    }
                }
            }
            "wait" -> {
                // 智能Wait:Model决定Wait时长
                val duration = (action.duration ?: 3).coerceIn(1, 10)
                log("Wait ${duration} s...")
                delay(duration * 1000L)
            }
            "take_over" -> {
                // 人机协作:暂停Wait用户手动Done操作
                val message = action.message ?: "请Done操作后Click继续"
                log("🖐 人机协作: $message")
                withContext(Dispatchers.Main) {
                    waitForUserTakeOver(message)
                }
                log("✅ 用户Completed 继续执行")
            }
            else -> {
                log("未知动作类型: ${action.type}")
            }
        }
    }

    /**
     * Wait用户Done手动操作（人机协作）
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun waitForUserTakeOver(message: String) = suspendCancellableCoroutine<Unit> { continuation ->
        com.roubao.autopilot.ui.OverlayService.showTakeOver(message) {
            if (continuation.isActive) {
                continuation.resume(Unit) {}
            }
        }
    }

    /**
     * Wait用户Confirm敏感操作
     * @return true = 用户Confirm false = 用户Cancel
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun waitForUserConfirm(message: String) = suspendCancellableCoroutine<Boolean> { continuation ->
        com.roubao.autopilot.ui.OverlayService.showConfirm(message) { confirmed ->
            if (continuation.isActive) {
                continuation.resume(confirmed) {}
            }
        }
    }

    /**
     * 坐标映射 - 支持相对坐标和绝对坐标
     *
     * 坐标格式判断:
     * - 0-999: Qwen-VL 相对坐标 (0-999 映射到屏幕)
     * - >= 1000: 绝对像素坐标 直接使用
     *
     * @param value Model输出的坐标值
     * @param screenMax 屏幕实际尺寸
     */
    private fun mapCoordinate(value: Int, screenMax: Int): Int {
        return if (value < 1000) {
            // 相对坐标 (0-999) -> 绝对像素
            (value * screenMax / 999)
        } else {
            // 绝对坐标 限制在屏幕范围内
            value.coerceAtMost(screenMax)
        }
    }

    /**
     * 检查Error升级
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

    // Stop回调（由 MainActivity Settings forCancel协程）
    var onStopRequested: (() -> Unit)? = null

    /**
     * Stop执行
     */
    fun stop() {
        OverlayService.hide(context)
        updateState { copy(isRunning = false) }
        // 通知 MainActivity Cancel协程
        onStopRequested?.invoke()
    }

    /**
     * 清空Logs
     */
    fun clearLogs() {
        _logs.value = emptyList()
        updateState { copy(executionSteps = emptyList()) }
    }

    /**
     * Return to Baozi App
     */
    private fun bringAppToFront() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            context.startActivity(intent)
        } catch (e: Exception) {
            log("Return to App Failed: ${e.message}")
        }
    }

    private fun log(message: String) {
        println("[Baozi] $message")
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

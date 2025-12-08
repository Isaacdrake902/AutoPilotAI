package com.example.autopilot

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import android.net.Uri
import android.provider.Settings
import com.example.autopilot.agent.MobileAgent
import com.example.autopilot.controller.AppScanner
import com.example.autopilot.controller.DeviceController
import com.example.autopilot.data.*
import com.example.autopilot.ui.screens.*
import com.example.autopilot.ui.theme.*
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import com.example.autopilot.vlm.VLMClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

sealed class Screen(val route: String, val title: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    object Home : Screen("home", "肉包", Icons.Outlined.Home, Icons.Filled.Home)
    object History : Screen("history", "记录", Icons.Outlined.List, Icons.Filled.List)
    object Settings : Screen("settings", "设置", Icons.Outlined.Settings, Icons.Filled.Settings)
}

class MainActivity : ComponentActivity() {

    private lateinit var deviceController: DeviceController
    private lateinit var settingsManager: SettingsManager
    private lateinit var executionRepository: ExecutionRepository

    private val mobileAgent = mutableStateOf<MobileAgent?>(null)
    private var shizukuAvailable = mutableStateOf(false)

    // 执行记录列表
    private val executionRecords = mutableStateOf<List<ExecutionRecord>>(emptyList())

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        shizukuAvailable.value = true
        if (checkShizukuPermission()) {
            deviceController.bindService()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shizukuAvailable.value = false
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            deviceController.bindService()
            Toast.makeText(this, "Shizuku 权限已获取", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // 设置边到边显示，深色状态栏和导航栏
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        deviceController = DeviceController(this)
        deviceController.setCacheDir(cacheDir)
        settingsManager = SettingsManager(this)
        executionRepository = ExecutionRepository(this)

        // 加载执行记录
        lifecycleScope.launch {
            executionRecords.value = executionRepository.getAllRecords()
        }

        // 添加 Shizuku 监听器
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        // 检查 Shizuku 状态
        try {
            if (Shizuku.pingBinder()) {
                shizukuAvailable.value = true
                if (checkShizukuPermission()) {
                    deviceController.bindService()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 预加载已安装应用
        lifecycleScope.launch(Dispatchers.IO) {
            AppScanner(this@MainActivity).getApps()
        }

        setContent {
            val settings by settingsManager.settings.collectAsState()
            BaoziTheme(themeMode = settings.themeMode) {
                val colors = BaoziTheme.colors
                // 动态更新系统栏颜色
                SideEffect {
                    val window = this@MainActivity.window
                    window.statusBarColor = colors.background.toArgb()
                    window.navigationBarColor = colors.backgroundCard.toArgb()
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !colors.isDark
                        isAppearanceLightNavigationBars = !colors.isDark
                    }
                }

                // 首次启动显示引导画面
                if (!settings.hasSeenOnboarding) {
                    OnboardingScreen(
                        onComplete = {
                            settingsManager.setOnboardingSeen()
                        }
                    )
                } else {
                    MainApp()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainApp() {
        var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
        var selectedRecord by remember { mutableStateOf<ExecutionRecord?>(null) }
        var showShizukuHelpDialog by remember { mutableStateOf(false) }
        var hasShownShizukuHelp by remember { mutableStateOf(false) }

        val settings by settingsManager.settings.collectAsState()
        val colors = BaoziTheme.colors
        val agent = mobileAgent.value
        val agentState by agent?.state?.collectAsState() ?: remember { mutableStateOf(null) }
        val logs by agent?.logs?.collectAsState() ?: remember { mutableStateOf(emptyList<String>()) }
        val records by remember { executionRecords }
        val isShizukuAvailable = shizukuAvailable.value && checkShizukuPermission()

        // 首次进入且 Shizuku 未连接时，显示帮助引导（只显示一次）
        LaunchedEffect(Unit) {
            if (!isShizukuAvailable && settings.hasSeenOnboarding && !hasShownShizukuHelp) {
                hasShownShizukuHelp = true
                showShizukuHelpDialog = true
            }
        }

        Scaffold(
            modifier = Modifier.background(colors.background),
            containerColor = colors.background,
            bottomBar = {
                if (selectedRecord == null) {
                    NavigationBar(
                        containerColor = colors.background,
                        contentColor = colors.textPrimary,
                        tonalElevation = 0.dp
                    ) {
                        listOf(Screen.Home, Screen.History, Screen.Settings).forEach { screen ->
                            val selected = currentScreen == screen
                            NavigationBarItem(
                                icon = {
                                    Icon(
                                        imageVector = if (selected) screen.selectedIcon else screen.icon,
                                        contentDescription = screen.title
                                    )
                                },
                                label = { Text(screen.title) },
                                selected = selected,
                                onClick = { currentScreen = screen },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = if (colors.isDark) colors.textPrimary else Color.White,
                                    selectedTextColor = colors.primary,
                                    unselectedIconColor = colors.textSecondary,
                                    unselectedTextColor = colors.textSecondary,
                                    indicatorColor = colors.primary
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // 处理系统返回手势
                BackHandler(enabled = selectedRecord != null) {
                    selectedRecord = null
                }

                // 详情页优先显示
                if (selectedRecord != null) {
                    HistoryDetailScreen(
                        record = selectedRecord!!,
                        onBack = { selectedRecord = null }
                    )
                } else {
                    // 主页面切换
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            fadeIn() togetherWith fadeOut()
                        },
                        label = "screen"
                    ) { screen ->
                        when (screen) {
                            Screen.Home -> HomeScreen(
                                agentState = agentState,
                                logs = logs,
                                onExecute = { instruction ->
                                    runAgent(instruction, settings.apiKey, settings.baseUrl, settings.model)
                                },
                                onStop = { mobileAgent.value?.stop() },
                                shizukuAvailable = isShizukuAvailable
                            )
                            Screen.History -> HistoryScreen(
                                records = records,
                                onRecordClick = { record -> selectedRecord = record },
                                onDeleteRecord = { id -> deleteRecord(id) }
                            )
                            Screen.Settings -> SettingsScreen(
                                settings = settings,
                                onUpdateApiKey = { settingsManager.updateApiKey(it) },
                                onUpdateBaseUrl = { settingsManager.updateBaseUrl(it) },
                                onUpdateModel = { settingsManager.updateModel(it) },
                                onAddCustomModel = { settingsManager.addCustomModel(it) },
                                onRemoveCustomModel = { settingsManager.removeCustomModel(it) },
                                onUpdateThemeMode = { settingsManager.updateThemeMode(it) },
                                allModels = settingsManager.getAllModels(),
                                shizukuAvailable = isShizukuAvailable
                            )
                        }
                    }
                }
            }
        }

        // Shizuku 帮助对话框
        if (showShizukuHelpDialog) {
            ShizukuHelpDialog(onDismiss = { showShizukuHelpDialog = false })
        }
    }

    private fun deleteRecord(id: String) {
        lifecycleScope.launch {
            executionRepository.deleteRecord(id)
            executionRecords.value = executionRepository.getAllRecords()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        deviceController.unbindService()
    }

    private fun checkShizukuPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    private fun requestShizukuPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "请先启动 Shizuku App", Toast.LENGTH_SHORT).show()
                return
            }

            if (Shizuku.isPreV11()) {
                Toast.makeText(this, "Shizuku 版本过低", Toast.LENGTH_SHORT).show()
                return
            }

            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Shizuku 权限已获取", Toast.LENGTH_SHORT).show()
                shizukuAvailable.value = true
                deviceController.bindService()
                return
            }

            Shizuku.requestPermission(0)
        } catch (e: Exception) {
            Toast.makeText(this, "请先启动 Shizuku App", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runAgent(instruction: String, apiKey: String, baseUrl: String, model: String) {
        if (instruction.isBlank()) {
            Toast.makeText(this, "请输入指令", Toast.LENGTH_SHORT).show()
            return
        }
        if (apiKey.isBlank()) {
            Toast.makeText(this, "请输入 API Key", Toast.LENGTH_SHORT).show()
            return
        }

        // 检查悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_LONG).show()
            val intent = android.content.Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        val vlmClient = VLMClient(
            apiKey = apiKey,
            baseUrl = baseUrl.ifBlank { "https://dashscope.aliyuncs.com/compatible-mode/v1" },
            model = model.ifBlank { "qwen3-vl-plus" }
        )

        mobileAgent.value = MobileAgent(vlmClient, deviceController, this)

        // 创建执行记录
        val record = ExecutionRecord(
            title = generateTitle(instruction),
            instruction = instruction,
            startTime = System.currentTimeMillis(),
            status = ExecutionStatus.RUNNING
        )

        lifecycleScope.launch {
            // 保存初始记录
            executionRepository.saveRecord(record)
            executionRecords.value = executionRepository.getAllRecords()

            try {
                val result = mobileAgent.value!!.runInstruction(instruction)

                // 更新记录状态
                val agentState = mobileAgent.value?.state?.value
                val steps = agentState?.executionSteps ?: emptyList()

                val updatedRecord = record.copy(
                    endTime = System.currentTimeMillis(),
                    status = if (result.success) ExecutionStatus.COMPLETED else ExecutionStatus.FAILED,
                    steps = steps,
                    resultMessage = result.message
                )
                executionRepository.saveRecord(updatedRecord)
                executionRecords.value = executionRepository.getAllRecords()

                Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()

                // 延迟3秒后清空日志，恢复默认状态
                kotlinx.coroutines.delay(3000)
                mobileAgent.value?.clearLogs()
            } catch (e: Exception) {
                // 更新失败记录
                val updatedRecord = record.copy(
                    endTime = System.currentTimeMillis(),
                    status = ExecutionStatus.FAILED,
                    resultMessage = "错误: ${e.message}"
                )
                executionRepository.saveRecord(updatedRecord)
                executionRecords.value = executionRepository.getAllRecords()

                Toast.makeText(this@MainActivity, "错误: ${e.message}", Toast.LENGTH_LONG).show()

                // 延迟3秒后清空日志，恢复默认状态
                kotlinx.coroutines.delay(3000)
                mobileAgent.value?.clearLogs()
            }
        }
    }

    private fun generateTitle(instruction: String): String {
        // 生成简短标题
        val keywords = listOf(
            "打开" to "打开应用",
            "点" to "点餐",
            "发" to "发送消息",
            "看" to "浏览内容",
            "搜" to "搜索",
            "设置" to "调整设置",
            "播放" to "播放媒体"
        )
        for ((key, title) in keywords) {
            if (instruction.contains(key)) {
                return title
            }
        }
        return if (instruction.length > 10) instruction.take(10) + "..." else instruction
    }
}

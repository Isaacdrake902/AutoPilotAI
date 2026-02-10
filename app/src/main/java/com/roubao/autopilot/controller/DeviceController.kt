package com.roubao.autopilot.controller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.roubao.autopilot.IShellService
import com.roubao.autopilot.service.ShellService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Device Controller - Execute shell command via Shizuku UserService
 */
class DeviceController(private val context: Context? = null) {

    companion object {
        // Use /data/local/tmp as shell user has permission
        private const val SCREENSHOT_PATH = "/data/local/tmp/autopilot_screen.png"
    }

    private var shellService: IShellService? = null
    private var serviceBound = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clipboardManager: ClipboardManager? by lazy {
        context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(
            "com.roubao.autopilot",
            ShellService::class.java.name
        )
    )
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(true)
        .version(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shellService = IShellService.Stub.asInterface(service)
            serviceBound = true
            println("[DeviceController] ShellService connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            serviceBound = false
            println("[DeviceController] ShellService disconnected")
        }
    }

    /**
     * Bind Shizuku UserService
     */
    fun bindService() {
        if (!isShizukuAvailable()) {
            println("[DeviceController] Shizuku not available")
            return
        }
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Unbind service
     */
    fun unbindService() {
        try {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Check if Shizuku is available
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if service is available
     */
    fun isAvailable(): Boolean {
        return serviceBound && shellService != null
    }

    /**
     * Shizuku Privilege Level
     */
    enum class ShizukuPrivilegeLevel {
        NONE,       // Not Connected
        ADB,        // ADB Mode (UID 2000)
        ROOT        // Root Mode (UID 0)
    }

    /**
     * Get current Shizuku Privilege Level
     * UID 0 = root, UID 2000 = shell (ADB)
     */
    fun getShizukuPrivilegeLevel(): ShizukuPrivilegeLevel {
        if (!isAvailable()) {
            return ShizukuPrivilegeLevel.NONE
        }
        return try {
            val uid = Shizuku.getUid()
            println("[DeviceController] Shizuku UID: $uid")
            when (uid) {
                0 -> ShizukuPrivilegeLevel.ROOT
                else -> ShizukuPrivilegeLevel.ADB
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ShizukuPrivilegeLevel.NONE
        }
    }

    /**
     * Execute shell command (Local, no root/adb)
     */
    private fun execLocal(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            reader.close()
            output
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    /**
     * Execute shell command (via Shizuku)
     */
    private fun exec(command: String): String {
        return try {
            shellService?.exec(command) ?: execLocal(command)
        } catch (e: Exception) {
            e.printStackTrace()
            execLocal(command)
        }
    }

    /**
     * Click screen
     */
    fun tap(x: Int, y: Int) {
        exec("input tap $x $y")
    }

    /**
     * Long press
     */
    fun longPress(x: Int, y: Int, durationMs: Int = 1000) {
        exec("input swipe $x $y $x $y $durationMs")
    }

    /**
     * Double tap
     */
    fun doubleTap(x: Int, y: Int) {
        exec("input tap $x $y && input tap $x $y")
    }

    /**
     * Swipe
     */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 500) {
        exec("input swipe $x1 $y1 $x2 $y2 $durationMs")
    }

    /**
     * Input text (Use Clipboard method for special characters compatibility)
     */
    fun type(text: String) {
        // Check for non-ASCII characters
        val hasNonAscii = text.any { it.code > 127 }

        if (hasNonAscii) {
            // Use Clipboard method for non-ASCII characters
            typeViaClipboard(text)
        } else {
            // Use input text for ASCII
            val escaped = text.replace("'", "'\\''")
            exec("input text '$escaped'")
        }
    }

    /**
     * Input text via Clipboard method
     * Use Android ClipboardManager API to set clipboard then send Paste key event
     */
    private fun typeViaClipboard(text: String) {
        println("[DeviceController] Attempting input text: $text")

        // Method 1: Use Android Clipboard API + Paste (Most reliable, no extra App required)
        if (clipboardManager != null) {
            try {
                // Use CountDownLatch to wait for clipboard set
                val latch = CountDownLatch(1)
                var clipboardSet = false

                // Must operate Clipboard on main thread
                mainHandler.post {
                    try {
                        val clip = ClipData.newPlainText("autopilot_input", text)
                        clipboardManager?.setPrimaryClip(clip)
                        clipboardSet = true
                        println("[DeviceController] ✅ Clipboard set: $text")
                    } catch (e: Exception) {
                        println("[DeviceController] ❌ Set Clipboard exception: ${e.message}")
                    } finally {
                        latch.countDown()
                    }
                }

                // Wait for clipboard set (Max 1s)
                val success = latch.await(1, TimeUnit.SECONDS)
                if (!success) {
                    println("[DeviceController] ❌ Wait clipboard timeout")
                    return
                }

                if (!clipboardSet) {
                    println("[DeviceController] ❌ Set Clipboard failed")
                    return
                }

                // Wait a bit to ensure Clipboard is ready
                Thread.sleep(200)

                // Send Paste key event (KEYCODE_PASTE = 279)
                exec("input keyevent 279")
                println("[DeviceController] ✅ Paste key sent")
                return
            } catch (e: Exception) {
                println("[DeviceController] ❌ Clipboard method failed: ${e.message}")
                e.printStackTrace()
            }
        } else {
            println("[DeviceController] ❌ ClipboardManager is null or Context not set")
        }

        // Method 2: Use ADB Keyboard broadcast (Fallback, requires ADBKeyboard installed)
        val escaped = text.replace("\"", "\\\"")
        val adbKeyboardResult = exec("am broadcast -a ADB_INPUT_TEXT --es msg \"$escaped\"")
        println("[DeviceController] ADBKeyboard broadcast result: $adbKeyboardResult")

        if (adbKeyboardResult.contains("result=0")) {
            println("[DeviceController] ✅ ADBKeyboard Input success")
            return
        }

        // Method 3: Use cmd input text (Android 12+ may support UTF-8)
        println("[DeviceController] Trying cmd input text...")
        exec("cmd input text '$text'")
    }

    /**
     * Input text (Char by char, better compatibility)
     */
    fun typeCharByChar(text: String) {
        text.forEach { char ->
            when {
                char == ' ' -> exec("input text %s")
                char == '\n' -> exec("input keyevent 66")
                char.isLetterOrDigit() && char.code <= 127 -> exec("input text $char")
                char in "-.,!?@'/:;()" -> exec("input text \"$char\"")
                else -> {
                    // Use broadcast for non-ASCII characters
                    exec("am broadcast -a ADB_INPUT_TEXT --es msg \"$char\"")
                }
            }
        }
    }

    /**
     * Back key
     */
    fun back() {
        exec("input keyevent 4")
    }

    /**
     * Home key
     */
    fun home() {
        exec("input keyevent 3")
    }

    /**
     * Enter key
     */
    fun enter() {
        exec("input keyevent 66")
    }

    private var cacheDir: File? = null

    fun setCacheDir(dir: File) {
        cacheDir = dir
    }

    /**
     * Screenshot Result
     */
    data class ScreenshotResult(
        val bitmap: Bitmap,
        val isSensitive: Boolean = false,  // Is sensitive page (Screenshot failed)
        val isFallback: Boolean = false    // Is fallback black screen placeholder
    )

    /**
     * Screenshot - Use /data/local/tmp and set global read permission
     * Return black screen placeholder on failure (Fallback)
     */
    suspend fun screenshotWithFallback(): ScreenshotResult = withContext(Dispatchers.IO) {
        try {
            // Screencap to /data/local/tmp and set permission for App to read
            val output = exec("screencap -p $SCREENSHOT_PATH && chmod 666 $SCREENSHOT_PATH")
            delay(500)

            // Check if screenshot failed (Sensitive page protection)
            if (output.contains("Status: -1") || output.contains("Failed") || output.contains("error")) {
                println("[DeviceController] Screenshot blocked (sensitive screen), returning fallback")
                return@withContext createFallbackScreenshot(isSensitive = true)
            }

            // Try direct read
            val file = File(SCREENSHOT_PATH)
            if (file.exists() && file.canRead() && file.length() > 0) {
                println("[DeviceController] Reading screenshot from: $SCREENSHOT_PATH, size: ${file.length()}")
                val bitmap = BitmapFactory.decodeFile(SCREENSHOT_PATH)
                if (bitmap != null) {
                    return@withContext ScreenshotResult(bitmap)
                }
            }

            // If cannot read directly, read binary data via shell cat
            println("[DeviceController] Cannot read directly, trying shell cat...")
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $SCREENSHOT_PATH"))
            val bytes = process.inputStream.readBytes()
            process.waitFor()

            if (bytes.isNotEmpty()) {
                println("[DeviceController] Read ${bytes.size} bytes via shell")
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    return@withContext ScreenshotResult(bitmap)
                }
            }

            println("[DeviceController] Screenshot file empty or not accessible, returning fallback")
            createFallbackScreenshot(isSensitive = false)
        } catch (e: Exception) {
            e.printStackTrace()
            println("[DeviceController] Screenshot exception, returning fallback")
            createFallbackScreenshot(isSensitive = false)
        }
    }

    /**
     * Create black screen placeholder (Fallback)
     */
    private fun createFallbackScreenshot(isSensitive: Boolean): ScreenshotResult {
        val (width, height) = getScreenSize()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Default is black, no fill needed
        return ScreenshotResult(
            bitmap = bitmap,
            isSensitive = isSensitive,
            isFallback = true
        )
    }

    /**
     * Screenshot - Use /data/local/tmp and set global read permission
     * @deprecated Use screenshotWithFallback() instead
     */
    suspend fun screenshot(): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // Screencap to /data/local/tmp and set permission for App to read
            exec("screencap -p $SCREENSHOT_PATH && chmod 666 $SCREENSHOT_PATH")
            delay(500)

            // Try direct read
            val file = File(SCREENSHOT_PATH)
            if (file.exists() && file.canRead() && file.length() > 0) {
                println("[DeviceController] Reading screenshot from: $SCREENSHOT_PATH, size: ${file.length()}")
                return@withContext BitmapFactory.decodeFile(SCREENSHOT_PATH)
            }

            // If cannot read directly, read binary data via shell cat
            println("[DeviceController] Cannot read directly, trying shell cat...")
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $SCREENSHOT_PATH"))
            val bytes = process.inputStream.readBytes()
            process.waitFor()

            if (bytes.isNotEmpty()) {
                println("[DeviceController] Read ${bytes.size} bytes via shell")
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else {
                println("[DeviceController] Screenshot file empty or not accessible")
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Get screen size (Considering orientation)
     */
    fun getScreenSize(): Pair<Int, Int> {
        val output = exec("wm size")
        // Output format: Physical size: 1080x2400
        val match = Regex("(\\d+)x(\\d+)").find(output)
        val (physicalWidth, physicalHeight) = if (match != null) {
            val (w, h) = match.destructured
            Pair(w.toInt(), h.toInt())
        } else {
            Pair(1080, 2400)
        }

        // Detect screen orientation
        val orientation = getScreenOrientation()
        return if (orientation == 1 || orientation == 3) {
            // Landscape: Swap width and height
            Pair(physicalHeight, physicalWidth)
        } else {
            // Portrait
            Pair(physicalWidth, physicalHeight)
        }
    }

    /**
     * Get screen orientation
     * @return 0=Portrait, 1=Landscape(90°), 2=Portrait(180°), 3=Landscape(270°)
     */
    private fun getScreenOrientation(): Int {
        val output = exec("dumpsys window displays | grep mCurrentOrientation")
        // Output format: mCurrentOrientation=0 or mCurrentOrientation=1
        val match = Regex("mCurrentOrientation=(\\d)").find(output)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /**
     * Open App - Supports package name or app name
     */
    fun openApp(packageName: String) {
        // Mapping of common app names to package names (Fallback)
        val packageMap = mapOf(
            "settings" to "com.android.settings",
            "browser" to "com.android.browser",
            "chrome" to "com.android.chrome",
            "camera" to "com.android.camera",
            "phone" to "com.android.dialer",
            "dialer" to "com.android.dialer",
            "contacts" to "com.android.contacts",
            "messages" to "com.android.mms",
            "gallery" to "com.android.gallery3d",
            "clock" to "com.android.deskclock",
            "calculator" to "com.android.calculator2",
            "calendar" to "com.android.calendar",
            "files" to "com.android.documentsui"
        )

        val lowerName = packageName.lowercase().trim()
        val finalPackage = if (packageName.contains(".")) {
            // Already package name format
            packageName
        } else {
            // Try to find in mapping
            packageMap[lowerName] ?: packageName
        }

        // Use monkey command to start app (Most reliable)
        val result = exec("monkey -p $finalPackage -c android.intent.category.LAUNCHER 1 2>/dev/null")
        println("[DeviceController] openApp: $packageName -> $finalPackage, result: $result")
    }

    /**
     * Open via Intent
     */
    fun openIntent(action: String, data: String? = null) {
        val cmd = buildString {
            append("am start -a $action")
            if (data != null) {
                append(" -d \"$data\"")
            }
        }
        exec(cmd)
    }

    /**
     * Open DeepLink
     */
    fun openDeepLink(uri: String) {
        exec("am start -a android.intent.action.VIEW -d \"$uri\"")
    }
}

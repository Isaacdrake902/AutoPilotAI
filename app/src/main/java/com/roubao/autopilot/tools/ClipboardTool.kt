package com.roubao.autopilot.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Clipboard工具
 *
 * 提供Clipboard的读写功能
 */
class ClipboardTool(private val context: Context) : Tool {

    override val name = "clipboard"
    override val displayName = "Clipboard"
    override val description = "读取或写入系统ClipboardContent"

    override val params = listOf(
        ToolParam(
            name = "action",
            type = "string",
            description = "操作类型:read（读取）或 write（写入）",
            required = true
        ),
        ToolParam(
            name = "text",
            type = "string",
            description = "要写入的文本（action=write 时必填）",
            required = false
        ),
        ToolParam(
            name = "label",
            type = "string",
            description = "Clipboard标签（可选）",
            required = false,
            defaultValue = "roubao"
        )
    )

    private val clipboardManager: ClipboardManager by lazy {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override suspend fun execute(params: Map<String, Any?>): ToolResult {
        val action = params["action"] as? String
            ?: return ToolResult.Error("缺少 action 参数")

        return when (action.lowercase()) {
            "read" -> readClipboard()
            "write" -> {
                val text = params["text"] as? String
                    ?: return ToolResult.Error("write 操作Requires text 参数")
                val label = params["label"] as? String ?: "roubao"
                writeClipboard(text, label)
            }
            else -> ToolResult.Error("不支持的操作: $action（只支持 read/write）")
        }
    }

    /**
     * 读取ClipboardContent
     */
    private suspend fun readClipboard(): ToolResult = suspendCancellableCoroutine { cont ->
        mainHandler.post {
            try {
                val clip = clipboardManager.primaryClip
                if (clip == null || clip.itemCount == 0) {
                    cont.resume(ToolResult.Success(data = "", message = "Clipboard为空"))
                } else {
                    val text = clip.getItemAt(0).coerceToText(context).toString()
                    cont.resume(ToolResult.Success(
                        data = text,
                        message = "已读取ClipboardContent（${text.length} 字符）"
                    ))
                }
            } catch (e: Exception) {
                cont.resume(ToolResult.Error("读取ClipboardFailed: ${e.message}"))
            }
        }
    }

    /**
     * 写入ClipboardContent
     */
    private suspend fun writeClipboard(text: String, label: String): ToolResult = suspendCancellableCoroutine { cont ->
        mainHandler.post {
            try {
                val clip = ClipData.newPlainText(label, text)
                clipboardManager.setPrimaryClip(clip)
                cont.resume(ToolResult.Success(
                    data = text,
                    message = "已写入Clipboard（${text.length} 字符）"
                ))
            } catch (e: Exception) {
                cont.resume(ToolResult.Error("写入ClipboardFailed: ${e.message}"))
            }
        }
    }

    /**
     * 同steps读取（for非协程环境）
     */
    fun readSync(): String? {
        var result: String? = null
        val latch = java.util.concurrent.CountDownLatch(1)

        mainHandler.post {
            try {
                val clip = clipboardManager.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    result = clip.getItemAt(0).coerceToText(context).toString()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                latch.countDown()
            }
        }

        latch.await(1, java.util.concurrent.TimeUnit.SECONDS)
        return result
    }

    /**
     * 同steps写入（for非协程环境）
     */
    fun writeSync(text: String, label: String = "roubao"): Boolean {
        var success = false
        val latch = java.util.concurrent.CountDownLatch(1)

        mainHandler.post {
            try {
                val clip = ClipData.newPlainText(label, text)
                clipboardManager.setPrimaryClip(clip)
                success = true
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                latch.countDown()
            }
        }

        latch.await(1, java.util.concurrent.TimeUnit.SECONDS)
        return success
    }
}

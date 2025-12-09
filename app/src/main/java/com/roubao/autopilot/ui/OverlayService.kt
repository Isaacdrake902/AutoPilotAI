package com.roubao.autopilot.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.roubao.autopilot.MainActivity
import com.roubao.autopilot.R

/**
 * 七彩悬浮窗服务 - 显示当前执行步骤
 * 放在屏幕顶部状态栏下方，不影响截图识别
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var textView: TextView? = null
    private var animator: ValueAnimator? = null

    companion object {
        private var instance: OverlayService? = null
        private var stopCallback: (() -> Unit)? = null

        fun show(context: Context, text: String, onStop: (() -> Unit)? = null) {
            stopCallback = onStop
            instance?.updateText(text) ?: run {
                val intent = Intent(context, OverlayService::class.java).apply {
                    putExtra("text", text)
                }
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun hide(context: Context) {
            stopCallback = null
            context.stopService(Intent(context, OverlayService::class.java))
        }

        fun update(text: String) {
            instance?.updateText(text)
        }

        /** 截图时临时隐藏悬浮窗 */
        fun setVisible(visible: Boolean) {
            instance?.overlayView?.post {
                instance?.overlayView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundService()
        createOverlayView()
    }

    private fun startForegroundService() {
        val channelId = "baozi_overlay"
        val channelName = "肉包状态"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示肉包执行状态"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("肉包运行中")
            .setContentText("正在执行自动化任务...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1001, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra("text") ?: "AutoPilot"
        updateText(text)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        animator?.cancel()
        overlayView?.let { windowManager?.removeView(it) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlayView() {
        // 容器
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 12)
        }

        // 七彩渐变背景
        val gradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 30f
            setStroke(2, Color.WHITE)
        }
        container.background = gradientDrawable

        // 状态文字
        textView = TextView(this).apply {
            text = "肉包"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(16, 4, 16, 4)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
        }
        container.addView(textView)

        // 分隔线
        val divider = View(this).apply {
            setBackgroundColor(Color.WHITE)
            alpha = 0.5f
        }
        val dividerParams = LinearLayout.LayoutParams(2, 36).apply {
            setMargins(12, 0, 12, 0)
        }
        container.addView(divider, dividerParams)

        // 停止按钮
        val stopButton = TextView(this).apply {
            text = "⏹ 停止"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(16, 4, 16, 4)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                stopCallback?.invoke()
                hide(this@OverlayService)
            }
        }
        container.addView(stopButton)

        // 动画：七彩渐变流动效果
        startRainbowAnimation(gradientDrawable)

        val params = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON  // 保持屏幕常亮
            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        // 添加拖动功能
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val dragThreshold = 10f

        container.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY
                    if (kotlin.math.abs(deltaX) > dragThreshold || kotlin.math.abs(deltaY) > dragThreshold) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialX + deltaX.toInt()
                        params.y = initialY + deltaY.toInt()
                        windowManager?.updateViewLayout(container, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // 如果不是拖动，传递点击事件给子 View
                        false
                    } else {
                        true
                    }
                }
                else -> false
            }
        }

        overlayView = container
        windowManager?.addView(overlayView, params)
    }

    private fun startRainbowAnimation(drawable: GradientDrawable) {
        val colors = intArrayOf(
            Color.parseColor("#FF6B6B"), // 红
            Color.parseColor("#FFA94D"), // 橙
            Color.parseColor("#FFE066"), // 黄
            Color.parseColor("#69DB7C"), // 绿
            Color.parseColor("#4DABF7"), // 蓝
            Color.parseColor("#9775FA"), // 紫
            Color.parseColor("#F783AC"), // 粉
            Color.parseColor("#FF6B6B")  // 回到红
        )

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART

            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                val index = (fraction * (colors.size - 1)).toInt()
                val nextIndex = minOf(index + 1, colors.size - 1)
                val localFraction = (fraction * (colors.size - 1)) - index

                val color1 = interpolateColor(colors[index], colors[nextIndex], localFraction)
                val color2 = interpolateColor(
                    colors[(index + 2) % colors.size],
                    colors[(nextIndex + 2) % colors.size],
                    localFraction
                )
                val color3 = interpolateColor(
                    colors[(index + 4) % colors.size],
                    colors[(nextIndex + 4) % colors.size],
                    localFraction
                )

                drawable.colors = intArrayOf(color1, color2, color3)
                drawable.orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            start()
        }
    }

    private fun interpolateColor(startColor: Int, endColor: Int, fraction: Float): Int {
        val startA = Color.alpha(startColor)
        val startR = Color.red(startColor)
        val startG = Color.green(startColor)
        val startB = Color.blue(startColor)

        val endA = Color.alpha(endColor)
        val endR = Color.red(endColor)
        val endG = Color.green(endColor)
        val endB = Color.blue(endColor)

        return Color.argb(
            (startA + (endA - startA) * fraction).toInt(),
            (startR + (endR - startR) * fraction).toInt(),
            (startG + (endG - startG) * fraction).toInt(),
            (startB + (endB - startB) * fraction).toInt()
        )
    }

    private fun updateText(text: String) {
        textView?.post {
            textView?.text = text
        }
    }
}

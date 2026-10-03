package com.bobo.touping.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 反向控制的关键组件：把电脑端发来的触摸指令注入到系统。
 *
 * 必须由用户在「设置 → 无障碍」里手动开启，这是 Android 的硬性要求。
 *
 * 已知限制：
 * - 注入本身有 30~80ms 延迟，快速拖动会感觉不够跟手。
 * - 一次 dispatchGesture 是一次完整手势，跨调用的连续拖动（按住不放）需要
 *   用 StrokeDescription.continueStroke() 串联，后续版本再实现。
 */
class TouchAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍服务已连接，反向控制可用")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** 归一化坐标 → 屏幕像素。协议里一律传 [0,1]，这样分辨率/旋转变化不用改协议。 */
    private fun toPixels(nx: Float, ny: Float): Pair<Float, Float> {
        val dm = resources.displayMetrics
        val x = nx.coerceIn(0f, 1f) * dm.widthPixels
        val y = ny.coerceIn(0f, 1f) * dm.heightPixels
        return x to y
    }

    fun tap(nx: Float, ny: Float): Boolean {
        val (x, y) = toPixels(nx, ny)
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    fun swipe(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long,
    ): Boolean {
        val (x1, y1) = toPixels(fromX, fromY)
        val (x2, y2) = toPixels(toX, toY)
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(
            path,
            0,
            durationMs.coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS)
        )
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    private fun dispatch(gesture: GestureDescription): Boolean = try {
        dispatchGesture(gesture, null, null)
    } catch (t: Throwable) {
        Log.w(TAG, "手势注入失败", t)
        false
    }

    companion object {
        private const val TAG = "TouchA11y"
        private const val TAP_DURATION_MS = 40L
        private const val MIN_SWIPE_MS = 20L
        private const val MAX_SWIPE_MS = 2_000L

        @Volatile
        var instance: TouchAccessibilityService? = null

        val isReady: Boolean
            get() = instance != null
    }
}

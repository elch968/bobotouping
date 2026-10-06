package com.bobo.touping.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
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

    /**
     * 触摸注入用的屏幕尺寸。
     *
     * 必须和「电脑端看到的画面」是同一套坐标系：画面是 MediaProjection 镜像的
     * **整块屏幕**（状态栏、导航栏都在里面），所以这里必须取真实屏幕尺寸。
     *
     * 不能用 resources.displayMetrics —— 它是 App 的可用区域，绝大多数机型上已经
     * 扣掉了底部导航栏（部分机型还要再扣掉挖孔安全区），比真实屏幕矮一截。
     * 拿它换算 [0,1] 坐标，所有触点会整体上移，越往屏幕下方偏得越多：
     * 点桌面图标点到相邻的应用、点页面底部的按钮完全点不中，就是这么来的。
     *
     * 每次都重新取：投屏过程中屏幕旋转、折叠屏展开、系统「显示大小」调整时尺寸都会变，
     * 缓存下来就会按旧尺寸换算。
     */
    private fun screenSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        try {
            val display = getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    return metrics.widthPixels to metrics.heightPixels
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "读取真实屏幕尺寸失败", t)
        }
        // 兜底：拿不到 Display 时退回 App 可用区域（可能偏小，但总比不注入好）
        val fallback = resources.displayMetrics
        return fallback.widthPixels to fallback.heightPixels
    }

    /** 归一化坐标 → 屏幕像素。协议里一律传 [0,1]，这样分辨率/旋转变化不用改协议。 */
    private fun toPixels(nx: Float, ny: Float): Pair<Float, Float> {
        val (screenW, screenH) = screenSize()
        val width = screenW.coerceAtLeast(1)
        val height = screenH.coerceAtLeast(1)
        // 夹到「屏幕内最后一个像素」：归一化值正好是 1.0 时算出来就是 width/height，
        // 那已经落在屏幕外了，系统会把这个手势判成非法直接丢掉（点右下角没反应）。
        val x = (nx.coerceIn(0f, 1f) * width).coerceIn(0f, (width - 1).toFloat())
        val y = (ny.coerceIn(0f, 1f) * height).coerceIn(0f, (height - 1).toFloat())
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
        val ok = dispatchGesture(gesture, null, null)
        if (!ok) {
            // 返回 false 说明系统拒绝了这次注入（无障碍服务被回收/连接断开）。
            // 这里必须留痕，否则电脑端只会看到「点了没反应」。
            Log.w(TAG, "手势注入被系统拒绝（无障碍服务可能已断开）")
        }
        ok
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

package com.bobo.touping

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.bobo.touping.control.TouchAccessibilityService
import com.bobo.touping.core.AudioEncoder
import com.bobo.touping.core.ControlClient
import com.bobo.touping.core.Protocol
import com.bobo.touping.core.UdpSender
import com.bobo.touping.core.VideoEncoder
import org.json.JSONObject

/**
 * 投屏主服务：抓屏 → 编码 → 发送，并处理 PC 端下发的控制指令。
 */
class ScreenCastService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var videoSender: UdpSender? = null
    private var audioSender: UdpSender? = null
    private var control: ControlClient? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDpi = 0
    private var videoWidth = 0
    private var videoHeight = 0

    private var fps = DEFAULT_FPS
    private var bitrate = DEFAULT_BITRATE
    private var audioEnabled = true

    private var host = ""
    private var ctrlPort = Protocol.DEFAULT_CTRL_PORT
    private var videoPort = Protocol.DEFAULT_VIDEO_PORT
    private var audioPort = Protocol.DEFAULT_AUDIO_PORT
    private var token = ""

    private var lastFrameTsMs = 0L
    private var framesEncoded = 0L
    private var lastStatsAt = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection 被系统停止")
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        host = intent?.getStringExtra(EXTRA_HOST).orEmpty()
        ctrlPort = intent?.getIntExtra(EXTRA_CTRL_PORT, Protocol.DEFAULT_CTRL_PORT)
            ?: Protocol.DEFAULT_CTRL_PORT
        videoPort = intent?.getIntExtra(EXTRA_VIDEO_PORT, Protocol.DEFAULT_VIDEO_PORT)
            ?: Protocol.DEFAULT_VIDEO_PORT
        audioPort = intent?.getIntExtra(EXTRA_AUDIO_PORT, Protocol.DEFAULT_AUDIO_PORT)
            ?: Protocol.DEFAULT_AUDIO_PORT
        audioEnabled = intent?.getBooleanExtra(EXTRA_AUDIO, true) ?: true
        token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()

        if (resultData == null || resultCode == 0 || host.isBlank()) {
            Log.w(TAG, "启动参数不完整")
            rememberError("投屏参数不完整（可能是投屏授权被拒绝，或没拿到电脑地址）")
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14+ 要求：必须先成为 mediaProjection 类型的前台服务，才能取 MediaProjection。
        startForegroundCompat()

        return try {
            startCasting(resultCode, resultData)
            START_NOT_STICKY
        } catch (t: Throwable) {
            Log.e(TAG, "启动投屏失败", t)
            rememberError(t.message ?: t.javaClass.simpleName)
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun startCasting(resultCode: Int, resultData: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp = manager.getMediaProjection(resultCode, resultData)
            ?: throw IllegalStateException("无法获取 MediaProjection")
        projection = mp

        // Android 14+ 硬性要求：必须在 createVirtualDisplay 之前注册回调。
        mp.registerCallback(projectionCallback, handler)

        readScreenMetrics()
        computeVideoSize()
        bitrate = computeBitrate()

        val videoOut = UdpSender(host, videoPort)
        videoSender = videoOut

        val encoder = VideoEncoder(
            width = videoWidth,
            height = videoHeight,
            fps = fps,
            bitrate = bitrate,
        ) { data, keyframe, config, tsMs ->
            framesEncoded++
            lastFrameTsMs = tsMs
            videoOut.send(data, data.size, keyframe, config, tsMs)
        }
        val inputSurface = encoder.start()
        videoEncoder = encoder

        virtualDisplay = mp.createVirtualDisplay(
            "bobotouping",
            videoWidth,
            videoHeight,
            screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            handler
        )

        if (audioEnabled) {
            val audioOut = UdpSender(host, audioPort)
            audioSender = audioOut
            val audio = AudioEncoder(mp) { data, tsMs ->
                audioOut.sendSingle(data, data.size, tsMs)
            }
            if (audio.start()) {
                audioEncoder = audio
            } else {
                audioSender = null
                audioOut.close()
            }
        }

        connectControl()
        startStatsLoop()

        Log.i(TAG, "投屏已启动 ${videoWidth}x$videoHeight@$fps -> $host")
        rememberStartInfo(encoder)
    }

    /** 把这次投屏用的编码器信息记下来，手机界面上会显示，便于排查画质/起不来问题。 */
    private fun rememberStartInfo(encoder: VideoEncoder) {
        prefs().edit()
            .putString(KEY_LAST_ERROR, "")
            .putString(
                KEY_LAST_INFO,
                "编码器：${encoder.codecName} / ${videoWidth}x$videoHeight@$fps / ${encoder.usedBitrate / 1000} kbps"
            )
            .apply()
    }

    private fun rememberError(message: String) {
        prefs().edit().putString(KEY_LAST_ERROR, message).apply()
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun readScreenMetrics() {
        val metrics = DisplayMetrics()
        val wm = getSystemService(WindowManager::class.java)
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDpi = metrics.densityDpi
    }

    /** 按长边上限等比缩放，并保证宽高都是偶数（H.264 要求）。 */
    private fun computeVideoSize() {
        val longEdge = maxOf(screenWidth, screenHeight)
        val scale = if (longEdge > MAX_LONG_EDGE) MAX_LONG_EDGE.toFloat() / longEdge else 1f
        videoWidth = ((screenWidth * scale).toInt() / 2) * 2
        videoHeight = ((screenHeight * scale).toInt() / 2) * 2
        if (videoWidth <= 0) videoWidth = 2
        if (videoHeight <= 0) videoHeight = 2
    }

    /**
     * 码率按「像素数 × 帧率」估：屏幕内容（文字、缩略图、图标）比普通视频更吃码率，
     * 原来的 8Mbps@60fps 只有约 0.065 bit/像素/帧，高细节页面会被压出马赛克。
     */
    private fun computeBitrate(): Int {
        val pixels = videoWidth.toLong() * videoHeight.toLong()
        return (pixels * fps * BITS_PER_PIXEL)
            .toInt()
            .coerceIn(MIN_BITRATE, MAX_BITRATE)
    }

    private fun connectControl() {
        val client = ControlClient(
            host = host,
            port = ctrlPort,
            onMessage = ::handleControlMessage,
            onClosed = { reason ->
                Log.i(TAG, "控制通道关闭: $reason")
                if (reason != null) {
                    handler.post { stopSelf() }
                }
            },
        )
        control = client
        client.connect()
        client.send(
            JSONObject().apply {
                put("t", "hello")
                put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("android", Build.VERSION.SDK_INT)
                put("w", screenWidth)
                put("h", screenHeight)
                put("dpi", screenDpi)
                put("videoW", videoWidth)
                put("videoH", videoHeight)
                put("audio", audioEncoder != null)
                put("touch", TouchAccessibilityService.isReady)
                put("ver", Protocol.VERSION)
                put("token", token)
            }
        )
    }

    private fun handleControlMessage(msg: JSONObject) {
        when (msg.optString("t")) {
            "ping" -> control?.send(JSONObject().apply {
                put("t", "pong")
                put("ts", msg.optLong("ts"))
            })

            "request_keyframe" -> videoEncoder?.requestKeyFrame()

            "config" -> applyConfig(msg)

            "touch" -> handleTouch(msg)

            "key" -> handleKey(msg)

            "stop" -> handler.post { stopSelf() }
        }
    }

    private fun applyConfig(msg: JSONObject) {
        // 分辨率变更需要重建 VirtualDisplay + 编码器，这里先只支持码率与帧率热更新。
        if (msg.has("bitrate")) {
            bitrate = msg.optInt("bitrate", bitrate)
        }
        val newFps = msg.optInt("fps", fps)
        if (newFps > 0) fps = newFps
        Log.i(TAG, "config: fps=$fps bitrate=$bitrate（需重建编码器才能完全生效）")
    }

    private fun handleTouch(msg: JSONObject) {
        val service = TouchAccessibilityService.instance ?: return
        val x = msg.optDouble("x", 0.0).toFloat()
        val y = msg.optDouble("y", 0.0).toFloat()

        when (msg.optString("action")) {
            "tap", "down", "up" -> service.tap(x, y)
            "swipe" -> {
                val x2 = msg.optDouble("x2", x.toDouble()).toFloat()
                val y2 = msg.optDouble("y2", y.toDouble()).toFloat()
                val duration = msg.optLong("duration", 150L)
                service.swipe(x, y, x2, y2, duration)
            }
        }
    }

    private fun handleKey(msg: JSONObject) {
        val service = TouchAccessibilityService.instance ?: return
        when (msg.optString("code")) {
            "back" -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            "recents" -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
        }
    }

    private fun startStatsLoop() {
        lastStatsAt = SystemClock.elapsedRealtime()
        handler.post(object : Runnable {
            override fun run() {
                if (videoEncoder == null) return
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - lastStatsAt
                if (elapsed >= 1000) {
                    val frames = framesEncoded
                    framesEncoded = 0
                    lastStatsAt = now
                    control?.send(
                        JSONObject().apply {
                            put("t", "stats")
                            put("fps", frames * 1000 / elapsed)
                            put("bytes", (videoSender?.sentBytes ?: 0) +
                                (audioSender?.sentBytes ?: 0))
                            put("audio", audioEncoder != null)
                            put("touch", TouchAccessibilityService.isReady)
                        }
                    )
                }
                handler.postDelayed(this, 500)
            }
        })
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_cast)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "停止投屏")
        handler.removeCallbacksAndMessages(null)

        try {
            control?.send(JSONObject().apply { put("t", "bye") })
        } catch (_: Throwable) {
        }
        control?.close()
        control = null

        audioEncoder?.stop()
        audioEncoder = null
        videoEncoder?.stop()
        videoEncoder = null

        virtualDisplay?.release()
        virtualDisplay = null

        audioSender?.close()
        audioSender = null
        videoSender?.close()
        videoSender = null

        projection?.unregisterCallback(projectionCallback)
        try {
            projection?.stop()
        } catch (_: Throwable) {
        }
        projection = null

        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenCastService"
        private const val PREFS_NAME = "bobotouping"
        private const val CHANNEL_ID = "bobotouping_cast"
        private const val NOTIFICATION_ID = 1001
        private const val DEFAULT_FPS = 60
        private const val DEFAULT_BITRATE = 12_000_000
        private const val MAX_LONG_EDGE = 1920

        /** 屏幕内容的经验码率：约 0.12 bit/像素/帧。 */
        private const val BITS_PER_PIXEL = 0.12
        private const val MIN_BITRATE = 10_000_000
        private const val MAX_BITRATE = 24_000_000

        /** 最后一次投屏失败原因 / 成功时的编码器信息，手机界面直接显示，便于排查。 */
        const val KEY_LAST_ERROR = "last_cast_error"
        const val KEY_LAST_INFO = "last_cast_info"

        const val ACTION_START = "com.bobo.touping.action.START"
        const val ACTION_STOP = "com.bobo.touping.action.STOP"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_HOST = "host"
        const val EXTRA_CTRL_PORT = "ctrl_port"
        const val EXTRA_VIDEO_PORT = "video_port"
        const val EXTRA_AUDIO_PORT = "audio_port"
        const val EXTRA_AUDIO = "audio_enabled"
        const val EXTRA_TOKEN = "token"

        fun start(
            context: Context,
            resultCode: Int,
            data: Intent,
            host: String,
            token: String,
            ctrlPort: Int,
            videoPort: Int,
            audioPort: Int,
        ) {
            val intent = Intent(context, ScreenCastService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_TOKEN, token)
                putExtra(EXTRA_CTRL_PORT, ctrlPort)
                putExtra(EXTRA_VIDEO_PORT, videoPort)
                putExtra(EXTRA_AUDIO_PORT, audioPort)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ScreenCastService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}

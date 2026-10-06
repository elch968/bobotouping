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

    /**
     * 反向控制的注入结果回报。
     *
     * 手机端注入失败（无障碍服务被系统回收、被电脑端断开等）以前是完全静默的，
     * 电脑端只知道「点了没反应」。这两个字段用来把失败告知电脑端，并且失败期间
     * 最多每秒回报一次，别把控制通道刷爆。
     */
    private var lastInputErrorAt = 0L
    private var inputErrorReported = false

    /**
     * 会话代号。teardown 时自增，用来丢弃「上一场投屏的异步回调」，
     * 避免它们误伤刚开始的新一场投屏。
     */
    private var sessionGeneration = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection 被系统停止")
            val generation = sessionGeneration
            handler.post {
                if (generation == sessionGeneration) stopSelf()
            }
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

        // 上一场投屏如果还没完全释放（用户「停止」后马上又点「开始」，或者直接重新扫码），
        // Android 14+ 会认为「已经有一个活跃的投屏会话」，新的录屏授权弹窗会被系统挡掉，
        // 用户看到的就是「点了没反应」。所以这里先彻底拆掉旧会话，再谈新的。
        teardownSession()

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
            teardownSession()
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun startCasting(resultCode: Int, resultData: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp = obtainProjection(manager, resultCode, resultData)
        projection = mp

        // Android 14+ 硬性要求：必须在 createVirtualDisplay 之前注册回调。
        mp.registerCallback(projectionCallback, handler)

        // 每次开始投屏都按默认参数重新来一遍，别把上一场调过的参数带进来。
        fps = DEFAULT_FPS
        framesEncoded = 0
        lastFrameTsMs = 0L

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
            startAudioAsync(mp)
        }

        updateRunningState(true)
        connectControl()
        startStatsLoop()

        Log.i(TAG, "投屏已启动 ${videoWidth}x$videoHeight@$fps -> $host")
        rememberStartInfo(encoder)
    }

    /**
     * 取 MediaProjection。上一场投屏刚 stop 时，个别机型还会「占用」一会儿，
     * 这里重试几次，避免用户刚好卡在这个窗口上时直接起不来。
     */
    private fun obtainProjection(
        manager: MediaProjectionManager,
        resultCode: Int,
        resultData: Intent,
    ): MediaProjection {
        var lastError: Throwable? = null
        for (attempt in 0 until PROJECTION_ATTEMPTS) {
            try {
                return manager.getMediaProjection(resultCode, resultData)
                    ?: throw IllegalStateException("无法获取 MediaProjection")
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "获取 MediaProjection 失败（第 ${attempt + 1} 次）", t)
                if (attempt < PROJECTION_ATTEMPTS - 1) {
                    // 有界等待（总计约 200ms），主线程能接受，别让用户看到「没反应」
                    SystemClock.sleep(PROJECTION_RETRY_DELAY_MS)
                }
            }
        }
        throw IllegalStateException(
            "拿不到投屏授权，上一次投屏可能还没释放，请稍等 1 秒再点一次",
            lastError
        )
    }

    /**
     * 音频初始化要走 AudioRecord + MediaCodec，个别机型上会卡住好几百毫秒甚至更久。
     * 放到后台线程，保证「开始投屏」这个点击本身立刻有反应。
     */
    private fun startAudioAsync(mp: MediaProjection) {
        val generation = sessionGeneration
        val audioOut = UdpSender(host, audioPort)
        audioSender = audioOut
        val audio = AudioEncoder(mp) { data, tsMs ->
            audioOut.sendSingle(data, data.size, tsMs)
        }
        Thread { initAudio(audio, audioOut, generation) }.apply {
            name = "audio-init"
            start()
        }
    }

    /** 音频初始化的实际工作，跑在后台线程上，避免卡住「开始投屏」这个点击。 */
    private fun initAudio(audio: AudioEncoder, audioOut: UdpSender, generation: Int) {
        val started = try {
            audio.start()
        } catch (t: Throwable) {
            Log.w(TAG, "音频启动失败", t)
            false
        }
        if (generation != sessionGeneration) {
            // 这一场已经结束了，别把资源挂在新的会话上
            audio.stop()
            return
        }
        if (started) {
            audioEncoder = audio
        } else {
            audio.stop()
            audioSender = null
            audioOut.close()
            Log.i(TAG, "音频不可用，只投画面")
        }
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
        val generation = sessionGeneration
        val client = ControlClient(
            host = host,
            port = ctrlPort,
            onMessage = ::handleControlMessage,
            onClosed = { reason -> onControlClosed(generation, reason) },
        )
        control = client
        client.connect()
        // 注意：hello 现在会先在 ControlClient 里排队，等 TCP 真正连上再发出去，
        // 不会再因为「连上之前就 send」而丢掉握手。
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
                // 音频在后台线程初始化，这里先按「打算开音频」上报，
                // 真实结果随后由 stats 消息刷新到电脑端。
                put("audio", audioEnabled)
                put("touch", TouchAccessibilityService.isReady)
                put("ver", Protocol.VERSION)
                put("token", token)
            }
        )
    }

    /** 控制通道断开。generation 用来忽略「上一场投屏的旧连接」的收尾回调。 */
    private fun onControlClosed(generation: Int, reason: String?) {
        Log.i(TAG, "控制通道关闭: $reason")
        if (generation != sessionGeneration) return
        updateControlState(false)
        if (reason != null) {
            rememberError(reason)
            handler.post { stopSelf() }
        }
    }

    private fun handleControlMessage(msg: JSONObject) {
        when (msg.optString("t")) {
            "hello_ack" -> updateControlState(true)

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
        val service = TouchAccessibilityService.instance
        if (service == null) {
            reportInputResult(false, "手机端无障碍服务未开启或已被系统关闭")
            return
        }
        val x = msg.optDouble("x", 0.0).toFloat()
        val y = msg.optDouble("y", 0.0).toFloat()

        val ok = when (msg.optString("action")) {
            "tap", "down", "up" -> service.tap(x, y)
            "swipe" -> {
                val x2 = msg.optDouble("x2", x.toDouble()).toFloat()
                val y2 = msg.optDouble("y2", y.toDouble()).toFloat()
                val duration = msg.optLong("duration", 150L)
                service.swipe(x, y, x2, y2, duration)
            }
            // 不认识的动作用不着报失败（协议日后扩展时，老版本安静跳过即可）
            else -> true
        }
        if (!ok) {
            reportInputResult(false, "触摸注入被系统拒绝（无障碍服务可能已被回收，请在手机上重新开启）")
        } else {
            reportInputResult(true, "")
        }
    }

    private fun handleKey(msg: JSONObject) {
        val service = TouchAccessibilityService.instance
        if (service == null) {
            reportInputResult(false, "手机端无障碍服务未开启或已被系统关闭")
            return
        }
        val ok = when (msg.optString("code")) {
            "back" -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            "recents" -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
            else -> true
        }
        if (!ok) {
            reportInputResult(false, "按键注入被系统拒绝（无障碍服务可能已被回收，请在手机上重新开启）")
        } else {
            reportInputResult(true, "")
        }
    }

    /**
     * 把一次注入的结果回报给电脑端。
     *
     * 只在「失败」和「失败之后重新成功」这两个时刻发消息：失败消息最多每秒一条
     * （拖拽/连点时会连续失败），恢复消息只发一次，电脑端据此把提示条收掉。
     */
    private fun reportInputResult(ok: Boolean, detail: String) {
        val client = control ?: return
        if (!ok) {
            val now = SystemClock.elapsedRealtime()
            if (inputErrorReported && now - lastInputErrorAt < INPUT_ERROR_INTERVAL_MS) return
            inputErrorReported = true
            lastInputErrorAt = now
            client.send(
                JSONObject().apply {
                    put("t", "input_error")
                    put("detail", detail)
                }
            )
        } else if (inputErrorReported) {
            inputErrorReported = false
            client.send(JSONObject().apply { put("t", "input_ok") })
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
                    // 每秒钟重新读一次屏幕尺寸：中途旋转/折叠时电脑端要靠它识别出
                    // 「画面方向和手机屏幕对不上了」，提醒用户重新开始投屏。
                    readScreenMetrics()
                    control?.send(
                        JSONObject().apply {
                            put("t", "stats")
                            put("fps", frames * 1000 / elapsed)
                            put("bytes", (videoSender?.sentBytes ?: 0) +
                                (audioSender?.sentBytes ?: 0))
                            put("audio", audioEncoder != null)
                            put("touch", TouchAccessibilityService.isReady)
                            put("w", screenWidth)
                            put("h", screenHeight)
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

        teardownSession()

        super.onDestroy()
    }

    /**
     * 释放当前会话的全部资源。必须在主线程调用，并且允许重复调用。
     *
     * 最关键的一步是 [MediaProjection.stop]：不 stop 的话，Android 14+ 会认为本机
     * 还有一个活跃的投屏会话，下一次请求录屏授权时系统直接不弹窗 ——
     * 用户看到的就是「停止投屏之后再点开始投屏，没反应」。
     */
    private fun teardownSession() {
        sessionGeneration += 1

        val hadSession = isRunning || projection != null || videoEncoder != null ||
            audioEncoder != null || control != null || videoSender != null ||
            audioSender != null || virtualDisplay != null
        if (!hadSession) return

        updateRunningState(false)
        updateControlState(false)

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

        stopForegroundCompat()
    }

    /** 把前台服务降下来并撤掉通知。重复调用无副作用。 */
    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "停止前台服务失败", t)
        }
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

        /** 上一场投屏刚停止时，个别机型会短暂占用 MediaProjection，最多重试两次。 */
        private const val PROJECTION_ATTEMPTS = 2
        private const val PROJECTION_RETRY_DELAY_MS = 200L

        /** 注入连续失败时，最多这么久回报一次，避免刷爆控制通道。 */
        private const val INPUT_ERROR_INTERVAL_MS = 1_000L

        /** 最后一次投屏失败原因 / 成功时的编码器信息，手机界面直接显示，便于排查。 */
        const val KEY_LAST_ERROR = "last_cast_error"
        const val KEY_LAST_INFO = "last_cast_info"

        /** 供界面读取的真实会话状态（不再靠界面自己猜）。 */
        @Volatile
        private var runningState = false

        /** 与电脑端的控制通道是否已经握手成功（反向控制、状态回传都靠它）。 */
        @Volatile
        private var controlState = false

        val isRunning: Boolean get() = runningState
        val controlConnected: Boolean get() = controlState

        /** 只给本服务用：更新对外暴露的状态。 */
        internal fun updateRunningState(running: Boolean) {
            runningState = running
        }

        internal fun updateControlState(connected: Boolean) {
            controlState = connected
        }

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
            // 两条路都走一遍，保证在任何前后台状态下都能停下来：
            // 1) 显式停止指令 —— 服务在运行时会走 onDestroy 做完整释放；
            // 2) stopService —— App 在后台、startService 被系统拦住时也能生效。
            try {
                context.startService(
                    Intent(context, ScreenCastService::class.java).apply { action = ACTION_STOP }
                )
            } catch (t: Throwable) {
                Log.w(TAG, "发送停止指令失败（多半是后台限制了 startService）", t)
            }
            try {
                context.stopService(Intent(context, ScreenCastService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "停止投屏服务失败", t)
            }
        }
    }
}

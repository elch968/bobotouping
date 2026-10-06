package com.bobo.touping

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.bobo.touping.control.TouchAccessibilityService
import com.bobo.touping.core.Protocol
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var hostInput: EditText
    private lateinit var scanButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var accessibilityRow: View
    private lateinit var controlHintView: TextView

    private lateinit var pillStatus: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroSubtitle: TextView

    private lateinit var dotCast: View
    private lateinit var valueCast: TextView
    private lateinit var dotLink: View
    private lateinit var valueLink: TextView
    private lateinit var dotControl: View
    private lateinit var valueControl: TextView
    private lateinit var dotAudio: View
    private lateinit var valueAudio: TextView

    private lateinit var detailCard: View
    private lateinit var detailText: TextView

    /** 本机安装的 App 版本号，显示在状态区，方便确认装的是不是最新包。 */
    private var appVersion = ""

    /**
     * 用户点了「开始投屏 / 重新扫码」，但上一场投屏还占着 MediaProjection。
     * 记下请求时刻，等服务真正停下来再拉录屏授权弹窗。
     */
    private var pendingStartSince = 0L
    private var serviceStoppedAt = 0L

    /** 扫码拿到的信息，优先于手动填写的 IP。 */
    private var scannedName = ""
    private var scannedToken = ""
    private var scannedCtrlPort = Protocol.DEFAULT_CTRL_PORT
    private var scannedVideoPort = Protocol.DEFAULT_VIDEO_PORT
    private var scannedAudioPort = Protocol.DEFAULT_AUDIO_PORT
    private var hasScanned = false

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) return@registerForActivityResult
        applyScannedPayload(contents)
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val host = hostInput.text.toString().trim()
            if (host.isEmpty()) {
                toast("请先扫码或填写电脑 IP")
                return@registerForActivityResult
            }
            preferences().edit().putString(KEY_HOST, host).apply()
            ScreenCastService.start(
                context = this,
                resultCode = result.resultCode,
                data = data,
                host = host,
                token = if (hasScanned) scannedToken else "",
                ctrlPort = if (hasScanned) scannedCtrlPort else Protocol.DEFAULT_CTRL_PORT,
                videoPort = if (hasScanned) scannedVideoPort else Protocol.DEFAULT_VIDEO_PORT,
                audioPort = if (hasScanned) scannedAudioPort else Protocol.DEFAULT_AUDIO_PORT,
            )
            serviceStoppedAt = 0L
            renderStatus()
        } else {
            toast("已取消屏幕授权")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            toast("没有通知权限，投屏时后台可能被杀掉")
        }
        ensureAudioPermissionThenCast()
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            toast("没有录音权限，只能投画面，手机声音无法传送")
        }
        requestProjectionWhenIdle()
    }

    private val handler = Handler(Looper.getMainLooper())

    private val statusTicker = object : Runnable {
        override fun run() {
            renderStatus()
            pollPendingStart()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hostInput = findViewById(R.id.input_host)
        scanButton = findViewById(R.id.btn_scan)
        startButton = findViewById(R.id.btn_start)
        stopButton = findViewById(R.id.btn_stop)
        accessibilityRow = findViewById(R.id.row_accessibility)
        controlHintView = findViewById(R.id.text_control_hint)
        pillStatus = findViewById(R.id.pill_status)
        heroTitle = findViewById(R.id.hero_title)
        heroSubtitle = findViewById(R.id.hero_subtitle)
        dotCast = findViewById(R.id.dot_cast)
        valueCast = findViewById(R.id.value_cast)
        dotLink = findViewById(R.id.dot_link)
        valueLink = findViewById(R.id.value_link)
        dotControl = findViewById(R.id.dot_control)
        valueControl = findViewById(R.id.value_control)
        dotAudio = findViewById(R.id.dot_audio)
        valueAudio = findViewById(R.id.value_audio)
        detailCard = findViewById(R.id.card_detail)
        detailText = findViewById(R.id.text_detail)

        hostInput.setText(preferences().getString(KEY_HOST, ""))
        appVersion = readVersionName()

        scanButton.setOnClickListener { launchScanner() }
        startButton.setOnClickListener { ensureNotificationThenCast() }
        stopButton.setOnClickListener {
            pendingStartSince = 0L
            serviceStoppedAt = 0L
            ScreenCastService.stop(this)
            renderStatus()
        }
        accessibilityRow.setOnClickListener { openAccessibilitySettings() }
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        pollPendingStart()
        handler.post(statusTicker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusTicker)
    }

    private fun launchScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(getString(R.string.scan_prompt))
            setBeepEnabled(false)
            // 扫码页固定竖屏：见 PortraitCaptureActivity
            setCaptureActivity(PortraitCaptureActivity::class.java)
        }
        scanLauncher.launch(options)
    }

    /**
     * 解析电脑端二维码。
     *
     * 格式：{"v":1,"ip":"192.168.1.10","ctrl":8765,"video":8766,"audio":8768,
     *        "name":"DESKTOP-XXX","token":"a1b2c3"}
     */
    private fun applyScannedPayload(payload: String) {
        try {
            val json = JSONObject(payload)
            val ip = json.optString("ip").trim()
            if (ip.isEmpty()) {
                toast("这个二维码不是波波投屏的（缺少 ip 字段）")
                return
            }

            scannedName = json.optString("name", ip)
            scannedToken = json.optString("token", "")
            scannedCtrlPort = json.optInt("ctrl", Protocol.DEFAULT_CTRL_PORT)
            scannedVideoPort = json.optInt("video", Protocol.DEFAULT_VIDEO_PORT)
            scannedAudioPort = json.optInt("audio", Protocol.DEFAULT_AUDIO_PORT)
            hasScanned = true

            hostInput.setText(ip)
            renderStatus()
            toast("已识别电脑：$scannedName，开始投屏")
            ensureNotificationThenCast()
        } catch (t: Throwable) {
            toast("二维码解析失败，请确认扫的是电脑端显示的二维码")
        }
    }

    /**
     * 刷新状态区。
     *
     * 状态全部以服务里的真实情况为准，界面不自己猜（以前失败也会显示「已启动」）。
     * 界面上拆成「主状态 + 四条明细 + 排查详情」，而不是拼成一整段文字，
     * 这样用户扫一眼就知道现在能不能投、缺什么。
     */
    private fun renderStatus() {
        val casting = ScreenCastService.isRunning
        val linked = ScreenCastService.controlConnected
        val controlReady = TouchAccessibilityService.isReady
        val audioGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val target = hostInput.text.toString().trim()

        // 顶部胶囊
        pillStatus.setText(if (casting) R.string.pill_casting else R.string.pill_idle)
        pillStatus.setBackgroundResource(
            if (casting) R.drawable.bg_pill_on else R.drawable.bg_pill_off
        )
        pillStatus.setTextColor(color(if (casting) R.color.on_brand else R.color.text_secondary))

        // 主状态
        heroTitle.setText(if (casting) R.string.hero_casting else R.string.hero_idle)
        heroSubtitle.text = when {
            pendingStartSince != 0L -> getString(R.string.hero_restarting)
            casting && target.isNotEmpty() -> getString(R.string.hero_casting_sub, target)
            casting -> getString(R.string.hero_casting_sub_no_target)
            target.isNotEmpty() -> getString(R.string.hero_ready_sub, target)
            else -> getString(R.string.hero_idle_sub)
        }

        // 四条明细
        bindRow(
            dot = dotCast,
            value = valueCast,
            ok = casting,
            okTextRes = R.string.state_cast_on,
            badTextRes = R.string.state_cast_off,
            badDotColorRes = R.color.state_off,
            badTextColorRes = R.color.text_secondary,
        )
        bindRow(
            dot = dotLink,
            value = valueLink,
            ok = linked,
            okTextRes = R.string.state_link_on,
            badTextRes = R.string.state_link_off,
            badDotColorRes = R.color.state_off,
            badTextColorRes = R.color.text_secondary,
        )
        bindRow(
            dot = dotControl,
            value = valueControl,
            ok = controlReady,
            okTextRes = R.string.state_control_on,
            badTextRes = R.string.state_control_off,
            badDotColorRes = R.color.state_warn,
            badTextColorRes = R.color.state_warn,
        )
        bindRow(
            dot = dotAudio,
            value = valueAudio,
            ok = audioGranted,
            okTextRes = R.string.state_audio_on,
            badTextRes = R.string.state_audio_off,
            badDotColorRes = R.color.state_warn,
            badTextColorRes = R.color.state_warn,
        )

        controlHintView.setText(
            if (controlReady) R.string.control_on_hint else R.string.control_off_hint
        )

        // 详情区：服务端上次的失败原因 / 成功时的编码器参数，直接显示在手机上便于排查
        val lastError = preferences()
            .getString(ScreenCastService.KEY_LAST_ERROR, "")
            .orEmpty()
        val lastInfo = preferences()
            .getString(ScreenCastService.KEY_LAST_INFO, "")
            .orEmpty()
        val details = buildList {
            if (lastError.isNotBlank()) add(getString(R.string.detail_error, lastError))
            if (hasScanned && scannedName.isNotBlank()) {
                add(getString(R.string.detail_scanned, scannedName))
            }
            if (lastInfo.isNotBlank()) add(lastInfo)
            if (appVersion.isNotBlank()) add(getString(R.string.detail_version, appVersion))
        }
        detailCard.isVisible = details.isNotEmpty()
        detailText.text = details.joinToString("\n")
        detailText.setTextColor(
            color(if (lastError.isNotBlank()) R.color.state_error else R.color.text_tertiary)
        )
    }

    /** 一行状态：圆点 + 右侧结论，颜色跟随状态走。 */
    private fun bindRow(
        dot: View,
        value: TextView,
        ok: Boolean,
        okTextRes: Int,
        badTextRes: Int,
        badDotColorRes: Int,
        badTextColorRes: Int,
    ) {
        dot.backgroundTintList =
            ColorStateList.valueOf(color(if (ok) R.color.state_on else badDotColorRes))
        value.setText(if (ok) okTextRes else badTextRes)
        value.setTextColor(color(if (ok) R.color.text_primary else badTextColorRes))
    }

    private fun color(@ColorRes res: Int) = ContextCompat.getColor(this, res)

    private fun readVersionName(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    } catch (_: Throwable) {
        ""
    }

    private fun ensureNotificationThenCast() {
        if (hostInput.text.toString().isBlank()) {
            toast("请先扫码或填写电脑 IP")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        ensureAudioPermissionThenCast()
    }

    /**
     * 内录手机声音需要 RECORD_AUDIO 权限；缺了它 AudioRecord 会初始化失败，
     * 结果就是「只有画面没有声音」。没有授权也允许继续投屏（只是没声音）。
     */
    private fun ensureAudioPermissionThenCast() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        requestProjectionWhenIdle()
    }

    /**
     * 拉录屏授权弹窗之前，先确认没有还活着的投屏会话。
     *
     * Android 14+ 不允许一个 App 同时持有两个 MediaProjection：上一场还没释放就去要新的授权，
     * 系统会直接把弹窗挡掉，用户看到的就是「停止投屏之后再点开始（或重新扫码），没反应」。
     * 所以这里先把旧会话停掉，等服务报告停止、再留一点时间给系统回收，然后才拉弹窗。
     */
    private fun requestProjectionWhenIdle() {
        if (!ScreenCastService.isRunning) {
            pendingStartSince = 0L
            serviceStoppedAt = 0L
            requestProjection()
            return
        }

        if (pendingStartSince == 0L) {
            toast("正在结束上一次投屏，稍后会自动弹出投屏授权")
        }
        pendingStartSince = SystemClock.elapsedRealtime()
        serviceStoppedAt = 0L
        ScreenCastService.stop(this)
    }

    /** 轮询：等旧会话彻底停下来，再去拉授权弹窗。 */
    private fun pollPendingStart() {
        if (pendingStartSince == 0L) return

        val sinceRequest = SystemClock.elapsedRealtime() - pendingStartSince
        if (ScreenCastService.isRunning) {
            serviceStoppedAt = 0L
            if (sinceRequest < PENDING_START_TIMEOUT_MS) return
            // 等了很久服务还说自己活着，可能卡住了，先把请求放掉，让用户能重新点
            pendingStartSince = 0L
            toast("上一次投屏没有正常结束，请再点一次「开始投屏」")
            return
        }

        if (serviceStoppedAt == 0L) {
            serviceStoppedAt = SystemClock.elapsedRealtime()
            return
        }
        if (SystemClock.elapsedRealtime() - serviceStoppedAt < STOP_SETTLE_MS) return

        pendingStartSince = 0L
        serviceStoppedAt = 0L
        requestProjection()
    }

    /**
     * 拉起系统录屏授权弹窗。这一次授权同时覆盖屏幕画面和手机内部声音，
     * 用户只需要点一次。Android 10 起无法静默持久化，每次都要走这一步。
     */
    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("请在列表里找到「波波投屏 · 反向控制」并开启")
        } catch (t: Throwable) {
            toast("无法打开无障碍设置：${t.message}")
        }
    }

    private fun preferences() =
        getSharedPreferences("bobotouping", Context.MODE_PRIVATE)

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val KEY_HOST = "host"

        /** 服务报告停止后，再等这么久，给系统回收 MediaProjection 的时间。 */
        const val STOP_SETTLE_MS = 400L

        /** 等旧会话结束的总超时，避免卡死时用户永远点不动。 */
        const val PENDING_START_TIMEOUT_MS = 8_000L
    }
}

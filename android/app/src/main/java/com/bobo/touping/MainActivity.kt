package com.bobo.touping

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private lateinit var accessibilityButton: Button
    private lateinit var statusView: TextView

    private var casting = false

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
            casting = true
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
        requestProjection()
    }

    private val handler = Handler(Looper.getMainLooper())

    private val statusTicker = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hostInput = findViewById(R.id.input_host)
        scanButton = findViewById(R.id.btn_scan)
        startButton = findViewById(R.id.btn_start)
        stopButton = findViewById(R.id.btn_stop)
        accessibilityButton = findViewById(R.id.btn_accessibility)
        statusView = findViewById(R.id.text_status)

        hostInput.setText(preferences().getString(KEY_HOST, ""))

        scanButton.setOnClickListener { launchScanner() }
        startButton.setOnClickListener { ensureNotificationThenCast() }
        stopButton.setOnClickListener {
            ScreenCastService.stop(this)
            casting = false
            renderStatus()
        }
        accessibilityButton.setOnClickListener { openAccessibilitySettings() }
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
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

    private fun renderStatus() {
        val lines = buildList {
            add(if (casting) "投屏状态：已启动" else "投屏状态：未启动")
            add(
                if (TouchAccessibilityService.isReady) {
                    "反向控制：已就绪"
                } else {
                    "反向控制：未开启（需要开启无障碍服务）"
                }
            )
            val audioGranted = ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            add(if (audioGranted) "声音传送：已授权" else "声音传送：缺少录音权限")
            val target = hostInput.text.toString().ifBlank { "未填写" }
            add(
                if (hasScanned) {
                    "目标：$target（扫码识别：$scannedName）"
                } else {
                    "目标：$target"
                }
            )

            // 服务端上次的失败原因 / 成功时的编码器参数，直接显示在手机上便于排查
            val lastError = preferences()
                .getString(ScreenCastService.KEY_LAST_ERROR, "")
                .orEmpty()
            if (lastError.isNotBlank()) {
                add("上次投屏失败：$lastError")
            }
            val lastInfo = preferences()
                .getString(ScreenCastService.KEY_LAST_INFO, "")
                .orEmpty()
            if (lastInfo.isNotBlank()) {
                add(lastInfo)
            }
        }
        statusView.text = lines.joinToString("\n")
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
    }
}

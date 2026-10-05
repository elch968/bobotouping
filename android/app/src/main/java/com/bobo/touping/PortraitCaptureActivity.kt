package com.bobo.touping

import android.content.pm.ActivityInfo
import com.journeyapps.barcodescanner.CaptureActivity

/**
 * 扫码页固定竖屏。
 *
 * zxing 自带的 CaptureActivity 不固定朝向，靠传感器决定；而扫码时人通常要把手机举起来
 * 对着电脑屏幕，加速度计很容易把它判定成横屏，于是「竖着拿手机，相机页却横过来了」。
 *
 * 除了在清单里声明 portrait，这里再拦一道 setRequestedOrientation：
 * CaptureManager 会按 `setOrientationLocked(...)` 之类的东西重新设置朝向，
 * 只写清单会被它覆盖掉。
 */
class PortraitCaptureActivity : CaptureActivity() {

    override fun setRequestedOrientation(requestedOrientation: Int) {
        super.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
    }
}

package com.bobo.touping.core

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface

/**
 * H.264 硬编码器。输入是 Surface（由 VirtualDisplay 把屏幕画面渲染进来），
 * 输出是 Annex-B 字节流，直接交给 [UdpSender] 分包。
 *
 * 参数策略很保守：只配已经验证过能用的那套（CBR + 无 B 帧 + 低延迟），
 * 码率先夹到这台机器编码器自己声明的范围内，配置失败再逐级降档重试。
 * 教训：某些机型对不认识的编码参数会直接拒绝 configure，甚至比换一个参数
 * 更糟的是「重建编码器」本身也可能失败，所以这里宁可少用花哨参数，
 * 也不能让整场投屏起不来。
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val onFrame: (data: ByteArray, keyframe: Boolean, config: Boolean, tsMs: Long) -> Unit,
) {

    /** 实际使用的码率（被编码器能力夹过），用于诊断。 */
    var usedBitrate: Int = bitrate
        private set

    private val codec: MediaCodec = createConfiguredCodec()
    private val bufferInfo = MediaCodec.BufferInfo()

    /** 实际使用的编码器名字，用于诊断。 */
    val codecName: String
        get() = try {
            codec.name
        } catch (_: Throwable) {
            "未知编码器"
        }

    @Volatile
    private var running = false
    private var drainThread: Thread? = null

    /** 配置编码器并返回输入 Surface，调用方把它交给 VirtualDisplay。 */
    fun start(): Surface {
        val surface = codec.createInputSurface()
        codec.start()

        running = true
        drainThread = Thread(::drainLoop, "video-drain").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return surface
    }

    /** 丢包后请求立刻编一个 IDR 帧，让接收端快速恢复画面。 */
    fun requestKeyFrame() {
        if (!running) return
        try {
            codec.setParameters(
                Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }
            )
        } catch (t: Throwable) {
            Log.w(TAG, "requestKeyFrame failed", t)
        }
    }

    private fun drainLoop() {
        while (running) {
            try {
                val index = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                if (index < 0) continue

                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && bufferInfo.size > 0) {
                    val data = ByteArray(bufferInfo.size)
                    buffer.position(bufferInfo.offset)
                    buffer.limit(bufferInfo.offset + bufferInfo.size)
                    buffer.get(data)

                    val isConfig =
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val isKey =
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                    onFrame(data, isKey, isConfig, bufferInfo.presentationTimeUs / 1000)
                }
                codec.releaseOutputBuffer(index, false)
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "编码器输出中断", t)
                break
            }
        }
    }

    fun stop() {
        running = false
        drainThread?.let {
            try {
                it.join(500)
            } catch (_: InterruptedException) {
            }
        }
        drainThread = null
        try {
            codec.stop()
        } catch (_: Throwable) {
        }
        try {
            codec.release()
        } catch (_: Throwable) {
        }
    }

    /**
     * 档位 0：已验证参数（CBR / 无 B 帧 / API 30+ 低延迟）
     * 档位 1：最小参数（连上面那些键都不认的机型）
     * 每档重试一次：上一次的编码器实例可能还没完全释放。
     */
    private fun createConfiguredCodec(): MediaCodec {
        var lastError: Throwable? = null

        for (tier in 0..1) {
            repeat(2) { attempt ->
                try {
                    val encoder = MediaCodec.createEncoderByType(MIME)
                    try {
                        val limit = bitrateWithinCodecRange(encoder)
                        usedBitrate = limit
                        encoder.configure(
                            buildFormat(limit, tier),
                            null,
                            null,
                            MediaCodec.CONFIGURE_FLAG_ENCODE
                        )
                        Log.i(
                            TAG,
                            "编码器配置成功：${encoder.name} ${width}x$height@$fps ${limit / 1000}kbps（档位 $tier）"
                        )
                        return encoder
                    } catch (t: Throwable) {
                        releaseQuietly(encoder)
                        throw t
                    }
                } catch (t: Throwable) {
                    lastError = t
                    Log.w(TAG, "编码器配置失败（档位 $tier 第 ${attempt + 1} 次）", t)
                    if (attempt == 0) sleepQuietly(RETRY_DELAY_MS)
                }
            }
        }

        throw IllegalStateException("无法配置 H.264 编码器", lastError)
    }

    private fun buildFormat(bitrate: Int, tier: Int): MediaFormat =
        MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)

            if (tier == 0) {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // B 帧要等后续帧才能输出，直接增加端到端延迟。
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // 0 表示最低延迟模式。
                    setInteger(MediaFormat.KEY_LATENCY, 0)
                }
            }
        }

    /** 把请求的码率夹到这台机器编码器声明的范围内，超出范围有些机型会拒绝配置。 */
    private fun bitrateWithinCodecRange(encoder: MediaCodec): Int {
        return try {
            val range = encoder.codecInfo
                .getCapabilitiesForType(MIME)
                .encoderCapabilities
                .bitrateRange
            val clamped = bitrate.coerceIn(range.lower, range.upper)
            if (clamped != bitrate) {
                Log.i(TAG, "码率 $bitrate 超出编码器范围 $range，改用 $clamped")
            }
            clamped
        } catch (t: Throwable) {
            Log.w(TAG, "读取编码器能力失败", t)
            bitrate
        }
    }

    private fun releaseQuietly(encoder: MediaCodec) {
        try {
            encoder.release()
        } catch (_: Throwable) {
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    private companion object {
        const val TAG = "VideoEncoder"
        const val MIME = "video/avc"
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val I_FRAME_INTERVAL_SECONDS = 1
        const val RETRY_DELAY_MS = 150L
    }
}

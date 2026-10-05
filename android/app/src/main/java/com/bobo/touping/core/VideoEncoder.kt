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
 * 两个直接影响「画面花屏 / 马赛克」的开关：
 *
 * 1. KEY_REPEAT_PREVIOUS_FRAME_AFTER：手机屏幕静止时 VirtualDisplay 不再产生新帧，
 *    编码器也就没有输出。这时接收端一旦丢包、参考帧对不上，画面就会一直停在那张花掉的
 *    画面上（切到别的页面才恢复，因为那时才有新帧进来）。让它每 100ms 补一帧重复画面，
 *    码流就不会断，I 帧间隔也能按时生效，坏画面最多 1 秒自愈。
 * 2. 每个 IDR 前内联 SPS/PPS：接收端重建解码器时不必再等带外配置。
 *
 * 不同机型的编码器对可选参数支持不一致，配置失败会自动退回最小参数集，保证能开流。
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val onFrame: (data: ByteArray, keyframe: Boolean, config: Boolean, tsMs: Long) -> Unit,
) {

    private val codec: MediaCodec = createConfiguredCodec()
    private val bufferInfo = MediaCodec.BufferInfo()

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
                if (running) Log.w(TAG, "drain stopped", t)
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

    private fun createConfiguredCodec(): MediaCodec {
        try {
            return configureCodec(fullFormat())
        } catch (t: Throwable) {
            Log.w(TAG, "编码器不支持可选参数，退回最小参数集", t)
        }
        return configureCodec(minimalFormat())
    }

    private fun configureCodec(format: MediaFormat): MediaCodec {
        val encoder = MediaCodec.createEncoderByType(MIME)
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (t: Throwable) {
            try {
                encoder.release()
            } catch (_: Throwable) {
            }
            throw t
        }
        return encoder
    }

    private fun minimalFormat(): MediaFormat =
        MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
        }

    private fun fullFormat(): MediaFormat =
        minimalFormat().apply {
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // B 帧要等后续帧才能输出，直接增加端到端延迟。
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                // 每个 I 帧前内联 SPS/PPS，接收端重建解码器时更稳。
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 0 表示最低延迟模式。
                setInteger(MediaFormat.KEY_LATENCY, 0)
            }
            // 静止画面也按固定间隔重复上一帧，见类注释。
            setLong(
                MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,
                REPEAT_FRAME_INTERVAL_US
            )
        }

    private companion object {
        const val TAG = "VideoEncoder"
        const val MIME = "video/avc"
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val I_FRAME_INTERVAL_SECONDS = 1

        /** 静止画面补帧间隔（微秒）：10fps，够让码流不断，又几乎不占带宽。 */
        const val REPEAT_FRAME_INTERVAL_US = 100_000L
    }
}

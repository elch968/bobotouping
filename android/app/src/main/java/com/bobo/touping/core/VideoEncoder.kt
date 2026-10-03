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
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val onFrame: (data: ByteArray, keyframe: Boolean, config: Boolean, tsMs: Long) -> Unit,
) {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME)
    private val bufferInfo = MediaCodec.BufferInfo()

    @Volatile
    private var running = false
    private var drainThread: Thread? = null

    /** 配置编码器并返回输入 Surface，调用方把它交给 VirtualDisplay。 */
    fun start(): Surface {
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 禁用 B 帧：B 帧需要等待后续帧，会直接增加端到端延迟。
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 0 表示最低延迟模式。
                setInteger(MediaFormat.KEY_LATENCY, 0)
            }
        }

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
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

    private companion object {
        const val TAG = "VideoEncoder"
        const val MIME = "video/avc"
        const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}

package com.bobo.touping.core

import android.annotation.TargetApi
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log

/**
 * 手机内部声音采集与编码。
 *
 * 用 [AudioPlaybackCaptureConfiguration] 内录，复用投屏那一次 MediaProjection 授权，
 * 用户不需要再点一次弹窗。输出 ADTS 包头的 AAC-LC 帧，接收端可直接解。
 *
 * 注意：目标 App 若主动声明不允许被内录，系统会给出静音，这是无法绕过的系统行为。
 * 需要 Android 10（API 29）及以上。
 */
class AudioEncoder(
    private val projection: MediaProjection,
    private val bitrate: Int = 128_000,
    private val onFrame: (data: ByteArray, tsMs: Long) -> Unit,
) {

    private val sampleRate = SAMPLE_RATE
    private val channelCount = CHANNELS

    @Volatile
    private var running = false
    private var captureThread: Thread? = null
    private var drainThread: Thread? = null

    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null

    private val bufferInfo = MediaCodec.BufferInfo()

    /** @return true 表示成功启动；false 表示设备不支持或初始化失败。 */
    fun start(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "系统版本低于 Android 10，无法内录")
            return false
        }
        return try {
            record = buildRecord()
            codec = buildCodec()
            if (record?.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord 初始化失败")
                releaseAll()
                return false
            }
            codec!!.start()
            record!!.startRecording()
            running = true

            captureThread = Thread(::captureLoop, "audio-capture").apply { start() }
            drainThread = Thread(::drainLoop, "audio-drain").apply { start() }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "启动音频失败", t)
            releaseAll()
            false
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun buildRecord(): AudioRecord {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer * 2, PCM_CHUNK * 4))
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
    }

    private fun buildCodec(): MediaCodec {
        val format = MediaFormat.createAudioFormat(MIME, sampleRate, channelCount).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT)
        }
        return MediaCodec.createEncoderByType(MIME).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    private fun captureLoop() {
        val pcm = ByteArray(PCM_CHUNK)
        var fedUs = 0L
        // 每帧 PCM 字节数 = 采样率 * 声道 * 2 字节 / 秒
        val bytesPerSecond = sampleRate * channelCount * 2

        while (running) {
            val rec = record ?: break
            val read = rec.read(pcm, 0, pcm.size)
            if (read <= 0) continue

            val codec = this.codec ?: break
            try {
                val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (index < 0) continue
                val buffer = codec.getInputBuffer(index) ?: continue
                buffer.clear()
                buffer.put(pcm, 0, read)
                codec.queueInputBuffer(index, 0, read, fedUs, 0)
                fedUs += read * 1_000_000L / bytesPerSecond
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "feed 失败", t)
                break
            }
        }
    }

    private fun drainLoop() {
        while (running) {
            val codec = this.codec ?: break
            try {
                val index = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                if (index < 0) continue

                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && bufferInfo.size > 0) {
                    val aac = ByteArray(bufferInfo.size)
                    buffer.position(bufferInfo.offset)
                    buffer.limit(bufferInfo.offset + bufferInfo.size)
                    buffer.get(aac)

                    // csd-0（AudioSpecificConfig）不是音频数据，跳过。
                    val isConfig =
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (!isConfig) {
                        onFrame(
                            wrapAdts(aac, aac.size),
                            bufferInfo.presentationTimeUs / 1000
                        )
                    }
                }
                codec.releaseOutputBuffer(index, false)
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "audio drain 停止", t)
                break
            }
        }
    }

    /** 给裸 AAC 帧加上 7 字节 ADTS 头，接收端无需额外传 AudioSpecificConfig。 */
    private fun wrapAdts(aac: ByteArray, size: Int): ByteArray {
        val frameLength = size + 7
        val out = ByteArray(frameLength)
        out[0] = 0xFF.toByte()
        out[1] = 0xF1.toByte() // MPEG-4, Layer 0, 无 CRC
        out[2] = (((AAC_PROFILE - 1) shl 6) or (FREQ_INDEX shl 2) or (channelCount shr 2)).toByte()
        out[3] = (((channelCount and 3) shl 6) or ((frameLength shr 11) and 0x03)).toByte()
        out[4] = ((frameLength shr 3) and 0xFF).toByte()
        out[5] = (((frameLength and 0x07) shl 5) or 0x1F).toByte()
        out[6] = 0xFC.toByte()
        System.arraycopy(aac, 0, out, 7, size)
        return out
    }

    fun stop() {
        running = false
        try {
            record?.stop()
        } catch (_: Throwable) {
        }
        captureThread?.let { runCatching { it.join(500) } }
        drainThread?.let { runCatching { it.join(500) } }
        captureThread = null
        drainThread = null
        releaseAll()
    }

    private fun releaseAll() {
        try {
            record?.release()
        } catch (_: Throwable) {
        }
        record = null
        try {
            codec?.stop()
        } catch (_: Throwable) {
        }
        try {
            codec?.release()
        } catch (_: Throwable) {
        }
        codec = null
    }

    companion object {
        private const val TAG = "AudioEncoder"
        private const val MIME = "audio/mp4a-latm"
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val MAX_INPUT = 16_384
        private const val PCM_CHUNK = 4096

        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2

        /** ADTS 中的 profile 字段，2 = AAC-LC */
        private const val AAC_PROFILE = 2

        /** 48000 Hz 对应的采样率索引 */
        private const val FREQ_INDEX = 3
    }
}

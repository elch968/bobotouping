package com.bobo.touping.core

import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 把一帧编码数据切成若干 UDP 包发出。
 *
 * 包头布局（20 字节，大端）：
 *   0..1   magic
 *   2      version
 *   3      flags
 *   4..7   frameId
 *   8..9   pktIndex
 *   10..11 pktCount
 *   12..15 frameTsMs
 *   16..19 sendTsMs
 */
class UdpSender(host: String, private val port: Int) {

    private val address: InetAddress = InetAddress.getByName(host)

    private val socket = DatagramSocket().apply {
        // 一个 I 帧会瞬间发出上百个包，默认 64KB 的发送缓冲很容易把尾部丢掉。
        try {
            sendBufferSize = SEND_BUFFER_BYTES
        } catch (t: Throwable) {
            Log.w(TAG, "设置发送缓冲失败: ${t.message}")
        }
    }

    private val packet = ByteArray(Protocol.HEADER_SIZE + Protocol.MAX_PAYLOAD)

    private var frameId = 0

    /** 关键帧/配置帧是否完整送达，供上层统计。 */
    @Volatile var sentBytes: Long = 0
        private set

    fun send(data: ByteArray, length: Int, keyframe: Boolean, config: Boolean, tsMs: Long) {
        if (length <= 0) return

        val total = (length + Protocol.MAX_PAYLOAD - 1) / Protocol.MAX_PAYLOAD
        val fid = frameId++
        val sendTs = System.currentTimeMillis().toInt()

        for (i in 0 until total) {
            val offset = i * Protocol.MAX_PAYLOAD
            val chunk = minOf(Protocol.MAX_PAYLOAD, length - offset)

            var flags = 0
            if (keyframe) flags = flags or Protocol.FLAG_KEYFRAME
            if (config && i == 0) flags = flags or Protocol.FLAG_CONFIG
            if (i == total - 1) flags = flags or Protocol.FLAG_LAST_PACKET

            writeHeader(fid, i, total, flags, tsMs, sendTs)
            System.arraycopy(data, offset, packet, Protocol.HEADER_SIZE, chunk)

            try {
                socket.send(
                    DatagramPacket(
                        packet,
                        Protocol.HEADER_SIZE + chunk,
                        address,
                        port
                    )
                )
                sentBytes += Protocol.HEADER_SIZE + chunk
            } catch (t: Throwable) {
                // 网络抖动或目标不可达时丢包即可，实时流不重传。
                Log.w(TAG, "send failed: ${t.message}")
            }
        }
    }

    /** 音频帧通常远小于 MTU，单包发完。 */
    fun sendSingle(data: ByteArray, length: Int, tsMs: Long) {
        send(data, length, keyframe = false, config = false, tsMs = tsMs)
    }

    private fun writeHeader(
        fid: Int,
        index: Int,
        count: Int,
        flags: Int,
        frameTsMs: Long,
        sendTsMs: Int,
    ) {
        packet[0] = (Protocol.MAGIC ushr 8).toByte()
        packet[1] = (Protocol.MAGIC and 0xFF).toByte()
        packet[2] = Protocol.VERSION.toByte()
        packet[3] = flags.toByte()
        putInt(4, fid)
        putShort(8, index)
        putShort(10, count)
        putInt(12, frameTsMs.toInt())
        putInt(16, sendTsMs)
    }

    private fun putInt(offset: Int, value: Int) {
        packet[offset] = (value ushr 24).toByte()
        packet[offset + 1] = (value ushr 16).toByte()
        packet[offset + 2] = (value ushr 8).toByte()
        packet[offset + 3] = value.toByte()
    }

    private fun putShort(offset: Int, value: Int) {
        packet[offset] = (value ushr 8).toByte()
        packet[offset + 1] = value.toByte()
    }

    fun close() {
        try {
            socket.close()
        } catch (_: Throwable) {
        }
    }

    fun nowMs(): Long = SystemClock.elapsedRealtime()

    private companion object {
        const val TAG = "UdpSender"

        /** 大帧（I 帧）会突发发送，默认发送缓冲太小会丢包。 */
        const val SEND_BUFFER_BYTES = 4 * 1024 * 1024
    }
}

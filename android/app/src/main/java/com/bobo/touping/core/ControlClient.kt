package com.bobo.touping.core

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 与 PC 端的控制通道：TCP + 按行分隔的 JSON。
 *
 * 上行：hello / stats / bye
 * 下行：hello_ack / start / stop / config / request_keyframe / touch / key / ping
 *
 * 关键点：socket 是异步连的，「连上之前」发的消息不能直接丢掉。
 * 之前 hello 就是在 connect() 后立刻 send 的，output 还是 null，于是被静默丢弃，
 * 电脑端永远等不到握手 —— 反向控制失效、心跳对不上，第二次投屏也受影响。
 * 现在改成：连上之前先进队列，连上后按顺序补发。
 */
class ControlClient(
    private val host: String,
    private val port: Int,
    private val onMessage: (JSONObject) -> Unit,
    private val onClosed: (reason: String?) -> Unit,
) {

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var output: OutputStream? = null

    @Volatile
    private var closed = false

    /** 握手是否已经有回应（收到过下行消息）。用来区分「连不上」和「被电脑端拒绝」。 */
    @Volatile
    private var heardFromDesktop = false

    /** 是不是我们自己主动关的（停投屏、换一场）。主动关不该报「电脑端没回应」。 */
    @Volatile
    private var closedByUs = false

    private val lock = Any()

    /** 还没连上时先攒着，连上后一次补发。上限防止对端一直连不上时无限堆积。 */
    private val pending = ArrayDeque<JSONObject>()

    fun connect() {
        thread(name = "control-client") {
            var reason: String? = null
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket = s
                val out = s.getOutputStream()
                synchronized(lock) {
                    output = out
                    flushPendingLocked(out)
                }

                val reader = BufferedReader(
                    InputStreamReader(s.getInputStream(), Charsets.UTF_8)
                )
                while (!closed) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    heardFromDesktop = true
                    try {
                        onMessage(JSONObject(line))
                    } catch (t: Throwable) {
                        Log.w(TAG, "收到无法解析的消息: $line")
                    }
                }
            } catch (t: Throwable) {
                reason = t.message
                Log.w(TAG, "控制通道断开: ${t.message}")
            } finally {
                closed = true
                closeQuietly()
                if (reason == null && !closedByUs) {
                    // 被对端断开时给一句人能看懂的原因，手机上要显示出来。
                    reason = if (heardFromDesktop) {
                        "电脑端已断开连接（电脑端可能已关闭投屏程序）"
                    } else {
                        "电脑端没有回应（电脑端可能已重启或已关闭，请重新扫码连接）"
                    }
                }
                onClosed(reason)
            }
        }
    }

    fun send(json: JSONObject) {
        if (closed) return
        synchronized(lock) {
            val out = output
            if (out == null) {
                if (pending.size >= MAX_PENDING) {
                    // 队列满了优先保住 hello（握手没了整条通道都白连），其余按先进先出丢
                    val staleIndex = pending.indexOfFirst { it.optString("t") != "hello" }
                    if (staleIndex >= 0) pending.removeAt(staleIndex) else pending.removeFirst()
                }
                pending.addLast(json)
                return
            }
            try {
                out.write(json.toString().toByteArray(Charsets.UTF_8))
                out.write('\n'.code)
                out.flush()
            } catch (t: Throwable) {
                Log.w(TAG, "发送失败: ${t.message}")
            }
        }
    }

    private fun flushPendingLocked(out: OutputStream) {
        while (pending.isNotEmpty()) {
            val json = pending.removeFirst()
            try {
                out.write(json.toString().toByteArray(Charsets.UTF_8))
                out.write('\n'.code)
                out.flush()
            } catch (t: Throwable) {
                Log.w(TAG, "补发失败: ${t.message}")
                return
            }
        }
    }

    val isConnected: Boolean
        get() = !closed && socket?.isConnected == true && output != null

    fun close() {
        closedByUs = true
        closed = true
        closeQuietly()
    }

    private fun closeQuietly() {
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        synchronized(lock) {
            socket = null
            output = null
            pending.clear()
        }
    }

    private companion object {
        const val TAG = "ControlClient"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val MAX_PENDING = 32
    }
}

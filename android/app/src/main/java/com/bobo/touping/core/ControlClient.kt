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

    fun connect() {
        thread(name = "control-client") {
            var reason: String? = null
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket = s
                output = s.getOutputStream()

                val reader = BufferedReader(
                    InputStreamReader(s.getInputStream(), Charsets.UTF_8)
                )
                while (!closed) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
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
                onClosed(reason)
            }
        }
    }

    fun send(json: JSONObject) {
        val out = output ?: return
        if (closed) return
        try {
            synchronized(this) {
                out.write(json.toString().toByteArray(Charsets.UTF_8))
                out.write('\n'.code)
                out.flush()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "发送失败: ${t.message}")
        }
    }

    val isConnected: Boolean
        get() = !closed && socket?.isConnected == true

    fun close() {
        closed = true
        closeQuietly()
    }

    private fun closeQuietly() {
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        output = null
    }

    private companion object {
        const val TAG = "ControlClient"
        const val CONNECT_TIMEOUT_MS = 5_000
    }
}

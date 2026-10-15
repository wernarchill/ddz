package com.ddz.net

import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 客户端：连接房主，收发一行一条的 JSON。
 *
 * 连接是异步的（[connect] 立刻返回，结果通过 [NetEvents] 回调），
 * 这样 UI 线程永远不会被网络阻塞。
 */
class GameClient(private val events: NetEvents) {

    private var socket: Socket? = null
    private var out: BufferedWriter? = null
    private val sendLock = Any()

    @Volatile
    var connected = false
        private set

    @Volatile
    private var closedByUser = false

    /**
     * 发起连接。[timeoutMs] 只用作**连接超时**。
     *
     * 连接成功后会把 `soTimeout` 设为 0（永不超时）：因为断线检测交给了应用层心跳
     * （见 AppController.heartbeat），比依赖 SocketTimeoutException 更可控 ——
     * 后者在两次消息之间也会触发，很容易把「对手在思考」误判成断线。
     */
    fun connect(host: String, port: Int, timeoutMs: Int = 5000) {
        closeInternal(notify = false)
        closedByUser = false
        Thread({
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.soTimeout = 0 // 交给 TCP keepAlive 与心跳探测断线，避免误判断开
                val writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                socket = s
                out = writer
                connected = true
                events.onConnected()
                readLoop(reader)
            } catch (e: Exception) {
                connected = false
                if (!closedByUser) {
                    events.onDisconnected(e.message ?: "连接失败")
                }
            }
        }, "ddz-client-connect").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun readLoop(reader: BufferedReader) {
        var reason = "连接已关闭"
        try {
            while (!closedByUser) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val msg = try {
                    JSONObject(line)
                } catch (e: Exception) {
                    continue
                }
                events.onMessage(msg)
            }
        } catch (e: Exception) {
            reason = e.message ?: "网络异常"
        }
        connected = false
        if (!closedByUser) events.onDisconnected(reason)
    }

    /** 返回 false 表示发送失败（已断线） */
    fun send(msg: JSONObject): Boolean {
        if (!connected) return false
        return try {
            synchronized(sendLock) {
                val w = out ?: return false
                w.write(msg.toString())
                w.write("\n")
                w.flush()
            }
            true
        } catch (e: Exception) {
            connected = false
            events.onDisconnected(e.message ?: "发送失败")
            false
        }
    }

    fun close() {
        closedByUser = true
        closeInternal(notify = true)
    }

    private fun closeInternal(notify: Boolean) {
        val wasConnected = connected
        connected = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        out = null
        if (notify && wasConnected) events.onDisconnected("已主动断开")
    }
}

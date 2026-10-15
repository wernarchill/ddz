package com.ddz.net

import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 房主侧 TCP 服务器：监听端口，接受最多 2 名其他玩家连接。
 *
 * 线程模型：
 *  - 1 个 accept 线程负责接收连接
 *  - 每个连接 1 个读取线程
 *  - 发送在同一把锁里串行化，保证不会出现两条消息交错、半个 JSON 的情况
 *
 * 房主自己不走 socket，而是直接把消息交给同一个会话对象处理，省掉一次本机回环。
 */
class HostServer(
    private val port: Int,
    private val listener: Listener
) {

    interface Listener {
        fun onPeerConnected(connId: Int)
        fun onPeerMessage(connId: Int, msg: JSONObject)
        fun onPeerDisconnected(connId: Int)
    }

    class Peer(val connId: Int, val socket: Socket) {
        private val out = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        private val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        private val sendLock = Any()

        @Volatile
        var closed = false
            private set

        /**
         * 最近一次收到该连接消息的时间。用于清理「僵尸连接」：
         * 手机被系统冻结/挂起时 socket 不会发 FIN/RST，读取线程会永久阻塞在 readLine 上，
         * onPeerDisconnected 永远不会触发，房主就会一直以为对方在线。
         */
        @Volatile
        var lastActivity: Long = System.currentTimeMillis()

        /** 返回 false 表示发送失败（对端已断开） */
        fun send(msg: JSONObject): Boolean {
            if (closed) return false
            return try {
                synchronized(sendLock) {
                    out.write(msg.toString())
                    out.write("\n")
                    out.flush()
                }
                true
            } catch (e: Exception) {
                closed = true
                false
            }
        }

        fun readLine(): String? = try {
            input.readLine()
        } catch (e: Exception) {
            null
        }

        fun close() {
            closed = true
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val peers = ConcurrentHashMap<Int, Peer>()
    private val nextConnId = java.util.concurrent.atomic.AtomicInteger(1)

    @Volatile
    private var running = false

    val peerCount: Int get() = peers.size

    fun isRunning(): Boolean = running && serverSocket != null

    /** 启动监听。绑定失败（端口被占用等）会抛异常，由调用方提示用户换端口。 */
    fun start() {
        if (running) return
        // reuseAddress 必须在 bind 之前设置才生效，所以先建未绑定的 socket
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(java.net.InetSocketAddress(port))
        serverSocket = ss
        running = true
        acceptThread = Thread({ acceptLoop(ss) }, "ddz-accept").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val socket = try {
                ss.accept()
            } catch (e: Exception) {
                if (running) listener.onPeerDisconnected(-1) // -1 表示监听本身出问题
                break
            }
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
            } catch (_: Exception) {
            }
            val connId = nextConnId.getAndIncrement()
            val peer = Peer(connId, socket)
            peers[connId] = peer
            listener.onPeerConnected(connId)
            Thread({ readLoop(peer) }, "ddz-read-$connId").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    private fun readLoop(peer: Peer) {
        while (running && !peer.closed) {
            val line = peer.readLine() ?: break
            if (line.isBlank()) continue
            // 正常消息最大也就 400 字节（17 张牌）。超过这个尺寸说明对端不正常，
            // 直接断开，避免被超长消息拖垮房主的内存与带宽。
            if (line.length > MAX_LINE_CHARS) {
                break
            }
            peer.lastActivity = System.currentTimeMillis()
            val msg = try {
                JSONObject(line)
            } catch (e: Exception) {
                continue // 忽略无法解析的行
            }
            listener.onPeerMessage(peer.connId, msg)
        }
        peers.remove(peer.connId)
        peer.close()
        listener.onPeerDisconnected(peer.connId)
    }

    fun sendTo(connId: Int, msg: JSONObject) {
        val peer = peers[connId] ?: return
        if (!peer.send(msg)) {
            peers.remove(connId)
            peer.close()
            listener.onPeerDisconnected(connId)
        }
    }

    fun broadcast(msg: JSONObject) {
        for (p in peers.values) {
            if (!p.send(msg)) {
                peers.remove(p.connId)
                p.close()
                listener.onPeerDisconnected(p.connId)
            }
        }
    }

    fun disconnect(connId: Int) {
        val peer = peers.remove(connId) ?: return
        peer.close()
    }

    /**
     * 断开「长时间一条消息都没发」的连接。
     *
     * 存在的意义：手机被系统冻结、进入深度休眠、或进程被挂起时，对端 socket 不会发 FIN/RST，
     * 读取线程会永久阻塞在 readLine 上（SO_TIMEOUT 为 0），onPeerDisconnected 永远不会触发。
     * 结果是房主一直认为对方在线，牌局只能干等每回合 20 秒超时，「掉线托管」实际不会生效。
     *
     * 健康客户端每 3 秒发一次 PING，所以 15 秒的阈值非常宽松，不会误伤。
     */
    fun disconnectIdle(now: Long, timeoutMs: Long) {
        if (!running) return
        for (p in peers.values) {
            if (now - p.lastActivity > timeoutMs) {
                peers.remove(p.connId)
                p.close()
                listener.onPeerDisconnected(p.connId)
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        for (p in peers.values) p.close()
        peers.clear()
    }

    companion object {
        /** 默认端口，避开常见冲突端口 */
        const val DEFAULT_PORT = 9527

        /** 单条消息的字符数上限，超过即断开连接（正常消息 < 500 字符） */
        const val MAX_LINE_CHARS = 64 * 1024

        /**
         * 取本机局域网 IPv4，用于界面展示「我的 IP」给其他玩家手输。
         * 优先返回 192.168.x / 10.x / 172.16-31.x 这类站点本地地址。
         */
        fun localIpv4(): String {
            return try {
                val candidates = ArrayList<String>()
                for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (addr in Collections.list(nif.inetAddresses)) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (ip.startsWith("192.168.") ||
                                ip.startsWith("10.") ||
                                ip.startsWith("172.")
                            ) {
                                candidates.add(ip)
                            }
                        }
                    }
                }
                candidates.firstOrNull() ?: "127.0.0.1"
            } catch (e: Exception) {
                "127.0.0.1"
            }
        }
    }
}

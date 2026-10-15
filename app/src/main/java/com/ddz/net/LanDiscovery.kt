package com.ddz.net

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * 局域网房间发现：走 UDP 广播，不需要任何额外权限（IPv4 广播不需要 multicast lock）。
 *
 * 房主每 800ms 广播一次自己的房间信息；客户端监听同一端口，看到房间就加入列表。
 * 同时保留「手动输入 IP:端口」的入口 —— 不少路由器/企业 WiFi 会屏蔽广播，
 * 部分手机开热点时广播也可能不通，手输是永远可用的兜底方案。
 */
object LanDiscovery {
    /** 房间公告专用端口，与对战端口分开，避免和对战数据流混淆 */
    const val DISCOVERY_PORT = 9528

    /** 广播间隔 */
    const val ANNOUNCE_INTERVAL_MS = 800L

    /** 超过这个时间没收到公告就认为房间已消失 */
    const val ROOM_EXPIRE_MS = 4000L
}

/** 房主侧：周期广播房间信息 */
class RoomBroadcaster(private val port: Int = LanDiscovery.DISCOVERY_PORT) {

    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /**
     * [provider] 每次广播前被调用，返回要发送的 JSON 文本；返回空串表示这次跳过。
     *
     * 注意这里收的是**已经序列化好的字符串**，而不是让回调去现读游戏状态：
     * 早期版本传的是 `() -> JSONObject`，广播线程因此每 800ms 就去读一遍
     * ViewState 与 HostSession（还顺带枚举网卡），属于跨线程读状态机 ——
     * 现在由渲染线程维护一份 @Volatile 快照，广播线程只读那个不可变字符串。
     */
    fun start(provider: () -> String) {
        if (running) return
        running = true
        thread = Thread({
            var s: DatagramSocket? = null
            try {
                s = DatagramSocket()
                s.broadcast = true
                socket = s
                // 子网广播地址只算一次，避免每轮都去枚举网卡
                val subnet = subnetBroadcast()
                while (running) {
                    val payload = try {
                        provider()
                    } catch (e: Exception) {
                        ""
                    }
                    if (payload.isNotEmpty()) {
                        val bytes = payload.toByteArray(Charsets.UTF_8)
                        sendTo(s, bytes, "255.255.255.255")
                        // 有些路由器不转发 255.255.255.255，再补一发子网定向广播
                        if (subnet != null) sendTo(s, bytes, subnet)
                    }
                    try {
                        Thread.sleep(LanDiscovery.ANNOUNCE_INTERVAL_MS)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            } catch (e: Exception) {
                // 广播不可用不影响对战（手输 IP 依然能玩），静默降级
            } finally {
                try {
                    s?.close()
                } catch (_: Exception) {
                }
                socket = null
            }
        }, "ddz-broadcast").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun sendTo(s: DatagramSocket, bytes: ByteArray, host: String) {
        try {
            s.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(host), port))
        } catch (_: Exception) {
        }
    }

    /**
     * 由本机 IP 推算子网广播地址，例如 192.168.1.5 -> "192.168.1.255"（按 /24 估算，家用场景够用）。
     *
     * 返回 String 而不是 InetAddress：调用方 [sendTo] 收的就是字符串地址，
     * 返回 InetAddress 会在调用点产生类型不匹配。
     */
    private fun subnetBroadcast(): String? {
        return try {
            val parts = HostServer.localIpv4().split(".")
            if (parts.size != 4) null
            else "${parts[0]}.${parts[1]}.${parts[2]}.255"
        } catch (e: Exception) {
            null
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        thread?.interrupt()
        thread = null
    }
}

/** 客户端侧：监听广播，维护一份会自动过期的房间列表 */
class RoomScanner(private val port: Int = LanDiscovery.DISCOVERY_PORT) {

    data class Room(
        val roomId: String,
        val name: String,
        val ip: String,
        val port: Int,
        val players: Int,
        val maxPlayers: Int,
        val lastSeen: Long
    )

    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /** rooms 同时被扫描线程和 UI 线程访问，统一用这把锁保护 */
    private val lock = Any()
    private val rooms = LinkedHashMap<String, Room>()

    /**
     * [onChanged] 在房间列表变化时回调（**后台线程**），调用方需自行切到渲染线程刷新 UI。
     */
    fun start(onChanged: (List<Room>) -> Unit) {
        if (running) return
        running = true
        thread = Thread({
            var s: DatagramSocket? = null
            try {
                s = DatagramSocket(null)
                s.reuseAddress = true
                s.bind(InetSocketAddress(port))
                s.soTimeout = 500 // 用超时来周期性清理过期房间
                socket = s
                val buf = ByteArray(2048)
                var lastExpire = 0L

                while (running) {
                    var changed = false
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val obj = try {
                            JSONObject(text)
                        } catch (e: Exception) {
                            null
                        }
                        if (obj != null && obj.optString("t") == "room") {
                            val roomId = obj.optString("roomId")
                            // 以公告里自报的 IP 为准；缺失时用数据包来源地址兜底
                            val ip = obj.optString("ip").ifBlank {
                                packet.address?.hostAddress ?: ""
                            }
                            if (roomId.isNotBlank() && ip.isNotBlank()) {
                                changed = upsert(
                                    Room(
                                        roomId = roomId,
                                        name = obj.optString("name", "斗地主房间"),
                                        ip = ip,
                                        port = obj.optInt("port", HostServer.DEFAULT_PORT),
                                        players = obj.optInt("players", 1),
                                        maxPlayers = obj.optInt("max", 3),
                                        lastSeen = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    } catch (e: SocketTimeoutException) {
                        // 正常：靠这个超时推进下面的过期检查
                    } catch (e: Exception) {
                        if (running) break // 接收出错直接退出，避免空转烧 CPU
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastExpire > 1000) {
                        lastExpire = now
                        changed = expire(now) || changed
                    }
                    if (changed) onChanged(currentRooms())
                }
            } catch (e: Exception) {
                // 端口被占用等情况：自动发现不可用，但手输 IP 依然能玩
            } finally {
                try {
                    s?.close()
                } catch (_: Exception) {
                }
                socket = null
            }
        }, "ddz-scan").also {
            it.isDaemon = true
            it.start()
        }
    }

    /** @return 是否发生了实质变化（新房间或人数变化） */
    private fun upsert(room: Room): Boolean = synchronized(lock) {
        val old = rooms[room.roomId]
        rooms[room.roomId] = room
        old == null || old.players != room.players || old.ip != room.ip || old.port != room.port
    }

    private fun expire(now: Long): Boolean = synchronized(lock) {
        var removed = false
        val iter = rooms.entries.iterator()
        while (iter.hasNext()) {
            if (now - iter.next().value.lastSeen > LanDiscovery.ROOM_EXPIRE_MS) {
                iter.remove()
                removed = true
            }
        }
        removed
    }

    fun currentRooms(): List<Room> = synchronized(lock) { rooms.values.toList() }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        thread?.interrupt()
        thread = null
    }
}

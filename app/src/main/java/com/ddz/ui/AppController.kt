package com.ddz.ui

import android.content.Context
import com.ddz.CrashLog
import com.ddz.audio.Sfx
import com.ddz.core.Card
import com.ddz.core.Combo
import com.ddz.core.ComboParser
import com.ddz.core.ComboType
import com.ddz.core.Rules
import com.ddz.game.HostSession
import com.ddz.net.GameClient
import com.ddz.net.HostServer
import com.ddz.net.NetEvents
import com.ddz.net.Proto
import com.ddz.net.RoomBroadcaster
import com.ddz.net.RoomScanner
import com.ddz.net.T
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random

/**
 * 总控制器：把网络、状态、输入粘在一起。
 *
 * ## 线程模型（这是本项目最关键的一处设计）
 * 状态**只在渲染线程上被修改**：
 *  - 网络回调（后台线程）只做一件事：把闭包丢进 [queue]；
 *  - 渲染线程每帧调用 [tick]，先 [drain] 把队列里的闭包跑完，再驱动房主会话的定时器。
 * 由于 [HostSession] 也只在渲染线程被触碰，它内部就不需要任何锁，彻底消灭竞态。
 * 房主自己的操作同步直达会话（因为本来就在渲染线程上），不绕队列，省掉一帧延迟。
 */
class AppController(
    private val context: Context,
    private val inputHost: TextInputHost
) {

    val state = ViewState()
    val textures = Textures(context)
    val painter = Painter()
    val sfx = Sfx(context)

    private val prefs = context.getSharedPreferences("ddz", Context.MODE_PRIVATE)

    // ---- 网络 ----
    private var session: HostSession? = null
    private var hostServer: HostServer? = null
    private var broadcaster: RoomBroadcaster? = null
    private var client: GameClient? = null
    private var scanner: RoomScanner? = null

    /** connId → seat 与 seat → connId 的双向映射 */
    private val connSeat = HashMap<Int, Int>()
    private val seatConn = HashMap<Int, Int>()

    // ---- 跨线程投递 ----
    private val queue = ConcurrentLinkedQueue<() -> Unit>()
    private var lastHostTick = 0L
    private var frameNow = 0L

    /** 心跳：客户端最近一次收到房主消息的时间 / 最近一次发 PING 的时间 */
    private var lastRecvAt = 0L
    private var lastPingAt = 0L

    /** 上一帧的时间戳，用来识别「应用刚从后台回来」（帧间隔异常大） */
    private var lastFrameAt = 0L

    /** 上次清理僵尸连接的时间（仅房主侧） */
    private var lastIdleCheck = 0L

    /**
     * 连接代次。每次发起新连接就 +1；回调闭包里带着发起时的代次，
     * 执行时若与当前代次不符就丢弃 —— 否则旧连接的迟到回调会作用到新连接上。
     */
    @Volatile
    private var clientGeneration = 0

    /** 投递队列过载标记（对端疯狂刷消息时置位，下一帧断开） */
    @Volatile
    private var queueOverflow = false

    /**
     * 给 UDP 广播线程读的房间公告快照（已序列化）。
     * 只由渲染线程写、广播线程读，因此用 @Volatile 而不是锁 —— 内容是不可变字符串，天然安全。
     */
    @Volatile
    private var announcePayload: String = ""

    /**
     * 已释放标记。`release()` 由主线程在 Activity 销毁时调用，而 `stopRender()` 的 join 有超时，
     * 因此渲染线程理论上可能还活着。置位后 `tick()` 立即变成空操作，
     * 尽量缩小「主线程清理」与「渲染线程驱动」并发的窗口。
     */
    @Volatile
    private var released = false

    init {
        // 走到这一行说明前面四个字段初始化（ViewState / Textures / Painter / Sfx）全部成功了。
        // 启动打点里如果没有这一条，崩溃就在那四个构造器之一。
        CrashLog.mark(context, "AppController.init 开始（字段初始化均成功）")

        state.clientId = prefs.getString(KEY_CLIENT_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_CLIENT_ID, it).apply()
        }
        state.nickname = prefs.getString(KEY_NICKNAME, null)
            ?: "玩家${Random.nextInt(1000, 9999)}"
        CrashLog.mark(context, "prefs 读取完成")

        state.myIp = HostServer.localIpv4()
        CrashLog.mark(context, "本机 IP 探测完成：${state.myIp}")

        startScanner()
        CrashLog.mark(context, "UDP 房间扫描器已启动")

        // 预热牌面贴图，避免第一次进牌桌时集中解码造成掉帧
        textures.preloadAsync(AssetsSpec.cards + listOf("card_back"), Textures.CARD_TEX_W)
        CrashLog.mark(context, "AppController 构造完成")
    }

    private fun post(block: () -> Unit) {
        // 过载保护：单条消息已限长 64K，但如果对端疯狂刷小消息，队列增长会快过 drain。
        // 这里宁可断开连接，也不静默丢消息 —— 丢消息会让状态错乱，比断开难查得多。
        if (queue.size >= MAX_QUEUED_BLOCKS) {
            queueOverflow = true
            return
        }
        queue.add(block)
    }

    /** 供外部（例如输入框异常提示）从任意线程安全地弹一条提示 */
    fun toastFromAnyThread(msg: String) {
        post { state.toast(msg) }
    }

    /** 是否已经释放（渲染线程据此停止绘制） */
    fun isReleased(): Boolean = released

    private fun drain() {
        var guard = 0
        while (guard++ < 500) {
            val block = queue.poll() ?: return
            try {
                block()
            } catch (e: Exception) {
                state.toast("内部错误：${e.message}")
            }
        }
    }

    // ================================================================ 主循环

    fun tick(now: Long) {
        if (released) return
        frameNow = now

        // 应用切到后台时渲染线程会停跑（onPause → stopRender），回到前台的第一帧
        // now 与上一帧可能相差几十秒。此时心跳基准必须重置，否则会误判「与房主失去联系」
        // 把好端端的连接自己踢掉（socket 其实从未断开）。
        if (lastFrameAt != 0L && now - lastFrameAt > 2000L) {
            lastRecvAt = now
            lastPingAt = now
        }
        lastFrameAt = now

        drain()

        if (queueOverflow) {
            queueOverflow = false
            state.toast("消息量异常，已断开连接")
            closeAll()
            state.screen = Screen.LOBBY
            return
        }

        if (state.isHost && now - lastHostTick >= 100) {
            lastHostTick = now
            session?.tick(now)
            // 每 3 秒清理一次僵尸连接。手机被系统冻结时 socket 不发 FIN/RST，
            // 读取线程会永久阻塞、onPeerDisconnected 永不触发，房主就会一直以为对方在线，
            // 牌局只能干等 20 秒超时。健康客户端每 3 秒发一次 PING，15 秒阈值很宽松。
            if (now - lastIdleCheck >= 3000L) {
                lastIdleCheck = now
                hostServer?.disconnectIdle(now, HOST_IDLE_TIMEOUT_MS)
            }
        }

        if (state.toastText != null && now > state.toastUntil) state.toastText = null

        heartbeat(now)
    }

    /**
     * 应用层心跳。
     *
     * 没有它的话，房主手机突然断网/关机时，客户端的 readLine() 会一直阻塞
     * （TCP keepalive 默认两小时才开始探测），玩家就永远卡在「等待出牌」界面。
     * 这里 3 秒发一次 PING，12 秒收不到任何消息就判定失联并退回大厅。
     */
    private fun heartbeat(now: Long) {
        if (state.isHost || !state.connected) return
        if (lastRecvAt == 0L) lastRecvAt = now
        if (now - lastRecvAt > 12_000L) {
            state.toast("与房主失去联系，已退回大厅")
            closeAll()
            state.screen = Screen.LOBBY
            return
        }
        if (now - lastPingAt > 3_000L) {
            lastPingAt = now
            client?.send(Proto.obj(T.PING))
        }
    }

    // ================================================================ 大厅

    private fun startScanner() {
        if (scanner != null) return
        val s = RoomScanner()
        scanner = s
        state.scanning = true
        s.start { rooms ->
            post {
                state.rooms = rooms
            }
        }
    }

    fun saveNickname(name: String) {
        val n = name.trim().take(12)
        if (n.isBlank()) return
        state.nickname = n
        prefs.edit().putString(KEY_NICKNAME, n).apply()
    }

    fun createRoom() {
        if (session != null) return
        state.isHost = true
        state.mySeat = 0
        state.connected = true
        state.roomId = Random.nextInt(100000, 999999).toString()
        state.myIp = HostServer.localIpv4()

        val s = HostSession(state.roomId, hostOutput)
        s.setHostPlayer(state.nickname, state.clientId)
        session = s

        // 端口被占用（比如同时开了两个实例）时不阻断建房：
        // 降级成「只能和电脑打」的单机房间，至少自己还能玩起来。
        var serverOk = true
        val server = HostServer(HostServer.DEFAULT_PORT, hostServerListener)
        try {
            server.start()
            hostServer = server
        } catch (e: Exception) {
            serverOk = false
            hostServer = null
        }

        // 只有真的在监听时才广播房间，否则会向大厅里的其他人广告一个连不上的房间
        if (serverOk) {
            broadcaster = RoomBroadcaster().also { b -> b.start { announcePayload } }
        }

        state.screen = Screen.ROOM
        state.hostServerRunning = serverOk
        s.broadcastRoom()
        refreshAnnounce()
        if (serverOk) {
            state.toast("房间已创建，房间号 ${state.roomId}")
        } else {
            state.toast("端口 9527 被占用：本次只能和电脑打，别人无法加入")
        }
        sfx.play("click")
    }

    fun joinRoom(ip: String, port: Int = HostServer.DEFAULT_PORT) {
        val target = ip.trim()
        if (target.isBlank()) {
            state.toast("请输入房主的 IP")
            return
        }
        closeAll()
        state.hostIp = target
        state.hostPort = port
        state.statusText = "正在连接 $target:$port …"
        state.toast(state.statusText)

        // 每次连接都新建一个带「代次」的回调对象。
        // 早期版本所有连接复用同一个 NetEvents 实例，于是旧连接的读取线程在结束时
        // 仍会回调 onDisconnected，而那时新连接已经建立 —— 表现为「刚连上就被踢回大厅」。
        clientGeneration++
        val gen = clientGeneration
        val events = object : NetEvents {
            override fun onConnected() {
                post {
                    if (gen != clientGeneration) return@post
                    state.connected = true
                    state.statusText = ""
                    // 从「连接成功」这一刻起算心跳，避免用上一次会话的旧时间戳误判断线
                    lastRecvAt = frameNow
                    lastPingAt = frameNow
                    sendJoin()
                }
            }

            override fun onMessage(msg: JSONObject) {
                post {
                    if (gen != clientGeneration) return@post
                    handleServerMessage(msg)
                }
            }

            override fun onDisconnected(reason: String) {
                post {
                    if (gen != clientGeneration) return@post
                    state.connected = false
                    state.statusText = "已断开：$reason"
                    if (state.screen != Screen.LOBBY) {
                        state.toast("与房主的连接中断：$reason")
                        closeAll()
                        state.screen = Screen.LOBBY
                    }
                }
            }
        }

        val c = GameClient(events)
        client = c
        c.connect(target, port)
    }

    fun leaveRoom() {
        if (!state.isHost) client?.send(Proto.obj(T.LEAVE))
        closeAll()
        state.screen = Screen.LOBBY
        state.toast("已返回大厅")
    }

    private fun closeAll() {
        // 代次 +1：让所有还挂着的旧连接回调（含正在读取的线程）在投递时被识别为过期
        clientGeneration++
        client?.close()
        client = null
        broadcaster?.stop()
        broadcaster = null
        hostServer?.stop()
        hostServer = null
        session = null
        connSeat.clear()
        seatConn.clear()
        state.isHost = false
        state.connected = false
        state.phase = "WAITING"
        state.result = null
        state.hand = mutableListOf()
        state.selected.clear()
        state.landlord = -1
        state.baseScore = 0
        state.bidScores = arrayOfNulls(3)
        state.tablePlays = arrayOfNulls(3)
        state.passed = BooleanArray(3)
        state.currentSeat = -1
        state.targetCombo = null
        state.targetSeat = -1
        state.bombCount = 0
        state.multiplier = 1
        state.bottom = mutableListOf()
        state.seats = MutableList(3) { SeatView() }
        state.statusText = ""
        state.hostServerRunning = false
        // 心跳时间戳必须一起复位。否则「加入 → 退出 → 在大厅停留 12 秒以上 → 再加入」时，
        // 心跳会拿着上一次会话的旧时间戳，把刚连上的客户端当成失联直接踢回大厅。
        lastRecvAt = 0L
        lastPingAt = 0L
        refreshAnnounce()
    }

    private fun roomAnnounce(): JSONObject = Proto.obj("room")
        .put("roomId", state.roomId)
        .put("name", "${state.nickname}的房间")
        .put("ip", state.myIp) // 用 init/createRoom 时算好的值，避免广播线程反复枚举网卡
        .put("port", HostServer.DEFAULT_PORT)
        .put("players", session?.occupancy() ?: 1)
        .put("max", 3)

    /**
     * 刷新给 UDP 广播线程读的那份快照。
     *
     * 只在渲染线程调用（createRoom / applyRoom / closeAll），广播线程只读这个
     * 不可变字符串，从而彻底避免「广播线程读状态机」这种跨线程访问。
     */
    private fun refreshAnnounce() {
        announcePayload = if (!state.isHost || state.roomId.isBlank()) {
            ""
        } else {
            roomAnnounce().toString()
        }
    }

    // ================================================================ 房主侧网络

    private val hostOutput = object : HostSession.Output {
        override fun sendToSeat(seat: Int, msg: JSONObject) {
            if (seat == 0) {
                // 房主自己：同步处理，省掉一帧延迟；因为本来就在渲染线程上，不存在竞态
                handleServerMessage(msg)
            } else {
                seatConn[seat]?.let { hostServer?.sendTo(it, msg) }
            }
        }

        override fun broadcast(msg: JSONObject) {
            handleServerMessage(msg)
            hostServer?.broadcast(msg)
        }
    }

    private val hostServerListener = object : HostServer.Listener {
        override fun onPeerConnected(connId: Int) {
            // 座位分配要等 JOIN 消息（见 attachClient），这里不做任何事
        }

        override fun onPeerMessage(connId: Int, msg: JSONObject) {
            post { handleHostPeerMessage(connId, msg) }
        }

        override fun onPeerDisconnected(connId: Int) {
            if (connId < 0) return
            post { handleHostPeerGone(connId) }
        }
    }

    private fun handleHostPeerMessage(connId: Int, msg: JSONObject) {
        val s = session ?: return
        val existing = connSeat[connId]
        if (existing == null) {
            // 第一条必须是 JOIN，否则丢弃
            if (msg.optString("t") != T.JOIN) return
            val assigned = s.attachClient(msg.optString("clientId"), msg.optString("name"))
            if (assigned == null) {
                hostServer?.sendTo(connId, Proto.obj(T.KICKED).put("msg", "房间已满（最多 3 人）"))
                hostServer?.disconnect(connId)
                return
            }
            // 必须先把映射指向新连接，再下发加入信息；否则重连时会发到已断开的旧连接上
            // （T.JOINED 是唯一把界面切到房间页的消息，发丢了加入者会一直卡在大厅）
            val oldConn = seatConn[assigned]
            if (oldConn != null && oldConn != connId) {
                // 同一座位已被另一条连接占用（典型场景：手机 WiFi 抖一下，旧 socket 还没被探测到断开）。
                // 把旧连接踢掉，保证「一个座位只有一条连接」，否则两条连接都能替这个座位出牌，
                // 而旧连接关闭时还会把新连接的映射删掉，导致新连接静默失联。
                connSeat.remove(oldConn)
                hostServer?.disconnect(oldConn)
            }
            connSeat[connId] = assigned
            seatConn[assigned] = connId
            s.sendJoinInfo(assigned)
            return
        }
        if (msg.optString("t") == T.JOIN) return // 重复 JOIN，忽略
        s.handleMessage(existing, msg)
    }

    private fun handleHostPeerGone(connId: Int) {
        val seat = connSeat.remove(connId) ?: return
        // 只有「这个座位当前确实绑在这条连接上」才清理。
        // 否则旧连接的断开事件会把新连接的映射误删，造成新连接收不到 TURN/PLAYED/SETTLE。
        if (seatConn[seat] != connId) return
        seatConn.remove(seat)
        session?.onPeerDisconnected(seat)
    }

    // ================================================================ 客户端侧网络

    private fun sendJoin() {
        client?.send(
            Proto.obj(T.JOIN)
                .put("clientId", state.clientId)
                .put("name", state.nickname)
        )
    }

    // ================================================================ 服务端消息 → 界面状态

    private fun handleServerMessage(msg: JSONObject) {
        lastRecvAt = frameNow
        when (msg.optString("t")) {
            T.JOINED -> {
                state.mySeat = msg.optInt("seat", 0).coerceIn(0, 2)
                state.roomId = msg.optString("roomId", state.roomId)
                state.connected = true
                state.screen = Screen.ROOM
                state.toast("已加入房间 ${state.roomId}")
            }

            T.ROOM -> applyRoom(msg)

            T.START -> {
                state.mySeat = msg.optInt("seat", state.mySeat).coerceIn(0, 2)
                state.hand = Rules.sortDesc(Proto.decodeCards(msg.optJSONArray("hand")))
                    .toMutableList()
                state.selected.clear()
                state.landlord = msg.optInt("landlord", -1)
                state.baseScore = msg.optInt("baseScore", 0)
                state.bidScores = arrayOfNulls(3)
                state.tablePlays = arrayOfNulls(3)
                state.result = null
                state.targetCombo = null
                state.targetSeat = -1
                state.bombCount = 0
                state.multiplier = 1
                state.bottom = Proto.decodeCards(msg.optJSONArray("bottom")).toMutableList()
                state.passed = BooleanArray(3)
                applyCounts(msg)
                state.screen = Screen.TABLE
                state.phase = "BIDDING"
                sfx.play("deal")
            }

            T.BID_TURN -> {
                state.currentSeat = msg.optInt("seat", -1)
                state.deadlineTotalMs = 10_000L
                state.deadline = frameNow + msg.optLong("timeLeft", 10000L)
                state.phase = "BIDDING"
                val arr = msg.optJSONArray("scores")
                if (arr != null) {
                    for (i in 0 until minOf(3, arr.length())) {
                        state.bidScores[i] = if (arr.isNull(i)) null else arr.optInt(i, 0)
                    }
                }
                if (state.currentSeat == state.mySeat) sfx.play("your_turn")
            }

            T.BID_DONE -> {
                val seat = msg.optInt("seat", -1).coerceIn(0, 2)
                val score = msg.optInt("score", 0)
                state.bidScores[seat] = score
                val who = if (seat == state.mySeat) "你" else state.seats[seat].name
                state.toast(if (score == 0) "$who 不叫" else "$who 叫 $score 分")
            }

            T.LANDLORD -> {
                state.landlord = msg.optInt("seat", -1).coerceIn(0, 2)
                state.baseScore = msg.optInt("baseScore", 1)
                state.bottom = Proto.decodeCards(msg.optJSONArray("bottom")).toMutableList()
                state.phase = "PLAYING"
                state.multiplier = 1
                if (state.landlord == state.mySeat) {
                    state.hand = Rules.sortDesc(state.hand + state.bottom).toMutableList()
                }
                // 地主多拿三张底牌，必须同步刷新三家的张数角标
                applyCounts(msg)
                sfx.play("landlord")
            }

            T.TURN -> {
                state.currentSeat = msg.optInt("seat", -1)
                state.deadlineTotalMs = 20_000L
                state.deadline = frameNow + msg.optLong("timeLeft", 20000L)
                state.mustPlay = msg.optBoolean("mustPlay", false)
                state.phase = "PLAYING"
                // 重连快照会带上当前炸弹数，否则重连者的倍数显示会偏低
                if (msg.has("bombCount")) state.bombCount = msg.optInt("bombCount", state.bombCount)
                if (state.mustPlay) {
                    // 新的一轮：清空桌面上的牌
                    state.tablePlays = arrayOfNulls(3)
                    state.passed = BooleanArray(3)
                    state.targetCombo = null
                    state.targetSeat = -1
                } else {
                    val ts = msg.optInt("targetSeat", -1)
                    if (ts in 0..2) {
                        state.targetSeat = ts
                        val cards = Proto.decodeCards(msg.optJSONArray("targetCards"))
                        if (cards.isNotEmpty()) {
                            state.tablePlays[ts] = cards
                            state.targetCombo = ComboParser.parse(cards)
                        }
                    }
                }
                state.selected.clear()
                if (state.currentSeat == state.mySeat) sfx.play("your_turn")
            }

            T.PLAYED -> {
                val seat = msg.optInt("seat", -1).coerceIn(0, 2)
                val cards = Proto.decodeCards(msg.optJSONArray("cards"))
                state.tablePlays[seat] = cards
                state.lastPlacedAt[seat] = frameNow
                state.passed[seat] = false
                state.bombCount = msg.optInt("bombCount", state.bombCount)
                state.seats[seat].cardCount = msg.optInt("remain", state.seats[seat].cardCount)
                if (seat == state.mySeat) {
                    state.hand = Rules.remove(state.hand, cards).toMutableList()
                    state.selected.clear()
                }
                val type = msg.optString("type")
                if (type == ComboType.BOMB.name || type == ComboType.ROCKET.name) {
                    sfx.play(if (type == ComboType.ROCKET.name) "rocket" else "bomb")
                } else {
                    sfx.play("play")
                }
            }

            T.PASSED -> {
                val seat = msg.optInt("seat", -1).coerceIn(0, 2)
                state.tablePlays[seat] = null
                state.passed[seat] = true
                state.lastPlacedAt[seat] = frameNow
                sfx.play("pass")
            }

            T.SETTLE -> applySettle(msg)

            T.ERROR -> {
                state.toast(msg.optString("msg"))
                val fatal = msg.optBoolean("fatal", false)
                if (fatal) {
                    closeAll()
                    state.screen = Screen.LOBBY
                }
            }

            T.PEER_LEFT -> {
                state.toast("${msg.optString("name")} 离开了房间")
            }

            T.TAKEOVER -> {
                val seat = msg.optInt("seat", -1).coerceIn(0, 2)
                state.toast("${state.seats[seat].name} 掉线，已由电脑托管")
            }

            T.CHAT -> {
                val sys = msg.optBoolean("system", false)
                if (sys) state.toast(msg.optString("text"))
            }

            T.KICKED -> {
                state.toast(msg.optString("msg") + "，已断开")
                closeAll()
                state.screen = Screen.LOBBY
            }

            T.PONG -> Unit
        }
    }

    private fun applyRoom(msg: JSONObject) {
        val arr = msg.optJSONArray("seats") ?: return
        for (i in 0 until minOf(3, arr.length())) {
            val o = arr.optJSONObject(i) ?: continue
            val idx = o.optInt("seat", i).coerceIn(0, 2)
            val sv = state.seats[idx]
            sv.name = o.optString("name")
            sv.occupied = o.optBoolean("occupied")
            sv.ready = o.optBoolean("ready")
            sv.connected = o.optBoolean("connected")
            sv.isHost = o.optBoolean("isHost")
            sv.ai = o.optBoolean("ai")
            sv.score = o.optInt("score")
        }
        state.roomId = msg.optString("roomId", state.roomId)
        val ph = msg.optString("phase", state.phase)
        state.phase = ph

        // 对局中有人离开，服务端会把 phase 打回 WAITING 并停掉本局。
        // 但客户端的 screen 还停在 TABLE，而牌桌页在非 BIDDING/PLAYING 时一个按钮都不画
        // （准备、电脑补齐都只在大厅/房间页），于是其余人会被永久卡死在牌桌上。
        // 这里检测到这种相位回退就自动退回房间页。
        if (ph == "WAITING" && state.screen == Screen.TABLE) {
            state.screen = Screen.ROOM
            state.result = null
            state.hand = mutableListOf()
            state.selected.clear()
            state.currentSeat = -1
            state.targetCombo = null
            state.targetSeat = -1
            state.tablePlays = arrayOfNulls(3)
            state.passed = BooleanArray(3)
            state.bombCount = 0
            state.multiplier = 1
            state.bottom = mutableListOf()
            state.landlord = -1
            state.toast("本局已中止（有人离开），请重新准备")
        }

        // 座位/人数变了，刷新给广播线程的快照，让别人的房间列表能实时看到人数
        refreshAnnounce()
    }

    private fun applySettle(msg: JSONObject) {
        val handsArr = msg.optJSONArray("hands")
        val hands = ArrayList<List<Card>>(3)
        for (i in 0..2) hands.add(Proto.decodeCards(handsArr?.optJSONArray(i)))
        val deltas = toIntList(msg.optJSONArray("deltas"))
        val totals = toIntList(msg.optJSONArray("totals"))
        val landlord = msg.optInt("landlord", state.landlord).coerceIn(0, 2)
        val landlordWon = msg.optBoolean("landlordWon", false)

        state.landlord = landlord
        state.baseScore = msg.optInt("baseScore", state.baseScore)
        state.multiplier = msg.optInt("multiplier", 1)
        state.bombCount = msg.optInt("bombCount", 0)
        state.phase = "SETTLED"
        state.result = ResultInfo(
            winner = msg.optInt("winner", -1).coerceIn(0, 2),
            landlord = landlord,
            landlordWon = landlordWon,
            spring = msg.optBoolean("spring", false),
            antiSpring = msg.optBoolean("antiSpring", false),
            bombCount = msg.optInt("bombCount", 0),
            baseScore = state.baseScore,
            multiplier = state.multiplier,
            deltas = deltas,
            totals = totals,
            hands = hands
        )
        // 结算画面要亮牌，直接把手牌替换成服务端亮出的那一份
        state.hand = Rules.sortDesc(hands.getOrElse(state.mySeat) { emptyList() }).toMutableList()
        state.selected.clear()
        for (i in 0..2) {
            if (i < state.seats.size) state.seats[i].cardCount = hands.getOrElse(i) { emptyList() }.size
        }

        val iWon = (landlordWon && state.mySeat == landlord) || (!landlordWon && state.mySeat != landlord)
        sfx.play(if (iWon) "win" else "lose")
    }

    private fun toIntList(arr: JSONArray?): List<Int> {
        if (arr == null) return listOf(0, 0, 0)
        val out = ArrayList<Int>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.optInt(i, 0))
        return out
    }

    /**
     * 同步三家的剩余张数角标。
     *
     * 服务端在 START / LANDLORD 里下发 counts，是因为客户端无法自己推断别人的手牌数 ——
     * 只靠 PLAYED 里的 remain 会让开局时两个对手都显示「剩 0 张」。
     */
    private fun applyCounts(msg: JSONObject) {
        val arr = msg.optJSONArray("counts") ?: return
        for (i in 0 until minOf(3, arr.length())) {
            val seat = i.coerceIn(0, 2)
            if (seat == state.mySeat) continue // 自己的张数以本地手牌为准，永远更准
            state.seats[seat].cardCount = arr.optInt(i, state.seats[seat].cardCount)
        }
    }

    // ================================================================ 玩家动作

    private fun sendOrHandle(msg: JSONObject) {
        if (state.isHost) {
            session?.handleMessage(0, msg)
        } else {
            if (client?.send(msg) != true) state.toast("发送失败：连接已断开")
        }
    }

    fun actionReady(ready: Boolean) {
        sfx.play("click")
        sendOrHandle(Proto.obj(T.READY).put("ready", ready))
    }

    fun actionStartWithAi() {
        sfx.play("click")
        if (!state.isHost) return
        session?.startWithAi()
    }

    fun actionBid(score: Int) {
        sfx.play("click")
        sendOrHandle(Proto.obj(T.BID).put("score", score))
    }

    fun actionPass() {
        if (state.mustPlay) {
            state.toast("你是这一轮首家，必须出牌")
            return
        }
        sfx.play("pass")
        sendOrHandle(Proto.obj(T.PASS))
    }

    fun actionPlay() {
        val cards = state.selectedCards()
        if (cards.isEmpty()) {
            state.toast("请先选牌")
            return
        }
        // 本地先校验一遍，给出即时反馈；服务端仍会再校验一次（权威）
        val combo = ComboParser.parse(cards)
        if (!combo.isValid || combo.type == ComboType.PASS) {
            state.toast("不是合法牌型")
            return
        }
        if (!Rules.canBeat(combo, if (state.mustPlay) null else state.targetCombo)) {
            state.toast("压不过上家的牌")
            return
        }
        sfx.play("play")
        sendOrHandle(Proto.obj(T.PLAY).put("cards", Proto.encodeCards(cards)))
    }

    fun actionHint() {
        val target = if (state.mustPlay) null else state.targetCombo
        val hint = Rules.findHint(state.hand, target)
        if (hint == null) {
            state.toast("没有能压过的牌")
            state.selected.clear()
            return
        }
        state.selected.clear()
        // 按 rank+suit 精确匹配回手牌下标，正确处理重复点数
        val used = HashSet<Int>()
        for (c in hint) {
            for (i in state.hand.indices) {
                if (i in used) continue
                val h = state.hand[i]
                if (h.rank == c.rank && h.suit == c.suit) {
                    used.add(i)
                    state.selected.add(i)
                    break
                }
            }
        }
        sfx.play("select")
    }

    fun actionRestart() {
        sfx.play("click")
        state.result = null
        // 房主点「再来一局」时先把已经离开的空位补成电脑；
        // 否则那个空位没人「准备」，三家永远凑不齐，下一局就开不起来。
        if (state.isHost) session?.fillEmptySeatsWithAi()
        // 结算后的「再来一局」等价于重新准备：三家都就绪时服务端会自动开局
        actionReady(true)
    }

    fun actionToggleCard(index: Int) {
        if (state.phase != "PLAYING") return
        if (state.currentSeat != state.mySeat) {
            state.toast("还没轮到你出牌")
            return
        }
        if (state.selected.contains(index)) state.selected.remove(index)
        else state.selected.add(index)
        sfx.play("select")
    }

    fun actionEditNickname() {
        // 输入框的回调在**主线程**触发，所以同样必须投递回渲染线程再改状态
        inputHost.requestText("输入昵称", state.nickname, 12, false) { name ->
            post { saveNickname(name) }
        }
    }

    fun actionEditIp() {
        inputHost.requestText("输入房主 IP", state.manualIp.ifBlank { "192.168.1." }, 21, false) { ip ->
            post { state.manualIp = ip.trim() }
        }
    }

    // ================================================================ 触摸

    private var downRegion: HitRegion? = null
    private var downX = 0f
    private var downY = 0f

    /**
     * **这两个方法由 Android 主线程调用**（SurfaceView.onTouchEvent 由 ViewRootImpl 在主线程派发），
     * 所以绝不能在这里直接改状态或碰 HostSession —— 必须投递到渲染线程执行。
     *
     * 早期版本直接在主线程里跑命中测试和 dispatch，后果是：
     *  - `findHit` 遍历 `state.hits`（渲染线程每帧重建）→ 主线程 ConcurrentModificationException 崩溃；
     *  - 房主点「出牌」会与渲染线程的 `tick()` 并发进入 HostSession →
     *    `hand` 读改写丢失更新（能打出手里没有的牌）、`advanceTurn` 推进两次（跳过一家）、
     *    三家 currentSeat/remain 不一致。
     */
    fun onTouchDown(x: Float, y: Float) {
        post { handleTouchDown(x, y) }
    }

    fun onTouchUp(x: Float, y: Float) {
        post { handleTouchUp(x, y) }
    }

    private fun handleTouchDown(x: Float, y: Float) {
        downX = x
        downY = y
        downRegion = findHit(x, y)
    }

    private fun handleTouchUp(x: Float, y: Float) {
        val region = findHit(x, y) ?: run {
            downRegion = null
            return
        }
        // 允许一点手指抖动，但按下和抬起必须是同一个目标
        val moved = Math.abs(x - downX) + Math.abs(y - downY)
        val down = downRegion
        val same = down != null && down.id == region.id && down.data == region.data
        downRegion = null
        if (!same && moved > 60f) return
        dispatch(region)
    }

    private fun findHit(x: Float, y: Float): HitRegion? {
        // 后登记的在上层，所以要倒序找
        for (i in state.hits.indices.reversed()) {
            val r = state.hits[i]
            if (r.rect.contains(x, y)) return r
        }
        return null
    }

    private fun dispatch(region: HitRegion) {
        when (region.id) {
            "card" -> actionToggleCard(region.data)
            "play" -> actionPlay()
            "pass" -> actionPass()
            "hint" -> actionHint()
            "ready" -> actionReady(!state.seats[state.mySeat].ready)
            "start_ai" -> actionStartWithAi()
            "leave" -> leaveRoom()
            "create" -> createRoom()
            "edit_name" -> actionEditNickname()
            "edit_ip" -> actionEditIp()
            "connect" -> joinRoom(state.manualIp)
            "join_room" -> {
                val room = state.rooms.getOrNull(region.data)
                if (room != null) joinRoom(room.ip, room.port)
            }
            "bid0" -> actionBid(0)
            "bid1" -> actionBid(1)
            "bid2" -> actionBid(2)
            "bid3" -> actionBid(3)
            "restart" -> actionRestart()
            "checklist" -> state.showChecklist = !state.showChecklist
            "close_checklist" -> state.showChecklist = false
            "toggle_sound" -> {
                sfx.enabled = !sfx.enabled
                state.toast(if (sfx.enabled) "已开启音效" else "已关闭音效")
            }
        }
    }

    /**
     * 返回键处理。由 Activity 在**主线程**调用，所以只能在这里做一次「只读判断」，
     * 真正的状态变更一律投递到渲染线程。
     *
     * 这里读的都是 enum / 布尔 / 引用，不涉及集合遍历，主线程读是安全的。
     */
    fun handleBack(): Boolean {
        val consumed = state.showChecklist || state.result != null || state.screen != Screen.LOBBY
        if (consumed) post { handleBackInternal() }
        return consumed
    }

    private fun handleBackInternal() {
        if (state.showChecklist) {
            state.showChecklist = false
            return
        }
        if (state.result != null) {
            state.result = null
            return
        }
        when (state.screen) {
            Screen.TABLE, Screen.ROOM -> leaveRoom()
            Screen.LOBBY -> Unit
        }
    }

    fun release() {
        released = true
        closeAll()
        scanner?.stop()
        scanner = null
        textures.clear()
        sfx.release()
    }

    companion object {
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_NICKNAME = "nickname"

        /** 投递队列上限，超过即视为对端异常并断开 */
        private const val MAX_QUEUED_BLOCKS = 2000

        /** 房主判定某条连接「僵尸」的无消息时长 */
        private const val HOST_IDLE_TIMEOUT_MS = 15_000L
    }
}

package com.ddz.net

import com.ddz.core.Card
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通信协议：TCP 上跑「一行一条 JSON」，UTF-8，消息之间以 \n 分隔。
 *
 * 选择这种格式的原因：可读、可直接用 Android 自带的 org.json 解析、抓包就能看懂、不需要 schema 编译。
 * 单条消息体积极小（最大也就是 17 张手牌 ≈ 400 字节），TCP_NODELAY 下延迟可忽略。
 *
 * 座位约定：0 = 自己（客户端视角固定），主机分配的真实座位号由 join 应答告知。
 */
object T {
    // ---- 客户端 → 主机 ----
    const val JOIN = "join"           // {name, avatar}
    const val READY = "ready"         // {ready: Boolean}
    const val LEAVE = "leave"         // {}
    const val BID = "bid"             // {score: 0..3}
    const val PLAY = "play"           // {cards: [[rank,suit],...]}
    const val PASS = "pass"           // {}
    const val CHAT = "chat"           // {text}
    const val PING = "ping"           // {}
    const val RESTART = "restart"     // {} 请求再来一局（仅房主有效）

    // ---- 主机 → 客户端 ----
    const val JOINED = "joined"       // {seat, roomId, port, resume}
    const val ROOM = "room"           // {seats:[{name,occupied,ready,connected,isHost,ai,score}], canStart, phase}
    const val START = "start"         // {hand, seat, landlord, baseScore, counts, bottom?, resume}
    // 注意：BID_TURN / TURN 里的计时字段叫 timeLeft，是**相对剩余毫秒**，不是绝对时间戳。
    // 用绝对值会依赖两端时钟一致，而手机之间差几秒很常见，倒计时会直接算错。
    const val BID_TURN = "bidTurn"    // {seat, timeLeft, scores:[Int?], highestBid}
    const val BID_DONE = "bidDone"    // {seat, score}
    const val LANDLORD = "landlord"   // {seat, bottom, baseScore, counts} —— 绝不包含地主手牌（会广播给所有人）
    const val TURN = "turn"           // {seat, timeLeft, mustPlay, targetSeat?, targetCards?, targetType?, bombCount?}
    const val PLAYED = "played"       // {seat, cards, type, mainRank, length, bombCount, remain}
    const val PASSED = "passed"       // {seat}
    const val SETTLE = "settle"       // 见 HostSession.settle
    const val ERROR = "error"         // {msg, fatal}
    const val PONG = "pong"           // {}
    const val PEER_LEFT = "peerLeft"  // {seat, name}
    const val TAKEOVER = "takeover"   // {seat} 掉线交由 AI 托管
    const val KICKED = "kicked"       // {msg}
}

object Proto {

    /** 牌 → JSON：压缩成 [rank, suit] 二元数组 */
    fun encodeCards(cards: List<Card>): JSONArray {
        val arr = JSONArray()
        for (c in cards) {
            val pair = JSONArray()
            pair.put(c.rank)
            pair.put(c.suit)
            arr.put(pair)
        }
        return arr
    }

    /** JSON → 牌。容错：非法元素直接跳过，避免一条脏消息把整个客户端搞崩 */
    fun decodeCards(arr: JSONArray?): List<Card> {
        if (arr == null) return emptyList()
        val out = ArrayList<Card>(arr.length())
        for (i in 0 until arr.length()) {
            val pair = arr.optJSONArray(i) ?: continue
            if (pair.length() < 2) continue
            val rank = pair.optInt(0, -1)
            val suit = pair.optInt(1, -1)
            if (rank in 3..17) out.add(Card(rank, suit))
        }
        return out
    }

    fun encodeInts(values: List<Int>): JSONArray {
        val arr = JSONArray()
        for (v in values) arr.put(v)
        return arr
    }

    fun obj(type: String): JSONObject = JSONObject().put("t", type)
}

/** 网络事件回调，全部在后台线程触发；UI 侧必须自己切回渲染线程再改状态 */
interface NetEvents {
    fun onMessage(msg: JSONObject)
    fun onDisconnected(reason: String)
    fun onConnected()
}

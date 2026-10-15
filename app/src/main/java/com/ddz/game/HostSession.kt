package com.ddz.game

import com.ddz.core.Card
import com.ddz.core.CardConst
import com.ddz.core.Combo
import com.ddz.core.ComboParser
import com.ddz.core.ComboType
import com.ddz.core.Deal
import com.ddz.core.Deck
import com.ddz.core.Rules
import com.ddz.core.Scorer
import com.ddz.net.Proto
import com.ddz.net.T
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * 房主侧的权威对局会话。
 *
 * 设计要点（这是「可联机」且不容易出 bug 的关键）：
 *  1. **完全权威**：只有这里持有全部三家手牌、决定发牌、校验每一手牌是否合法。
 *     客户端发来的任何出牌都要过 [Rules.contains] + [ComboParser] + [Rules.canBeat] 三道校验，
 *     所以改客户端无法作弊，最多是把自己弄成非法操作被拒绝。
 *  2. **单线程驱动**：所有状态变更都通过 [handleMessage] / [tick] 进入，调用方保证串行
 *     （网络回调投递到同一队列），因此内部不需要任何锁 —— 这是避免竞态最简单的办法。
 *  3. **超时即托管**：每个决策点都有 deadline，超时由 [tick] 强制推进；
 *     玩家掉线后由 AI 接管，牌局永远不会卡死。
 *
 * 叫分规则采用标准的「一轮叫分」：三人各一次机会，每次必须高于当前最高分（或弃叫），
 * 叫 3 分立即成为地主；全部弃叫则重新发牌。底分 = 最终叫分。
 */
class HostSession(
    val roomId: String,
    private val out: Output,
    private val rng: Random = Random.Default
) {

    /** 会话向外发送消息的出口。座位的连接映射由调用方维护，本类只认座位号。 */
    interface Output {
        fun sendToSeat(seat: Int, msg: JSONObject)
        fun broadcast(msg: JSONObject)
    }

    enum class Phase { WAITING, BIDDING, PLAYING, SETTLED }

    inner class Seat(val index: Int) {
        var occupied = false
        var isHost = false
        var name = ""
        var clientId = ""
        var ready = false
        var connected = false
        var aiControlled = false

        /**
         * 这个座位是「电脑补位」出来的（而不是真人掉线后托管）。
         * 用途：重连匹配时必须排除电脑座位，否则客户端只要把 clientId 填成电脑座位的值，
         * 就能抢占电脑座位、进而拿到它的整手牌。
         */
        var aiSeat = false
        var aiThinkAt = 0L
        var hand = mutableListOf<Card>()
        var bid = -1
        var playedHands = 0
        var lastPlay: List<Card> = emptyList()
        var totalScore = 0
    }

    val seats: List<Seat> = listOf(Seat(0), Seat(1), Seat(2))

    var phase: Phase = Phase.WAITING
        private set

    private var landlord = -1
    private var baseScore = 0
    private var bottom: List<Card> = emptyList()

    private var currentSeat = -1
    private var deadline = 0L

    /** 当前需要被压过的牌型；null 表示自由出牌（新的一轮） */
    private var targetCombo: Combo? = null
    private var targetSeat = -1
    private var targetCards: List<Card> = emptyList()
    private var passCount = 0

    private var bombCount = 0

    private var highestBid = 0
    private var highestBidder = -1
    private var bidRoundCount = 0

    /** 连续「三家都不叫」的次数，用来给重新发牌封顶，避免弱手牌时无限重发 */
    private var redealCount = 0

    // ---------------------------------------------------------------- 生命周期

    init {
        seats[0].isHost = true
    }

    fun setHostPlayer(name: String, clientId: String) {
        val s = seats[0]
        s.occupied = true
        s.connected = true
        s.name = name.ifBlank { "房主" }
        s.clientId = clientId
    }

    /**
     * 客户端接入（第一条 JOIN 消息到达时调用）。**只改状态，不发任何消息。**
     *
     * 为什么不在这里发消息：座位分配完成后，调用方还需要先建立
     * 「座位 ↔ 新连接」的映射，才能把 JOINED / 房间信息 / 牌局快照送对新连接。
     * 早期版本在这里直接发，结果重连时这些消息全被发到了**已经断掉的旧连接**上，
     * 客户端永远拿不回自己的手牌。所以拆成两步：先 [attachClient]，映射好之后调用
     * [sendJoinInfo]。
     *
     * 座位分配放在 JOIN 时（而不是 TCP 连接建立时），是为了支持断线重连：
     * clientId 相同的客户端会复用自己原来的座位，而不是新占一个位置。
     *
     * @return 分配到的座位号；null 表示房间已满（调用方应回 KICKED 并断开连接）
     */
    fun attachClient(clientId: String, name: String): Int? {
        val rejoin = if (clientId.isNotBlank()) {
            // 只允许重连到「真人占用的座位」：房主座位与电脑补位座位一律不参与重连匹配。
            // 否则客户端只要把 clientId 填成电脑座位的值，就能抢占电脑座位，
            // 修好重连快照后还会连带拿到那个座位的整手牌。
            seats.firstOrNull {
                it.occupied && !it.isHost && !it.aiSeat && it.clientId == clientId
            }
        } else null
        val seat = rejoin ?: seats.firstOrNull { !it.occupied }
        if (seat == null) return null
        // 房主座位永远不交给任何远端连接（即使对方伪造了房主的 clientId）
        if (seat.isHost) return null

        seat.occupied = true
        seat.connected = true
        seat.aiControlled = false
        seat.aiSeat = false
        seat.ready = false
        // 昵称截断：房主会把昵称广播给所有人，不限制长度就是一个带宽/内存放大点
        seat.name = name.trim().take(12).ifBlank { "玩家${seat.index + 1}" }
        // clientId 同样要截断：它是重连匹配的键，超长值会造成内存/广播放大，
        // 而且截断策略一旦在客户端侧变化，前缀相同的 ID 会互相抢占座位。
        seat.clientId = clientId.take(64)
        return seat.index
    }

    /** 连接映射建立后调用：把「你是谁 / 房间现状 / 牌局快照」发给这个座位 */
    fun sendJoinInfo(seat: Int) {
        if (seat !in 0..2) return
        val s = seats[seat]
        if (!s.occupied) return
        val inGame = phase == Phase.PLAYING || phase == Phase.BIDDING
        out.sendToSeat(
            seat,
            Proto.obj(T.JOINED)
                .put("seat", seat)
                .put("roomId", roomId)
                .put("resume", inGame)
        )
        broadcastRoom()
        if (inGame) {
            sendResumeSnapshot(s)
        } else {
            maybeAutoStart()
        }
    }

    /**
     * 处理一条来自某座位的消息。所有入口都收敛到这里，保证串行。
     * 注意：JOIN 不在这里处理 —— 座位分配必须发生在消息路由之前（见 [attachClient]）。
     */
    fun handleMessage(seat: Int, msg: JSONObject) {
        if (seat !in 0..2) return
        val s = seats[seat]
        if (!s.occupied) return

        when (msg.optString("t")) {
            T.READY -> {
                s.ready = msg.optBoolean("ready", true)
                broadcastRoom()
                maybeAutoStart()
            }
            T.BID -> onBid(s, msg.optInt("score", 0))
            T.PLAY -> onPlay(s, Proto.decodeCards(msg.optJSONArray("cards")))
            T.PASS -> onPass(s)
            T.CHAT -> onChat(s, msg.optString("text"))
            T.PING -> out.sendToSeat(s.index, Proto.obj(T.PONG))
            T.RESTART -> if (s.isHost) requestRestart()
            T.LEAVE -> onLeave(s.index)
        }
    }

    /** 重连时把「我是谁、我有什么牌、现在轮到谁、台面上是什么」一次补齐 */
    private fun sendResumeSnapshot(s: Seat) {
        val start = Proto.obj(T.START)
            .put("seat", s.index)
            .put("hand", Proto.encodeCards(s.hand))
            .put("landlord", landlord)
            .put("baseScore", baseScore)
            .put("resume", true)
            .put("bottom", Proto.encodeCards(bottom))
            .put("counts", Proto.encodeInts(List(3) { seats[it].hand.size }))
        out.sendToSeat(s.index, start)

        if (phase == Phase.BIDDING) {
            // 叫分阶段必须发 BID_TURN 而不是 TURN：
            // 客户端收到 TURN 会把自己判成「出牌阶段」，于是叫分按钮根本不出现，
            // 而服务端其实正在等他叫分 —— 双方就这么对等到超时。
            val scores = JSONArray()
            for (st in seats) scores.put(if (st.bid < 0) JSONObject.NULL else st.bid)
            out.sendToSeat(
                s.index,
                Proto.obj(T.BID_TURN)
                    .put("seat", currentSeat)
                    .put("timeLeft", (deadline - System.currentTimeMillis()).coerceAtLeast(0L))
                    .put("scores", scores)
                    .put("highestBid", highestBid)
            )
            return
        }

        val turn = Proto.obj(T.TURN)
            .put("seat", currentSeat)
            .put("timeLeft", (deadline - System.currentTimeMillis()).coerceAtLeast(0L))
            .put("mustPlay", targetCombo == null)
            .put("bombCount", bombCount) // 重连者错过了之前的 PLAYED，倍数得靠这里补
        if (targetSeat >= 0 && targetCombo != null) {
            turn.put("targetSeat", targetSeat)
            turn.put("targetCards", Proto.encodeCards(targetCards))
            turn.put("targetType", targetCombo?.type?.name ?: "")
        }
        out.sendToSeat(s.index, turn)
    }

    fun onPeerDisconnected(seat: Int) {
        if (seat !in 0..2) return
        val s = seats[seat]
        if (!s.occupied) return
        s.connected = false

        if (phase == Phase.WAITING || phase == Phase.SETTLED) {
            // 还没开局或已结算：直接释放座位
            resetSeat(s)
            out.broadcast(Proto.obj(T.PEER_LEFT).put("seat", seat).put("name", s.name))
            broadcastRoom()
            return
        }

        // 对局中：交给 AI 托管，牌局继续
        s.aiControlled = true
        s.aiThinkAt = System.currentTimeMillis() + 600
        out.broadcast(
            Proto.obj(T.PEER_LEFT).put("seat", seat).put("name", s.name)
        )
        out.broadcast(Proto.obj(T.TAKEOVER).put("seat", seat))
        broadcastRoom()
    }

    fun onLeave(seat: Int) {
        val s = seats[seat]
        if (s.isHost) return // 房主不能中途走人，只能解散
        resetSeat(s)
        out.broadcast(Proto.obj(T.PEER_LEFT).put("seat", seat).put("name", s.name))
        broadcastRoom()
    }

    private fun resetSeat(s: Seat) {
        s.occupied = false
        s.connected = false
        s.ready = false
        s.aiControlled = false
        s.aiSeat = false
        s.clientId = ""
        s.hand = mutableListOf()
        s.bid = -1
        s.playedHands = 0
        s.lastPlay = emptyList()
        // 只有对局进行中有人离开才需要强制结束本局；结算画面/等待中不影响其他人
        if (phase == Phase.BIDDING || phase == Phase.PLAYING) {
            phase = Phase.WAITING
            currentSeat = -1
            targetCombo = null
        }
    }

    // ---------------------------------------------------------------- 开局

    private fun maybeAutoStart() {
        if (phase != Phase.WAITING && phase != Phase.SETTLED) return
        if (seats.none { it.occupied }) return
        // 电脑/托管座位没有「准备」这个动作，按 aiControlled 视为随时就绪；
        // 真人座位必须连接正常且已准备。掉线被托管的座位如果还要求 connected，就会永远开不了下一局。
        // 这个判据必须和 roomInfo().canStart 保持一致。
        if (!seats.all { it.occupied && (it.aiControlled || (it.connected && it.ready)) }) return
        startGame()
    }

    fun requestRestart() {
        if (phase != Phase.PLAYING && phase != Phase.BIDDING) {
            for (s in seats) {
                if (!s.aiControlled) s.ready = false
                s.hand = mutableListOf()
                s.bid = -1
                s.playedHands = 0
                s.lastPlay = emptyList()
            }
            phase = Phase.WAITING
            currentSeat = -1
            targetCombo = null
            landlord = -1
            broadcastRoom()
        }
    }

    /**
     * 把空位补成电脑。返回是否真的补了人。
     *
     * 单独抽出来是因为它有两个调用场景：房主主动「电脑补齐」开局，
     * 以及结算后有人已经离开、房主点「再来一局」——后者不补位就永远凑不齐三人。
     */
    fun fillEmptySeatsWithAi(): Boolean {
        var filled = false
        for (s in seats) {
            if (!s.occupied) {
                s.occupied = true
                s.connected = true
                s.aiControlled = true
                s.aiSeat = true
                s.ready = true
                s.name = "电脑${s.index}"
                // 用不可预测的随机 clientId。电脑座位本来就不参与重连匹配（见 attachClient），
                // 这里再随机化一道，避免固定字符串将来变成后门。
                s.clientId = "ai-" + java.util.UUID.randomUUID().toString()
                filled = true
            }
        }
        // 补位后房间人数变了，必须广播一次：否则大厅里别人看到的还是旧人数
        // （典型现象：房间其实已经 3/3 满员，房间列表里却一直显示「1/3」，把人骗进来再 KICKED）。
        if (filled) broadcastRoom()
        return filled
    }

    /**
     * 人数不足时由房主直接开局，空位用电脑补齐。
     * 这样「一个人也能马上试玩」，不必等凑齐三个人。
     */
    fun startWithAi() {
        if (phase == Phase.PLAYING || phase == Phase.BIDDING) return
        fillEmptySeatsWithAi()
        startGame()
    }

    fun startGame() {
        val deal: Deal = Deck.deal(Deck.shuffled(rng))
        for (i in 0..2) {
            seats[i].hand = Rules.sortDesc(deal.hands[i]).toMutableList()
            seats[i].bid = -1
            seats[i].playedHands = 0
            seats[i].lastPlay = emptyList()
        }
        bottom = deal.bottom
        bombCount = 0
        landlord = -1
        baseScore = 0
        highestBid = 0
        highestBidder = -1
        bidRoundCount = 0
        passCount = 0
        targetCombo = null
        targetSeat = -1
        targetCards = emptyList()
        phase = Phase.BIDDING

        // 发牌：手牌只发给本人，杜绝「抓包看别人牌」。
        // counts 必须一起下发，否则客户端只能靠「有人出牌」推断别人剩几张，
        // 开局时两个对手会显示成「剩 0 张」。
        val counts = Proto.encodeInts(List(3) { seats[it].hand.size })
        for (s in seats) {
            out.sendToSeat(
                s.index,
                Proto.obj(T.START)
                    .put("seat", s.index)
                    .put("hand", Proto.encodeCards(s.hand))
                    .put("landlord", -1)
                    .put("baseScore", 0)
                    .put("counts", counts)
                    .put("resume", false)
            )
        }

        currentSeat = rng.nextInt(3)
        deadline = System.currentTimeMillis() + BID_TIMEOUT_MS
        broadcastBidTurn()
    }

    private fun broadcastBidTurn() {
        val scores = JSONArray()
        for (s in seats) scores.put(if (s.bid < 0) JSONObject.NULL else s.bid)
        out.broadcast(
            Proto.obj(T.BID_TURN)
                .put("seat", currentSeat)
                .put("timeLeft", (deadline - System.currentTimeMillis()).coerceAtLeast(0L))
                .put("scores", scores)
                .put("highestBid", highestBid)
        )
        val s = seats[currentSeat]
        if (s.aiControlled || !s.connected) {
            s.aiThinkAt = System.currentTimeMillis() + AI_DELAY_MS
        }
    }

    // ---------------------------------------------------------------- 叫分

    private fun onBid(s: Seat, rawScore: Int) {
        if (phase != Phase.BIDDING || s.index != currentSeat) return
        var score = rawScore.coerceIn(0, 3)
        // 必须高于当前最高分，否则视为弃叫（客户端被改也不会造成非法状态）
        if (score != 0 && score <= highestBid) score = 0

        s.bid = score
        if (score > highestBid) {
            highestBid = score
            highestBidder = s.index
        }
        out.broadcast(Proto.obj(T.BID_DONE).put("seat", s.index).put("score", score))

        if (score == 3) {
            finishBidding()
            return
        }

        bidRoundCount++
        if (bidRoundCount >= 3) {
            if (highestBidder < 0) {
                redealCount++
                if (redealCount >= MAX_REDEAL) {
                    // 连续三家都不叫：不能无限重发（三个弱手牌可能一直循环下去）。
                    // 强制由手牌最强的一家以 1 分当地主，把牌局推进下去。
                    out.broadcast(
                        Proto.obj(T.CHAT).put("system", true)
                            .put("text", "连续 ${MAX_REDEAL} 轮无人叫分，强制由手牌最强的一方当地主")
                    )
                    highestBidder = seats.indices.maxByOrNull { handStrength(seats[it]) } ?: 0
                    highestBid = 1
                    finishBidding()
                } else {
                    out.broadcast(Proto.obj(T.CHAT).put("system", true).put("text", "三家都不叫，重新发牌"))
                    startGame()
                }
            } else {
                finishBidding()
            }
            return
        }

        currentSeat = (currentSeat + 1) % 3
        deadline = System.currentTimeMillis() + BID_TIMEOUT_MS
        broadcastBidTurn()
    }

    private fun finishBidding() {
        landlord = highestBidder
        baseScore = highestBid.coerceAtLeast(1)
        // 叫分结束，重发计数器归零（它统计的是「连续」无人叫分的次数）
        redealCount = 0
        val lord = seats[landlord]
        // 地主 = 17 张 + 3 张底牌 = 20 张
        lord.hand = Rules.sortDesc(lord.hand + bottom).toMutableList()

        // 注意：这条消息是**广播**给三家的，所以绝对不能带上地主的手牌。
        // 早期版本在这里 put("hand", 地主手牌)，注释写着「只有地主本人会用到」，
        // 但 broadcast 会把同一份 JSON 发给所有连接 —— 改个客户端抓包就能看到地主的 20 张牌。
        // 地主客户端本来就能从 bottom 字段自己把底牌加进手牌，所以这里不需要 hand。
        out.broadcast(
            Proto.obj(T.LANDLORD)
                .put("seat", landlord)
                .put("bottom", Proto.encodeCards(bottom))
                .put("baseScore", baseScore)
                .put("counts", Proto.encodeInts(List(3) { seats[it].hand.size }))
        )

        phase = Phase.PLAYING
        currentSeat = landlord
        targetCombo = null
        targetSeat = -1
        targetCards = emptyList()
        passCount = 0
        deadline = System.currentTimeMillis() + PLAY_TIMEOUT_MS
        broadcastTurn()
    }

    // ---------------------------------------------------------------- 出牌

    private fun broadcastTurn() {
        val mustPlay = targetCombo == null
        val msg = Proto.obj(T.TURN)
            .put("seat", currentSeat)
            .put("timeLeft", (deadline - System.currentTimeMillis()).coerceAtLeast(0L))
            .put("mustPlay", mustPlay)
        if (targetSeat >= 0 && !mustPlay) {
            msg.put("targetSeat", targetSeat)
            msg.put("targetCards", Proto.encodeCards(targetCards))
            msg.put("targetType", targetCombo?.type?.name ?: "")
        }
        out.broadcast(msg)

        val s = seats[currentSeat]
        if (s.aiControlled || !s.connected) {
            s.aiThinkAt = System.currentTimeMillis() + AI_DELAY_MS
        }
    }

    private fun onPlay(s: Seat, cards: List<Card>) {
        if (phase != Phase.PLAYING || s.index != currentSeat) return
        if (cards.isEmpty()) {
            fail(s.index, "没有选择任何牌")
            return
        }
        if (!Rules.contains(s.hand, cards)) {
            fail(s.index, "手牌里没有这些牌")
            return
        }
        val combo = ComboParser.parse(cards)
        if (!combo.isValid || combo.type == ComboType.PASS) {
            fail(s.index, "不是合法牌型")
            return
        }
        if (!Rules.canBeat(combo, targetCombo)) {
            fail(s.index, "压不过上家的牌")
            return
        }

        s.hand = Rules.remove(s.hand, cards).toMutableList()
        s.lastPlay = cards
        s.playedHands++

        if (combo.type == ComboType.BOMB || combo.type == ComboType.ROCKET) {
            bombCount++
        }

        out.broadcast(
            Proto.obj(T.PLAYED)
                .put("seat", s.index)
                .put("cards", Proto.encodeCards(cards))
                .put("type", combo.type.name)
                .put("mainRank", combo.mainRank)
                .put("length", combo.length)
                .put("bombCount", bombCount)
                .put("remain", s.hand.size)
        )

        if (s.hand.isEmpty()) {
            settle(s.index)
            return
        }

        targetCombo = combo
        targetSeat = s.index
        targetCards = cards
        passCount = 0
        advanceTurn()
    }

    private fun onPass(s: Seat) {
        if (phase != Phase.PLAYING || s.index != currentSeat) return
        if (targetCombo == null) {
            fail(s.index, "你是首家，必须出牌")
            return
        }
        out.broadcast(Proto.obj(T.PASSED).put("seat", s.index))
        passCount++
        if (passCount >= 2) {
            // 其余两家都不要：由最后出牌的人开新的一轮
            targetCombo = null
            targetSeat = -1
            targetCards = emptyList()
            passCount = 0
        }
        advanceTurn()
    }

    private fun advanceTurn() {
        currentSeat = (currentSeat + 1) % 3
        deadline = System.currentTimeMillis() + PLAY_TIMEOUT_MS
        broadcastTurn()
    }

    private fun fail(seat: Int, msg: String) {
        out.sendToSeat(seat, Proto.obj(T.ERROR).put("msg", msg).put("fatal", false))
    }

    private fun onChat(s: Seat, text: String) {
        if (text.isBlank()) return
        out.broadcast(
            Proto.obj(T.CHAT)
                .put("seat", s.index)
                .put("name", s.name)
                .put("text", text.take(60))
        )
    }

    // ---------------------------------------------------------------- 结算

    private fun settle(winner: Int) {
        phase = Phase.SETTLED
        val landlordWon = winner == landlord
        val farmers = (0..2).filter { it != landlord }
        val spring = landlordWon && farmers.all { seats[it].playedHands == 0 }
        val antiSpring = !landlordWon && seats[landlord].playedHands == 1

        val result = Scorer.settle(
            baseScore = baseScore,
            bombCount = bombCount,
            spring = spring,
            antiSpring = antiSpring,
            landlordWon = landlordWon
        )

        val deltas = IntArray(3)
        for (i in 0..2) {
            deltas[i] = if (i == landlord) result.landlordDelta else result.farmerDelta
            seats[i].totalScore += deltas[i]
        }

        // 结算时亮出所有手牌（此时已无作弊意义）
        val hands = JSONArray()
        for (i in 0..2) hands.put(Proto.encodeCards(seats[i].hand))

        val totals = JSONArray()
        for (i in 0..2) totals.put(seats[i].totalScore)

        out.broadcast(
            Proto.obj(T.SETTLE)
                .put("winner", winner)
                .put("landlord", landlord)
                .put("landlordWon", landlordWon)
                .put("spring", spring)
                .put("antiSpring", antiSpring)
                .put("bombCount", bombCount)
                .put("baseScore", baseScore)
                .put("multiplier", result.multiplier)
                .put("deltas", Proto.encodeInts(deltas.toList()))
                .put("totals", totals)
                .put("hands", hands)
        )

        for (s in seats) {
            if (!s.aiControlled) s.ready = false
        }
    }

    // ---------------------------------------------------------------- 定时驱动

    /**
     * 由外部调度线程周期调用（建议 100~200ms 一次）。
     * 负责：超时强制推进、AI/托管玩家自动行动。
     */
    fun tick(now: Long) {
        if (currentSeat !in 0..2) return
        if (phase != Phase.BIDDING && phase != Phase.PLAYING) return
        val s = seats[currentSeat]
        if (!s.occupied) return

        val timeout = now >= deadline
        val aiTurn = (s.aiControlled || !s.connected) && now >= s.aiThinkAt && s.aiThinkAt > 0
        if (!timeout && !aiTurn) return

        if (phase == Phase.BIDDING) {
            onBid(s, aiBidScore(s))
        } else {
            autoPlay(s)
        }
    }

    private fun autoPlay(s: Seat) {
        val isLandlord = s.index == landlord
        val teammate = if (isLandlord) -1 else (0..2).firstOrNull { it != landlord && it != s.index } ?: -1
        val lastPlayerIsTeammate = targetSeat >= 0 && !isLandlord && targetSeat != landlord
        val landlordRemain = if (landlord >= 0) seats[landlord].hand.size else 99
        val teammateRemain = if (teammate >= 0) seats[teammate].hand.size else 99

        val decision = AiPlayer.decide(
            hand = s.hand,
            target = targetCombo,
            isLandlord = isLandlord,
            lastPlayerIsTeammate = lastPlayerIsTeammate,
            landlordRemain = landlordRemain,
            teammateRemain = teammateRemain,
            rng = rng
        )
        if (decision.isEmpty()) {
            if (targetCombo == null) {
                // 必须出牌却拿不出牌：理论上不可能（单张永远合法），兜底防止死锁
                val card = s.hand.minByOrNull { it.rank } ?: return
                onPlay(s, listOf(card))
            } else {
                onPass(s)
            }
        } else {
            onPlay(s, decision)
        }
    }

    /**
     * 手牌强度估值：王、2、炸弹、对 A 都加分。
     * 同时被 AI 叫分和「连续无人叫分时指定地主」使用，保证两处口径一致。
     */
    private fun handStrength(s: Seat): Int {
        var score = 0
        var twos = 0
        var jokers = 0
        var bombs = 0
        val countByRank = HashMap<Int, Int>()
        for (c in s.hand) {
            countByRank[c.rank] = (countByRank[c.rank] ?: 0) + 1
            if (c.rank == CardConst.R2) twos++
            if (c.isJoker) jokers++
        }
        for (v in countByRank.values) if (v == 4) bombs++
        score += twos * 2 + jokers * 3 + bombs * 5
        // 注意括号：Kotlin 里 >= 的优先级高于 ?:，不写括号会变成 Int ?: Boolean 的类型错误
        if ((countByRank[CardConst.RA] ?: 0) >= 2) score += 2
        return score
    }

    /** AI 叫分：按手牌强度给个简单估值 */
    private fun aiBidScore(s: Seat): Int {
        val score = handStrength(s)
        val want = when {
            score >= 12 -> 3
            score >= 8 -> 2
            score >= 4 -> 1
            else -> 0
        }
        // 不能叫得比当前最高分低，否则只能弃叫
        return if (want > highestBid) want else 0
    }

    // ---------------------------------------------------------------- 信息输出

    fun broadcastRoom() {
        out.broadcast(roomInfo())
    }

    fun roomInfo(): JSONObject {
        val arr = JSONArray()
        for (s in seats) {
            arr.put(
                JSONObject()
                    .put("seat", s.index)
                    .put("name", s.name)
                    .put("occupied", s.occupied)
                    .put("ready", s.ready)
                    .put("connected", s.connected)
                    .put("isHost", s.isHost)
                    .put("ai", s.aiControlled)
                    .put("score", s.totalScore)
            )
        }
        return Proto.obj(T.ROOM)
            .put("roomId", roomId)
            .put("seats", arr)
            .put(
                "canStart",
                seats.all { it.occupied && (it.aiControlled || (it.connected && it.ready)) }
            )
            .put("phase", phase.name)
    }

    fun occupancy(): Int = seats.count { it.occupied }

    fun currentTurnSeat(): Int = currentSeat

    fun deadlineMs(): Long = deadline

    companion object {
        const val BID_TIMEOUT_MS = 10_000L
        const val PLAY_TIMEOUT_MS = 20_000L

        /** AI / 托管玩家的「思考」延迟，让操作看起来像真人 */
        const val AI_DELAY_MS = 800L

        /** 连续「三家都不叫」的重发上限，达到后强制指定地主，避免无限重发 */
        const val MAX_REDEAL = 3
    }
}

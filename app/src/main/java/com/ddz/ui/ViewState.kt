package com.ddz.ui

import com.ddz.core.Card
import com.ddz.core.Combo
import com.ddz.net.HostServer
import com.ddz.net.RoomScanner
import android.graphics.RectF

/** 一个座位的展示信息 */
class SeatView(
    var name: String = "",
    var occupied: Boolean = false,
    var ready: Boolean = false,
    var connected: Boolean = false,
    var isHost: Boolean = false,
    var ai: Boolean = false,
    var cardCount: Int = 0,
    var score: Int = 0
)

/** 一局结束后的结算数据 */
class ResultInfo(
    val winner: Int,
    val landlord: Int,
    val landlordWon: Boolean,
    val spring: Boolean,
    val antiSpring: Boolean,
    val bombCount: Int,
    val baseScore: Int,
    val multiplier: Int,
    val deltas: List<Int>,
    val totals: List<Int>,
    val hands: List<List<Card>>
)

/** 可点击区域。渲染时登记，触摸时命中测试 —— 立即模式 UI 的常见做法，布局和点击永远一致。 */
class HitRegion(val id: String, val rect: RectF, val data: Int = -1)

/** 文本输入的宿主（由 SurfaceView 实现，内部用一个隐藏的 EditText 拉起重输入法） */
interface TextInputHost {
    fun requestText(
        title: String,
        initial: String,
        maxLength: Int,
        numeric: Boolean,
        onDone: (String) -> Unit
    )
}

/**
 * 全部界面状态。渲染层只读它，控制器只写它 —— 单向数据流，调 UI 时不容易出乱子。
 */
class ViewState {

    /**
     * 当前界面。
     *
     * 这个字段会被**主线程只读**一次（返回键判断该不该退出应用），
     * 因此加 @Volatile 保证主线程能看到渲染线程的最新写入。
     */
    @Volatile
    var screen = Screen.LOBBY

    var nickname = ""
    var clientId = ""

    // 房间
    var isHost = false
    var mySeat = 0
    var roomId = ""
    var hostIp = ""
    var hostPort = HostServer.DEFAULT_PORT
    var manualIp = ""
    var myIp = ""
    /** 房主的 TCP 服务器是否真的在监听（端口被占用时会降级为单机房） */
    var hostServerRunning = false
    var seats = MutableList(3) { SeatView() }
    var phase = "WAITING"

    // 牌局
    var hand = mutableListOf<Card>()
    var selected = HashSet<Int>()
    var landlord = -1
    var baseScore = 0
    var bottom = mutableListOf<Card>()
    var currentSeat = -1
    var deadline = 0L
    var mustPlay = false
    var targetCombo: Combo? = null
    var targetSeat = -1
    var tablePlays = arrayOfNulls<List<Card>>(3)
    var bombCount = 0
    var multiplier = 1
    var bidScores = arrayOfNulls<Int>(3)
    @Volatile
    var result: ResultInfo? = null

    /** 同 [screen]：这几个字段会被主线程只读一次（返回键判断），加 @Volatile 保证可见性 */
    @Volatile
    var showChecklist = false
    var lastPlacedAt = arrayOf(0L, 0L, 0L)

    /** 每家是否刚说过「不出」，用于短暂显示「不出」标签 */
    var passed = BooleanArray(3)

    /** 当前回合计时总时长，用于计算倒计时环的比例（叫分 10s / 出牌 20s） */
    var deadlineTotalMs = 20_000L

    // 大厅
    var rooms: List<RoomScanner.Room> = emptyList()
    var scanning = false
    var connected = false
    var statusText = ""
    var toastText: String? = null
    var toastUntil = 0L
    var showHelp = false

    /** 由渲染层每帧填充 */
    val hits = ArrayList<HitRegion>(48)

    fun seatCount(index: Int): Int {
        if (index == mySeat) return hand.size
        return seats.getOrNull(index)?.cardCount ?: 0
    }

    /** 按顺时针把另外两家映射成「左边」和「右边」 */
    fun leftSeat(): Int = (mySeat + 1) % 3
    fun rightSeat(): Int = (mySeat + 2) % 3

    fun selectedCards(): List<Card> =
        selected.sorted().mapNotNull { hand.getOrNull(it) }

    fun toast(msg: String, now: Long = System.currentTimeMillis()) {
        toastText = msg
        toastUntil = now + 2600
    }
}

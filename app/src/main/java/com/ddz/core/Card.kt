package com.ddz.core

/**
 * 一张扑克牌。
 *
 * rank：3..15 表示 3,4,5,6,7,8,9,10,J,Q,K,A,2；16 = 小王；17 = 大王。
 * suit：0 黑桃 1 红桃 2 梅花 3 方块；大小王使用 [CardConst.JOKER] = -1。
 */
data class Card(val rank: Int, val suit: Int) {

    /** 是否大小王 */
    val isJoker: Boolean
        get() = rank >= CardConst.JOKER_SMALL

    /** 人类可读的牌面文字，如 "3" / "10" / "J" / "小王" */
    val label: String
        get() = when (rank) {
            CardConst.RJ -> "J"
            CardConst.RQ -> "Q"
            CardConst.RK -> "K"
            CardConst.RA -> "A"
            CardConst.R2 -> "2"
            CardConst.JOKER_SMALL -> "小王"
            CardConst.JOKER_BIG -> "大王"
            else -> rank.toString()
        }

    /**
     * 贴图资源名（不含扩展名）。
     * 黑桃 card_s_*、红桃 card_h_*、梅花 card_c_*、方块 card_d_*；
     * 大小王 card_joker_small / card_joker_big。
     *
     * **点数后缀一律小写**（`card_s_j.png`，不是 `card_s_J.png`）：
     * Android 资源名禁止大写字母，用小写则同一份文件既能放 `assets/`，
     * 也能放进 `res/drawable-nodpi/`。注意 [label] 仍是给人看的大写形式，
     * 两者刻意分开。
     */
    val textureName: String
        get() {
            if (isJoker) {
                return if (rank == CardConst.JOKER_SMALL) "card_joker_small" else "card_joker_big"
            }
            val prefix = when (suit) {
                CardConst.SPADE -> "card_s_"
                CardConst.HEART -> "card_h_"
                CardConst.CLUB -> "card_c_"
                CardConst.DIAMOND -> "card_d_"
                else -> "card_x_"
            }
            return prefix + textureSuffix
        }

    /** 贴图用的点数后缀：3..10 直接用数字，J/Q/K/A 小写，**2 也是 "2" 而不是 rank 值 15** */
    val textureSuffix: String
        get() = when (rank) {
            CardConst.RJ -> "j"
            CardConst.RQ -> "q"
            CardConst.RK -> "k"
            CardConst.RA -> "a"
            // 必须显式列出 R2：rank 15 直接 toString() 会得到 "15"，
            // 于是四张 2 的贴图名变成 card_s_15.png，永远找不到图 → 全部退化成占位图。
            // 这个 bug 是自测里的 "texture 黑桃2" 断言抓出来的。
            CardConst.R2 -> "2"
            else -> rank.toString()
        }

    override fun toString(): String = if (isJoker) label else label + suitSymbol()

    private fun suitSymbol(): String = when (suit) {
        CardConst.SPADE -> "♠"
        CardConst.HEART -> "♥"
        CardConst.CLUB -> "♣"
        CardConst.DIAMOND -> "♦"
        else -> "?"
    }
}

/** 牌面常量表 */
object CardConst {
    const val SPADE = 0
    const val HEART = 1
    const val CLUB = 2
    const val DIAMOND = 3
    const val JOKER = -1

    const val R3 = 3
    const val R4 = 4
    const val R5 = 5
    const val R6 = 6
    const val R7 = 7
    const val R8 = 8
    const val R9 = 9
    const val R10 = 10
    const val RJ = 11
    const val RQ = 12
    const val RK = 13
    const val RA = 14
    const val R2 = 15

    const val JOKER_SMALL = 16
    const val JOKER_BIG = 17

    /** 普通牌最小 rank */
    const val MIN_RANK = R3
    /** 普通牌最大 rank（2，不参与任何连续序列） */
    const val MAX_RANK = R2
    /** 连续序列（顺子/连对/飞机）允许的最大 rank：A */
    const val MAX_CHAIN_RANK = RA
    /** rank 统计数组大小（下标 0..17） */
    const val RANK_TABLE = 18
}

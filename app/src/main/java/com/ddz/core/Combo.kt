package com.ddz.core

/** 牌型枚举 */
enum class ComboType {
    PASS, INVALID, SINGLE, PAIR, TRIPLE, TRIPLE_ONE, TRIPLE_TWO,
    STRAIGHT, STRAIGHT_PAIR, PLANE, PLANE_ONE, PLANE_TWO,
    FOUR_TWO_SINGLE, FOUR_TWO_PAIR, BOMB, ROCKET
}

/**
 * 一手牌。
 *
 * [mainRank]：
 *  - STRAIGHT / STRAIGHT_PAIR / PLANE*：连续序列的**最大** rank
 *  - BOMB / TRIPLE* / FOUR_TWO_*：那个三张或四张的 rank
 *  - PAIR / SINGLE：该牌 rank
 *  - ROCKET：JOKER_BIG
 *  - PASS / INVALID：0
 *
 * [length]：
 *  - STRAIGHT：张数（5..12）
 *  - STRAIGHT_PAIR：对数（3..10）
 *  - PLANE / PLANE_ONE / PLANE_TWO：三张的组数（2..6）
 *  - 其余：cards.size
 */
data class Combo(val type: ComboType, val mainRank: Int, val length: Int, val cards: List<Card>) {

    /** 只有 PASS / INVALID 之外、且手牌非空的牌型才算合法 */
    val isValid: Boolean
        get() = type != ComboType.PASS && type != ComboType.INVALID && cards.isNotEmpty()

    override fun toString(): String =
        "$type(rank=$mainRank, len=$length, cards=${cards.joinToString(",") { it.label }})"

    companion object {
        /** 过牌（不出） */
        val PASS: Combo = Combo(ComboType.PASS, 0, 0, emptyList())

        /** 非法牌型（无牌） */
        fun invalid(): Combo = Combo(ComboType.INVALID, 0, 0, emptyList())
    }
}

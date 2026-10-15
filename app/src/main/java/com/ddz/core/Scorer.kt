package com.ddz.core

/** 结算结果 */
data class ScoreResult(
    val base: Int,          // 叫分底分 1..3
    val multiplier: Int,    // 总倍数
    val landlordDelta: Int, // 地主得分变化（赢为正）
    val farmerDelta: Int    // 每个农民得分变化
)

/**
 * 计分：
 * multiplier = 2^bombCount × (春天 || 反春 ? 2 : 1) × extraMultiplier，上限 [MAX_MULTIPLIER]。
 * 地主赢：landlordDelta = base × multiplier × 2，farmerDelta = -base × multiplier；地主输取反。
 */
object Scorer {

    /** 倍数上限，防止溢出 */
    const val MAX_MULTIPLIER = 1024

    fun settle(
        baseScore: Int,
        bombCount: Int,
        spring: Boolean,
        antiSpring: Boolean,
        landlordWon: Boolean,
        extraMultiplier: Int = 1
    ): ScoreResult {
        val bombs = if (bombCount < 0) 0 else bombCount
        val extra = if (extraMultiplier < 1) 1 else extraMultiplier

        // 2^bombs 先按上限截断，避免移位溢出
        var m = 1L
        var i = 0
        while (i < bombs) {
            m *= 2L
            if (m >= MAX_MULTIPLIER.toLong()) {
                m = MAX_MULTIPLIER.toLong()
                break
            }
            i++
        }
        if (spring || antiSpring) m *= 2L
        m *= extra.toLong()
        if (m > MAX_MULTIPLIER.toLong()) m = MAX_MULTIPLIER.toLong()
        if (m < 1L) m = 1L

        val base = if (baseScore < 0) 0 else baseScore
        val landlord = clamp(base.toLong() * m * 2L)
        val farmer = clamp(-base.toLong() * m)
        return if (landlordWon) {
            ScoreResult(base, m.toInt(), landlord, farmer)
        } else {
            ScoreResult(base, m.toInt(), -landlord, -farmer)
        }
    }

    /** 限制在 Int 范围内（极限参数下也不会溢出） */
    private fun clamp(v: Long): Int = when {
        v > Int.MAX_VALUE.toLong() -> Int.MAX_VALUE
        v < Int.MIN_VALUE.toLong() -> Int.MIN_VALUE
        else -> v.toInt()
    }
}

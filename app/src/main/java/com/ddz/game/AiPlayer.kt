package com.ddz.game

import com.ddz.core.Card
import com.ddz.core.CardConst
import com.ddz.core.Combo
import com.ddz.core.ComboParser
import com.ddz.core.ComboType
import com.ddz.core.Rules
import kotlin.random.Random

/**
 * 托管 / 电脑玩家的出牌决策。
 *
 * 这不是要把 AI 做得像人，而是保证三件事：
 *  1. **永远合法**：只在 [Rules.enumerateAll] 产出的合法牌型里挑，绝不产生非法操作。
 *  2. **绝不卡死**：无论什么局面都能给出一个决定（要么出牌要么过）。
 *  3. **不给自己人拆台**：农民之间不互相压制，地主则优先压制手牌最少的那家。
 *
 * 策略上就是个「先跟牌、再控场」的启发式，够局域网朋友局用了。
 */
object AiPlayer {

    /**
     * @param target 需要压过的牌型；null 表示自由出牌（自己是这一轮首家）
     * @param lastPlayerIsTeammate 上家是不是自己的农民队友（地主恒为 false）
     * @param landlordRemain 地主剩余张数
     * @param teammateRemain 队友剩余张数（地主视角无意义，传 99 即可）
     * @return 要出的牌；空列表表示「不出」
     */
    fun decide(
        hand: List<Card>,
        target: Combo?,
        isLandlord: Boolean,
        lastPlayerIsTeammate: Boolean,
        landlordRemain: Int,
        teammateRemain: Int,
        rng: Random = Random.Default
    ): List<Card> {
        if (hand.isEmpty()) return emptyList()

        // 预计算：所有合法牌型 + 解析结果。没几张牌，这点开销可以忽略。
        val all = ArrayList<Pair<List<Card>, Combo>>(64)
        for (cards in Rules.enumerateAll(hand)) {
            val combo = ComboParser.parse(cards)
            if (combo.isValid && combo.type != ComboType.PASS) all.add(cards to combo)
        }
        if (all.isEmpty()) return emptyList()

        val freeLead = target == null || !target.isValid || target.type == ComboType.PASS

        // 能一把走完就直接走完，任何策略都让位于「赢」
        val finish = all.firstOrNull { it.first.size == hand.size && Rules.canBeat(it.second, target) }
        if (finish != null) return finish.first

        if (freeLead) return chooseLead(hand, all, rng)

        return chooseFollow(
            all = all,
            target = target!!,
            isLandlord = isLandlord,
            lastPlayerIsTeammate = lastPlayerIsTeammate,
            landlordRemain = landlordRemain,
            teammateRemain = teammateRemain,
            handSize = hand.size
        )
    }

    /** 自由出牌：优先出点数小的长牌型（顺子/连对/飞机），把散牌留在手里 */
    private fun chooseLead(
        hand: List<Card>,
        all: List<Pair<List<Card>, Combo>>,
        rng: Random
    ): List<Card> {
        val normal = all.filter {
            it.second.type != ComboType.BOMB && it.second.type != ComboType.ROCKET
        }
        if (normal.isEmpty()) {
            // 只剩炸弹/王炸这种极端情况：直接扔出去
            return all.minByOrNull { it.first.size }?.first
                ?: listOf(hand.minByOrNull { it.rank }!!)
        }

        // 只剩两手牌且其中一手能走完大半时，先出小的那手
        val sorted = normal.sortedWith(
            compareBy(
                { it.second.mainRank },          // 点数小的先出
                { -it.first.size }               // 同点数优先出张数多的
            )
        )

        // 手里牌还多的时候，避免把「三带一/三带二」的带牌拆散导致后面走不掉，
        // 这里简单地倾向于：如果有多张的长牌型（顺子/连对/飞机），优先甩出去。
        if (hand.size >= 8) {
            val chain = sorted.firstOrNull {
                it.second.type == ComboType.STRAIGHT ||
                    it.second.type == ComboType.STRAIGHT_PAIR ||
                    it.second.type == ComboType.PLANE ||
                    it.second.type == ComboType.PLANE_ONE ||
                    it.second.type == ComboType.PLANE_TWO
            }
            if (chain != null) return chain.first
        }

        return sorted.first().first
    }

    /** 跟牌：用最小的代价压过去 */
    private fun chooseFollow(
        all: List<Pair<List<Card>, Combo>>,
        target: Combo,
        isLandlord: Boolean,
        lastPlayerIsTeammate: Boolean,
        landlordRemain: Int,
        teammateRemain: Int,
        handSize: Int
    ): List<Card> {
        val beats = all.filter { Rules.canBeat(it.second, target) }
        if (beats.isEmpty()) return emptyList()

        val normal = beats.filter {
            it.second.type != ComboType.BOMB && it.second.type != ComboType.ROCKET
        }

        // 上家是队友：一般让队友走，除非对手马上要赢
        if (lastPlayerIsTeammate) {
            val teammateWinningSoon = teammateRemain <= 2
            if (teammateWinningSoon) return emptyList()
            if (target.type == ComboType.BOMB || target.type == ComboType.ROCKET) return emptyList()
            // 队友出了很大的牌（A 以上或者是长顺子），没必要压
            if (target.mainRank >= CardConst.RA) return emptyList()
            if (normal.isEmpty()) return emptyList()
            // 队友出小牌时，用很小的牌「顶一下」是合理的，但不强求
            if (handSize > 6) return emptyList()
        }

        if (normal.isNotEmpty()) {
            // 最小的代价：先比张数，再比点数
            return normal.minWithOrNull(
                compareBy({ it.first.size }, { it.second.mainRank })
            )!!.first
        }

        // 只能用炸弹/王炸了。值得炸的情形：
        //  - 我是农民，地主只剩 1~2 张（不炸就输了）
        //  - 我是地主，有农民只剩 1~2 张
        //  - 对手出的是炸弹/王炸（必须用更大的炸或王炸）
        val opponentAboutToWin = if (isLandlord) teammateRemain <= 2 else landlordRemain <= 2
        val targetIsBomb = target.type == ComboType.BOMB || target.type == ComboType.ROCKET
        if (opponentAboutToWin || targetIsBomb) {
            return beats.minWithOrNull(
                compareBy({ if (it.second.type == ComboType.ROCKET) 1 else 0 }, { it.second.mainRank })
            )!!.first
        }
        return emptyList()
    }
}

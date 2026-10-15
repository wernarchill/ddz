package com.ddz.core

import kotlin.random.Random

/** 一副牌的生成、洗牌与发牌 */
object Deck {

    /**
     * 完整 54 张：花色顺序 黑桃(0)、红桃(1)、梅花(2)、方块(3)，
     * 每个花色内 rank 3..15 升序，最后是 [小王, 大王]。
     * 顺序是稳定且确定的，方便调试/复盘。
     */
    fun full(): List<Card> {
        val cards = ArrayList<Card>(54)
        for (suit in CardConst.SPADE..CardConst.DIAMOND) {
            for (rank in CardConst.R3..CardConst.R2) {
                cards.add(Card(rank, suit))
            }
        }
        cards.add(Card(CardConst.JOKER_SMALL, CardConst.JOKER))
        cards.add(Card(CardConst.JOKER_BIG, CardConst.JOKER))
        return cards
    }

    /** Fisher-Yates 原地洗牌（由后向前），返回洗好的新列表，不修改入参 */
    fun shuffled(rng: Random = Random.Default): List<Card> {
        val list = ArrayList(full())
        for (i in list.size - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = list[i]
            list[i] = list[j]
            list[j] = tmp
        }
        return list
    }

    /** 发牌：前 51 张按 17/17/17 分给三家，最后 3 张为底牌 */
    fun deal(shuffled: List<Card>): Deal {
        require(shuffled.size == 54) { "斗地主需要 54 张牌，实际 ${shuffled.size} 张" }
        val hands = ArrayList<List<Card>>(3)
        for (i in 0..2) {
            hands.add(ArrayList(shuffled.subList(i * 17, i * 17 + 17)))
        }
        return Deal(hands, ArrayList(shuffled.subList(51, 54)))
    }
}

/** 发牌结果：三个玩家的手牌 + 三张底牌 */
data class Deal(val hands: List<List<Card>>, val bottom: List<Card>)
